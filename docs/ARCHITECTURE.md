# Architecture Overview

## System Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                     IntelliJ IDEA IDE                            │
│                                                                  │
│  ┌────────────────────────────────────────────────────────┐    │
│  │              AgentBridge Plugin (Java 21)              │    │
│  │                                                          │    │
│  │  ┌──────────────┐  ┌───────────────────────────────┐  │    │
│  │  │ Tool Window  │  │  Services Layer               │  │    │
│  │  │  (JCEF Chat) │  │                               │  │    │
│  │  │              │  │  - AgentService (abstract)    │  │    │
│  │  │ • Chat panel │◄─┤  - CopilotService             │  │    │
│  │  │ • Toolbar    │  │  - OpenCodeService            │  │    │
│  │  │ • Prompt     │  │  - JunieService               │  │    │
│  │  │              │  │  - CustomProfileService       │  │    │
│  │  └──────────────┘  └─────┬─────────────────────────┘  │    │
│  │                           │                             │    │
│  │  ┌────────────────────────▼──────────────────────┐    │    │
│  │  │     Bridge Layer (AcpClient)                  │    │    │
│  │  │  • JSON-RPC 2.0 over stdin/stdout            │    │    │
│  │  │  • Permission handler (deny + retry)         │    │    │
│  │  │  • Streaming response handling               │    │    │
│  │  └────────────────────┬──────────────────────────┘    │    │
│  │                       │                                │    │
│  │  ┌────────────────────▼──────────────────────┐        │    │
│  │  │     PSI Bridge (PsiBridgeService)         │        │    │
│  │  │  • HTTP server inside IntelliJ process    │        │    │
│  │  │  • 92 MCP tools via IntelliJ APIs         │        │    │
│  │  └────────────────────┬──────────────────────┘        │    │
│  └───────────────────────┼───────────────────────────────┘    │
│                          │ stdin/stdout (ACP)                  │
└──────────────────────────┼────────────────────────────────────┘
                           │
              ┌────────────▼────────────┐
              │   ACP-Compatible CLI    │
              │   (Copilot/OpenCode/    │
              │    Junie/Kiro/Custom)   │
              └────────────┬────────────┘
                           │ stdio
              ┌────────────▼────────────┐
              │   MCP Server (JAR)      │
              │   (routes to PSI Bridge)│
              └────────────┬────────────┘
                           │ HTTP
              ┌────────────▼────────────┐
              │   Cloud LLM Provider    │
              │   (OpenAI/Anthropic/etc)│
              └─────────────────────────┘
```

---

## Component Details

### 1. Plugin Layer (Java 21)

#### Tool Window

- **Framework**: JCEF (Chromium Embedded Framework) for chat rendering
- **Layout**: Single-panel chat interface with toolbar and prompt input
- **Responsibilities**:
    - Render conversation in streaming markdown
    - Handle user input and context attachments
    - Display agent profiles and model selection
    - Show tool execution feedback

#### Services

All services implement `Disposable` for proper cleanup.

**AgentService** (Abstract base):

- Defines lifecycle: start, stop, restart, dispose
- Provides `createAgentConfig()` and `createAgentSettings()`
- Agent-agnostic ACP client management

**CopilotService / OpenCodeService / JunieService**:

- Concrete implementations for specific agents
- Each provides agent-specific `AgentConfig` and `AgentSettings`
- Handle agent-specific quirks and workarounds

**PsiBridgeService** (Project-level):

- HTTP server for MCP tool execution
- Exposes 92 tools via IntelliJ APIs
- Manages tool permissions and execution

#### Bridge Layer

**AcpClient**:

```java
public class AcpClient {
    // Communicates with any ACP-compatible CLI via JSON-RPC 2.0 over stdin/stdout

    public void initialize();
    public SessionResponse createSession();
    public void sendPrompt(String sessionId, String prompt);
    public void cancelSession(String sessionId);
}
```

**Permission Handler**:

Built-in agent file operations are denied so all writes go through IntelliJ's Document API:

1. Agent requests permission (kind="edit")
2. Plugin denies the permission
3. Agent retries using MCP tool (`write_file`)
4. Write goes through Document API with undo support
5. Auto-format runs (optimize imports + reformat)

---

### 2. MCP Tool Bridge

```
Agent CLI ──stdio──► MCP Server (JAR) ──HTTP──► PsiBridgeService
                     agentbridge               (IntelliJ process)
```

- **MCP Server** (`mcp-server/`): Standalone JAR, stdio protocol, routes tool calls to PSI bridge
- **PSI Bridge** (`PsiBridgeService`): HTTP server inside IntelliJ process, accesses PSI/VFS/Document APIs
- **Bridge file**: `~/.copilot/psi-bridge.json` contains port for HTTP connection

---

### 3. Tool Callbacks

When an agent CLI invokes a tool (e.g., `write_file`), the MCP server makes an HTTP request to the PSI bridge:

```
Agent CLI → MCP Server (stdio) → PSI Bridge (HTTP) → IntelliJ APIs
```

#### Auto-Format After Write

Every file write through `write_file` triggers:

1. `PsiDocumentManager.commitAllDocuments()`
2. `OptimizeImportsProcessor`
3. `ReformatCodeProcessor`

This runs inside a single undoable command group on the EDT.

---

## Data Flow

### Typical Prompt Flow

```
┌─────────┐            ┌────────┐           ┌─────────────┐
│  User   │            │ Plugin │           │  Agent CLI  │
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
     │                     │  (write_file)          │
     │                     ├──────────────────────►│
     │                     │                       │
```

---

## Security Considerations

### Tool Permissions

- **deny**: Never execute (fail immediately)
- **ask**: Prompt user for approval
- **allow**: Execute without prompt (for safe ops only)

### Sensitive Operations

Always require approval:

- `git_push --force`
- `run_command` (shell commands)
- File deletions
- Operations outside project root

### Token Storage

- Agent auth tokens stored by their respective CLIs
- Plugin does not store or access tokens directly
- Agent-specific authentication handled by each CLI

---

## Error Handling

### Plugin Layer

- Network timeouts: Retry with exponential backoff
- Process crashes: Auto-restart with backoff
- Auth failures: Display guidance to re-authenticate via CLI

---

## Testing Strategy

### Unit Tests (Plugin)

- `AcpClient`: Mock stdin/stdout protocol
- Service tests: Mock IntelliJ APIs
- Settings tests: JSON serialization

### Integration Tests

- Full workflow with real agent CLI
- Tool execution end-to-end
- Permission flows

---

## Performance Optimization

### Plugin

- Lazy-load ACP client (on first use)
- Cache model list (5 min TTL)
- Debounce UI updates
- Use background threads for I/O

### Memory

- Close sessions promptly
- Limit concurrent sessions
- Clear old conversation history

---

*Last Updated: 2026-03-22*
