# Project Development Guidelines — IntelliJ Copilot Plugin

> This file provides project-specific conventions. Tool usage rules and workflow instructions are provided by the MCP server at initialization.
>
> **Project Spec**: See `PROJECT-SPEC.md` for full architecture details.

---

## 1) Project Context

- IntelliJ plugin integrating GitHub Copilot with 54 MCP tools for code intelligence
- Written in **Java 21**, targets IntelliJ 2024.3-2025.2, built with Gradle
- Three modules: `plugin-core` (UI + services), `mcp-server` (tool definitions), `integration-tests`
- ACP protocol via Copilot CLI, MCP via stdio server, PSI bridge via HTTP

---

## 2) IntelliJ Platform Rules

### Threading
- ✅ **Always** wrap PSI access in `ReadAction.compute()` or `ReadAction.run()`
- ✅ **Always** wrap file writes in `WriteAction.compute()` or `WriteAction.run()`
- ❌ **Never** nest read/write actions
- ✅ Use `invokeLater()` for UI operations

### Null Checks
- ✅ **Keep defensive null checks** even if marked `@NotNull` (runtime != compile-time)
- ✅ Suppress with `@SuppressWarnings({"ConstantValue", "DataFlowIssue"})` if IntelliJ complains

---

## 3) Code Quality Standards

### Priority Order
1. **Compilation errors** (must fix immediately)
2. **Warnings** (fix all when asked to "clean up")
3. **Style issues** (fix if easy)
4. **Grammar** (LOW priority — only if requested)

### Tool-Specific Quirks

**GrazieInspection (Grammar)**:
- ❌ Does NOT support `apply_quickfix`
- ✅ Must manually edit with `intellij_write_file`
- If 50+ grammar issues, ask user first

**SonarLint**:
- Shows in `get_highlights` only (not `run_inspections`)
- Open file in editor first: `open_in_editor` → `get_highlights(path)`

**Cognitive Complexity**:
- When writing NEW code: check `get_highlights` after each method
- If "Cognitive Complexity" warning: refactor IMMEDIATELY by extracting methods

### Commit Strategy

**Group RELATED changes as logical units** — may span multiple files:
- ✅ "Fix unused parameters across 3 test files" (related problem)
- ✅ "Refactor authentication flow" (4 files, one feature)
- ❌ "Fix random issues in 5 unrelated files" (separate commits)
- ❌ "Grammar + refactoring + bug fix" (3 different concerns)

**Build must pass** before committing.

---

## 4) Common Pitfalls

### ❌ Split related refactoring across commits
```
Commit 1: Class A (broken build)
Commit 2: Class B (still broken)
Commit 3: Class C (finally works)
```

### ✅ Keep related changes atomic
```
Commit 1: All 3 classes → "refactor: extract common interface"
Build passes, logical change is complete
```

---

### ❌ Mix unrelated fixes in one commit
```
One commit: grammar + null checks + unused params
```

### ✅ Separate commits per problem type
```
Commit 1: "fix: unused parameters in test mocks"
Commit 2: "fix: add null safety checks"
```

---

## 5) SonarQube / SonarLint Findings

- Use constants for duplicate string literals
- Extract methods for cognitive complexity
- Add proper exception handling (don't leave empty catch blocks)
- Fix `InterruptedException`: always call `Thread.currentThread().interrupt()`

---

## 6) Implementation Decisions (Feb 2026)

- **Build**: Gradle 8.x with IntelliJ Platform Plugin 2.x
- **Protocol**: ACP (via Copilot CLI), MCP (stdio server)
- **Authentication**: GitHub OAuth via Copilot CLI
- **Development**: Sandbox-first with `./restart-sandbox.sh` for fast reload
- **Testing**: JUnit 5, 89 unit tests passing
- **All code**: Java 21. Kotlin shims only when IntelliJ API requires it.

---

*Tool usage rules and workflows are provided by the MCP server. See `PROJECT-SPEC.md` for architecture.*
