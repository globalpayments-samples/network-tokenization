# Network Tokenization

> Collect a card via Drop-In UI, convert the single-use token to a reusable network token (PMT ID) via `/verifications`, and charge it with `USE_NETWORK_TOKEN` mode using the GP API — implemented in PHP, Node.js, .NET, Java, Python, and Go.

## Critical Patterns

1. **`storage_mode: ON_SUCCESS` is what creates the PMT ID.** Token creation calls `POST /verifications` with `payment_method.storage_mode = "ON_SUCCESS"`. Without it, the verification succeeds but no PMT ID is returned. PHP/Node.js use `card.tokenize(true, 'USE_NETWORK_TOKEN')` — the SDK sets this internally. Direct-HTTP langs set it explicitly in the request body.

2. **`usage_mode: USE_NETWORK_TOKEN` must be set on every payment.** Charging a PMT ID without it processes as a stored-credential charge, not a network token — no fresh cryptogram is issued by the card network. PHP uses `.withPaymentMethodUsageMode('USE_NETWORK_TOKEN')` in `process-payment.php`; direct-HTTP langs set `payment_method.usage_mode = "USE_NETWORK_TOKEN"` in the transaction body.

3. **Direct-HTTP langs send amount as minor-unit string, not a decimal.** `.NET`, Java, Python, and Go all convert `amount` to cents and stringify it before sending (e.g., `"1000"` for $10.00). The SDK handles this for PHP and Node.js automatically.

4. **Go and Java declare the GP SDK in their dep files but never use it.** `go/go.mod` lists `globalpayments/go-sdk v1.1.3`; `java/pom.xml` lists `globalpayments-sdk 14.2.20`. Neither imports the SDK — all calls are direct HTTP. The entries are unused and can be removed.

## Repository Structure

### PHP (built-in server)
- [`php/config.php`](php/config.php) — GET /config; sets `permissions = ['PMT_POST_Create_Single']` in `GpApiConfig` setup, calls `GpApiService::generateTransactionKey()`
- [`php/create-network-token.php`](php/create-network-token.php) — POST /create-network-token; calls `$card->tokenize(true, 'USE_NETWORK_TOKEN')`; saves to `php/data/tokens.json`
- [`php/process-payment.php`](php/process-payment.php) — POST /process-payment; calls `$card->charge()->withPaymentMethodUsageMode('USE_NETWORK_TOKEN')`
- [`php/list-tokens.php`](php/list-tokens.php) — GET /list-tokens; reads and returns `php/data/tokens.json`
- [`php/composer.json`](php/composer.json), [`php/.env.sample`](php/.env.sample)

### Node.js (Express)
- [`nodejs/server.js`](nodejs/server.js) — all endpoints; GET /config uses direct `fetch()` to `/accesstoken`; POST /create-network-token uses SDK `card.tokenize(true, 'USE_NETWORK_TOKEN')`; POST /process-payment uses SDK `.withPaymentMethodUsageMode('USE_NETWORK_TOKEN')`; token storage in `nodejs/data/tokens.json`
- [`nodejs/package.json`](nodejs/package.json), [`nodejs/.env.sample`](nodejs/.env.sample)

### .NET (ASP.NET Core, direct HTTP)
- [`dotnet/Program.cs`](dotnet/Program.cs) — all endpoints via `app.MapGet("/config")`, `app.MapPost("/create-network-token")`, `app.MapGet("/list-tokens")`, `app.MapPost("/process-payment")`; `storage_mode = "ON_SUCCESS"` in `verifyPayload`, `usage_mode = "USE_NETWORK_TOKEN"` in `txPayload`; token storage in `<AppContext.BaseDirectory>/data/tokens.json`
- [`dotnet/dotnet.csproj`](dotnet/dotnet.csproj) — DotEnv.Net only; no GP SDK

### Java (Tomcat/Jakarta Servlet, direct HTTP)
- [`java/src/main/java/com/globalpayments/example/ProcessPaymentServlet.java`](java/src/main/java/com/globalpayments/example/ProcessPaymentServlet.java) — single servlet; methods `handleConfig()`, `handleCreateNetworkToken()`, `handleListTokens()`, `handleProcessPayment()`; `storage_mode: ON_SUCCESS` set in `handleCreateNetworkToken()`, `usage_mode: USE_NETWORK_TOKEN` set in `handleProcessPayment()`; token storage in `~/.network-tokens/tokens.json`
- [`java/pom.xml`](java/pom.xml) — lists `globalpayments-sdk 14.2.20` but unused

