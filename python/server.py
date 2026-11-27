"""
Global Payments Network Tokenization - Python Flask

Uses direct GP-API HTTP calls (no SDK dependency).

Endpoints:
- GET  /config:               Generate Drop-In UI access token
- POST /create-network-token: Create network token from single-use Drop-In UI token
- GET  /list-tokens:          List saved network tokens
- POST /process-payment:      Process payment using saved network token
"""

import os
import json
import hashlib
import secrets
import urllib.request
from datetime import datetime, timezone
from flask import Flask, request, jsonify, send_from_directory
from dotenv import load_dotenv

load_dotenv()

app = Flask(__name__, static_folder=None)

# Only the page is public, not server.py, requirements.txt or .env
PAGE_DIR = os.path.dirname(os.path.abspath(__file__))


class GatewayError(Exception):
    """An error the GP API sent back. Its message is shown on the page."""


def unexpected_error(step):
    # Keep the details in the server log, the page only gets a generic message
    app.logger.exception('%s failed', step)
    return jsonify({'success': False, 'message': 'Unexpected server error, see the server log'}), 400


DATA_DIR = os.path.join(os.path.dirname(__file__), 'data')
TOKENS_FILE = os.path.join(DATA_DIR, 'tokens.json')


def api_base_url():
    if os.getenv('GP_API_ENVIRONMENT') == 'production':
        return 'https://apis.globalpay.com/ucp'
    return 'https://apis.sandbox.globalpay.com/ucp'


def generate_nonce():
    return secrets.token_hex(16)


def hash_secret(nonce, app_key):
    return hashlib.sha512((nonce + app_key).encode('utf-8')).hexdigest()


def get_access_token(permissions=None):
    nonce = generate_nonce()
    secret = hash_secret(nonce, os.getenv('GP_API_APP_KEY', ''))

    payload = {
        'app_id': os.getenv('GP_API_APP_ID'),
        'nonce': nonce,
        'secret': secret,
        'grant_type': 'client_credentials',
        'seconds_to_expire': 600,
    }
    if permissions:
        payload['permissions'] = permissions

    body = json.dumps(payload).encode('utf-8')
    req = urllib.request.Request(
        api_base_url() + '/accesstoken',
        data=body,
        headers={
            'Content-Type': 'application/json',
            'X-GP-Version': '2021-03-22',
        },
        method='POST',
    )

    try:
        with urllib.request.urlopen(req) as resp:
            result = json.loads(resp.read().decode('utf-8'))
    except urllib.error.HTTPError as e:
        try:
            result = json.loads(e.read().decode('utf-8'))
        except ValueError:
            result = {}

    token = result.get('token')
    if not token:
        desc = (result.get('error_description') or result.get('detailed_error_description')
                or 'Failed to get access token')
        raise GatewayError(desc)
    return token


def read_tokens():
    try:
        if not os.path.exists(TOKENS_FILE):
            return []
        with open(TOKENS_FILE, 'r') as f:
            return json.load(f)
    except Exception:
        return []


def write_tokens(tokens):
    os.makedirs(DATA_DIR, exist_ok=True)
    with open(TOKENS_FILE, 'w') as f:
        json.dump(tokens, f, indent=2)


@app.route('/')
def index():
    return send_from_directory(PAGE_DIR, 'index.html')


@app.route('/config', methods=['GET'])
def get_config():
    try:
        token = get_access_token(['PMT_POST_Create_Single'])
        return jsonify({'success': True, 'data': {'accessToken': token}})
    except GatewayError as e:
        return jsonify({'success': False, 'message': str(e)}), 400
    except Exception:
        return unexpected_error('config')


