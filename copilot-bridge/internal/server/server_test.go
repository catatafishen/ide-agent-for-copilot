package server

import (
	"bytes"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"
	
	"github.com/yourusername/intellij-copilot-plugin/copilot-bridge/internal/copilot"
)

func TestHealthCheck(t *testing.T) {
	// Use mock client for testing
	mockClient := copilot.NewMockClient()
	server, _ := NewWithClient(0, "", mockClient)
	
	req := httptest.NewRequest("GET", "/health", nil)
	w := httptest.NewRecorder()
	
	server.handleHealth(w, req)
	
	if w.Code != http.StatusOK {
		t.Errorf("Expected status 200, got %d", w.Code)
	}
	
	var response map[string]string
	json.NewDecoder(w.Body).Decode(&response)
	
	if response["status"] != "ok" {
		t.Errorf("Expected status 'ok', got '%s'", response["status"])
	}
}

func TestModelsListRPC(t *testing.T) {
	// Use mock client for testing
	mockClient := copilot.NewMockClient()
	server, _ := NewWithClient(0, "", mockClient)
	
	reqBody := map[string]interface{}{
		"jsonrpc": "2.0",
		"id":      1,
		"method":  "models.list",
		"params":  map[string]interface{}{},
	}
	
	body, _ := json.Marshal(reqBody)
	req := httptest.NewRequest("POST", "/rpc", bytes.NewBuffer(body))
	req.Header.Set("Content-Type", "application/json")
	w := httptest.NewRecorder()
	
	server.handleRPC(w, req)
	
	if w.Code != http.StatusOK {
		t.Errorf("Expected status 200, got %d", w.Code)
	}
	
	var response JSONRPCResponse
	json.NewDecoder(w.Body).Decode(&response)
	
	if response.Error != nil {
		t.Errorf("Expected no error, got %v", response.Error)
	}
	
	result := response.Result.(map[string]interface{})
	models := result["models"].([]interface{})
	
	// MockClient returns 3 models (not 5 like SDKClient would)
	if len(models) != 3 {
		t.Errorf("Expected 3 models from MockClient, got %d", len(models))
	}
}

func TestSessionCreateRPC(t *testing.T) {
	// Use mock client for testing
	mockClient := copilot.NewMockClient()
	server, _ := NewWithClient(0, "", mockClient)
	
	reqBody := map[string]interface{}{
		"jsonrpc": "2.0",
		"id":      1,
		"method":  "session.create",
		"params":  map[string]interface{}{},
	}
	
	body, _ := json.Marshal(reqBody)
	req := httptest.NewRequest("POST", "/rpc", bytes.NewBuffer(body))
	req.Header.Set("Content-Type", "application/json")
	w := httptest.NewRecorder()
	
	server.handleRPC(w, req)
	
	if w.Code != http.StatusOK {
		t.Errorf("Expected status 200, got %d", w.Code)
	}
	
	var response JSONRPCResponse
	json.NewDecoder(w.Body).Decode(&response)
	
	if response.Error != nil {
		t.Errorf("Expected no error, got %v", response.Error)
	}
	
	result := response.Result.(map[string]interface{})
	sessionId := result["sessionId"].(string)
	
	if sessionId == "" {
		t.Error("Expected non-empty session ID")
	}
}

func TestSessionSendRPC(t *testing.T) {
	// Use mock client for testing
	mockClient := copilot.NewMockClient()
	server, _ := NewWithClient(0, "", mockClient)
	
	// First create a session
	createReqBody := map[string]interface{}{
		"jsonrpc": "2.0",
		"id":      1,
		"method":  "session.create",
		"params":  map[string]interface{}{},
	}
	
	body, _ := json.Marshal(createReqBody)
	req := httptest.NewRequest("POST", "/rpc", bytes.NewBuffer(body))
	req.Header.Set("Content-Type", "application/json")
	w := httptest.NewRecorder()
	server.handleRPC(w, req)
	
	var createResponse JSONRPCResponse
	json.NewDecoder(w.Body).Decode(&createResponse)
	result := createResponse.Result.(map[string]interface{})
	sessionId := result["sessionId"].(string)
	
	// Now send a message
	sendReqBody := map[string]interface{}{
		"jsonrpc": "2.0",
		"id":      2,
		"method":  "session.send",
		"params": map[string]interface{}{
			"sessionId": sessionId,
			"prompt":    "Test prompt",
			"model":     "gpt-4o",
		},
	}
	
	body, _ = json.Marshal(sendReqBody)
	req = httptest.NewRequest("POST", "/rpc", bytes.NewBuffer(body))
	req.Header.Set("Content-Type", "application/json")
	w = httptest.NewRecorder()
	
	server.handleRPC(w, req)
	
	if w.Code != http.StatusOK {
		t.Errorf("Expected status 200, got %d", w.Code)
	}
	
	var sendResponse JSONRPCResponse
	json.NewDecoder(w.Body).Decode(&sendResponse)
	
	if sendResponse.Error != nil {
		t.Errorf("Expected no error, got %v", sendResponse.Error)
	}
	
	result = sendResponse.Result.(map[string]interface{})
	messageId := result["messageId"].(string)
	
	if messageId == "" {
		t.Error("Expected non-empty message ID")
	}
}

