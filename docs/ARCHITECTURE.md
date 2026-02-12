# Architecture Overview

## System Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                     IntelliJ IDEA IDE                            │
│                                                                  │
│  ┌────────────────────────────────────────────────────────┐    │
│  │           Agentic Copilot Plugin (Java 21)             │    │
│  │                                                          │    │
│  │  ┌──────────────┐  ┌───────────────────────────────┐  │    │
│  │  │ Tool Window  │  │  Services Layer               │  │    │
│  │  │  (Swing UI)  │  │                               │  │    │
│  │  │              │  │  - SidecarService             │  │    │
│  │  │ • Prompt     │◄─┤  - AgenticCopilotService     │  │    │
│  │  │ • Context    │  │  - GitService                 │  │    │
│  │  │ • Plans      │  │  - FormatService              │  │    │
│  │  │ • Timeline   │  │  - SettingsService            │  │    │
│  │  │ • Settings   │  │                               │  │    │
│  │  └──────────────┘  └─────┬─────────────────────────┘  │    │
│  │                           │                             │    │
│  │  ┌────────────────────────▼──────────────────────┐    │    │
│  │  │     Bridge Layer (SidecarClient)             │    │    │
│  │  │  • HTTP JSON-RPC Client                      │    │    │
│  │  │  • SSE Event Stream Consumer                 │    │    │
│  │  │  • Retry & Error Handling                    │    │    │
│  │  └────────────────────┬──────────────────────────┘    │    │
│  └───────────────────────┼───────────────────────────────┘    │
│                          │ HTTP/SSE                           │
└──────────────────────────┼────────────────────────────────────┘
                           │
                    localhost:dynamic-port
                           │
┌──────────────────────────▼────────────────────────────────────┐
│              Go Sidecar Process (copilot-sidecar.exe)         │
│                                                                 │
│  ┌──────────────────────────────────────────────────────────┐ │
│  │              HTTP Server (JSON-RPC 2.0)                  │ │
│  │  • POST /rpc        - Main RPC endpoint                  │ │
│  │  • GET /stream/{id} - SSE event stream                   │ │
│  │  • GET /health      - Health check                       │ │
│  └────────────────────┬─────────────────────────────────────┘ │
│                       │                                        │
│  ┌────────────────────▼─────────────────────────────────────┐ │
│  │            Session Manager                               │ │
│  │  • Create/Close sessions                                 │ │
│  │  • Session lifecycle tracking                            │ │
│  │  • Concurrent session support                            │ │
│  └────────────────────┬─────────────────────────────────────┘ │
│                       │                                        │
│  ┌────────────────────▼─────────────────────────────────────┐ │
│  │         Copilot SDK Integration                          │ │
│  │  • github.com/github/copilot-sdk/go                      │ │
│  │  • Agent session management                              │ │
│  │  • Model selection                                       │ │
│  │  • Tool registration & callbacks                         │ │
│  │  • Plan/Timeline event streaming                         │ │
│  └────────────────────┬─────────────────────────────────────┘ │
│                       │                                        │
└───────────────────────┼────────────────────────────────────────┘
                        │
            ┌───────────▼───────────┐
            │   GitHub Copilot CLI  │
            │  (managed by SDK)     │
            └───────────┬───────────┘
                        │
            ┌───────────▼───────────┐
            │  Copilot API Service  │
            │  (cloud or local LLM) │
            └───────────────────────┘
```

---

## Component Details

### 1. Plugin Layer (Java 21)

#### Tool Window
- **Framework**: Swing (JPanel-based)
- **Layout**: JBTabbedPane with 5 tabs
- **Responsibilities**:
  - Render UI components
  - Handle user input
  - Display plans/timeline
  - Show approval dialogs

#### Services
All services implement `Disposable` for proper cleanup.

**SidecarService** (Application-level):
- Manages sidecar process lifecycle
- Provides `SidecarClient` instance
- Auto-restarts on crashes (with backoff)

**AgenticCopilotService** (Project-level):
- High-level API for UI components
- Session management (create/close)
- Message sending with context
- Event stream handling

**GitService** (Project-level):
- Wraps IntelliJ Git4Idea APIs
- Conventional commit formatting
- Branch operations
- Safety checks for destructive operations

**FormatService** (Project-level):
- Code formatting after agent edits
- Import optimization
- Changed-range detection

**SettingsService** (Project-level):
- Load/save plugin configuration
- JSON serialization to `.idea/copilot-agent.json`
- Tool permission management

#### Bridge Layer

**SidecarClient**:
```java
public class SidecarClient {
    private final String baseUrl;
    private final HttpClient httpClient;
    
