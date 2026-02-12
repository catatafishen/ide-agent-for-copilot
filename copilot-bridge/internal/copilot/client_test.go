package copilot

import (
	"context"
	"testing"
)

func TestMockClientListModels(t *testing.T) {
	client := NewMockClient()
	
	models, err := client.ListModels(context.Background())
	if err != nil {
		t.Fatalf("ListModels failed: %v", err)
	}
	
	if len(models) != 3 {
		t.Errorf("Expected 3 models, got %d", len(models))
	}
	
	// Check first model
	if models[0].ID != "gpt-5-mini" {
		t.Errorf("Expected first model ID 'gpt-5-mini', got '%s'", models[0].ID)
	}
	
	if models[0].Name != "GPT-5 Mini" {
		t.Errorf("Expected first model name 'GPT-5 Mini', got '%s'", models[0].Name)
	}
}

func TestMockClientCreateSession(t *testing.T) {
	client := NewMockClient()
	
	session, err := client.CreateSession(context.Background())
	if err != nil {
		t.Fatalf("CreateSession failed: %v", err)
	}
	
	if session.ID == "" {
		t.Error("Expected non-empty session ID")
	}
	
	if session.CreatedAt.IsZero() {
		t.Error("Expected non-zero CreatedAt timestamp")
	}
}

func TestMockClientCloseSession(t *testing.T) {
	client := NewMockClient()
	
	// Create a session
	session, err := client.CreateSession(context.Background())
	if err != nil {
		t.Fatalf("CreateSession failed: %v", err)
	}
	
	// Close it
	err = client.CloseSession(context.Background(), session.ID)
	if err != nil {
		t.Fatalf("CloseSession failed: %v", err)
	}
}

func TestMockClientSendMessage(t *testing.T) {
	client := NewMockClient()
	
	// Create a session first
	session, err := client.CreateSession(context.Background())
	if err != nil {
		t.Fatalf("CreateSession failed: %v", err)
	}
	
	// Send a message
	req := &MessageRequest{
		SessionID: session.ID,
		Prompt:    "Hello, world!",
		Model:     "gpt-4o",
	}
	
	resp, err := client.SendMessage(context.Background(), req)
	if err != nil {
		t.Fatalf("SendMessage failed: %v", err)
	}
	
	if resp.MessageID == "" {
		t.Error("Expected non-empty message ID")
	}
	
	if resp.StreamURL == "" {
		t.Error("Expected non-empty stream URL")
	}
}

func TestSDKClientListModels(t *testing.T) {
	t.Skip("Skipping SDK client test - requires real Copilot CLI")
	
	client, err := NewSDKClient()
	if err != nil {
		t.Fatalf("NewSDKClient failed: %v", err)
	}
	
	models, err := client.ListModels(context.Background())
	if err != nil {
		t.Fatalf("ListModels failed: %v", err)
	}
	
	if len(models) == 0 {
		t.Error("Expected at least one model")
	}
}

func TestSDKClientCreateSession(t *testing.T) {
	t.Skip("Skipping SDK client test - requires real Copilot CLI")
	
	client, err := NewSDKClient()
	if err != nil {
		t.Fatalf("NewSDKClient failed: %v", err)
	}
	
	session, err := client.CreateSession(context.Background())
	if err != nil {
		t.Fatalf("CreateSession failed: %v", err)
	}
	
	if session.ID == "" {
		t.Error("Expected non-empty session ID")
	}
}

func TestSDKClientSendMessage(t *testing.T) {
	t.Skip("Skipping SDK client test - requires real Copilot CLI")
	client, err := NewSDKClient()
	if err != nil {
		t.Fatalf("NewSDKClient failed: %v", err)
	}
	
	req := &MessageRequest{
		SessionID: "test-session",
		Prompt:    "Test prompt",
		Model:     "gpt-4o",
	}
	
	resp, err := client.SendMessage(context.Background(), req)
	if err != nil {
		t.Fatalf("SendMessage failed: %v", err)
	}
	
	if resp.MessageID == "" {
		t.Error("Expected non-empty message ID")
	}
}
