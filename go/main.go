// Network Tokenization - Go
//
// Uses direct GP-API HTTP calls since the Go SDK does not support GP-API.
//
// Endpoints:
// - POST /config              Generate Drop-In UI access token
// - POST /create-network-token Create network token from single-use Drop-In UI token
// - GET  /list-tokens         List saved network tokens
// - POST /process-payment     Process payment using saved network token
package main

import (
	"bytes"
	"crypto/sha512"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"log"
	"crypto/rand"
	"math"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"time"

	"github.com/joho/godotenv"
)

type TokenData struct {
	ID         string `json:"id"`
	Brand      string `json:"brand"`
	MaskedCard string `json:"masked_card"`
	UsageMode  string `json:"usage_mode"`
	Status     string `json:"status"`
	CreatedAt  string `json:"created_at"`
}

type APIResponse struct {
	Success bool        `json:"success"`
	Data    interface{} `json:"data,omitempty"`
	Message string      `json:"message,omitempty"`
}

var tokensFile string

func init() {
	home, _ := os.UserHomeDir()
	dataDir := filepath.Join(home, ".network-tokens")
	os.MkdirAll(dataDir, 0755)
	tokensFile = filepath.Join(dataDir, "tokens.json")
}

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

func apiBaseURL() string {
	if os.Getenv("GP_API_ENVIRONMENT") == "production" {
		return "https://apis.globalpay.com/ucp"
	}
	return "https://apis.sandbox.globalpay.com/ucp"
}

func getAccessToken(permissions []string) (string, error) {
	nonce := generateNonce()
	secret := hashSecret(nonce, os.Getenv("GP_API_APP_KEY"))

	payload := map[string]interface{}{
		"app_id":           os.Getenv("GP_API_APP_ID"),
		"nonce":            nonce,
		"secret":           secret,
		"grant_type":       "client_credentials",
		"seconds_to_expire": 600,
		"permissions":      permissions,
	}

	body, _ := json.Marshal(payload)
	req, _ := http.NewRequest("POST", apiBaseURL()+"/accesstoken", bytes.NewBuffer(body))
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("X-GP-Version", "2021-03-22")

	client := &http.Client{Timeout: 30 * time.Second}
	resp, err := client.Do(req)
	if err != nil {
		return "", err
	}
	defer resp.Body.Close()

	var result map[string]interface{}
	json.NewDecoder(resp.Body).Decode(&result)

	token, ok := result["token"].(string)
	if !ok || token == "" {
		if desc, ok := result["error_description"].(string); ok {
			return "", fmt.Errorf(desc)
		}
		return "", fmt.Errorf("failed to get access token")
	}
	return token, nil
}

func readTokens() ([]TokenData, error) {
	data, err := os.ReadFile(tokensFile)
	if err != nil {
		return []TokenData{}, nil
	}
	var tokens []TokenData
	json.Unmarshal(data, &tokens)
	return tokens, nil
}

func writeTokens(tokens []TokenData) error {
	data, err := json.MarshalIndent(tokens, "", "  ")
	if err != nil {
		return err
	}
	return os.WriteFile(tokensFile, data, 0644)
}

func writeJSON(w http.ResponseWriter, status int, v interface{}) {
	w.Header().Set("Content-Type", "application/json")
	w.Header().Set("Access-Control-Allow-Origin", "*")
	w.WriteHeader(status)
	json.NewEncoder(w).Encode(v)
}

func handleConfig(w http.ResponseWriter, r *http.Request) {
	if r.Method == http.MethodOptions {
		w.Header().Set("Access-Control-Allow-Origin", "*")
		w.Header().Set("Access-Control-Allow-Methods", "GET, OPTIONS")
		w.Header().Set("Access-Control-Allow-Headers", "Content-Type")
		w.WriteHeader(200)
		return
	}
	if r.Method != http.MethodGet {
		writeJSON(w, 405, APIResponse{Success: false, Message: "Method not allowed"})
		return
	}

	token, err := getAccessToken([]string{"PMT_POST_Create_Single"})
	if err != nil {
		writeJSON(w, 400, APIResponse{Success: false, Message: err.Error()})
		return
	}
	writeJSON(w, 200, map[string]interface{}{"success": true, "data": map[string]interface{}{"accessToken": token}})
}

