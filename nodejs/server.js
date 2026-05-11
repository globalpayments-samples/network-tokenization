/**
 * Global Payments Network Tokenization - Node.js
 *
 * This Express application demonstrates network tokenization:
 * 1. Create network tokens from Drop-In UI single-use tokens (Tab 1)
 * 2. Process payments using saved network tokens (Tab 2)
 */

import express from 'express';
import * as dotenv from 'dotenv';
import crypto from 'crypto';
import fs from 'fs/promises';
import path from 'path';
import {
  ServicesContainer,
  GpApiConfig,
  CreditCardData,
  Channel
} from 'globalpayments-api';

// Load environment variables
dotenv.config();

const app = express();
const port = process.env.PORT || 8000;
const dataDir = './data';
const tokensFile = path.join(dataDir, 'tokens.json');

// Middleware
app.use(express.static('.'));
app.use(express.urlencoded({ extended: true }));
app.use(express.json());

// CORS headers
app.use((req, res, next) => {
  res.header('Access-Control-Allow-Origin', '*');
  res.header('Access-Control-Allow-Methods', 'POST, GET, OPTIONS');
  res.header('Access-Control-Allow-Headers', 'Content-Type');
  if (req.method === 'OPTIONS') {
    return res.sendStatus(200);
  }
  next();
});

// Utility: Initialize data directory and tokens file
const initializeDataDir = async () => {
  try {
    await fs.mkdir(dataDir, { recursive: true });
    try {
      await fs.access(tokensFile);
    } catch {
      await fs.writeFile(tokensFile, JSON.stringify([], null, 2));
    }
  } catch (error) {
    console.error('Error initializing data directory:', error.message);
  }
};

// Utility: Read saved tokens
const readTokens = async () => {
  try {
    const content = await fs.readFile(tokensFile, 'utf-8');
    return JSON.parse(content);
  } catch (error) {
    return [];
  }
};

// Utility: Write tokens to file
const writeTokens = async (tokens) => {
  try {
    await fs.writeFile(tokensFile, JSON.stringify(tokens, null, 2));
  } catch (error) {
    console.error('Error writing tokens:', error.message);
  }
};

/**
 * Generate access token for Drop-In UI
 * POST /config
 */
app.get('/config', async (req, res) => {
  try {
    const nonce = crypto.randomBytes(16).toString('hex');
    const secret = crypto.createHash('sha512')
      .update(nonce + process.env.GP_API_APP_KEY)
      .digest('hex');

    const tokenRequest = {
      app_id: process.env.GP_API_APP_ID,
      nonce: nonce,
      secret: secret,
      grant_type: 'client_credentials',
      seconds_to_expire: 600,
      permissions: ['PMT_POST_Create_Single']
    };

    const apiEndpoint = process.env.GP_API_ENVIRONMENT === 'production'
      ? 'https://apis.globalpay.com/ucp/accesstoken'
      : 'https://apis.sandbox.globalpay.com/ucp/accesstoken';

    const response = await fetch(apiEndpoint, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'X-GP-Version': '2021-03-22'
      },
      body: JSON.stringify(tokenRequest)
    });

    const data = await response.json();

    if (!response.ok || !data.token) {
      throw new Error(data.error_description || 'Failed to generate access token');
    }

    res.json({
      success: true,
      data: { accessToken: data.token },
      expiresIn: data.seconds_to_expire || 600
    });

  } catch (error) {
    res.status(400).json({
      success: false,
      message: 'Error generating access token',
      error: error.message
    });
  }
});

/**
 * Create network token from single-use Drop-In UI token
 * POST /create-network-token
 * Body: { payment_reference }
 */
app.post('/create-network-token', async (req, res) => {
  try {
    if (!req.body.payment_reference) {
      throw new Error('Missing payment_reference');
    }

    // Configure SDK
    const config = new GpApiConfig();
    config.appId = process.env.GP_API_APP_ID;
    config.appKey = process.env.GP_API_APP_KEY;
    config.environment = process.env.GP_API_ENVIRONMENT === 'production'
      ? 'production'
      : 'test';
    config.channel = Channel.CardNotPresent;
    config.country = 'US';

    ServicesContainer.configureService(config);

    // Create network token from single-use token
    const card = new CreditCardData();
    card.token = req.body.payment_reference;

    const response = await card.tokenize(true, 'USE_NETWORK_TOKEN')
      .withCurrency('USD')
      .execute();

    if (!response.token) {
      throw new Error('Failed to create network token');
    }

    // Extract card details
    const tokenData = {
      id: response.token,
      brand: response.cardBrand || 'Unknown',
      masked_card: response.maskedNumberLast4 || '',
      usage_mode: response.tokenUsageMode || 'USE_NETWORK_TOKEN',
      status: response.responseCode === 'SUCCESS' ? 'Active' : 'Failed',
      created_at: new Date().toISOString()
    };

    // Save to tokens.json
    const tokens = await readTokens();
    tokens.push(tokenData);
    await writeTokens(tokens);

    res.json({
      success: true,
      data: tokenData,
      message: 'Network token created successfully'
    });

  } catch (error) {
    res.status(400).json({
      success: false,
      message: 'Failed to create network token',
      error: error.message
    });
  }
});

/**
 * List saved network tokens
 * GET /list-tokens
 */
app.get('/list-tokens', async (req, res) => {
  try {
    const tokens = await readTokens();
    res.json({
      success: true,
      data: tokens
    });
  } catch (error) {
    res.status(400).json({
      success: false,
      message: 'Failed to retrieve tokens',
      error: error.message
    });
  }
});

/**
 * Process payment using saved network token
 * POST /process-payment
 * Body: { pmt_id, amount, currency }
 */
app.post('/process-payment', async (req, res) => {
  try {
    if (!req.body.pmt_id) {
      throw new Error('Missing pmt_id');
    }

    const amount = parseFloat(req.body.amount || 0);
    if (amount <= 0) {
      throw new Error('Invalid amount');
    }

    const currency = req.body.currency || 'USD';

    // Configure SDK
    const config = new GpApiConfig();
    config.appId = process.env.GP_API_APP_ID;
    config.appKey = process.env.GP_API_APP_KEY;
    config.environment = process.env.GP_API_ENVIRONMENT === 'production'
      ? 'production'
      : 'test';
    config.channel = Channel.CardNotPresent;
    config.country = 'US';

    ServicesContainer.configureService(config);

    // Process payment with network token
    const card = new CreditCardData();
    card.token = req.body.pmt_id;

    const response = await card.charge(amount)
      .withCurrency(currency)
      .withPaymentMethodUsageMode('USE_NETWORK_TOKEN')
      .execute();

    if (response.responseCode === 'SUCCESS') {
      res.json({
        success: true,
        data: {
          transactionId: response.transactionId,
          status: response.responseMessage,
          amount: amount,
          currency: currency,
          authCode: response.authorizationCode || '',
          reference: response.referenceNumber || '',
          tokenUsageMode: response.tokenUsageMode || ''
        },
        message: 'Payment processed successfully'
      });
    } else {
      throw new Error('Transaction declined: ' + (response.responseMessage || 'Unknown error'));
    }

  } catch (error) {
    res.status(400).json({
      success: false,
      message: 'Payment processing failed',
      error: error.message
    });
  }
});

// Initialize data directory and start server
await initializeDataDir();

app.listen(port, '0.0.0.0', () => {
  console.log(`✅ Server running at http://localhost:${port}`);
  console.log(`Environment: ${process.env.GP_API_ENVIRONMENT || 'sandbox'}`);
});
