# Network Tokenization — .NET

ASP.NET Core implementation of the network tokenization sample using direct GP API HTTP calls.

Part of the [network-tokenization](../) multi-language project.

---

## Requirements

- .NET 9.0 SDK
- GP API credentials (`GP_API_APP_ID`, `GP_API_APP_KEY`) with network tokenization enabled

---

## Project Structure

```
dotnet/
├── .env.sample         # Environment variable template
├── dotnet.csproj       # Project file (DotEnv.Net only — no GP SDK)
├── Program.cs          # All 4 endpoints via minimal API
├── wwwroot/            # Static files (index.html served here)
├── data/
│   └── tokens.json     # Saved PMT IDs (auto-created at runtime)
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
dotnet run
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

This implementation uses direct HTTP calls to the GP API rather than the .NET SDK. Authentication uses a nonce/secret pattern:

```csharp
private static string GenerateNonce()
{
    var bytes = new byte[16];
    using var rng = RandomNumberGenerator.Create();
    rng.GetBytes(bytes);
    return BitConverter.ToString(bytes).Replace("-", "").ToLower();
}

private static string HashSecret(string nonce, string appKey)
{
    using var sha512 = SHA512.Create();
    var bytes = Encoding.UTF8.GetBytes(nonce + appKey);
    var hash = sha512.ComputeHash(bytes);
    return BitConverter.ToString(hash).Replace("-", "").ToLower();
}

private static async Task<string> GetAccessToken(string[]? permissions = null)
{
    var nonce = GenerateNonce();
    var secret = HashSecret(nonce, Environment.GetEnvironmentVariable("GP_API_APP_KEY") ?? "");

    var payload = new { app_id = ..., nonce, secret, grant_type = "client_credentials",
                        seconds_to_expire = 600, permissions };

    // POST to https://apis.sandbox.globalpay.com/ucp/accesstoken
    // Returns: { "token": "Bearer ..." }
}
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
  "message": "Failed to generate access token: ..."
}
```

---

### `POST /create-network-token`

Converts a single-use Drop-In UI token into a reusable network token by posting directly to the GP API `/verifications` endpoint with `storage_mode: ON_SUCCESS`.

**Request**
```json
{
  "payment_reference": "PMT_single_use_token_from_drop_in"
}
```

```csharp
var verifyPayload = new {
    account_name = "transaction_processing",
    channel = "CNP",
    reference,
    currency = "USD",
    country = "US",
    payment_method = new {
        entry_mode = "ECOM",
        id = paymentReference,
        storage_mode = "ON_SUCCESS"
    }
};
// POST to /verifications → response.payment_method.id = PMT_xxxxx
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
    "created_at": "2025-05-11T14:30:00.0000000Z"
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

Returns all network tokens saved in `data/tokens.json` (relative to the application base directory).

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
      "created_at": "2025-05-11T14:30:00.0000000Z"
    }
  ]
}
```

---

### `POST /process-payment`

Charges a saved network token by posting directly to the GP API `/transactions` endpoint with `usage_mode: USE_NETWORK_TOKEN`.

**Request**
```json
{
  "pmt_id": "PMT_a1b2c3d4e5f6g7h8",
  "amount": 10.00,
  "currency": "USD"
}
```

```csharp
var txPayload = new {
    account_name = "transaction_processing",
    type = "SALE",
    channel = "CNP",
    capture_mode = "AUTO",
    amount = amountMinor,  // minor units: "1000" for $10.00
    currency,
    country = "US",
    reference = GenerateNonce(),
    payment_method = new {
        entry_mode = "ECOM",
        id = pmtId,
        usage_mode = "USE_NETWORK_TOKEN"
    }
};
// POST to /transactions
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
  Browser → GET /config → GetAccessToken(["PMT_POST_Create_Single"])
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
docker build -t network-tokenization-dotnet .
docker run -p 8000:8000 --env-file .env network-tokenization-dotnet
```

---

## Troubleshooting

**`ACTION_NOT_AUTHORIZED` on startup** — `GP_API_APP_ID` or `GP_API_APP_KEY` is wrong, or network tokenization is not enabled on the account. Verify credentials in the Developer Portal.

**Drop-In UI does not render** — The access token from `/config` has expired (600 s TTL). Reload the page to fetch a fresh token.

**`Failed to create network token`** — Network tokenization may not be enabled on the sandbox account. Contact Global Payments support.

**`dotnet: command not found`** — Install the .NET 9.0 SDK from [dot.net](https://dot.net).

**Port already in use** — Set `PORT=8001` in `.env` and restart.