func handleCreateNetworkToken(w http.ResponseWriter, r *http.Request) {
	if r.Method == http.MethodOptions {
		w.Header().Set("Access-Control-Allow-Origin", "*")
		w.Header().Set("Access-Control-Allow-Methods", "POST, OPTIONS")
		w.Header().Set("Access-Control-Allow-Headers", "Content-Type")
		w.WriteHeader(200)
		return
	}
	if r.Method != http.MethodPost {
		writeJSON(w, 405, APIResponse{Success: false, Message: "Method not allowed"})
		return
	}

	var input struct {
		PaymentReference string `json:"payment_reference"`
	}
	if err := json.NewDecoder(r.Body).Decode(&input); err != nil || input.PaymentReference == "" {
		writeJSON(w, 400, APIResponse{Success: false, Message: "Missing payment_reference"})
		return
	}

	accessToken, err := getAccessToken(nil)
	if err != nil {
		writeJSON(w, 400, APIResponse{Success: false, Message: "Failed to authenticate: " + err.Error()})
		return
	}

	// Verify with storage_mode ON_SUCCESS to create a stored PMT_ID
	reference := generateNonce()
	payload := map[string]interface{}{
		"account_name": "transaction_processing",
		"channel":      "CNP",
		"reference":    reference,
		"currency":     "USD",
		"country":      "US",
		"payment_method": map[string]interface{}{
			"entry_mode": "ECOM",
			"id":         input.PaymentReference,
			"storage_mode": "ON_SUCCESS",
		},
	}

	body, _ := json.Marshal(payload)
	req, _ := http.NewRequest("POST", apiBaseURL()+"/verifications", bytes.NewBuffer(body))
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("X-GP-Version", "2021-03-22")
	req.Header.Set("Authorization", "Bearer "+accessToken)

	client := &http.Client{Timeout: 30 * time.Second}
	resp, err := client.Do(req)
	if err != nil {
		writeJSON(w, 400, APIResponse{Success: false, Message: err.Error()})
		return
	}
	defer resp.Body.Close()

	respBody, _ := io.ReadAll(resp.Body)
	var result map[string]interface{}
	json.Unmarshal(respBody, &result)

	if resp.StatusCode != 200 && resp.StatusCode != 201 {
		msg := "Failed to create network token"
		if details, ok := result["details"].([]interface{}); ok && len(details) > 0 {
			if d, ok := details[0].(map[string]interface{}); ok {
				if m, ok := d["description"].(string); ok {
					msg = m
				}
			}
		}
		writeJSON(w, 400, APIResponse{Success: false, Message: msg})
		return
	}

	pmtID := ""
	brand := "Unknown"
	maskedCard := ""

	if pm, ok := result["payment_method"].(map[string]interface{}); ok {
		if id, ok := pm["id"].(string); ok {
			pmtID = id
		}
		if card, ok := pm["card"].(map[string]interface{}); ok {
			if b, ok := card["brand"].(string); ok {
				brand = b
			}
			if last4, ok := card["masked_number_last4"].(string); ok {
				maskedCard = last4
			}
		}
	}

	if pmtID == "" {
		writeJSON(w, 400, APIResponse{Success: false, Message: "Failed to create network token: no token returned"})
		return
	}

	status := "Failed"
	if action, ok := result["action"].(map[string]interface{}); ok {
		if rc, ok := action["result_code"].(string); ok {
			if strings.EqualFold(rc, "SUCCESS") {
				status = "Active"
			}
		}
	}

	tokenData := TokenData{
		ID:         pmtID,
		Brand:      brand,
		MaskedCard: maskedCard,
		UsageMode:  "USE_NETWORK_TOKEN",
		Status:     status,
		CreatedAt:  time.Now().UTC().Format(time.RFC3339),
	}

	tokens, _ := readTokens()
	tokens = append(tokens, tokenData)
	writeTokens(tokens)

	writeJSON(w, 200, map[string]interface{}{
		"success": true,
		"data":    tokenData,
		"message": "Network token created successfully",
	})
}

func handleListTokens(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Access-Control-Allow-Origin", "*")
	if r.Method != http.MethodGet {
		writeJSON(w, 405, APIResponse{Success: false, Message: "Method not allowed"})
		return
	}

	tokens, err := readTokens()
	if err != nil {
		writeJSON(w, 400, APIResponse{Success: false, Message: err.Error()})
		return
	}
	writeJSON(w, 200, map[string]interface{}{"success": true, "data": tokens})
}