func TestSessionCloseRPC(t *testing.T) {
	// Use mock client for testing
	mockClient := copilot.NewMockClient()
	server, _ := NewWithClient(0, "", mockClient)
	
	// First create a session
	createReqBody := map[string]interface{}{
		"jsonrpc": "2.0",
		"id":      1,
		"method":  "session.create",
		"params":  map[string]interface{}{},
	}
	
	body, _ := json.Marshal(createReqBody)
	req := httptest.NewRequest("POST", "/rpc", bytes.NewBuffer(body))
	req.Header.Set("Content-Type", "application/json")
	w := httptest.NewRecorder()
	server.handleRPC(w, req)
	
	var createResponse JSONRPCResponse
	json.NewDecoder(w.Body).Decode(&createResponse)
	result := createResponse.Result.(map[string]interface{})
	sessionId := result["sessionId"].(string)
	
	// Now close the session
	closeReqBody := map[string]interface{}{
		"jsonrpc": "2.0",
		"id":      2,
		"method":  "session.close",
		"params": map[string]interface{}{
			"sessionId": sessionId,
		},
	}
	
	body, _ = json.Marshal(closeReqBody)
	req = httptest.NewRequest("POST", "/rpc", bytes.NewBuffer(body))
	req.Header.Set("Content-Type", "application/json")
	w = httptest.NewRecorder()
	
	server.handleRPC(w, req)
	
	if w.Code != http.StatusOK {
		t.Errorf("Expected status 200, got %d", w.Code)
	}
	
	var closeResponse JSONRPCResponse
	json.NewDecoder(w.Body).Decode(&closeResponse)
	
	if closeResponse.Error != nil {
		t.Errorf("Expected no error, got %v", closeResponse.Error)
	}
}

func TestInvalidMethod(t *testing.T) {
	server, _ := New(0, "")
	
	reqBody := map[string]interface{}{
		"jsonrpc": "2.0",
		"id":      1,
		"method":  "invalid.method",
		"params":  map[string]interface{}{},
	}
	
	body, _ := json.Marshal(reqBody)
	req := httptest.NewRequest("POST", "/rpc", bytes.NewBuffer(body))
	req.Header.Set("Content-Type", "application/json")
	w := httptest.NewRecorder()
	
	server.handleRPC(w, req)
	
	if w.Code != http.StatusOK {
		t.Errorf("Expected status 200, got %d", w.Code)
	}
	
	var response JSONRPCResponse
	json.NewDecoder(w.Body).Decode(&response)
	
	if response.Error == nil {
		t.Error("Expected error for invalid method")
	}
	
	if response.Error.Code != -32601 {
		t.Errorf("Expected error code -32601, got %d", response.Error.Code)
	}
}

func TestInvalidJSON(t *testing.T) {
	// Use mock client for testing
	mockClient := copilot.NewMockClient()
	server, _ := NewWithClient(0, "", mockClient)
	
	req := httptest.NewRequest("POST", "/rpc", bytes.NewBufferString("{invalid json"))
	req.Header.Set("Content-Type", "application/json")
	w := httptest.NewRecorder()
	
	server.handleRPC(w, req)
	
	if w.Code != http.StatusOK {
		t.Errorf("Expected status 200, got %d", w.Code)
	}
	
	var response JSONRPCResponse
	json.NewDecoder(w.Body).Decode(&response)
	
	if response.Error == nil {
		t.Error("Expected error for invalid JSON")
	}
	
	if response.Error.Code != -32700 {
		t.Errorf("Expected error code -32700, got %d", response.Error.Code)
	}
}

func TestSessionNotFound(t *testing.T) {
	server, _ := New(0, "")
	
	reqBody := map[string]interface{}{
		"jsonrpc": "2.0",
		"id":      1,
		"method":  "session.send",
		"params": map[string]interface{}{
			"sessionId": "non-existent-session",
			"prompt":    "Test",
			"model":     "gpt-4o",
		},
	}
	
	body, _ := json.Marshal(reqBody)
	req := httptest.NewRequest("POST", "/rpc", bytes.NewBuffer(body))
	req.Header.Set("Content-Type", "application/json")
	w := httptest.NewRecorder()
	
	server.handleRPC(w, req)
	
	if w.Code != http.StatusOK {
		t.Errorf("Expected status 200, got %d", w.Code)
	}
	
	var response JSONRPCResponse
	json.NewDecoder(w.Body).Decode(&response)
	
	if response.Error == nil {
		t.Error("Expected error for non-existent session")
	}
}