    public SessionResponse createSession() throws SidecarException;
    public void closeSession(String sessionId) throws SidecarException;
    public MessageResponse sendMessage(SendRequest request) throws SidecarException;
    public List<Model> listModels() throws SidecarException;
    public EventStream streamEvents(String sessionId);
}
```

**EventStream**:
```java
public class EventStream implements AutoCloseable {
    public void onEvent(EventType type, Consumer<JsonObject> handler);
    public void start();
    public void stop();
}
```

---

### 2. Sidecar Layer (Go 1.22+)

#### HTTP Server
- **Framework**: `net/http` (stdlib)
- **Port**: Dynamic allocation (0 = OS picks)
- **Protocol**: JSON-RPC 2.0
- **Streaming**: Server-Sent Events (SSE)

#### Session Manager
```go
type Session struct {
    ID        string
    CreatedAt time.Time
    CopilotSession *copilot.Session  // SDK session
}

type Manager struct {
    sessions map[string]*Session
    mu       sync.RWMutex
}
```

#### Copilot SDK Integration
```go
type CopilotClient interface {
    CreateSession(opts SessionOptions) (*Session, error)
    SendMessage(sessionID, prompt string, context []Context) error
    RegisterTool(name string, handler ToolHandler) error
    ListModels() ([]Model, error)
}
```

**Tool Callbacks**:
When Copilot SDK invokes a tool (e.g., `git.commit`), the sidecar makes an HTTP POST back to the plugin:

```
POST http://localhost:{plugin-port}/tool-callback
{
    "sessionId": "session-uuid",
    "toolName": "git.commit",
    "callId": "call-uuid",
    "args": { "type": "feat", "description": "..." }
}
```

Plugin responds with approval and result or denial.

---

## Data Flow: Sending a Prompt

```
┌──────────┐                ┌────────┐               ┌─────────┐
│  User    │                │ Plugin │               │ Sidecar │
└────┬─────┘                └───┬────┘               └────┬────┘
     │                          │                         │
     │ 1. Types prompt         │                         │
     │ + adds context           │                         │
     │ + clicks "Run"           │                         │
     ├─────────────────────────►│                         │
     │                          │                         │
     │                          │ 2. POST /rpc            │
     │                          │    session.send         │
     │                          ├────────────────────────►│
     │                          │                         │
     │                          │ 3. Response             │
     │                          │    {messageId, streamUrl}│
     │                          │◄────────────────────────┤
     │                          │                         │
     │                          │ 4. Connect SSE          │
     │                          │    GET /stream/{id}     │
     │                          ├────────────────────────►│
     │                          │                         │
     │                          │                         │ 5. Forward to
     │                          │                         │    Copilot SDK
     │                          │                         ├────────────────►
     │                          │                         │                 
     │                          │                         │ 6. Plan events
     │                          │    event: plan.step     │◄────────────────
     │                          │◄────────────────────────┤
     │ 7. Update UI             │                         │
     │    (show plan steps)     │                         │
     │◄─────────────────────────┤                         │
     │                          │                         │
     │                          │    event: tool.approval │
     │                          │    (git.commit request) │
     │                          │◄────────────────────────┤
     │                          │                         │
     │ 8. Show approval dialog  │                         │
     │◄─────────────────────────┤                         │
     │                          │                         │
     │ 9. Approve               │                         │
     ├─────────────────────────►│                         │
     │                          │                         │
     │                          │ 10. Execute Git commit  │
     │                          │     (via GitService)    │
     │                          ├──────────┐              │
     │                          │          │              │
     │                          │◄─────────┘              │
     │                          │                         │
     │                          │ 11. POST /tool-callback │
     │                          │     {success, result}   │
     │                          ├────────────────────────►│
     │                          │                         │
     │                          │                         │ 12. Send result
     │                          │                         │     to SDK
     │                          │                         ├────────────────►
     │                          │                         │
     │                          │    event: plan.complete │
     │                          │◄────────────────────────┤
     │ 13. Show completion      │                         │
     │◄─────────────────────────┤                         │
     │                          │                         │
