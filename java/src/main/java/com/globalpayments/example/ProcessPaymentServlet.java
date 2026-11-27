package com.globalpayments.example;

import io.github.cdimascio.dotenv.Dotenv;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.stream.Collectors;

/**
 * Network Tokenization Servlet
 *
 * Endpoints:
 * - GET  /config:               Generate Drop-In UI access token
 * - POST /create-network-token: Create network token from single-use Drop-In UI token
 * - GET  /list-tokens:          List saved network tokens
 * - POST /process-payment:      Process payment using saved network token
 */
@WebServlet(urlPatterns = {"/config", "/create-network-token", "/list-tokens", "/process-payment"})
public class ProcessPaymentServlet extends HttpServlet {

    private static final long serialVersionUID = 1L;
    private final Dotenv dotenv = Dotenv.load();

    /** An error the GP API sent back. Its message is shown on the page. */
    private static class GatewayException extends Exception {
        GatewayException(String message) { super(message); }
    }

    private void writeError(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
        response.getWriter().write(new JSONObject().put("success", false).put("message", message).toString());
    }

    // Keep the details in the server log, the page only gets a generic message
    private void writeUnexpectedError(HttpServletResponse response, String step, Exception e) throws IOException {
        log(step + " failed", e);
        writeError(response, "Unexpected server error, see the server log");
    }

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

