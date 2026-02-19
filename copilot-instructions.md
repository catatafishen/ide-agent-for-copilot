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

## 5) Fixing Inspection Issues (SonarQube, IntelliJ, Qodana)

### Philosophy: Fix Root Causes, Don't Suppress

**When asked to fix inspection issues:**

✅ **DO**:
- **Investigate thoroughly** — understand WHY the issue is flagged
- **Fix the underlying problem** — refactor code to eliminate the issue
- **Test your fix** — ensure it doesn't break functionality
- **Question false positives** — if genuinely wrong, document why and get confirmation

❌ **DON'T**:
- **Suppress warnings** without investigation
- **Add `@SuppressWarnings` as first resort**
- **Disable inspection rules** to make count go down
- **Assume all issues are false positives**

### Common Issues & Proper Fixes

**Duplicate string literals**:
- ✅ Extract to private static final constants
- ❌ Don't suppress — it's real technical debt

**Cognitive complexity**:
- ✅ Extract methods, simplify conditions, reduce nesting
- ❌ Don't suppress — refactor the complex logic

**Empty catch blocks**:
- ✅ Log the exception or add comment explaining why it's safe to ignore
- ❌ Don't suppress — either handle it or document why it's intentional

**InterruptedException**:
- ✅ Always call `Thread.currentThread().interrupt()` after catching
- ❌ Don't suppress — this is critical for thread safety

**Unused parameters/methods**:
- ✅ Remove if truly unused; keep if required by interface/override
- ✅ Add `@SuppressWarnings("unused")` ONLY if kept for API compatibility
- ❌ Don't suppress private methods that can be deleted

### When Suppression IS Appropriate

Only suppress when:
1. **Required by framework** (override signature, interface implementation)
2. **Public API** that must be kept for backward compatibility
3. **Defensive code** that IntelliJ marks as "always true/false" but protects against edge cases
4. **Known false positive** that has been investigated and documented

Example of appropriate suppression:
```java
@Override
@SuppressWarnings("unused") // Required by SomeInterface contract
public void methodUsedByFramework(Object param) {
    // Framework calls this reflectively
}
```

### Workflow for Fixing Issues

1. **Run inspections**: `run_inspections` to see all issues
2. **Understand the issue**: Read the description, check the flagged code
3. **Fix properly**: Refactor code to eliminate root cause
4. **Verify**: Re-run inspections to confirm issue is gone
5. **Test**: Build project to ensure no breakage
6. **Commit**: Group related fixes in logical commits

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
