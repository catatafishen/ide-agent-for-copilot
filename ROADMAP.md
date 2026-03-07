# Project Roadmap

## Overview

IntelliJ plugin providing agentic GitHub Copilot capabilities via ACP protocol, with IntelliJ-native MCP tools for code
intelligence, formatting, and file operations.

---

## ✅ Phase 1: Foundation (COMPLETE)

- ✅ Multi-module Gradle project (plugin-core, mcp-server, integration-tests)
- ✅ Tool Window UI with 4 tabs (Prompt, Context, Session, Settings)
- ✅ Infrastructure prototype (later replaced with direct ACP integration)

## ✅ Phase 2: ACP Integration (COMPLETE)

- ✅ Direct ACP protocol integration
- ✅ JSON-RPC 2.0 over stdin/stdout with Copilot CLI
- ✅ Session lifecycle, model selection, streaming responses
- ✅ Authentication via Copilot CLI

## ✅ Phase 3: MCP Code Intelligence (COMPLETE)

- ✅ MCP server with 19 IntelliJ-native tools
- ✅ PSI bridge HTTP server for tool execution inside IntelliJ process
- ✅ Symbol search, file outline, reference finding
- ✅ Test runner, coverage, run configurations
- ✅ IntelliJ read/write via Document API
- ✅ Code problems, optimize imports, format code

## ✅ Phase 4: IntelliJ-Native File Operations (COMPLETE)

- ✅ Deny built-in edit/create permissions
- ✅ Auto-retry with MCP tool instruction
- ✅ Auto-format (optimize imports + reformat) after every write
- ✅ All writes through IntelliJ Document API (undo support)
- ✅ No "file changed externally" dialog

## ✅ Phase 5: Polish & Usage Tracking (COMPLETE)

- ✅ Reconnect logic (auto-restart dead ACP process)
- ✅ Model persistence, cost multiplier display
- ✅ Real GitHub billing data (premium requests, entitlement)
- ✅ Agent/Plan mode toggle
- ✅ IntelliJ platform UI conventions (JBColor, JBUI, etc.)

## ✅ Phase 6: Feature Completion (COMPLETE)

- ✅ Context tab wired to ACP resource references
- ✅ Multi-turn conversation (session reuse)
- ✅ Plans/Timeline from real ACP events
- ✅ Test infrastructure (48 tests across 4 test classes)

---

## 🎯 Future Work

### Multi-Agent Support

The ACP client has been refactored to support multiple agent backends via the `AgentConfig` strategy interface.
Currently only Copilot CLI is implemented (`CopilotAgentConfig`), but the architecture is ready for additional agents.

**Priority agents for integration (by popularity/maturity):**

1. **Claude Code** (Anthropic) — Highly popular CLI agent with strong coding capabilities
2. **Codex CLI** (OpenAI) — Widely used, backed by OpenAI's latest models
3. **Gemini CLI** (Google) — Multimodal capabilities, growing ecosystem
4. **Auggie CLI** (Augment Code) — Enterprise-focused, context-aware coding agent
5. **goose** (Block) — Open-source, extensible agent framework

**Implementation steps per agent:**

- [ ] Create `<Agent>AgentConfig` implementing `AgentConfig` (binary discovery, auth, process builder)
- [ ] Add agent selection UI (Settings tab or Tool Window dropdown)
- [ ] Agent-specific instructions/context management
- [ ] **Verify context reference handling** — Copilot ignores `ResourceReference` content (see
  `buildEffectivePromptWithContent()` workaround in `AgenticCopilotToolWindowContent.kt` and
  "Known ACP Limitations" in DEVELOPMENT.md). Each new agent must be tested to determine whether
  it surfaces resource-reference content natively or needs the same text-duplication workaround.
- [ ] Test suite per agent backend

**Architecture:**
- `AgentConfig` — strategy interface for agent-specific concerns
- `CopilotAgentConfig` — Copilot CLI implementation (binary discovery, auth, model metadata)
- `AcpClient` — generic JSON-RPC 2.0 protocol layer, agent-agnostic

### UI Improvements

- [ ] Markdown rendering in response area
- [ ] IntelliJ notifications (replace JOptionPane)
- [ ] Kotlin UI DSL migration for Settings tab
- [ ] Tool permissions in Settings tab

### Agent Capabilities

- [ ] Redirect built-in file reads through IntelliJ (read from editor buffer)

### Quality

- [ ] Cross-platform testing (macOS, Linux)
- [ ] E2E integration tests with mock Copilot agent

---

*Last Updated: 2026-03-05*