    private String apiBaseUrl() {
        return "production".equals(dotenv.get("GP_API_ENVIRONMENT", "sandbox"))
            ? "https://apis.globalpay.com/ucp"
            : "https://apis.sandbox.globalpay.com/ucp";
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

        URL url = new URL(apiBaseUrl() + "/accesstoken");
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("X-GP-Version", "2021-03-22");
        conn.setRequestProperty("Accept-Encoding", "identity");
        conn.setDoOutput(true);

        try (OutputStream os = conn.getOutputStream()) {
            os.write(payload.toString().getBytes(StandardCharsets.UTF_8));
        }

        int status = conn.getResponseCode();
        InputStream stream = (status >= 200 && status < 300) ? conn.getInputStream() : conn.getErrorStream();
        String body = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))
            .lines().collect(Collectors.joining());

        JSONObject result;
        try {
            result = new JSONObject(body);
        } catch (Exception e) {
            throw new GatewayException("Failed to get access token: " + body);
        }
        String token = result.optString("token", "");
        if (status < 200 || status >= 300 || token.isEmpty()) {
            String desc = result.optString("error_description", result.optString("detailed_error_description", ""));
            throw new GatewayException(desc.isEmpty() ? "Failed to get access token: " + body : desc);
        }
        return token;
    }

    private Path getTokensFile() throws IOException {
        Path dataDir = Paths.get(System.getProperty("user.home"), ".network-tokens");
        Files.createDirectories(dataDir);
        return dataDir.resolve("tokens.json");
    }

    private JSONArray readTokens() {
        try {
            Path file = getTokensFile();
            if (!Files.exists(file)) return new JSONArray();
            return new JSONArray(Files.readString(file));
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    private void writeTokens(JSONArray tokens) throws IOException {
        Files.writeString(getTokensFile(), tokens.toString(2));
    }

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");

        String path = request.getServletPath();
        if ("/list-tokens".equals(path)) {
            handleListTokens(response);
        } else if ("/config".equals(path)) {
            handleConfig(response);
        }
    }

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws IOException {
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");

        String path = request.getServletPath();
        switch (path) {
            case "/create-network-token" -> handleCreateNetworkToken(request, response);
            case "/process-payment" -> handleProcessPayment(request, response);
        }
    }

    private void handleConfig(HttpServletResponse response) throws IOException {
        try {
            String token = getAccessToken(new String[]{"PMT_POST_Create_Single"});
            JSONObject dataObj = new JSONObject();
            dataObj.put("accessToken", token);
            JSONObject result = new JSONObject();
            result.put("success", true);
            result.put("data", dataObj);
            response.getWriter().write(result.toString());
        } catch (GatewayException e) {
            writeError(response, e.getMessage());
        } catch (Exception e) {
            writeUnexpectedError(response, "config", e);
        }
    }

    private void handleCreateNetworkToken(HttpServletRequest request, HttpServletResponse response) throws IOException {
        try {
            String body = request.getReader().lines().collect(Collectors.joining());
            JSONObject jsonRequest = new JSONObject(body);

            if (!jsonRequest.has("payment_reference")) {
                writeError(response, "Missing payment_reference");
                return;
            }

            String paymentReference = jsonRequest.getString("payment_reference");
            String accessToken = getAccessToken(null);

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

            URL url = new URL(apiBaseUrl() + "/verifications");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("X-GP-Version", "2021-03-22");
            conn.setRequestProperty("Authorization", "Bearer " + accessToken);
            conn.setRequestProperty("Accept-Encoding", "identity");
            conn.setDoOutput(true);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(payload.toString().getBytes(StandardCharsets.UTF_8));
            }

            int status = conn.getResponseCode();
            InputStream stream = (status >= 200 && status < 300) ? conn.getInputStream() : conn.getErrorStream();
            String respBody = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))
                .lines().collect(Collectors.joining());

            if (status < 200 || status >= 300) {
                String msg = "Failed to create network token";
                try {
                    JSONObject errJson = new JSONObject(respBody);
                    if (errJson.has("details")) {
                        JSONArray details = errJson.getJSONArray("details");
                        if (details.length() > 0) msg = details.getJSONObject(0).optString("description", msg);
                    }
                } catch (Exception ignored) {}
                response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                response.getWriter().write(new JSONObject().put("success", false).put("message", msg).toString());
                return;
            }

            JSONObject result = new JSONObject(respBody);
            String pmtId = "";
            String brand = "Unknown";
            String maskedCard = "";

            if (result.has("payment_method")) {
                JSONObject pmResp = result.getJSONObject("payment_method");
                pmtId = pmResp.optString("id", "");
                if (pmResp.has("card")) {
                    JSONObject card = pmResp.getJSONObject("card");
                    brand = card.optString("brand", "Unknown");
                    maskedCard = card.optString("masked_number_last4", "");
                }
            }

            if (pmtId.isEmpty()) {
                response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                response.getWriter().write(new JSONObject().put("success", false).put("message", "Failed to create network token: no token returned").toString());
                return;
            }

            String tokenStatus = "Failed";
            if (result.has("action")) {
                String rc = result.getJSONObject("action").optString("result_code", "");
                if ("SUCCESS".equalsIgnoreCase(rc)) tokenStatus = "Active";
            }

            JSONObject tokenData = new JSONObject();
            tokenData.put("id", pmtId);
            tokenData.put("brand", brand);
            tokenData.put("masked_card", maskedCard);
            tokenData.put("usage_mode", "USE_NETWORK_TOKEN");
            tokenData.put("status", tokenStatus);
            tokenData.put("created_at", Instant.now().toString());

            JSONArray tokens = readTokens();
            tokens.put(tokenData);
            writeTokens(tokens);

            JSONObject resultObj = new JSONObject();
            resultObj.put("success", true);
            resultObj.put("data", tokenData);
            resultObj.put("message", "Network token created successfully");
            response.getWriter().write(resultObj.toString());

        } catch (GatewayException e) {
            writeError(response, e.getMessage());
        } catch (Exception e) {
            writeUnexpectedError(response, "create-network-token", e);
        }
    }

    private void handleListTokens(HttpServletResponse response) throws IOException {
        try {
            JSONArray tokens = readTokens();
            JSONObject result = new JSONObject();
            result.put("success", true);
            result.put("data", tokens);
            response.getWriter().write(result.toString());
        } catch (Exception e) {
            writeUnexpectedError(response, "list-tokens", e);
        }
    }

    private void handleProcessPayment(HttpServletRequest request, HttpServletResponse response) throws IOException {
        try {
            String body = request.getReader().lines().collect(Collectors.joining());
            JSONObject jsonRequest = new JSONObject(body);

            if (!jsonRequest.has("pmt_id")) {
                writeError(response, "Missing pmt_id");
                return;
            }
            if (!(jsonRequest.optDouble("amount", 0) > 0)) {
                writeError(response, "Invalid amount");
                return;
            }

            String pmtId = jsonRequest.getString("pmt_id");
            double amount = jsonRequest.getDouble("amount");
            String currency = jsonRequest.optString("currency", "USD");

            String accessToken = getAccessToken(null);
            long amountMinor = Math.round(amount * 100);

            JSONObject pm = new JSONObject();
            pm.put("entry_mode", "ECOM");
            pm.put("id", pmtId);
            pm.put("usage_mode", "USE_NETWORK_TOKEN");

            JSONObject payload = new JSONObject();
            payload.put("account_name", "transaction_processing");
            payload.put("type", "SALE");
            payload.put("channel", "CNP");
            payload.put("capture_mode", "AUTO");
            payload.put("amount", String.valueOf(amountMinor));
            payload.put("currency", currency);
            payload.put("country", "US");
            payload.put("reference", generateNonce());
            payload.put("payment_method", pm);

            URL url = new URL(apiBaseUrl() + "/transactions");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("X-GP-Version", "2021-03-22");
            conn.setRequestProperty("Authorization", "Bearer " + accessToken);
            conn.setRequestProperty("Accept-Encoding", "identity");
            conn.setDoOutput(true);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(payload.toString().getBytes(StandardCharsets.UTF_8));
            }

            int status = conn.getResponseCode();
            InputStream stream = (status >= 200 && status < 300) ? conn.getInputStream() : conn.getErrorStream();
            String respBody = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))
                .lines().collect(Collectors.joining());

            if (status < 200 || status >= 300) {
                String msg = "Transaction failed";
                try {
                    JSONObject errJson = new JSONObject(respBody);
                    if (errJson.has("details")) {
                        JSONArray details = errJson.getJSONArray("details");
                        if (details.length() > 0) msg = details.getJSONObject(0).optString("description", msg);
                    }
                } catch (Exception ignored) {}
                response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                response.getWriter().write(new JSONObject().put("success", false).put("message", msg).toString());
                return;
            }

            JSONObject txResult = new JSONObject(respBody);
            String txnId = txResult.optString("id", "");
            String txStatus = "";
            if (txResult.has("action")) {
                txStatus = txResult.getJSONObject("action").optString("result_code", "");
            }
            if (!"SUCCESS".equalsIgnoreCase(txStatus)) {
                // GP API sends declines back with HTTP 200, so the result code decides
                writeError(response, "Transaction declined: " + txResult.optString("status", txStatus));
                return;
            }
            String authCode = "";
            if (txResult.has("payment_method")) {
                JSONObject txPm = txResult.getJSONObject("payment_method");
                if (txPm.has("card")) {
                    authCode = txPm.getJSONObject("card").optString("authcode", "");
                }
            }

            JSONObject data = new JSONObject();
            data.put("transactionId", txnId);
            data.put("status", txStatus);
            data.put("amount", amount);
            data.put("currency", currency);
            data.put("authCode", authCode);
            data.put("tokenUsageMode", "USE_NETWORK_TOKEN");

            JSONObject result = new JSONObject();
            result.put("success", true);
            result.put("data", data);
            result.put("message", "Payment processed successfully");
            response.getWriter().write(result.toString());

        } catch (GatewayException e) {
            writeError(response, e.getMessage());
        } catch (Exception e) {
            writeUnexpectedError(response, "process-payment", e);
        }
    }
}
