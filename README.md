# Agentic GitHub Copilot for JetBrains

A lightweight IntelliJ Platform plugin that embeds GitHub Copilot's agent capabilities directly into your IDE, enabling AI-powered code assistance with full context awareness, planning, and Git integration.

## 🚧 Development Status

**Current Phase**: Infrastructure Setup (Phase 1)

- [ ] Multi-module Gradle project structure
- [ ] JSON-RPC protocol definitions
- [ ] Go sidecar scaffold with Copilot SDK integration
- [ ] Basic plugin skeleton with Tool Window
- [ ] Sidecar lifecycle management

## ✨ Features (Planned for v1)

### Core Capabilities
- **Agentic Workflow**: Multi-step planning and execution via GitHub Copilot SDK
- **Context Management**: Add code selections, files, and symbols to provide rich context
- **Interactive Planning**: Visual step-by-step plans with real-time progress
- **Timeline View**: Chronological view of agent reasoning and tool invocations
- **Git Integration**: Conventional Commits, branch management, push/pull with approval gates
- **Smart Formatting**: Automatic code formatting and import optimization after agent edits

### Tool Window Components
1. **Prompt Editor**: Multi-line Markdown editor with token estimates
2. **Context Bag**: Manage files, ranges, and symbols for context
3. **Plans View**: Hierarchical plan visualization with status indicators
4. **Timeline**: Expandable event stream showing agent actions
5. **Settings**: Model selection, tool permissions, formatting options

## 🏗️ Architecture

```
┌─────────────────────────────────────────────────┐
│         IntelliJ IDEA Plugin (Java 21)          │
│  ┌──────────────┐  ┌──────────────────────────┐ │
│  │ Tool Window  │  │   Services & Adapters    │ │
│  │   (Swing)    │  │  - Git (VCS API)         │ │
│  │              │  │  - Formatter             │ │
│  │ - Prompt     │  │  - Settings Persistence  │ │
│  │ - Context    │  │  - Approval Manager      │ │
│  │ - Plans      │  │                          │ │
│  │ - Timeline   │  │                          │ │
│  └──────┬───────┘  └────────┬─────────────────┘ │
│         │                   │                    │
│         └─────────┬─────────┘                    │
│                   │ JSON-RPC/HTTP                │
└───────────────────┼──────────────────────────────┘
                    │
         ┌──────────▼──────────┐
         │   Go Sidecar        │
         │  (Copilot SDK)      │
         │                     │
         │ - Session Mgmt      │
         │ - Model Selection   │
         │ - Event Streaming   │
         │ - Tool Registration │
         └─────────────────────┘
```

### Module Structure

```
intellij-copilot-plugin/
├── plugin-core/              # Main plugin module (Java 21)
│   ├── src/main/java/
│   │   └── com/github/copilot/intellij/
│   │       ├── ui/           # Tool Window, actions, editors
│   │       ├── services/     # Application/project services
│   │       ├── git/          # Git VCS integration
│   │       ├── format/       # Code formatting hooks
│   │       └── settings/     # Configuration & persistence
│   └── src/main/resources/
│       └── META-INF/plugin.xml
│
├── copilot-bridge/           # Sidecar process (Go)
│   ├── protocol/             # JSON-RPC schemas
│   ├── cmd/sidecar/          # Main entry point
│   ├── internal/
│   │   ├── server/           # HTTP JSON-RPC server
│   │   ├── copilot/          # SDK integration
│   │   └── session/          # Session lifecycle
│   └── Makefile
│
└── integration-tests/        # Functional tests (Java 21)
    └── src/test/java/
```

## 🛠️ Technology Stack

- **Plugin**: Java 21, IntelliJ Platform SDK 2025.x
- **Build System**: Gradle 8.x with Kotlin DSL
- **Sidecar**: Go 1.22+, GitHub Copilot SDK (technical preview)
- **Protocol**: JSON-RPC over HTTP/1.1
- **Testing**: JUnit 5, AssertJ (optional), Mockito (optional)

## 📋 Requirements