### Python (Flask, direct HTTP)
- [`python/server.py`](python/server.py) — all endpoints; Flask route functions `get_config()`, `create_network_token()`, `list_tokens()`, `process_payment()`; `storage_mode: ON_SUCCESS` in `create_network_token()`, `usage_mode: USE_NETWORK_TOKEN` in `process_payment()`; token storage in `python/data/tokens.json`
- [`python/requirements.txt`](python/requirements.txt) — Flask only; no GP SDK

### Go (net/http, direct HTTP)
- [`go/main.go`](go/main.go) — all endpoints; handler functions `handleConfig()`, `handleCreateNetworkToken()`, `handleListTokens()`, `handleProcessPayment()`; `storage_mode: ON_SUCCESS` in `handleCreateNetworkToken()`, `usage_mode: USE_NETWORK_TOKEN` in `handleProcessPayment()`; token storage in `~/.network-tokens/tokens.json`
- [`go/go.mod`](go/go.mod) — lists `globalpayments/go-sdk v1.1.3` but unused

### Shared
- [`docker-compose.yml`](docker-compose.yml) — multi-service orchestration
- `{lang}/index.html` — shared Drop-In UI frontend (each language serves its own copy)

## API Surface

| Method | Path | Purpose |
|--------|------|---------|
| GET | `/config` | Generate Drop-In UI access token scoped to `PMT_POST_Create_Single` |
| POST | `/create-network-token` | Convert single-use Drop-In token to reusable PMT ID; persist to tokens.json |
| GET | `/list-tokens` | Return saved PMT IDs from tokens.json |
| POST | `/process-payment` | Charge a PMT ID with `USE_NETWORK_TOKEN` mode |

All four endpoints are present in all six language implementations.

## Environment Variables

```bash
GP_API_APP_ID=your_app_id_here     # GP API application ID
GP_API_APP_KEY=your_app_key_here   # GP API application key (used in SHA-512 nonce/secret HMAC)
GP_API_ENVIRONMENT=sandbox         # sandbox or production; controls API base URL
PORT=8000                          # optional; all implementations default to 8000
```

`PORT` is read by all six server files but is absent from all `.env.sample` files.

## Test Cards / Sandbox Credentials

| Brand | Number | CVV | Expiry | Notes |
|-------|--------|-----|--------|-------|
| Visa | 4622 9431 2305 2970 | 999 | 12/25 | Required for network tokenization |
| Visa | 4263 9826 4026 9299 | 123 | Any future | Standard GP-API sandbox card |
| Mastercard | 5425 2334 2424 1200 | 123 | Any future | Standard GP-API sandbox card |

Network tokenization must be enabled on the GP API sandbox account before the first card will work. Standard cards test other GP-API features only.

Get credentials at [developer.globalpayments.com](https://developer.globalpayments.com).

## API Request Shape

For direct-HTTP implementations (.NET, Java, Python, Go):

**POST `/ucp/verifications`** — creates network token
- `payment_method.storage_mode` — must be `"ON_SUCCESS"`; this is what triggers PMT ID issuance
- `Authorization` — `"Bearer <token>"` (not `"AuthToken "`)
- `X-GP-Version` — `"2021-03-22"` required on every request

**POST `/ucp/transactions`** — charges network token
- `payment_method.usage_mode` — must be `"USE_NETWORK_TOKEN"` to get a fresh cryptogram
- `amount` — minor units as a string (e.g., `"1000"` for $10.00)

## Architecture Summary

**Token creation:** Browser → Drop-In UI tokenizes card in hosted iframe → `POST /create-network-token { payment_reference }` → backend calls GP API `/verifications` with `storage_mode: ON_SUCCESS` → response `payment_method.id` is the PMT_xxxxx → saved to tokens.json

**Payment:** Browser fetches `GET /list-tokens` → selects PMT ID → `POST /process-payment { pmt_id, amount }` → backend calls GP API `/transactions` with `usage_mode: USE_NETWORK_TOKEN` → returns transaction ID and auth code

## Security Notes

Token storage uses flat JSON files with no encryption or access control. Go and Java write to `~/.network-tokens/tokens.json`; PHP, Node.js, and Python write to `data/tokens.json` inside the language directory; .NET writes to `<AppContext.BaseDirectory>/data/tokens.json`. Use an encrypted database with access restrictions in production. No authentication is applied to any backend endpoint.

## SDK Versions

- PHP: `globalpayments/php-sdk` ^13.4
- Node.js: `globalpayments-api` ^3.10.6
- Java: `globalpayments-sdk` 14.2.20 (declared in pom.xml, unused)
- Go: `globalpayments/go-sdk` v1.1.3 (declared in go.mod, unused)
- .NET: no GP SDK (DotEnv.Net 3.2.1 only)
- Python: no GP SDK (Flask 3.0.0 only)
