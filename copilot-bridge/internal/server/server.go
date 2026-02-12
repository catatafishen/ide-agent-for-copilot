package server

import (
	"context"
	"encoding/json"
	"fmt"
	"log"
	"net"
	"net/http"
	"sync"
	"time"

	"github.com/yourusername/intellij-copilot-plugin/copilot-bridge/internal/copilot"
	"github.com/yourusername/intellij-copilot-plugin/copilot-bridge/internal/session"
)

// Server handles JSON-RPC requests from the IntelliJ plugin
type Server struct {
	port              int
	pluginCallbackURL string
	httpServer        *http.Server
	sessionManager    *session.Manager
	listener          net.Listener
	mu                sync.RWMutex
}

// New creates a new RPC server with default (SDK) client
// Returns error if SDK client cannot be initialized
func New(port int, callbackURL string) (*Server, error) {
	return NewWithClient(port, callbackURL, nil)
}

// NewWithClient creates a new RPC server with a specific client
// If client is nil, creates an SDK client (returns error if it fails)
func NewWithClient(port int, callbackURL string, client copilot.Client) (*Server, error) {
	var copilotClient copilot.Client
	
	if client != nil {
		// Use provided client (for testing)
		copilotClient = client
	} else {
		// Create real Copilot SDK client for production
		sdkClient, err := copilot.NewSDKClient()
		if err != nil {
			return nil, fmt.Errorf("failed to initialize Copilot SDK client: %w\n\nPlease ensure:\n1. GitHub Copilot CLI is installed\n2. You are authenticated (run: gh auth login)\n3. Copilot is enabled for your account", err)
		}
		copilotClient = sdkClient
	}
	
	return &Server{
		port:              port,
		pluginCallbackURL: callbackURL,
		sessionManager:    session.NewManager(copilotClient),
	}, nil
}

// Start begins listening for requests
func (s *Server) Start() error {
	var err error
	s.listener, err = net.Listen("tcp", fmt.Sprintf("localhost:%d", s.port))
	if err != nil {
		return fmt.Errorf("failed to listen: %w", err)
	}

	// If port was 0, get the dynamically allocated port
	s.port = s.listener.Addr().(*net.TCPAddr).Port

	mux := http.NewServeMux()
	mux.HandleFunc("/rpc", s.handleRPC)
	mux.HandleFunc("/stream/", s.handleStream)
	mux.HandleFunc("/health", s.handleHealth)

	s.httpServer = &http.Server{
		Handler:      mux,
		ReadTimeout:  30 * time.Second,
		WriteTimeout: 30 * time.Second,
		IdleTimeout:  120 * time.Second,
	}

	go func() {
		if err := s.httpServer.Serve(s.listener); err != nil && err != http.ErrServerClosed {
			log.Printf("Server error: %v", err)
		}
	}()

	return nil
}

// Port returns the port the server is listening on
func (s *Server) Port() int {
	return s.port
}

// Shutdown gracefully stops the server
func (s *Server) Shutdown() error {
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	return s.httpServer.Shutdown(ctx)
}

// handleHealth responds to health check requests
func (s *Server) handleHealth(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(map[string]string{"status": "ok"})
}

// handleRPC processes JSON-RPC 2.0 requests
func (s *Server) handleRPC(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		s.writeError(w, nil, -32600, "Invalid Request", "Method must be POST")
		return
	}

	var req JSONRPCRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		s.writeError(w, nil, -32700, "Parse error", err.Error())
		return
	}

	log.Printf("RPC Request: method=%s id=%v", req.Method, req.ID)

	var result interface{}
	var err error

	switch req.Method {
	case "session.create":
		result, err = s.handleSessionCreate(req.Params)
	case "session.close":
		result, err = s.handleSessionClose(req.Params)
	case "session.send":
		result, err = s.handleSessionSend(req.Params)
	case "models.list":
		result, err = s.handleModelsList(req.Params)
	default:
		s.writeError(w, req.ID, -32601, "Method not found", fmt.Sprintf("Unknown method: %s", req.Method))
		return
	}

	if err != nil {
		s.writeError(w, req.ID, -32603, "Internal error", err.Error())
		return
	}

	s.writeResult(w, req.ID, result)
}

// handleStream handles Server-Sent Events for session event streaming
func (s *Server) handleStream(w http.ResponseWriter, r *http.Request) {
	// Extract session ID from path
	sessionID := r.URL.Path[len("/stream/"):]
	if sessionID == "" {
		http.Error(w, "Session ID required", http.StatusBadRequest)
		return
	}

	log.Printf("Stream request for session: %s", sessionID)

	// Get session
	sess, ok := s.sessionManager.Get(sessionID)
	if !ok {
		http.Error(w, "Session not found", http.StatusNotFound)
		return
	}

	// Set SSE headers
	w.Header().Set("Content-Type", "text/event-stream")
	w.Header().Set("Cache-Control", "no-cache")
	w.Header().Set("Connection", "keep-alive")
	w.Header().Set("X-Accel-Buffering", "no") // Disable nginx buffering

	flusher, ok := w.(http.Flusher)
	if !ok {
		http.Error(w, "Streaming not supported", http.StatusInternalServerError)
		return
	}

	// Send connected event
	fmt.Fprintf(w, "event: connected\ndata: {\"sessionId\":\"%s\"}\n\n", sessionID)
	flusher.Flush()

	// Stream events from session channel
	ctx := r.Context()
	for {
		select {
		case <-ctx.Done():
			// Client disconnected
			log.Printf("Client disconnected from stream: %s", sessionID)
			return
			
		case event, ok := <-sess.EventChan:
			if !ok {
				// Channel closed, send done event
				fmt.Fprintf(w, "event: done\ndata: {\"status\":\"complete\"}\n\n")
				flusher.Flush()
				return
			}
			
			// Send event to client
			fmt.Fprintf(w, "data: %s\n\n", event)
			flusher.Flush()
		}
	}
}

