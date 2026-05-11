using dotenv.net;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;

namespace NetworkTokenizationSample;

public class Program
{
    public static void Main(string[] args)
    {
        DotEnv.Load();

        var builder = WebApplication.CreateBuilder(args);
        var app = builder.Build();

        app.UseDefaultFiles();
        app.UseStaticFiles();

        ConfigureEndpoints(app);

        var port = System.Environment.GetEnvironmentVariable("PORT") ?? "8000";
        app.Urls.Add($"http://0.0.0.0:{port}");

        Console.WriteLine($"✅ Server running at http://localhost:{port}");
        Console.WriteLine($"Environment: {System.Environment.GetEnvironmentVariable("GP_API_ENVIRONMENT") ?? "sandbox"}");

        app.Run();
    }

    private static string GenerateNonce()
    {
        var bytes = new byte[16];
        using (var rng = RandomNumberGenerator.Create())
        {
            rng.GetBytes(bytes);
        }
        return BitConverter.ToString(bytes).Replace("-", "").ToLower();
    }

    private static string HashSecret(string nonce, string appKey)
    {
        using (var sha512 = SHA512.Create())
        {
            var bytes = Encoding.UTF8.GetBytes(nonce + appKey);
            var hash = sha512.ComputeHash(bytes);
            return BitConverter.ToString(hash).Replace("-", "").ToLower();
        }
    }

    private static async Task<string> GetAccessToken(string[]? permissions = null)
    {
        var appKey = System.Environment.GetEnvironmentVariable("GP_API_APP_KEY") ?? "";
        var nonce = GenerateNonce();
        var secret = HashSecret(nonce, appKey);

        object tokenPayload = permissions != null
            ? (object)new { app_id = System.Environment.GetEnvironmentVariable("GP_API_APP_ID"), nonce, secret, grant_type = "client_credentials", seconds_to_expire = 600, permissions }
            : (object)new { app_id = System.Environment.GetEnvironmentVariable("GP_API_APP_ID"), nonce, secret, grant_type = "client_credentials", seconds_to_expire = 600 };

        var apiEndpoint = "production".Equals(System.Environment.GetEnvironmentVariable("GP_API_ENVIRONMENT"))
            ? "https://apis.globalpay.com/ucp/accesstoken"
            : "https://apis.sandbox.globalpay.com/ucp/accesstoken";

        using var handler = new System.Net.Http.HttpClientHandler
        {
            AutomaticDecompression = System.Net.DecompressionMethods.GZip | System.Net.DecompressionMethods.Deflate
        };
        using var httpClient = new System.Net.Http.HttpClient(handler);
        httpClient.DefaultRequestHeaders.Add("X-GP-Version", "2021-03-22");

        var json = JsonSerializer.Serialize(tokenPayload);
        var content = new System.Net.Http.StringContent(json, Encoding.UTF8, "application/json");

        var resp = await httpClient.PostAsync(apiEndpoint, content);
        var body = await resp.Content.ReadAsStringAsync();

        if (!resp.IsSuccessStatusCode)
            throw new Exception($"Failed to get access token: {body}");

        var parsed = JsonSerializer.Deserialize<JsonElement>(body);
        return parsed.GetProperty("token").GetString() ?? throw new Exception("No token in response");
    }

