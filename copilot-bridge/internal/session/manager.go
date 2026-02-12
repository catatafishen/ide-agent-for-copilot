package session

import (
	"context"
	"sync"
	"time"

	"github.com/google/uuid"
	"github.com/yourusername/intellij-copilot-plugin/copilot-bridge/internal/copilot"
)

// Session represents an active Copilot agent session
type Session struct {
	ID              string
	CreatedAt       time.Time
	CopilotSession  *copilot.Session
	EventChan       chan string // Channel for streaming events
}

// NewSession creates a new session with event channel
func NewSession(copilotSess *copilot.Session) *Session {
	return &Session{
		ID:             uuid.New().String(),
		CreatedAt:      time.Now(),
		CopilotSession: copilotSess,
		EventChan:      make(chan string, 100), // Buffered channel
	}
}

// Manager manages multiple agent sessions
type Manager struct {
	sessions      map[string]*Session
	copilotClient copilot.Client
	mu            sync.RWMutex
}

// NewManager creates a new session manager
func NewManager(client copilot.Client) *Manager {
	return &Manager{
		sessions:      make(map[string]*Session),
		copilotClient: client,
	}
}

// Create creates a new session
func (m *Manager) Create() (*Session, error) {
	m.mu.Lock()
	defer m.mu.Unlock()

	// Create Copilot session with proper context
	ctx := context.Background()
	copilotSess, err := m.copilotClient.CreateSession(ctx)
	if err != nil {
		return nil, err
	}

	sess := NewSession(copilotSess)
	m.sessions[sess.ID] = sess
	return sess, nil
}

// Get retrieves a session by ID
func (m *Manager) Get(id string) (*Session, bool) {
	m.mu.RLock()
	defer m.mu.RUnlock()
	sess, ok := m.sessions[id]
	return sess, ok
}

// Close closes and removes a session
func (m *Manager) Close(id string) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	
	if sess, ok := m.sessions[id]; ok {
		// Close event channel
		if sess.EventChan != nil {
			close(sess.EventChan)
		}
		
		// Close Copilot session if it exists
		if sess.CopilotSession != nil {
			ctx := context.Background()
			if err := m.copilotClient.CloseSession(ctx, sess.CopilotSession.ID); err != nil {
				return err
			}
		}
		delete(m.sessions, id)
	}
	
	return nil
}

// CloseAll closes all sessions
func (m *Manager) CloseAll() {
	m.mu.Lock()
	defer m.mu.Unlock()
	
	for id, sess := range m.sessions {
		// Close event channel
		if sess.EventChan != nil {
			close(sess.EventChan)
		}
		
		// Close Copilot session if it exists
		if sess.CopilotSession != nil {
			ctx := context.Background()
			m.copilotClient.CloseSession(ctx, sess.CopilotSession.ID)
		}
		delete(m.sessions, id)
	}
}

// GetClient returns the Copilot client
func (m *Manager) GetClient() copilot.Client {
	return m.copilotClient
}
