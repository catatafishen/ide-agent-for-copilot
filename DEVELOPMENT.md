# Development Guide

## Build & Deploy

### Prerequisites
- JDK 21 (e.g., `C:\Users\developer\.jdks\temurin-21.0.6`)
- GitHub Copilot CLI installed and authenticated (`winget install GitHub.Copilot`)

### Build Plugin

```powershell
$env:JAVA_HOME = "C:\Users\developer\.jdks\temurin-21.0.6"
.\gradlew.bat :plugin-core:clean :plugin-core:buildPlugin
```

Output: `plugin-core\build\distributions\plugin-core-0.1.0-SNAPSHOT.zip`

### Deploy to IntelliJ

```powershell
# Stop IntelliJ
$ij = Get-Process -Name "idea64" -ErrorAction SilentlyContinue
if ($ij) { Stop-Process -Id $ij.Id -Force; Start-Sleep -Seconds 5 }

# Install
Remove-Item "$env:APPDATA\JetBrains\IntelliJIdea2025.3\plugins\plugin-core" -Recurse -Force -ErrorAction SilentlyContinue
Expand-Archive "plugin-core\build\distributions\plugin-core-0.1.0-SNAPSHOT.zip" `
    "$env:APPDATA\JetBrains\IntelliJIdea2025.3\plugins" -Force

# Launch
Start-Process "C:\Users\developer\AppData\Local\JetBrains\IntelliJ IDEA 2023.3.3\bin\idea64.exe"
```

### Sandbox IDE (Development)

Run the plugin in a sandboxed IntelliJ instance (separate config/data, doesn't touch your main IDE):

```powershell
$env:JAVA_HOME = "C:\Users\developer\.jdks\temurin-21.0.6"
.\gradlew.bat :plugin-core:runIde
```

- First launch takes ~90s (Gradle configuration + dependency resolution)
- Opens a fresh IntelliJ with the plugin pre-installed
- Sandbox data stored in `plugin-core/build/idea-sandbox/`
- Open a **different project** than the one open in your main IDE to avoid conflicts

**Auto-reload (Linux only):** `autoReload = true` is configured in `build.gradle.kts`. On Linux, after code changes run `prepareSandbox` and the plugin reloads without restarting the sandbox IDE. On Windows, file locks prevent this — close the sandbox IDE first, then re-run `runIde`.

**Iterating on changes:**
1. Close the sandbox IDE
2. `.\gradlew.bat :plugin-core:prepareSandbox` (rebuilds plugin into sandbox)
3. `.\gradlew.bat :plugin-core:runIde` (relaunches sandbox)

### Run Tests

```powershell
.\gradlew.bat test                              # All tests
.\gradlew.bat :plugin-core:test                 # Plugin unit tests only
.\gradlew.bat :mcp-server:test                  # MCP server tests only
.\gradlew.bat :plugin-core:test -Dinclude.integration=true  # Include integration tests
```

## Architecture

### ACP Protocol Flow

The plugin communicates with GitHub Copilot CLI via the **Agent Client Protocol (ACP)** — JSON-RPC 2.0 over stdin/stdout:

```
Plugin (CopilotAcpClient)
  │
  ├─► initialize          → Agent capabilities, auth methods
  ├─► session/new         → Create session, get models
  ├─► session/prompt      → Send prompt, receive streaming chunks
  │     ◄── session/update (notifications: chunks, tool_calls, plan)
  │     ◄── session/request_permission (agent requests)
  │     ──► permission response (approve/deny)
  └─► session/cancel      → Abort current prompt
```

### Permission Deny + Retry Flow

Built-in Copilot file operations are **denied** so all writes go through IntelliJ's Document API:

```
1. User sends prompt
2. Agent decides to edit a file → sends request_permission (kind="edit")
3. Plugin DENIES the permission (responds with reject_once)
4. Agent reports tool failure, turn ends (stopReason: end_turn)
5. Plugin detects denial occurred → sends automatic retry prompt:
   "Use intellij_write_file MCP tool instead"
6. Agent retries using MCP tool → write goes through Document API
7. Auto-format runs (optimize imports + reformat code)
```

**Denied permission kinds**: `edit`, `create`  
**Auto-approved**: `other` (MCP tools), `read`, `execute`

### MCP Tool Bridge

```
Copilot CLI ──stdio──► MCP Server (JAR) ──HTTP──► PsiBridgeService
                       intellij-code-tools         (IntelliJ process)
```

- **MCP Server** (`mcp-server/`): Standalone JAR, stdio protocol, routes tool calls to PSI bridge
- **PSI Bridge** (`PsiBridgeService`): HTTP server inside IntelliJ process, accesses PSI/VFS/Document APIs
- **Bridge file**: `~/.copilot/psi-bridge.json` contains port for HTTP connection

### Auto-Format After Write

Every file write through `intellij_write_file` triggers:
1. `PsiDocumentManager.commitAllDocuments()`
2. `OptimizeImportsProcessor`
3. `ReformatCodeProcessor`

This runs inside a single undoable command group on the EDT.

## Key Files

| File | Purpose |
|------|---------|
| `plugin-core/.../bridge/CopilotAcpClient.java` | ACP client, permission handler, retry logic |
| `plugin-core/.../psi/PsiBridgeService.java` | 36 MCP tools via IntelliJ APIs |
| `plugin-core/.../services/CopilotService.java` | Service entry point, starts ACP client |
| `plugin-core/.../ui/AgenticCopilotToolWindowContent.kt` | Main UI (Kotlin Swing) |
| `mcp-server/.../mcp/McpServer.java` | MCP stdio server, tool registrations |

## Debugging

### Enable Debug Logging
Add to `Help > Diagnostic Tools > Debug Log Settings`:
```
#com.github.copilot.intellij
```

### Log Locations
- Main IDE: `%LOCALAPPDATA%\JetBrains\IntelliJIdea2025.3\log\idea.log`
- Sandbox IDE: `plugin-core/build/idea-sandbox/IU-2025.3.1.1/log/idea.log`
- PSI bridge port: `~/.copilot/psi-bridge.json`

### Common Issues

| Issue | Cause | Fix |
|-------|-------|-----|
| "Error loading models" | Copilot CLI not authenticated | Run `copilot auth` |
| "RPC call failed: session.create" | ACP process died | Check idea.log for stderr |
| Agent uses built-in edit tool | Deny+retry not working | Check permission handler logs |
| "file changed externally" dialog | Write bypassed Document API | Verify intellij_write_file is used |

## Test Coverage

- **AcpProtocolRegressionTest**: 16 tests — protocol format, permission handling, deny logic
- **CopilotAcpClientTest**: 6 unit + 9 integration tests — DTOs, lifecycle, real Copilot
- **WrapLayoutTest**: 6 tests — UI layout
- **McpServerTest**: 16 tests — all MCP tools, security (path traversal), protocol
