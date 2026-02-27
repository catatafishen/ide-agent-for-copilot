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

### UI Improvements

- [ ] Markdown rendering in response area
- [ ] IntelliJ notifications (replace JOptionPane)
- [ ] Kotlin UI DSL migration for Settings tab
- [ ] Tool permissions in Settings tab

### Agent Capabilities

- [ ] Terminal support (ACP terminal capability)
- [ ] Redirect built-in file reads through IntelliJ (read from editor buffer)
- [ ] Git integration (commit, branch, diff tools)

### Quality

- [ ] Cross-platform testing (macOS, Linux)
- [ ] E2E integration tests with mock Copilot agent
- [ ] Dynamic plugin reload support
- [ ] CI/CD pipeline

---

*Last Updated: 2026-02-13*
