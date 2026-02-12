package copilot

import (
	"context"
	"fmt"
	"sync"
	"time"

	copilot "github.com/github/copilot-sdk/go"
	"github.com/google/uuid"
)

// SDKClient wraps the official GitHub Copilot SDK
type SDKClient struct {
	client   *copilot.Client
	sessions map[string]*copilot.Session
	mu       sync.RWMutex
	started  bool
}

// NewSDKClient creates a new client using the official GitHub Copilot SDK
func NewSDKClient() (*SDKClient, error) {
	// Create a new Copilot client with default options (spawns CLI process)
	client := copilot.NewClient(nil)
	
	return &SDKClient{
		client:   client,
		sessions: make(map[string]*copilot.Session),
		started:  false,
	}, nil
}

// ensureStarted ensures the Copilot CLI is started
func (c *SDKClient) ensureStarted() error {
	c.mu.Lock()
	defer c.mu.Unlock()
	
	if c.started {
		return nil // Already started
	}
	
	// Start the Copilot CLI process
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	
	if err := c.client.Start(ctx); err != nil {
		return fmt.Errorf("failed to start Copilot CLI: %w", err)
	}
	
	c.started = true
	return nil
}

// CreateSession creates a new Copilot agent session
func (c *SDKClient) CreateSession(ctx context.Context) (*Session, error) {
	// Ensure CLI is started
	if err := c.ensureStarted(); err != nil {
		return nil, err
	}
	
	// Create SDK session with default model
	config := &copilot.SessionConfig{
		Model: "gpt-4o", // Direct string assignment, not pointer
	}
	
	sdkSession, err := c.client.CreateSession(ctx, config)
	if err != nil {
		return nil, fmt.Errorf("failed to create SDK session: %w", err)
	}
	
	// Generate our session ID
	sessionID := uuid.New().String()
	
	// Store the SDK session
	c.mu.Lock()
	c.sessions[sessionID] = sdkSession
	c.mu.Unlock()
	
	return &Session{
		ID:        sessionID,
		CreatedAt: time.Now(),
	}, nil
}

// CloseSession closes a session
func (c *SDKClient) CloseSession(ctx context.Context, sessionID string) error {
	c.mu.Lock()
	defer c.mu.Unlock()
	
	sdkSession, exists := c.sessions[sessionID]
	if !exists {
		return fmt.Errorf("session not found: %s", sessionID)
	}
	
	// Destroy the SDK session (no context parameter)
	if err := sdkSession.Destroy(); err != nil {
		return fmt.Errorf("failed to destroy SDK session: %w", err)
	}
	
	// Remove from our map
	delete(c.sessions, sessionID)
	
	return nil
}

// SendMessage sends a message to the Copilot agent
func (c *SDKClient) SendMessage(ctx context.Context, req *MessageRequest) (*MessageResponse, error) {
	// Get the SDK session
	c.mu.RLock()
	sdkSession, exists := c.sessions[req.SessionID]
	c.mu.RUnlock()
	
	if !exists {
		return nil, fmt.Errorf("session not found: %s", req.SessionID)
	}
	
	// Send message via SDK
	messageOptions := copilot.MessageOptions{
		Prompt: req.Prompt,
	}
	
	// Add context if provided
	if len(req.Context) > 0 {
		// Convert our context items to SDK attachments
		attachments := make([]copilot.Attachment, 0, len(req.Context))
		for _, item := range req.Context {
			attachments = append(attachments, copilot.Attachment{
				DisplayName: item.File,
				Type:        copilot.File,
				FilePath:    &item.File,
				Text:        &item.Content,
			})
		}
		messageOptions.Attachments = attachments
	}
	
	// Send the message (async, events come via session.On())
	// Send returns (requestID string, error)
	requestID, err := sdkSession.Send(ctx, messageOptions)
	if err != nil {
		return nil, fmt.Errorf("failed to send message: %w", err)
	}
	
	// Use request ID as message ID for tracking
	messageID := fmt.Sprintf("msg-%s", requestID)
	
	return &MessageResponse{
		MessageID: messageID,
		StreamURL: fmt.Sprintf("/stream/%s", req.SessionID),
	}, nil
}

// ListModels returns available models from Copilot SDK
func (c *SDKClient) ListModels(ctx context.Context) ([]*Model, error) {
	// Ensure CLI is started
	if err := c.ensureStarted(); err != nil {
		return nil, err
	}
	
	// Get models from SDK
	sdkModels, err := c.client.ListModels(ctx)
	if err != nil {
		return nil, fmt.Errorf("failed to list models: %w", err)
	}
	
	// Convert SDK models to our model format
	models := make([]*Model, 0, len(sdkModels))
	for _, sdkModel := range sdkModels {
		models = append(models, &Model{
			ID:            sdkModel.ID,
			Name:          sdkModel.Name, // Use Name field, not DisplayName
			Capabilities:  []string{"code", "chat"}, // SDK doesn't expose capabilities
			ContextWindow: 128000,                   // Default value
		})
	}
	
	return models, nil
}

// Stop stops the Copilot CLI client
func (c *SDKClient) Stop() error {
	c.mu.Lock()
	defer c.mu.Unlock()
	
	if c.client != nil && c.started {
		if err := c.client.Stop(); err != nil {
			return fmt.Errorf("failed to stop Copilot CLI: %w", err)
		}
		c.started = false
	}
	return nil
}
