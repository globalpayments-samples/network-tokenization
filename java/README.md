# Network Tokenization — Java

Java/Servlet implementation of the network tokenization sample using direct GP API HTTP calls.

Part of the [network-tokenization](../) multi-language project.

---

## Requirements

- Java 17+
- Maven 3.6+
- GP API credentials (`GP_API_APP_ID`, `GP_API_APP_KEY`) with network tokenization enabled

---

## Project Structure

```
java/
├── .env.sample
├── pom.xml                             # Maven: Tomcat, dotenv-java, org.json
├── src/main/java/com/globalpayments/example/
│   └── ProcessPaymentServlet.java      # All 4 endpoints as servlet handlers
├── src/main/webapp/
│   └── index.html                      # Frontend UI
├── data/
│   └── tokens.json                     # Saved PMT IDs (~/.network-tokens/)
├── run.sh                              # Build + start Tomcat
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
mvn package -q
mvn cargo:run -q
```

---

## Environment Variables

| Variable | Description | Example |
|----------|-------------|---------|
| `GP_API_APP_ID` | Your GP API application ID | `a8b5f800-...` |
| `GP_API_APP_KEY` | Your GP API application key | `qM31zQFkFh...` |
| `GP_API_ENVIRONMENT` | `sandbox` or `production` | `sandbox` |

Credentials available in the [GP Developer Portal](https://developer.globalpayments.com).

---

## Authentication

This implementation uses direct `HttpURLConnection` calls to the GP API. All connections include `Accept-Encoding: identity` to prevent gzip compression issues. Authentication uses a nonce/secret pattern:

```java
private String generateNonce() {
    SecureRandom random = new SecureRandom();
    byte[] bytes = new byte[16];
    random.nextBytes(bytes);
    StringBuilder sb = new StringBuilder();
    for (byte b : bytes) sb.append(String.format("%02x", b));
    return sb.toString();
}

private String hashSecret(String nonce, String appKey) throws Exception {
    MessageDigest digest = MessageDigest.getInstance("SHA-512");
    byte[] hash = digest.digest((nonce + appKey).getBytes(StandardCharsets.UTF_8));
    StringBuilder sb = new StringBuilder();
    for (byte b : hash) sb.append(String.format("%02x", b));
    return sb.toString();
}

private String getAccessToken(String[] permissions) throws Exception {
    String nonce = generateNonce();
    String secret = hashSecret(nonce, dotenv.get("GP_API_APP_KEY"));

    JSONObject payload = new JSONObject();
    payload.put("app_id", dotenv.get("GP_API_APP_ID"));
    payload.put("nonce", nonce);
    payload.put("secret", secret);
    payload.put("grant_type", "client_credentials");
    payload.put("seconds_to_expire", 600);
    if (permissions != null) payload.put("permissions", permissions);

    // HttpURLConnection with Accept-Encoding: identity
    // POST to /accesstoken → returns { "token": "..." }
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
  "message": "Failed to get access token: ..."
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

```java
JSONObject pm = new JSONObject();
pm.put("entry_mode", "ECOM");
pm.put("id", paymentReference);
pm.put("storage_mode", "ON_SUCCESS");

JSONObject payload = new JSONObject();
payload.put("account_name", "transaction_processing");
payload.put("channel", "CNP");
payload.put("reference", generateNonce());
payload.put("currency", "USD");
payload.put("country", "US");
payload.put("payment_method", pm);

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

```java
JSONObject pm = new JSONObject();
pm.put("entry_mode", "ECOM");
pm.put("id", pmtId);
pm.put("usage_mode", "USE_NETWORK_TOKEN");

JSONObject payload = new JSONObject();
payload.put("account_name", "transaction_processing");
payload.put("type", "SALE");
payload.put("channel", "CNP");
payload.put("capture_mode", "AUTO");
payload.put("amount", String.valueOf(amountMinor)); // minor units
payload.put("currency", currency);
payload.put("country", "US");
payload.put("reference", generateNonce());
payload.put("payment_method", pm);

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
  Browser → GET /config → getAccessToken(["PMT_POST_Create_Single"])
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
docker build -t network-tokenization-java .
docker run -p 8000:8000 --env-file .env network-tokenization-java
```

---

## Troubleshooting

**`ACTION_NOT_AUTHORIZED` on startup** — `GP_API_APP_ID` or `GP_API_APP_KEY` is wrong, or network tokenization is not enabled on the account. Verify credentials in the Developer Portal.

**`"A JSONObject text must begin with '{'"` in logs** — An HTTP connection returned gzip-compressed data that couldn't be parsed. All connections in this implementation include `Accept-Encoding: identity` to prevent this; if you see this error it suggests a custom fork is missing that header.

**Drop-In UI does not render** — The access token from `/config` has expired (600 s TTL). Reload the page to fetch a fresh token.

**`mvn: command not found`** — Install Maven from [maven.apache.org](https://maven.apache.org) or via your package manager (`brew install maven`, `apt-get install maven`).

**Port already in use** — Edit `pom.xml` and change the `<port>` value in the Cargo plugin configuration, then restart.
