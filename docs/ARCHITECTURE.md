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
│  │  │              │  │  - CopilotService             │  │    │
│  │  │ • Chat       │◄─┤  - AgenticCopilotService     │  │    │
│  │  │ • Context    │  │  - GitService                 │  │    │
│  │  │ • Session    │  │  - FormatService              │  │    │
│  │  │ • Settings   │  │  - SettingsService            │  │    │
│  │  └──────────────┘  └─────┬─────────────────────────┘  │    │
│  │                           │                             │    │
│  │  ┌────────────────────────▼──────────────────────┐    │    │
│  │  │     Bridge Layer (CopilotAcpClient)          │    │    │
│  │  │  • JSON-RPC 2.0 over stdin/stdout            │    │    │
│  │  │  • Permission handler (deny + retry)         │    │    │
│  │  │  • Streaming response handling               │    │    │
│  │  └────────────────────┬──────────────────────────┘    │    │
│  │                       │                                │    │
│  │  ┌────────────────────▼──────────────────────┐        │    │
│  │  │     PSI Bridge (PsiBridgeService)         │        │    │
│  │  │  • HTTP server inside IntelliJ process    │        │    │
│  │  │  • 55 MCP tools via IntelliJ APIs         │        │    │
│  │  └────────────────────┬──────────────────────┘        │    │
│  └───────────────────────┼───────────────────────────────┘    │
│                          │ stdin/stdout (ACP)                  │
└──────────────────────────┼────────────────────────────────────┘
                           │
              ┌────────────▼────────────┐
              │   GitHub Copilot CLI    │
              │   (ACP protocol)        │
              └────────────┬────────────┘
                           │ stdio
              ┌────────────▼────────────┐
              │   MCP Server (JAR)      │
              │   (routes to PSI Bridge)│
              └────────────┬────────────┘
                           │ HTTP
              ┌────────────▼────────────┐
              │  Copilot API Service    │
              │  (cloud LLM)            │
              └─────────────────────────┘
```

---

## Component Details

### 1. Plugin Layer (Java 21)

#### Tool Window

- **Framework**: Swing (JPanel-based)
- **Layout**: Single-panel with chat console, toolbar, and prompt input
- **Responsibilities**:
    - Render UI components
    - Handle user input
    - Display plans/timeline
    - Show approval dialogs

#### Services

All services implement `Disposable` for proper cleanup.

**CopilotService** (Application-level):

- Manages ACP client lifecycle
- Starts Copilot CLI process
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

**CopilotAcpClient**:

```java
public class CopilotAcpClient {
    // Communicates with Copilot CLI via ACP (JSON-RPC 2.0 over stdin/stdout)

    public void initialize();

    public SessionResponse createSession();

    public void sendPrompt(String sessionId, String prompt);

    public void cancelSession(String sessionId);
}
```

**Permission Handler**:

Built-in Copilot file operations are denied so all writes go through IntelliJ's Document API:

1. Agent requests permission (kind="edit")
2. Plugin denies the permission
3. Agent retries using MCP tool (`intellij_write_file`)
4. Write goes through Document API with undo support
5. Auto-format runs (optimize imports + reformat)

---

### 2. MCP Tool Bridge

```
Copilot CLI ──stdio──► MCP Server (JAR) ──HTTP──► PsiBridgeService
                       intellij-code-tools         (IntelliJ process)
```

- **MCP Server** (`mcp-server/`): Standalone JAR, stdio protocol, routes tool calls to PSI bridge
- **PSI Bridge** (`PsiBridgeService`): HTTP server inside IntelliJ process, accesses PSI/VFS/Document APIs
- **Bridge file**: `~/.copilot/psi-bridge.json` contains port for HTTP connection

---

### 3. Tool Callbacks

When Copilot CLI invokes a tool (e.g., `intellij_write_file`), the MCP server makes an HTTP request to the PSI bridge:

```
Copilot CLI → MCP Server (stdio) → PSI Bridge (HTTP) → IntelliJ APIs
```

#### Auto-Format After Write

Every file write through `intellij_write_file` triggers:

1. `PsiDocumentManager.commitAllDocuments()`
2. `OptimizeImportsProcessor`
3. `ReformatCodeProcessor`

This runs inside a single undoable command group on the EDT.

---

## Data Flow

### Typical Prompt Flow

```
┌─────────┐            ┌────────┐           ┌─────────────┐
│  User   │            │ Plugin │           │ Copilot CLI │
└────┬────┘            └───┬────┘           └──────┬──────┘
     │                     │                       │
     │  Type prompt        │                       │
     ├────────────────────►│                       │
     │                     │  session/prompt        │
     │                     ├──────────────────────►│
     │                     │                       │
     │                     │  session/update        │
     │                     │◄──────────────────────┤
     │                     │  (streaming chunks)    │
     │  Display response   │                       │
     │◄────────────────────┤                       │
     │                     │                       │
     │                     │  request_permission    │
     │                     │◄──────────────────────┤
     │                     │  (deny built-in edit)  │
     │                     ├──────────────────────►│
     │                     │                       │
     │                     │  MCP tool call         │
     │                     │◄──────────────────────┤
     │                     │  (intellij_write_file) │
     │                     ├──────────────────────►│
     │                     │                       │
```

---

## Configuration

### Plugin Settings (JSON)

```json
{
  "model": "gpt-4o",
  "mode": "agent",
  "formatting": {
    "optimizeImportsOnSave": true,
    "formatAfterAgentEdits": true,
    "preCommitReformat": true
  },
  "conventionalCommits": {
    "enabled": true,
    "defaultType": "chore",
    "enforceScopes": false,
    "allowedTypes": [
      "feat",
      "fix",
      "docs",
      "style",
      "refactor",
      "perf",
      "test",
      "build",
      "ci",
      "chore",
      "revert"
    ]
  }
}
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
try{
        client.sendPrompt(sessionId, prompt);
}catch(
AcpException e){
        if(e.

isRecoverable()){

// Auto-restart ACP process
restartAcpClient();
    }else{
            Notifications.Bus.

notify(
            new Notification("Copilot", "Error",e.getMessage(),NotificationType.ERROR)
        );
        }
        }
```

### SDK Errors

- Network timeouts: Retry with exponential backoff
- Rate limits: Queue requests, show progress
- Auth failures: Re-authenticate via `copilot auth`

---

## Testing Strategy

### Unit Tests (Plugin)

- `CopilotAcpClient`: Mock stdin/stdout protocol
- `GitService`: Mock VCS API
- `FormatService`: Test on sample code
- `SettingsService`: Test JSON serialization

### Integration Tests (Plugin)

- Start real Copilot CLI process
- Create session, send message
- Verify ACP communication
- Test event streaming

### E2E Tests

- Full workflow: Prompt → Plan → Git commit
- Error scenarios: Process crash, network failure
- Permission flows: Approve/deny dialogs

---

## Performance Optimization

### Plugin

- Lazy-load ACP client (on first use)
- Cache model list (5 min TTL)
- Debounce UI updates (50ms)
- Use background threads for I/O

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

### Integration

- GitHub PR generation
- Jira/Linear issue creation
- CI/CD pipeline integration
