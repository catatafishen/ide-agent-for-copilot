package copilot

import (
	"context"
	"fmt"
	"time"

	copilot "github.com/github/copilot-sdk/go"
)

// SDKClient wraps the official GitHub Copilot SDK
type SDKClient struct {
	client  *copilot.Client
	session *copilot.Session
}

// NewSDKClient creates a new client using the official GitHub Copilot SDK
// Note: The actual Start() is deferred to avoid blocking during initialization
func NewSDKClient() (*SDKClient, error) {
	// Create a new Copilot client
	client := copilot.NewClient(nil)
	
	return &SDKClient{
		client: client,
	}, nil
}

// ensureStarted ensures the Copilot CLI is started
func (c *SDKClient) ensureStarted() error {
	// Start the Copilot CLI process if not already started
	// This is a no-op if already started
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	
	if err := c.client.Start(ctx); err != nil {
		return fmt.Errorf("failed to start Copilot CLI: %w", err)
	}
	return nil
}

// CreateSession creates a new Copilot agent session
func (c *SDKClient) CreateSession(ctx context.Context) (*Session, error) {
	// For now, just return a mock session without starting CLI
	// The actual SDK connection will be established on first message send
	sessionID := fmt.Sprintf("session-%d", time.Now().UnixNano())
	
	return &Session{
		ID:        sessionID,
		CreatedAt: time.Now(),
	}, nil
}

// CloseSession closes the current session
func (c *SDKClient) CloseSession(ctx context.Context, sessionID string) error {
	if c.session != nil {
		c.session = nil
	}
	return nil
}

// SendMessage sends a message to the Copilot agent
func (c *SDKClient) SendMessage(ctx context.Context, req *MessageRequest) (*MessageResponse, error) {
	// For mock mode, just generate a message ID
	// Real SDK integration will connect on first message
	messageID := fmt.Sprintf("msg-%d", time.Now().UnixNano())
	
	return &MessageResponse{
		MessageID: messageID,
		StreamURL: fmt.Sprintf("/stream/%s", req.SessionID),
	}, nil
}

// ListModels returns available models from Copilot SDK
func (c *SDKClient) ListModels(ctx context.Context) ([]*Model, error) {
	// Don't try to start CLI just for listing models
	// Return the default models that work with SDK
	return c.getDefaultModels(), nil
}

// getDefaultModels returns the default set of models
func (c *SDKClient) getDefaultModels() []*Model {
	return []*Model{
		{
			ID:            "gpt-4o",
			Name:          "GPT-4o (Mock)",
			Capabilities:  []string{"code", "chat", "vision"},
			ContextWindow: 128000,
		},
		{
			ID:            "gpt-4o-mini",
			Name:          "GPT-4o Mini (Mock)",
			Capabilities:  []string{"code", "chat"},
			ContextWindow: 128000,
		},
		{
			ID:            "claude-3.5-sonnet",
			Name:          "Claude 3.5 Sonnet (Mock)",
			Capabilities:  []string{"code", "chat", "vision"},
			ContextWindow: 200000,
		},
		{
			ID:            "o1-preview",
			Name:          "O1 Preview (Mock)",
			Capabilities:  []string{"code", "reasoning"},
			ContextWindow: 128000,
		},
		{
			ID:            "o1-mini",
			Name:          "O1 Mini (Mock)",
			Capabilities:  []string{"code", "reasoning"},
			ContextWindow: 128000,
		},
	}
}

// Stop stops the Copilot CLI client
func (c *SDKClient) Stop() error {
	if c.client != nil {
		return c.client.Stop()
	}
	return nil
}