```

---

## Configuration & Settings

### Plugin Settings (`.idea/copilot-agent.json`)
```json
{
  "sidecarPort": 0,
  "model": "gpt-5-mini",
  "toolPermissions": {
    "git.commit": "ask",
    "git.push": "ask",
    "git.forcePush": "deny",
    "fs.write": "ask",
    "exec.run": "deny"
  },
  "formatting": {
    "formatOnSave": true,
    "optimizeImportsOnSave": true,
    "formatAfterAgentEdits": true,
    "preCommitReformat": true
  },
  "conventionalCommits": {
    "enabled": true,
    "defaultType": "chore",
    "enforceScopes": false,
    "allowedTypes": ["feat", "fix", "docs", "style", "refactor", "perf", "test", "build", "ci", "chore", "revert"]
  }
}
```

### Sidecar Configuration (CLI args)
```
copilot-sidecar.exe 
  --port 0                            # 0 = dynamic
  --callback http://localhost:XXXX    # Plugin callback URL
  --debug                             # Enable debug logging
```

---

## Security Considerations

### Tool Permissions
- **deny**: Never execute (fail immediately)
- **ask**: Prompt user for approval (default for dangerous ops)
- **allow**: Execute without prompt (for safe ops only)

### Sensitive Operations
Always require approval:
- `git.push --force`
- `exec.run` (shell commands)
- File deletions
- Operations outside project root

### Token Storage
- GitHub auth tokens stored in IntelliJ's `PasswordSafe`
- Never logged or exposed in UI
- Cleared on logout

---

## Error Handling

### Plugin Layer
```java
try {
    client.sendMessage(request);
} catch (SidecarException e) {
    if (e.isRecoverable()) {
        // Show retry dialog
        showRetryDialog(e);
    } else {
        // Show error notification
        Notifications.Bus.notify(
            new Notification("Copilot", "Error", e.getMessage(), NotificationType.ERROR)
        );
    }
}
```

### Sidecar Layer
```go
func (s *Server) handleRPC(w http.ResponseWriter, r *http.Request) {
    // ... parse request ...
    
    result, err := s.handleMethod(req.Method, req.Params)
    if err != nil {
        s.writeError(w, req.ID, errorCode(err), err.Error())
        return
    }
    
    s.writeResult(w, req.ID, result)
}
```

### SDK Errors
- Network timeouts: Retry with exponential backoff
- Rate limits: Queue requests, show progress
- Auth failures: Re-authenticate via OAuth flow

---

## Testing Strategy

### Unit Tests (Plugin)
- `SidecarClient`: Mock HTTP responses
- `GitService`: Mock VCS API
- `FormatService`: Test on sample code
- `SettingsService`: Test JSON serialization

### Integration Tests (Plugin)
- Start real sidecar process
- Create session, send message
- Verify JSON-RPC communication
- Test event streaming

### Unit Tests (Sidecar)
- Session manager: Concurrent operations
- JSON-RPC parsing: Valid/invalid requests
- Tool callbacks: Success/failure paths

### E2E Tests
- Full workflow: Prompt → Plan → Git commit
- Error scenarios: Sidecar crash, network failure
- Permission flows: Approve/deny dialogs

---

## Performance Optimization

### Plugin
- Lazy-load sidecar (on first use)
- Cache model list (5 min TTL)
- Debounce UI updates (50ms)
- Use background threads for I/O

### Sidecar
- Connection pooling (keep-alive)
- Stream events (don't buffer large responses)
- Graceful degradation (fall back to simple responses)

### Memory
- Close sessions promptly
- Limit concurrent sessions (default: 5)
- Clear old timeline events (keep last 100)

---

## Future Enhancements (Post-v1)

### Plugin
- Multiple simultaneous agents (parallel tasks)
- Workspace-level context (search across files)
- Custom tool registration (user-defined)
- Inline code suggestions (like Copilot Chat)

### Sidecar
- WebSocket for bidirectional streaming
- gRPC for better performance
- Plugin marketplace for custom tools

### Integration
- GitHub PR generation
- Jira/Linear issue creation
- CI/CD pipeline integration
