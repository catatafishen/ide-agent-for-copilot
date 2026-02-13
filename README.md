# Agentic GitHub Copilot for JetBrains

A lightweight IntelliJ Platform plugin that embeds GitHub Copilot's agent capabilities directly into your IDE via the **Agent Client Protocol (ACP)**, with **MCP-based code intelligence tools** that leverage IntelliJ's native APIs for symbol search, code formatting, test execution, git operations, and file operations.

## Status

**Working** — Plugin is functional with full Copilot agent integration.

### What Works
- Multi-turn conversation with GitHub Copilot agent
- 35 IntelliJ-native MCP tools (symbol search, file outline, references, test runner, code formatting, git, infrastructure, terminal, etc.)
- Built-in file operations redirected through IntelliJ Document API (undo support, no external file conflicts)
- Auto-format (optimize imports + reformat code) after every write
- Model selection with usage multiplier display
- Context management (attach files/selections to prompts)
- Session info panel with plan visualization and timeline
- Real-time streaming responses

## Architecture

```
┌──────────────────────────────────────────────────────────────┐
│                 IntelliJ IDEA Plugin (Java 21)               │
│  ┌────────────────┐  ┌──────────────────────────────────────┐│
│  │   Tool Window   │  │          CopilotAcpClient            ││
│  │    (Swing)      │  │  - JSON-RPC 2.0 over stdin/stdout   ││
│  │                 │  │  - Permission handler (deny edits)   ││
│  │  - Prompt       │  │  - Retry with MCP tool instruction   ││
│  │  - Context      │  │  - Streaming chunk delivery          ││
│  │  - Session      │  └──────────────┬───────────────────────┘│
│  │  - Settings     │                 │ spawns                  │
│  └─────────────────┘                 │                         │
│                                      ▼                         │
│  ┌──────────────────┐    ┌───────────────────────┐            │
│  │ PsiBridgeService │◄───│  Copilot CLI (--acp)  │            │
│  │  (HTTP server)   │    │                       │            │
│  │  35 MCP tools    │    │  - Agent reasoning    │            │
│  │  - read/write    │    │  - Tool selection     │            │
│  │  - format        │    │  - Permission reqs    │            │
│  │  - search        │    └───────────┬───────────┘            │
│  │  - test runner   │               │                         │
│  └──────────────────┘               ▼                         │
│                          ┌──────────────────────┐             │
│                          │  MCP Server (JAR)    │             │
│                          │  intellij-code-tools │             │
│                          │  (stdio bridge)      │             │
│                          └──────────────────────┘             │
└──────────────────────────────────────────────────────────────┘
```

### Key Design: IntelliJ-Native File Operations

Built-in Copilot file edits are **denied** at the permission level. The agent automatically retries using `intellij_write_file` MCP tool, which:
- Writes through IntelliJ's Document API (supports undo/redo)
- Auto-runs optimize imports + reformat code after every write
- Changes appear immediately in the editor (no "file changed externally" dialog)
- New files are created through VFS for proper project indexing

### Module Structure

```
intellij-copilot-plugin/
├── plugin-core/          # Main plugin (Java 21)
│   └── src/main/java/com/github/copilot/intellij/
│       ├── ui/           # Tool Window (Swing)
│       ├── services/     # CopilotService, CopilotSettings
│       ├── bridge/       # CopilotAcpClient (ACP protocol)
│       └── psi/          # PsiBridgeService (35 MCP tools)
├── mcp-server/           # MCP stdio server (bundled JAR)
│   └── src/main/java/com/github/copilot/mcp/
│       └── McpServer.java
└── integration-tests/    # (placeholder)
```

## MCP Tools (32 tools)

| Category | Tools |
|----------|-------|
| **Code Navigation** | `search_symbols`, `get_file_outline`, `find_references`, `list_project_files` |
| **File I/O** | `intellij_read_file`, `intellij_write_file` |
| **Code Quality** | `get_problems`, `optimize_imports`, `format_code` |
| **Testing** | `list_tests`, `run_tests`, `get_test_results`, `get_coverage` |
| **Project** | `get_project_info`, `list_run_configurations`, `run_configuration`, `create_run_configuration`, `edit_run_configuration` |
| **Git** | `git_status`, `git_diff`, `git_log`, `git_blame`, `git_commit`, `git_stage`, `git_unstage`, `git_branch`, `git_stash`, `git_show` |
| **Infrastructure** | `http_request`, `run_command`, `read_ide_log`, `get_notifications`, `read_run_output` |
| **Terminal** | `run_in_terminal`, `list_terminals` |

## Requirements

- **JDK 21** (for plugin development)
- **IntelliJ IDEA 2024.3+** (any JetBrains IDE)
- **GitHub Copilot CLI** (`winget install GitHub.Copilot`)
- **GitHub Copilot Subscription** (active)

## Quick Start

### Building

```powershell
$env:JAVA_HOME = "path\to\jdk-21"
.\gradlew.bat :plugin-core:clean :plugin-core:buildPlugin
```

### Installing

```powershell
# Close IntelliJ first, then:
Remove-Item "$env:APPDATA\JetBrains\IntelliJIdea2025.3\plugins\plugin-core" -Recurse -Force
Expand-Archive "plugin-core\build\distributions\plugin-core-0.1.0-SNAPSHOT.zip" `
    "$env:APPDATA\JetBrains\IntelliJIdea2025.3\plugins" -Force
```

### Running Tests

```powershell
.\gradlew.bat test    # All tests (unit + MCP)
```

## Technology Stack

- **Plugin**: Java 21, IntelliJ Platform SDK 2025.x, Swing
- **Protocol**: ACP (Agent Client Protocol) over JSON-RPC 2.0 / stdin+stdout
- **MCP Tools**: Model Context Protocol over stdio
- **Build**: Gradle 8.x with Kotlin DSL
- **Testing**: JUnit 5

## Documentation

- [Development Guide](DEVELOPMENT.md) — Build, deploy, architecture details
- [Quick Start](QUICK-START.md) — Fast setup instructions
- [Architecture](docs/ARCHITECTURE.md) — Detailed component descriptions

## License

*(License TBD)*