func handleProcessPayment(w http.ResponseWriter, r *http.Request) {
	if r.Method == http.MethodOptions {
		w.Header().Set("Access-Control-Allow-Origin", "*")
		w.Header().Set("Access-Control-Allow-Methods", "POST, OPTIONS")
		w.Header().Set("Access-Control-Allow-Headers", "Content-Type")
		w.WriteHeader(200)
		return
	}
	if r.Method != http.MethodPost {
		writeJSON(w, 405, APIResponse{Success: false, Message: "Method not allowed"})
		return
	}

	var input struct {
		PmtID    string  `json:"pmt_id"`
		Amount   float64 `json:"amount"`
		Currency string  `json:"currency"`
	}
	if err := json.NewDecoder(r.Body).Decode(&input); err != nil || input.PmtID == "" {
		writeJSON(w, 400, APIResponse{Success: false, Message: "Missing pmt_id"})
		return
	}
	if input.Amount <= 0 {
		writeJSON(w, 400, APIResponse{Success: false, Message: "Invalid amount"})
		return
	}
	if input.Currency == "" {
		input.Currency = "USD"
	}

	accessToken, err := getAccessToken(nil)
	if err != nil {
		writeJSON(w, 400, APIResponse{Success: false, Message: "Failed to authenticate: " + err.Error()})
		return
	}

	// Convert amount to minor units (cents)
	amountMinor := fmt.Sprintf("%d", int(math.Round(input.Amount*100)))

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

	body, _ := json.Marshal(payload)
	req, _ := http.NewRequest("POST", apiBaseURL()+"/transactions", bytes.NewBuffer(body))
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("X-GP-Version", "2021-03-22")
	req.Header.Set("Authorization", "Bearer "+accessToken)

	client := &http.Client{Timeout: 30 * time.Second}
	resp, err := client.Do(req)
	if err != nil {
		writeJSON(w, 400, APIResponse{Success: false, Message: err.Error()})
		return
	}
	defer resp.Body.Close()

	respBody, _ := io.ReadAll(resp.Body)
	var result map[string]interface{}
	json.Unmarshal(respBody, &result)

	if resp.StatusCode != 200 && resp.StatusCode != 201 {
		msg := "Transaction failed"
		if details, ok := result["details"].([]interface{}); ok && len(details) > 0 {
			if d, ok := details[0].(map[string]interface{}); ok {
				if m, ok := d["description"].(string); ok {
					msg = m
				}
			}
		}
		writeJSON(w, 400, APIResponse{Success: false, Message: msg})
		return
	}

	txnID := fmt.Sprint(result["id"])
	status := fmt.Sprint(result["status"])
	authCode := ""
	if action, ok := result["action"].(map[string]interface{}); ok {
		if rc, ok := action["result_code"].(string); ok {
			status = rc
		}
	}
	if pm, ok := result["payment_method"].(map[string]interface{}); ok {
		if card, ok := pm["card"].(map[string]interface{}); ok {
			if ac, ok := card["authcode"].(string); ok {
				authCode = ac
			}
		}
	}

	writeJSON(w, 200, map[string]interface{}{
		"success": true,
		"data": map[string]interface{}{
			"transactionId": txnID,
			"status":        status,
			"amount":        input.Amount,
			"currency":      input.Currency,
			"authCode":      authCode,
			"tokenUsageMode": "USE_NETWORK_TOKEN",
		},
		"message": "Payment processed successfully",
	})
}

func main() {
	if err := godotenv.Load(); err != nil {
		log.Fatal("Error loading .env file")
	}

	staticDir := "static"
	http.Handle("/", http.FileServer(http.Dir(staticDir)))
	http.HandleFunc("/config", handleConfig)
	http.HandleFunc("/create-network-token", handleCreateNetworkToken)
	http.HandleFunc("/list-tokens", handleListTokens)
	http.HandleFunc("/process-payment", handleProcessPayment)

	port := os.Getenv("PORT")
	if port == "" {
		port = "8000"
	}

	log.Printf("✅ Server running at http://localhost:%s", port)
	log.Printf("Environment: %s", os.Getenv("GP_API_ENVIRONMENT"))
	log.Fatal(http.ListenAndServe("0.0.0.0:"+port, nil))
}