    private static void ConfigureEndpoints(WebApplication app)
    {
        // Generate access token for Drop-In UI
        app.MapGet("/config", async (HttpContext context) =>
        {
            try
            {
                var appKey = System.Environment.GetEnvironmentVariable("GP_API_APP_KEY") ?? "";
                var nonce = GenerateNonce();
                var secret = HashSecret(nonce, appKey);

                var tokenRequest = new
                {
                    app_id = System.Environment.GetEnvironmentVariable("GP_API_APP_ID"),
                    nonce,
                    secret,
                    grant_type = "client_credentials",
                    seconds_to_expire = 600,
                    permissions = new[] { "PMT_POST_Create_Single" }
                };

                var apiEndpoint = "production".Equals(System.Environment.GetEnvironmentVariable("GP_API_ENVIRONMENT"))
                    ? "https://apis.globalpay.com/ucp/accesstoken"
                    : "https://apis.sandbox.globalpay.com/ucp/accesstoken";

                using var handler = new System.Net.Http.HttpClientHandler
                {
                    AutomaticDecompression = System.Net.DecompressionMethods.GZip | System.Net.DecompressionMethods.Deflate
                };
                using var httpClient = new System.Net.Http.HttpClient(handler);
                httpClient.DefaultRequestHeaders.Add("X-GP-Version", "2021-03-22");

                var json = JsonSerializer.Serialize(tokenRequest);
                var content = new System.Net.Http.StringContent(json, Encoding.UTF8, "application/json");

                var response = await httpClient.PostAsync(apiEndpoint, content);
                var body = await response.Content.ReadAsStringAsync();

                if (!response.IsSuccessStatusCode)
                    throw new Exception($"Failed to generate access token: {body}");

                var tokenResponse = JsonSerializer.Deserialize<JsonElement>(body);
                var token = tokenResponse.GetProperty("token").GetString();

                return Results.Ok(new { success = true, data = new { accessToken = token } });
            }
            catch (Exception ex)
            {
                return Results.Json(new { success = false, message = ex.Message }, statusCode: 400);
            }
        });

        // Create network token from Drop-In UI single-use token
        app.MapPost("/create-network-token", async (HttpContext context) =>
        {
            try
            {
                var body = await JsonSerializer.DeserializeAsync<JsonElement>(context.Request.Body);

                if (!body.TryGetProperty("payment_reference", out var refElement) ||
                    string.IsNullOrEmpty(refElement.GetString()))
                    throw new Exception("Missing payment_reference");

                var paymentReference = refElement.GetString()!;

                var accessToken = await GetAccessToken();

                var reference = GenerateNonce();
                var verifyPayload = new
                {
                    account_name = "transaction_processing",
                    channel = "CNP",
                    reference,
                    currency = "USD",
                    country = "US",
                    payment_method = new
                    {
                        entry_mode = "ECOM",
                        id = paymentReference,
                        storage_mode = "ON_SUCCESS"
                    }
                };

                var apiEndpoint = "production".Equals(System.Environment.GetEnvironmentVariable("GP_API_ENVIRONMENT"))
                    ? "https://apis.globalpay.com/ucp/verifications"
                    : "https://apis.sandbox.globalpay.com/ucp/verifications";

                using var handler2 = new System.Net.Http.HttpClientHandler
                {
                    AutomaticDecompression = System.Net.DecompressionMethods.GZip | System.Net.DecompressionMethods.Deflate
                };
                using var httpClient2 = new System.Net.Http.HttpClient(handler2);
                httpClient2.DefaultRequestHeaders.Add("X-GP-Version", "2021-03-22");
                httpClient2.DefaultRequestHeaders.Add("Authorization", "Bearer " + accessToken);

                var verifyJson = JsonSerializer.Serialize(verifyPayload);
                var verifyContent = new System.Net.Http.StringContent(verifyJson, Encoding.UTF8, "application/json");

                var verifyResp = await httpClient2.PostAsync(apiEndpoint, verifyContent);
                var verifyBody = await verifyResp.Content.ReadAsStringAsync();

                if (!verifyResp.IsSuccessStatusCode)
                {
                    var errJson = JsonSerializer.Deserialize<JsonElement>(verifyBody);
                    var msg = "Failed to create network token";
                    if (errJson.TryGetProperty("details", out var details) && details.GetArrayLength() > 0)
                        msg = details[0].GetProperty("description").GetString() ?? msg;
                    return Results.Json(new { success = false, message = msg }, statusCode: 400);
                }

                var verifyResult = JsonSerializer.Deserialize<JsonElement>(verifyBody);

                var pmtId = "";
                var brand = "Unknown";
                var maskedCard = "";

                if (verifyResult.TryGetProperty("payment_method", out var pm))
                {
                    if (pm.TryGetProperty("id", out var idEl)) pmtId = idEl.GetString() ?? "";
                    if (pm.TryGetProperty("card", out var cardEl))
                    {
                        if (cardEl.TryGetProperty("brand", out var brandEl)) brand = brandEl.GetString() ?? "Unknown";
                        if (cardEl.TryGetProperty("masked_number_last4", out var last4El)) maskedCard = last4El.GetString() ?? "";
                    }
                }

                if (string.IsNullOrEmpty(pmtId))
                    throw new Exception("Failed to create network token: no token returned");

                var tokenStatus = "Failed";
                if (verifyResult.TryGetProperty("action", out var actionEl) &&
                    actionEl.TryGetProperty("result_code", out var rcEl) &&
                    string.Equals(rcEl.GetString(), "SUCCESS", StringComparison.OrdinalIgnoreCase))
                    tokenStatus = "Active";

                var tokenData = new
                {
                    id = pmtId,
                    brand,
                    masked_card = maskedCard,
                    usage_mode = "USE_NETWORK_TOKEN",
                    status = tokenStatus,
                    created_at = DateTime.UtcNow.ToString("o")
                };

                // Save to tokens.json
                var dataDir = Path.Combine(AppContext.BaseDirectory, "data");
                Directory.CreateDirectory(dataDir);
                var dataFile = Path.Combine(dataDir, "tokens.json");

                var tokens = File.Exists(dataFile)
                    ? JsonSerializer.Deserialize<List<object>>(File.ReadAllText(dataFile)) ?? new List<object>()
                    : new List<object>();
                tokens.Add(tokenData);
                File.WriteAllText(dataFile, JsonSerializer.Serialize(tokens, new JsonSerializerOptions { WriteIndented = true }));

                return Results.Ok(new
                {
                    success = true,
                    data = tokenData,
                    message = "Network token created successfully"
                });
            }
            catch (Exception ex)
            {
                return Results.Json(new { success = false, message = ex.Message }, statusCode: 400);
            }
        });

        // List saved network tokens
        app.MapGet("/list-tokens", (HttpContext context) =>
        {
            try
            {
                var dataFile = Path.Combine(AppContext.BaseDirectory, "data", "tokens.json");
                var tokens = File.Exists(dataFile)
                    ? JsonSerializer.Deserialize<object>(File.ReadAllText(dataFile))
                    : new List<object>();

                return Results.Ok(new { success = true, data = tokens });
            }
            catch (Exception ex)
            {
                return Results.Json(new { success = false, message = ex.Message }, statusCode: 400);
            }
        });

        // Process payment using saved network token
        app.MapPost("/process-payment", async (HttpContext context) =>
        {
            try
            {
                var body = await JsonSerializer.DeserializeAsync<JsonElement>(context.Request.Body);

                if (!body.TryGetProperty("pmt_id", out var pmtElement) ||
                    string.IsNullOrEmpty(pmtElement.GetString()))
                    throw new Exception("Missing pmt_id");

                if (!body.TryGetProperty("amount", out var amountElement) ||
                    !amountElement.TryGetDecimal(out var amount) || amount <= 0)
                    throw new Exception("Invalid amount");

                var pmtId = pmtElement.GetString()!;
                var currency = body.TryGetProperty("currency", out var currEl)
                    ? currEl.GetString() ?? "USD"
                    : "USD";

                var accessToken2 = await GetAccessToken();
                var amountMinor = ((int)(amount * 100)).ToString();

                var txPayload = new
                {
                    account_name = "transaction_processing",
                    type = "SALE",
                    channel = "CNP",
                    capture_mode = "AUTO",
                    amount = amountMinor,
                    currency,
                    country = "US",
                    reference = GenerateNonce(),
                    payment_method = new
                    {
                        entry_mode = "ECOM",
                        id = pmtId,
                        usage_mode = "USE_NETWORK_TOKEN"
                    }
                };

                var txEndpoint = "production".Equals(System.Environment.GetEnvironmentVariable("GP_API_ENVIRONMENT"))
                    ? "https://apis.globalpay.com/ucp/transactions"
                    : "https://apis.sandbox.globalpay.com/ucp/transactions";

                using var txHandler = new System.Net.Http.HttpClientHandler
                {
                    AutomaticDecompression = System.Net.DecompressionMethods.GZip | System.Net.DecompressionMethods.Deflate
                };
                using var txClient = new System.Net.Http.HttpClient(txHandler);
                txClient.DefaultRequestHeaders.Add("X-GP-Version", "2021-03-22");
                txClient.DefaultRequestHeaders.Add("Authorization", "Bearer " + accessToken2);

                var txJson = JsonSerializer.Serialize(txPayload);
                var txContent = new System.Net.Http.StringContent(txJson, Encoding.UTF8, "application/json");

                var txResp = await txClient.PostAsync(txEndpoint, txContent);
                var txBody = await txResp.Content.ReadAsStringAsync();

                if (!txResp.IsSuccessStatusCode)
                {
                    var errJson = JsonSerializer.Deserialize<JsonElement>(txBody);
                    var errMsg = "Transaction failed";
                    if (errJson.TryGetProperty("details", out var dets) && dets.GetArrayLength() > 0)
                        errMsg = dets[0].GetProperty("description").GetString() ?? errMsg;
                    return Results.Json(new { success = false, message = errMsg }, statusCode: 400);
                }

                var txResult = JsonSerializer.Deserialize<JsonElement>(txBody);
                var txnId = txResult.TryGetProperty("id", out var idEl2) ? idEl2.GetString() ?? "" : "";
                var txStatus = "";
                if (txResult.TryGetProperty("action", out var txAction) && txAction.TryGetProperty("result_code", out var rcEl2))
                    txStatus = rcEl2.GetString() ?? "";
                var authCode = "";
                if (txResult.TryGetProperty("payment_method", out var txPm) &&
                    txPm.TryGetProperty("card", out var txCard) &&
                    txCard.TryGetProperty("authcode", out var acEl))
                    authCode = acEl.GetString() ?? "";

                return Results.Ok(new
                {
                    success = true,
                    data = new
                    {
                        transactionId = txnId,
                        status = txStatus,
                        amount,
                        currency,
                        authCode,
                        tokenUsageMode = "USE_NETWORK_TOKEN"
                    },
                    message = "Payment processed successfully"
                });
            }
            catch (Exception ex)
            {
                return Results.Json(new { success = false, message = ex.Message }, statusCode: 400);
            }
        });
    }
}