@app.route('/create-network-token', methods=['POST'])
def create_network_token():
    try:
        data = request.get_json()
        if not data or not data.get('payment_reference'):
            return jsonify({'success': False, 'message': 'Missing payment_reference'}), 400

        access_token = get_access_token()

        reference = generate_nonce()
        payload = {
            'account_name': 'transaction_processing',
            'channel': 'CNP',
            'reference': reference,
            'currency': 'USD',
            'country': 'US',
            'payment_method': {
                'entry_mode': 'ECOM',
                'id': data['payment_reference'],
                'storage_mode': 'ON_SUCCESS',
            },
        }

        body = json.dumps(payload).encode('utf-8')
        req = urllib.request.Request(
            api_base_url() + '/verifications',
            data=body,
            headers={
                'Content-Type': 'application/json',
                'X-GP-Version': '2021-03-22',
                'Authorization': 'Bearer ' + access_token,
            },
            method='POST',
        )

        try:
            with urllib.request.urlopen(req) as resp:
                result = json.loads(resp.read().decode('utf-8'))
                status_code = resp.status
        except urllib.error.HTTPError as e:
            err_body = json.loads(e.read().decode('utf-8'))
            msg = 'Failed to create network token'
            details = err_body.get('details', [])
            if details:
                msg = details[0].get('description', msg)
            msg = err_body.get('detailed_error_description') or msg
            return jsonify({'success': False, 'message': msg}), 400

        pmt_id = ''
        brand = 'Unknown'
        masked_card = ''

        pm = result.get('payment_method', {})
        pmt_id = pm.get('id', '')
        card = pm.get('card', {})
        brand = card.get('brand', 'Unknown')
        masked_card = card.get('masked_number_last4', '')

        if not pmt_id:
            return jsonify({'success': False, 'message': 'Failed to create network token: no token returned'}), 400

        action = result.get('action', {})
        status = 'Active' if str(action.get('result_code', '')).upper() == 'SUCCESS' else 'Failed'

        token_data = {
            'id': pmt_id,
            'brand': brand,
            'masked_card': masked_card,
            'usage_mode': 'USE_NETWORK_TOKEN',
            'status': status,
            'created_at': datetime.now(timezone.utc).isoformat(),
        }

        tokens = read_tokens()
        tokens.append(token_data)
        write_tokens(tokens)

        return jsonify({
            'success': True,
            'data': token_data,
            'message': 'Network token created successfully',
        })

    except GatewayError as e:
        return jsonify({'success': False, 'message': str(e)}), 400
    except Exception:
        return unexpected_error('create-network-token')


@app.route('/list-tokens', methods=['GET'])
def list_tokens():
    try:
        tokens = read_tokens()
        return jsonify({'success': True, 'data': tokens})
    except Exception:
        return unexpected_error('list-tokens')


@app.route('/process-payment', methods=['POST'])
def process_payment():
    try:
        data = request.get_json()
        if not data or not data.get('pmt_id'):
            return jsonify({'success': False, 'message': 'Missing pmt_id'}), 400

        try:
            amount = float(data.get('amount', 0))
        except (TypeError, ValueError):
            amount = 0
        if amount <= 0:
            return jsonify({'success': False, 'message': 'Invalid amount'}), 400

        currency = data.get('currency', 'USD')

        access_token = get_access_token()

        amount_minor = str(round(amount * 100))

        payload = {
            'account_name': 'transaction_processing',
            'type': 'SALE',
            'channel': 'CNP',
            'capture_mode': 'AUTO',
            'amount': amount_minor,
            'currency': currency,
            'country': 'US',
            'reference': generate_nonce(),
            'payment_method': {
                'entry_mode': 'ECOM',
                'id': data['pmt_id'],
                'usage_mode': 'USE_NETWORK_TOKEN',
            },
        }

        body = json.dumps(payload).encode('utf-8')
        req = urllib.request.Request(
            api_base_url() + '/transactions',
            data=body,
            headers={
                'Content-Type': 'application/json',
                'X-GP-Version': '2021-03-22',
                'Authorization': 'Bearer ' + access_token,
            },
            method='POST',
        )

        try:
            with urllib.request.urlopen(req) as resp:
                result = json.loads(resp.read().decode('utf-8'))
        except urllib.error.HTTPError as e:
            err_body = json.loads(e.read().decode('utf-8'))
            msg = 'Transaction failed'
            details = err_body.get('details', [])
            if details:
                msg = details[0].get('description', msg)
            msg = err_body.get('detailed_error_description') or msg
            return jsonify({'success': False, 'message': msg}), 400

        txn_id = result.get('id', '')
        action = result.get('action', {})
        status = action.get('result_code', result.get('status', ''))
        if str(status).upper() != 'SUCCESS':
            # GP API sends declines back with HTTP 200, so the result code decides
            return jsonify({'success': False, 'message': 'Transaction declined: ' + str(result.get('status') or status)}), 400
        auth_code = result.get('payment_method', {}).get('card', {}).get('authcode', '')

        return jsonify({
            'success': True,
            'data': {
                'transactionId': txn_id,
                'status': status,
                'amount': amount,
                'currency': currency,
                'authCode': auth_code,
                'tokenUsageMode': 'USE_NETWORK_TOKEN',
            },
            'message': 'Payment processed successfully',
        })

    except GatewayError as e:
        return jsonify({'success': False, 'message': str(e)}), 400
    except Exception:
        return unexpected_error('process-payment')


if __name__ == '__main__':
    port = int(os.getenv('PORT', 8000))
    print(f'✅ Server running at http://localhost:{port}')
    print(f'Environment: {os.getenv("GP_API_ENVIRONMENT", "sandbox")}')
    app.run(host='0.0.0.0', port=port)