// JSONRPCRequest represents a JSON-RPC 2.0 request
type JSONRPCRequest struct {
	JSONRPC string          `json:"jsonrpc"`
	ID      interface{}     `json:"id"`
	Method  string          `json:"method"`
	Params  json.RawMessage `json:"params"`
}

// JSONRPCResponse represents a JSON-RPC 2.0 response
type JSONRPCResponse struct {
	JSONRPC string      `json:"jsonrpc"`
	ID      interface{} `json:"id"`
	Result  interface{} `json:"result,omitempty"`
	Error   *RPCError   `json:"error,omitempty"`
}

// RPCError represents a JSON-RPC 2.0 error
type RPCError struct {
	Code    int         `json:"code"`
	Message string      `json:"message"`
	Data    interface{} `json:"data,omitempty"`
}

func (s *Server) writeResult(w http.ResponseWriter, id interface{}, result interface{}) {
	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(JSONRPCResponse{
		JSONRPC: "2.0",
		ID:      id,
		Result:  result,
	})
}

func (s *Server) writeError(w http.ResponseWriter, id interface{}, code int, message, data string) {
	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(JSONRPCResponse{
		JSONRPC: "2.0",
		ID:      id,
		Error: &RPCError{
			Code:    code,
			Message: message,
			Data:    data,
		},
	})
}

// Method handlers
func (s *Server) handleSessionCreate(params json.RawMessage) (interface{}, error) {
	sess, err := s.sessionManager.Create()
	if err != nil {
		return nil, err
	}
	
	return map[string]interface{}{
		"sessionId": sess.ID,
		"createdAt": sess.CreatedAt.Format(time.RFC3339),
	}, nil
}

func (s *Server) handleSessionClose(params json.RawMessage) (interface{}, error) {
	var req struct {
		SessionID string `json:"sessionId"`
	}
	if err := json.Unmarshal(params, &req); err != nil {
		return nil, err
	}

	if err := s.sessionManager.Close(req.SessionID); err != nil {
		return nil, err
	}
	
	return map[string]bool{"closed": true}, nil
}

func (s *Server) handleSessionSend(params json.RawMessage) (interface{}, error) {
	var req struct {
		SessionID   string                 `json:"sessionId"`
		Prompt      string                 `json:"prompt"`
		Context     []copilot.ContextItem  `json:"context"`
		Model       string                 `json:"model"`
		Permissions map[string]string      `json:"permissions"`
	}
	if err := json.Unmarshal(params, &req); err != nil {
		return nil, err
	}

	// Get session
	sess, ok := s.sessionManager.Get(req.SessionID)
	if !ok {
		return nil, fmt.Errorf("session not found: %s", req.SessionID)
	}

	// Send message via Copilot client
	client := s.sessionManager.GetClient()
	resp, err := client.SendMessage(context.Background(), &copilot.MessageRequest{
		SessionID:   req.SessionID,  // Use our session ID directly
		Prompt:      req.Prompt,
		Context:     req.Context,
		Model:       req.Model,
		Permissions: req.Permissions,
	})
	if err != nil {
		return nil, err
	}

	// Start streaming mock response chunks in background
	go s.streamMockResponse(sess, req.Prompt, req.Model)

	return map[string]string{
		"messageId": resp.MessageID,
		"streamUrl": fmt.Sprintf("/stream/%s", req.SessionID),
	}, nil
}

// streamMockResponse simulates streaming response chunks for demo
func (s *Server) streamMockResponse(sess *session.Session, prompt, model string) {
	// Mock response based on prompt
	responseChunks := []string{
		`{"type":"text","content":"I understand you're asking about: "}`,
		`{"type":"text","content":"\"` + truncate(prompt, 50) + `\"\n\n"}`,
		`{"type":"text","content":"Based on your query, here's what I can help with:\n"}`,
		`{"type":"text","content":"1. "}`,
		`{"type":"text","content":"Code analysis and understanding\n"}`,
		`{"type":"text","content":"2. "}`,
		`{"type":"text","content":"Implementation suggestions\n"}`,
		`{"type":"text","content":"3. "}`,
		`{"type":"text","content":"Best practices and patterns\n\n"}`,
		`{"type":"text","content":"Using model: ` + model + `\n"}`,
		`{"type":"text","content":"(Real Copilot SDK integration coming in Phase 4)"}`,
	}

	// Send chunks with realistic timing
	for i, chunk := range responseChunks {
		select {
		case sess.EventChan <- chunk:
			// Simulate typing delay (50-150ms per chunk)
			time.Sleep(time.Duration(50+i*10) * time.Millisecond)
		default:
			// Channel full or closed
			log.Printf("Cannot send chunk to session %s: channel issue", sess.ID)
			return
		}
	}

	// Send completion event
	sess.EventChan <- `{"type":"done","messageId":"mock-message-id"}`
}

// truncate truncates a string to max length with ellipsis
func truncate(s string, maxLen int) string {
	if len(s) <= maxLen {
		return s
	}
	return s[:maxLen-3] + "..."
}

func (s *Server) handleModelsList(params json.RawMessage) (interface{}, error) {
	client := s.sessionManager.GetClient()
	models, err := client.ListModels(context.Background())
	if err != nil {
		return nil, err
	}

	return map[string]interface{}{
		"models": models,
	}, nil
}
