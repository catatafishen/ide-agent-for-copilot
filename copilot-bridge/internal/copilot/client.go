package copilot

import (
	"context"
	"fmt"
	"time"

	"github.com/google/uuid"
)

// Client defines the interface for interacting with Copilot SDK
type Client interface {
	CreateSession(ctx context.Context) (*Session, error)
	CloseSession(ctx context.Context, sessionID string) error
	SendMessage(ctx context.Context, req *MessageRequest) (*MessageResponse, error)
	ListModels(ctx context.Context) ([]*Model, error)
}

// Session represents a Copilot agent session
type Session struct {
	ID        string
	CreatedAt time.Time
}

// MessageRequest represents a prompt sent to the agent
type MessageRequest struct {
	SessionID   string
	Prompt      string
	Context     []ContextItem
	Model       string
	Permissions map[string]string
}

// MessageResponse represents the agent's response
type MessageResponse struct {
	MessageID string
	StreamURL string
}

// ContextItem represents a code context item
type ContextItem struct {
	File      string `json:"file"`
	StartLine int    `json:"startLine"`
	EndLine   int    `json:"endLine"`
	Content   string `json:"content"`
	Symbol    string `json:"symbol,omitempty"`
}

// Model represents an available AI model
type Model struct {
	ID            string   `json:"id"`
	Name          string   `json:"name"`
	Capabilities  []string `json:"capabilities"`
	ContextWindow int      `json:"contextWindow"`
}

// MockClient is a mock implementation for testing
// TODO: Replace with real Copilot SDK client when available
type MockClient struct{}

// NewMockClient creates a new mock client
func NewMockClient() Client {
	return &MockClient{}
}

// CreateSession creates a new mock session
func (m *MockClient) CreateSession(ctx context.Context) (*Session, error) {
	return &Session{
		ID:        uuid.New().String(),
		CreatedAt: time.Now(),
	}, nil
}

// CloseSession closes a mock session
func (m *MockClient) CloseSession(ctx context.Context, sessionID string) error {
	// Mock implementation - no-op
	return nil
}

// SendMessage sends a message to the mock agent
func (m *MockClient) SendMessage(ctx context.Context, req *MessageRequest) (*MessageResponse, error) {
	if req.SessionID == "" {
		return nil, fmt.Errorf("session ID is required")
	}
	
	return &MessageResponse{
		MessageID: fmt.Sprintf("msg-%s", uuid.New().String()),
		StreamURL: fmt.Sprintf("/stream/%s", req.SessionID),
	}, nil
}

// ListModels returns mock model list
func (m *MockClient) ListModels(ctx context.Context) ([]*Model, error) {
	return []*Model{
		{
			ID:            "gpt-5-mini",
			Name:          "GPT-5 Mini (Mock)",
			Capabilities:  []string{"code", "chat"},
			ContextWindow: 128000,
		},
		{
			ID:            "gpt-5",
			Name:          "GPT-5 (Mock)",
			Capabilities:  []string{"code", "chat", "vision"},
			ContextWindow: 200000,
		},
		{
			ID:            "claude-opus-4",
			Name:          "Claude Opus 4 (Mock)",
			Capabilities:  []string{"code", "chat", "vision"},
			ContextWindow: 200000,
		},
	}, nil
}

// TODO: Real SDK client implementation
// When github.com/github/copilot-sdk/go is available:
//
// type SDKClient struct {
//     client *copilot.Client
// }
//
// func NewSDKClient() (*SDKClient, error) {
//     client, err := copilot.NewClient()
//     if err != nil {
//         return nil, err
//     }
//     return &SDKClient{client: client}, nil
// }
//
// Implement interface methods using real SDK calls
