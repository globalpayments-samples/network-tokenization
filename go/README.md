# Network Tokenization — Go

Go implementation of the network tokenization sample using direct GP API HTTP calls.

Part of the [network-tokenization](../) multi-language project.

---

## Requirements

- Go 1.21+
- GP API credentials (`GP_API_APP_ID`, `GP_API_APP_KEY`) with network tokenization enabled

---

## Project Structure

```
go/
├── .env.sample         # Environment variable template
├── go.mod              # Module: github.com/joho/godotenv only
├── go.sum
├── main.go             # All 4 endpoints as http.HandleFunc handlers
├── static/
│   └── index.html      # Frontend UI (served from static/)
├── run.sh              # Install + start server
├── Dockerfile
├── .devcontainer/
└── .codesandbox/
```

Token storage path: `~/.network-tokens/tokens.json` (home directory, auto-created).

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
go run main.go
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

This implementation uses the standard `net/http` package directly — no GP SDK is used. Authentication uses a nonce/secret pattern:

```go
func generateNonce() string {
    b := make([]byte, 16)
    rand.Read(b)
    return hex.EncodeToString(b)
}

func hashSecret(nonce, appKey string) string {
    h := sha512.New()
    h.Write([]byte(nonce + appKey))
    return hex.EncodeToString(h.Sum(nil))
}

func getAccessToken(permissions []string) (string, error) {
    nonce := generateNonce()
    secret := hashSecret(nonce, os.Getenv("GP_API_APP_KEY"))

    payload := map[string]interface{}{
        "app_id":            os.Getenv("GP_API_APP_ID"),
        "nonce":             nonce,
        "secret":            secret,
        "grant_type":        "client_credentials",
        "seconds_to_expire": 600,
        "permissions":       permissions,
    }
    // POST to /accesstoken → result["token"]
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
  "message": "failed to get access token"
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

```go
payload := map[string]interface{}{
    "account_name": "transaction_processing",
    "channel":      "CNP",
    "reference":    generateNonce(),
    "currency":     "USD",
    "country":      "US",
    "payment_method": map[string]interface{}{
        "entry_mode":   "ECOM",
        "id":           input.PaymentReference,
        "storage_mode": "ON_SUCCESS",
    },
}
// POST to /verifications → result["payment_method"]["id"] = PMT_xxxxx
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
    "created_at": "2025-05-11T14:30:00Z"
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

Returns all network tokens saved in `~/.network-tokens/tokens.json`.

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
      "created_at": "2025-05-11T14:30:00Z"
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

```go
amountMinor := fmt.Sprintf("%d", int(input.Amount*100)) // "1000" for $10.00

payload := map[string]interface{}{
    "account_name": "transaction_processing",
    "type":         "SALE",
    "channel":      "CNP",
    "capture_mode": "AUTO",
    "amount":       amountMinor,
    "currency":     input.Currency,
    "country":      "US",
    "reference":    generateNonce(),
    "payment_method": map[string]interface{}{
        "entry_mode": "ECOM",
        "id":         input.PmtID,
        "usage_mode": "USE_NETWORK_TOKEN",
    },
}
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
  Browser → GET /config → getAccessToken([]string{"PMT_POST_Create_Single"})
  → Drop-In UI initializes with scoped access token

Tab 1: Create Network Token
  User enters card → Drop-In UI emits payment_reference
  → POST /create-network-token { payment_reference }
  → POST GP API /verifications { storage_mode: ON_SUCCESS }
  → PMT_xxxxx extracted from payment_method.id → saved to ~/.network-tokens/tokens.json

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
docker build -t network-tokenization-go .
docker run -p 8000:8000 --env-file .env network-tokenization-go
```

---

## Troubleshooting

**`ACTION_NOT_AUTHORIZED` on startup** — `GP_API_APP_ID` or `GP_API_APP_KEY` is wrong, or network tokenization is not enabled on the account. Verify credentials in the Developer Portal.

**Drop-In UI does not render** — The access token from `/config` has expired (600 s TTL). Reload the page to fetch a fresh token.

**`Failed to create network token`** — Network tokenization may not be enabled on the sandbox account. Contact Global Payments support to confirm the feature is enabled.

**`go: command not found`** — Install Go from [go.dev/dl](https://go.dev/dl) or via your package manager (`brew install go`).

**Port already in use** — Set `PORT=8001` in `.env` and restart.

**`Error loading .env file`** — The server requires a `.env` file. Run `cp .env.sample .env` and fill in your credentials before starting.