### For Development
- **JDK 21** (IntelliJ plugin development)
- **Go 1.22+** (sidecar development)
- **IntelliJ IDEA 2025.x** (Community or Ultimate)
- **GitHub Copilot CLI** (installed and authenticated)
- **GitHub Copilot Subscription** (active)

### For Users (Runtime)
- **IntelliJ IDEA 2024.3 - 2025.2** (any JetBrains IDE on IntelliJ Platform)
- **GitHub Copilot CLI** (managed by sidecar installation)
- **GitHub Copilot Subscription**

## 🚀 Getting Started

### Building the Plugin

```bash
# Clone the repository
git clone https://github.com/yourusername/intellij-copilot-plugin.git
cd intellij-copilot-plugin

# Build the Go sidecar
cd copilot-bridge
make build

# Build the plugin
cd ..
./gradlew buildPlugin

# Run in a sandboxed IDE
./gradlew runIde
```

### Running Tests

```bash
# Run all tests
./gradlew test

# Run with coverage
./gradlew test jacocoTestReport
```

## 🔧 Configuration

Plugin settings are stored per-project in `.idea/copilot-agent.json`:

```json
{
  "model": "gpt-5-mini",
  "toolPermissions": {
    "git.commit": "ask",
    "git.push": "ask",
    "git.forcePush": "deny",
    "fs.write": "ask"
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
    "enforceScopes": false
  }
}
```

## 📖 Documentation

- [Architecture Details](docs/ARCHITECTURE.md) *(coming soon)*
- [Contributing Guide](docs/CONTRIBUTING.md) *(coming soon)*
- [API Reference](docs/API.md) *(coming soon)*

## 🧪 Development Roadmap

### Phase 1: Infrastructure (Current) - **90% COMPLETE** 🎉
- [x] Project setup decisions documented
- [x] Multi-module Gradle build (plugin-core, integration-tests)
- [x] JSON-RPC protocol definitions
- [x] Go 1.22.5 installed and configured
- [x] Gradle 8.11 installed
- [x] **Go sidecar fully implemented and tested** ✨
  - Mock Copilot client with clean interface
  - Session management working
  - All RPC endpoints functional
  - Binary size: 7.2 MB, fully tested
- [x] **Tool Window UI complete** ✨
  - Factory (Java) + Content (Kotlin hybrid approach)
  - 5 tabs: Prompt, Context, Plans, Timeline, Settings
  - Icon and registrations in plugin.xml
- [x] **Java bridge layer complete** ✨
  - SidecarProcess (lifecycle management)
  - SidecarClient (HTTP JSON-RPC with Gson)
  - SidecarException (error handling)
- [x] **Services layer complete** ✨
  - AgenticCopilotService (application service)
  - SidecarService (sidecar lifecycle)
- [x] Comprehensive documentation (Architecture, Development Guide, Plan)
- [x] Go plugin installed in IntelliJ
- [x] Hybrid UI approach implemented (Java core + Kotlin UI DSL)
- [x] All dependencies added (Gson, Kotlin stdlib)
- [ ] Gradle wrapper generation (IntelliJ SDK download ~95% complete)
- [ ] First plugin build and test in sandbox IDE

### Phase 2: Core Features
- [ ] Prompt editor with context management
- [ ] Plans and Timeline visualization
- [ ] Model selection and settings UI
- [ ] Session lifecycle management

### Phase 3: Git Integration
- [ ] Git status, branch, commit operations
- [ ] Conventional Commits support
- [ ] Approval/permission system
- [ ] Push with safety checks

### Phase 4: Code Quality
- [ ] Format-on-save integration
- [ ] Format-after-edit (changed ranges)
- [ ] Import optimization
- [ ] Pre-commit hooks

### Phase 5: Testing & Polish
- [ ] Unit tests (≥85% coverage)
- [ ] Integration tests
- [ ] Cross-platform support (macOS, Linux)
- [ ] Performance optimization
- [ ] Documentation

## 📝 License

*(License TBD)*

## 🤝 Contributing

Contributions are welcome! Please see [CONTRIBUTING.md](docs/CONTRIBUTING.md) for guidelines.

---

**Note**: This plugin uses the GitHub Copilot SDK which is currently in technical preview. Features and APIs may change.
