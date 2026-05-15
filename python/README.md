# Network Tokenization — Python

Python/Flask implementation of the network tokenization sample using direct GP API HTTP calls (no SDK dependency).

Part of the [network-tokenization](../) multi-language project.

---

## Requirements

- Python 3.10+
- pip
- GP API credentials (`GP_API_APP_ID`, `GP_API_APP_KEY`) with network tokenization enabled

---

## Project Structure

```
python/
├── .env.sample         # Environment variable template
├── requirements.txt    # Flask, gunicorn, python-dotenv (no GP SDK)
├── server.py           # All 4 endpoints as Flask routes
├── data/
│   └── tokens.json     # Saved PMT IDs (auto-created)
├── run.sh              # Install + start server
├── Dockerfile
├── .devcontainer/
└── .codesandbox/
```

---

## Setup

```bash
cp .env.sample .env
# Edit .env — fill in GP_API_APP_ID and GP_API_APP_KEY
./run.sh
```

Open `http://localhost:8000` in your browser.

**Manual start:**

```bash
pip install -r requirements.txt
python server.py
```

---

## Environment Variables

| Variable | Description | Example |
|----------|-------------|---------|
| `GP_API_APP_ID` | Your GP API application ID | `a8b5f800-...` |
| `GP_API_APP_KEY` | Your GP API application key | `qM31zQFkFh...` |
| `GP_API_ENVIRONMENT` | `sandbox` or `production` | `sandbox` |
| `PORT` | Server listen port (optional) | `8000` |

Credentials available in the [GP Developer Portal](https://developer.globalpayments.com).

---

## Authentication

This implementation uses `urllib.request` directly — no GP SDK is installed. Authentication uses a nonce/secret pattern:

```python
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
        headers={'Content-Type': 'application/json', 'X-GP-Version': '2021-03-22'},
        method='POST',
    )
    with urllib.request.urlopen(req) as resp:
        result = json.loads(resp.read().decode('utf-8'))
    return result['token']
```

---

## Endpoints

### `GET /config`

Generates a scoped access token for the Drop-In UI with `PMT_POST_Create_Single` permission.

**Response**
```json
{
  "success": true,
  "data": {
    "accessToken": "S0BiXG7jfVkBPKlMPIR..."
  }
}
```

**Error**
```json
{
  "success": false,
  "message": "ACTION_NOT_AUTHORIZED: ..."
}
```

---

### `POST /create-network-token`

Converts a single-use Drop-In UI token into a reusable network token by posting to the GP API `/verifications` endpoint with `storage_mode: ON_SUCCESS`.

**Request**
```json
{
  "payment_reference": "PMT_single_use_token_from_drop_in"
}
```

```python
payload = {
    'account_name': 'transaction_processing',
    'channel': 'CNP',
    'reference': generate_nonce(),
    'currency': 'USD',
    'country': 'US',
    'payment_method': {
        'entry_mode': 'ECOM',
        'id': data['payment_reference'],
        'storage_mode': 'ON_SUCCESS',
    },
}
# POST to /verifications → result['payment_method']['id'] = PMT_xxxxx
```

**Response**
```json
{
  "success": true,
  "data": {
    "id": "PMT_a1b2c3d4e5f6g7h8",
    "brand": "VISA",
    "masked_card": "2970",
    "usage_mode": "USE_NETWORK_TOKEN",
    "status": "Active",
    "created_at": "2025-05-11T14:30:00+00:00"
  },
  "message": "Network token created successfully"
}
```

**Error**
```json
{
  "success": false,
  "message": "Failed to create network token: no token returned"
}
```

---

### `GET /list-tokens`

Returns all network tokens saved in `data/tokens.json`.

**Response**
```json
{
  "success": true,
  "data": [
    {
      "id": "PMT_a1b2c3d4e5f6g7h8",
      "brand": "VISA",
      "masked_card": "2970",
      "usage_mode": "USE_NETWORK_TOKEN",
      "status": "Active",
      "created_at": "2025-05-11T14:30:00+00:00"
    }
  ]
}
```

---

### `POST /process-payment`

Charges a saved network token by posting to the GP API `/transactions` endpoint with `usage_mode: USE_NETWORK_TOKEN`.

**Request**
```json
{
  "pmt_id": "PMT_a1b2c3d4e5f6g7h8",
  "amount": 10.00,
  "currency": "USD"
}
```

```python
amount_minor = str(int(amount * 100))  # "1000" for $10.00

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
# POST to /transactions
```

**Response**
```json
{
  "success": true,
  "data": {
    "transactionId": "TRN_xxxxxxxxxxxx",
    "status": "SUCCESS",
    "amount": 10.00,
    "currency": "USD",
    "authCode": "123456",
    "tokenUsageMode": "USE_NETWORK_TOKEN"
  },
  "message": "Payment processed successfully"
}
```

**Error**
```json
{
  "success": false,
  "message": "Transaction failed: Insufficient funds"
}
```

---

## Flow

```
Page Load
  Browser → GET /config → get_access_token(['PMT_POST_Create_Single'])
  → Drop-In UI initializes with scoped access token

Tab 1: Create Network Token
  User enters card → Drop-In UI emits payment_reference
  → POST /create-network-token { payment_reference }
  → POST GP API /verifications { storage_mode: ON_SUCCESS }
  → PMT_xxxxx extracted from payment_method.id → saved to data/tokens.json

Tab 2: Process Payment
  Browser → GET /list-tokens → dropdown populated
  → POST /process-payment { pmt_id, amount }
  → POST GP API /transactions { usage_mode: USE_NETWORK_TOKEN }
```

---

## Test Cards

Network tokenization requires a specific test card:

| Brand | Number | CVV | Expiry |
|-------|--------|-----|--------|
| Visa | 4622 9431 2305 2970 | 999 | 12/25 |

Standard sandbox cards:

| Brand | Number | CVV | Expiry |
|-------|--------|-----|--------|
| Visa | 4263 9826 4026 9299 | 123 | Any future |
| Mastercard | 5425 2334 2424 1200 | 123 | Any future |

---

## Docker

```bash
docker build -t network-tokenization-python .
docker run -p 8000:8000 --env-file .env network-tokenization-python
```

---

## Troubleshooting

**`ACTION_NOT_AUTHORIZED` on startup** — `GP_API_APP_ID` or `GP_API_APP_KEY` is wrong, or network tokenization is not enabled on the account. Verify credentials in the Developer Portal.

**Drop-In UI does not render** — The access token from `/config` has expired (600 s TTL). Reload the page to fetch a fresh token.

**`Failed to create network token`** — Network tokenization may not be enabled on the sandbox account. Contact Global Payments support to confirm the feature is enabled.

**`pip: command not found`** — Use `pip3` instead, or install pip with `python -m ensurepip`.

**Port already in use** — Set `PORT=8001` in `.env` and restart.
