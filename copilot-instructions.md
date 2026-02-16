# Agent Instructions for IntelliJ Copilot Plugin Development

> **Context**: You are helping develop an IntelliJ plugin that integrates GitHub Copilot with 54 MCP tools for code intelligence. The plugin is written in Java 21, targets IntelliJ 2024.3-2025.2, and uses Gradle for builds.
>
> **Project Spec**: See `PROJECT-SPEC.md` for architecture details. Only reference it when you need architecture context.

---

## ⚠️ TASK TYPE DETECTION (Read This First!)

**Before starting, identify your task type:**

### 🔴 Type A: "Fix whole project" / "Fix all problems/highlights"
→ **Use Workflow 2** (Section 3)
→ Work in logical units (one problem area at a time)
→ Commit after each unit, then ask to continue

### 🟡 Type B: "Fix this specific file" / "Fix File.java"  
→ **Use Workflow 1** (Section 3)
→ Fix the file completely, commit once

### 🟢 Type C: Specific task (implement feature, fix bug)
→ **Use Workflow 3** (Section 3)
→ Complete the logical change (may span multiple files)
→ Commit when done

**Key principle**: Group RELATED changes together, commit as logical units, then ask before moving to next UNRELATED problem.

---

## 1) Your Role

- Fix bugs, implement features, and improve code quality
- Use IntelliJ's 54 MCP tools for all code operations
- Follow workflows systematically (templates below)
- Commit after each **logical unit of work**
- **Time limit**: ~10 minutes per prompt — plan accordingly

---

## 2) Critical Rules (Never Violate)

### Threading (IntelliJ Platform)
- ✅ **Always** wrap PSI access in `ReadAction.compute()` or `ReadAction.run()`
- ✅ **Always** wrap file writes in `WriteAction.compute()` or `WriteAction.run()`
- ❌ **Never** nest read/write actions
- ✅ Use `invokeLater()` for UI operations

### Tool Preferences
- ✅ **Always** use `intellij_write_file` for file edits (never shell `echo` or `sed`)
- ✅ **Always** use `intellij_read_file` for reading files (gets live editor buffer)
- ✅ **Always** use `search_symbols`, `find_references` over grep for code navigation
- ✅ **Always** use `run_tests` over `gradle test` commands
- ✅ **Always** call `get_project_info` first to get SDK/build paths

### Null Checks
- ✅ **Keep defensive null checks** even if marked `@NotNull` (runtime != compile-time)
- ✅ Suppress with `@SuppressWarnings({"ConstantValue", "DataFlowIssue"})` if IntelliJ complains

### Tool API Contract
- ✅ **Always** provide `description` parameter for bash commands
- ✅ Example: `bash(command="ls", description="List files")`
- ❌ **Never**: `bash(command="ls")` — will fail with tool error

---

## 3) Common Workflows

### Workflow 1: Fix Single File Issues

```
1. get_highlights(path="File.java")           # See what's wrong
2. Fix issues:
   - Use apply_quickfix for supported inspections
   - Use intellij_write_file for others
3. optimize_imports(path="File.java")         # Clean imports
4. format_code(path="File.java")              # Format code
5. get_highlights(path="File.java")           # Verify fixed
6. build_project                              # Ensure compiles
7. git add . && git commit -m "fix: ..."     # Commit
8. Report results to user
```

### Workflow 2: Fix Whole Project (Systematic)

**Key concept**: Group issues by PROBLEM TYPE, not by file. A single problem may span multiple files.

```
STEP 1: Analyze
  run_inspections()
  Group issues by CATEGORY (not just file):
    - "Unused parameters: 5 across 3 files"
    - "Redundant casts: 3 in PsiBridge"  
    - "Grammar: 50 issues in multiple files"
    - "Null checks: 2 in McpServer"
  
STEP 2: Prioritize
  Pick highest-value problem category
  Examples of logical units:
    ✅ "Fix all unused parameter warnings" (may touch 3 files)
    ✅ "Fix redundant casts in PsiBridge" (1 file, related issue)
    ✅ "Add null checks for optional parameters" (may touch 2 files)
    ❌ "Fix random issues in 5 different files" (unrelated)

STEP 3: Fix the problem completely
  - Fix ALL instances of that problem type
  - May span multiple files if it's the SAME issue
  - Verify with build_project

STEP 4: Commit the logical unit
  git add . && git commit -m "fix: unused parameters in test mocks"
  
STEP 5: Report & Ask
  Report: "✅ Fixed unused parameters (5 warnings across 3 files). Committed."
  **ASK USER**: "Next problem: redundant casts (3 issues). Continue?"
  
STEP 6: Wait for user response, then repeat from STEP 2

**Rules:**
- Commit after each LOGICAL UNIT (may be 1 file or 5 files)
- Related changes belong together (e.g., refactoring that touches 4 files)
- Unrelated changes need separate commits
- Skip categories with 50+ grammar issues unless user requests
```

### Workflow 2 Self-Check (Before Starting)

**Answer these questions:**
- [ ] What is the highest-priority problem CATEGORY? (Not just "File A has issues")
- [ ] Will fixing this problem span multiple files? (That's OK if related!)
- [ ] What will my commit message be? (Should describe the logical change)
- [ ] Am I planning to ask user before moving to NEXT problem type?

If unsure → **ASK USER**: "I see 3 problem categories. Should I start with [category]?"

### Workflow 3: Implement New Feature

```
1. get_indexing_status                        # Wait if indexing
2. get_project_info                           # Get SDK paths
3. build_project                              # Establish baseline
4. Read relevant files with view/intellij_read_file
5. Implement feature (write code)
6. optimize_imports + format_code on each changed file
7. build_project                              # Verify compiles
8. run_tests if relevant                      # Verify tests pass
9. git add . && git commit -m "feat: ..."    # Commit
10. Report results
```

### Workflow 4: Before Starting Complex Tasks

**Self-check**:
- ✓ Have I read the workflow template for this task?
- ✓ Do I understand the commit strategy?
- ✓ Do I know which tools NOT to use? (e.g., apply_quickfix on GrazieInspection)
- ✓ Do I have project info (SDK paths, modules)?

If any ✗, **ask user for clarification** before proceeding.

---

## 4) Code Quality Standards

### Priority Order
1. **Compilation errors** (must fix immediately)
2. **Warnings** (fix all when asked to "clean up")
3. **Style issues** (fix if easy)
4. **Grammar** (LOW priority — only if requested)

### Tool Sequence for Quality Checks
```
Single file:   get_highlights(path) → fix → format → verify
Whole project: run_inspections → group by PROBLEM TYPE → fix problem → commit → ask → repeat
After changes: optimize_imports → format_code → get_highlights
```

### Tool-Specific Quirks

**GrazieInspection (Grammar)**:
- ❌ Does NOT support `apply_quickfix`
- ✅ Must manually edit with `intellij_write_file`
- 🔹 LOW priority — fix code issues first, grammar last
- 🔹 If 50+ grammar issues, ask user: "Grammar fixes are time-consuming. Proceed?"

**SonarLint**:
- ✅ Shows in `get_highlights` only (not `run_inspections`)
- ✅ Open file in editor first: `open_in_editor` → `get_highlights(path)`

**Large Files**:
- ✅ Read 300-500 line chunks (not 50-100 lines)
- 🔹 Reason: Each tool call = LLM reasoning cycle overhead
- 🔹 Example: 1 read of 500 lines = ~$0.01, 5 reads of 100 lines = ~$0.05

**Cognitive Complexity**:
- When writing NEW code: check `get_highlights` after each method
- If "Cognitive Complexity" warning: refactor IMMEDIATELY by extracting methods
- For EXISTING complex code: explain refactoring needed, ask if in scope

### Commit Strategy

**Logical units**: Commit related changes together, even if they span multiple files
**Examples of good logical units**:
  - ✅ "Fix unused parameters across 3 test files" (related problem)
  - ✅ "Refactor authentication flow" (touches 4 files, one feature)
  - ✅ "Add null checks to optional params" (2 files, same issue type)

**Examples of bad logical units**:
  - ❌ "Fix random issues in 5 unrelated files" (should be separate commits)
  - ❌ "Grammar fixes + refactoring + bug fix" (3 different concerns)

**Build must pass** before committing

---

## 5) Common Mistakes to Avoid

### ❌ WRONG: Fix unrelated issues without commits
```
Agent sees: unused params, grammar, null checks, deprecations
Agent fixes all of them → no commits → mixes unrelated changes
```

### ✅ RIGHT: Group by problem type, commit each
```
Agent sees: unused params, grammar, null checks
1. Fix unused params (3 files) → commit "fix: unused parameters"
2. Ask user → continue
3. Fix null checks (2 files) → commit "fix: add null safety checks"  
4. Ask user → continue
5. Skip grammar (50+ issues, low priority)
```

---

### ❌ WRONG: Split related changes across commits
```
Refactoring needs changes in Class A, B, C
Commit 1: Class A (broken build)
Commit 2: Class B (still broken)
Commit 3: Class C (now works)
```

### ✅ RIGHT: Keep related changes together
```
Refactoring needs changes in Class A, B, C
Commit 1: All 3 classes → "refactor: extract common interface"
Build passes, logical change is atomic
```

---

### ❌ WRONG: Ignore bash tool requirements
```
bash(command="ls")  # FAILS - missing description parameter
```

### ✅ RIGHT: Follow tool API contract
```
bash(command="ls", description="List files in directory")
```

---

### ❌ WRONG: Skip verification
```
Fix code → don't run build → commit → build broken on main
```

### ✅ RIGHT: Verify before commit
```
Fix code → build_project → verify success → commit
```

---

### ❌ WRONG: Use wrong tools
```
grep "class MyClass"              # Misses inheritance, imports
sed -i 's/old/new/' file.java    # Breaks undo, VCS tracking
gradle test                       # Doesn't integrate with IDE test runner
```

### ✅ RIGHT: Use IntelliJ tools
```
search_symbols(query="MyClass", type="class")    # AST-aware
intellij_write_file(old_str=..., new_str=...)   # Undo support
run_tests(target="MyTest")                       # IDE integration
```

---

### ❌ WRONG: Small reads repeatedly
```
intellij_read_file(path="File.java", start=1, end=100)    # Call 1
intellij_read_file(path="File.java", start=100, end=200)  # Call 2
intellij_read_file(path="File.java", start=200, end=300)  # Call 3
# Result: 3 reasoning cycles, ~$0.04, slower
```

### ✅ RIGHT: Batch reads
```
intellij_read_file(path="File.java", start=1, end=500)    # 1 call
# Result: 1 reasoning cycle, ~$0.01, faster
```

---

### ❌ WRONG: Try apply_quickfix on GrazieInspection
```
apply_quickfix(inspection="GrazieInspection", line=42)
# FAILS - GrazieInspection doesn't support quickfixes
```

### ✅ RIGHT: Manual edit for grammar
```
intellij_read_file(path="File.java", start=40, end=50)
intellij_write_file(path="File.java", old_str="...", new_str="...")
```

---

## 6) Tool Efficiency Tips

- **Pagination is instant**: After first `run_inspections`, subsequent pages served from cache in milliseconds
- **Batch related edits**: Multiple `old_str/new_str` pairs in single `intellij_write_file` call
- **Don't re-read**: Take notes in your reasoning instead of re-reading same sections
- **Use view for exploration**: Fast, read-only, can see large sections without edit overhead

---

## 7) 54 Available Tools

### Code Intelligence (12 tools)
`search_symbols`, `get_file_outline`, `find_references`, `find_usages`, `find_implementations`, `get_type_hierarchy`, `go_to_declaration`, `quick_definition`, `get_documentation`, `optimize_imports`, `format_code`, `run_tests`

### File Operations (8 tools)
`list_project_files`, `intellij_read_file`, `intellij_write_file`, `create_file`, `delete_file`, `get_file_properties`, `search_project`, `search_in_path`

### Inspections & Quality (10 tools)
`get_highlights`, `run_inspections`, `get_problems`, `apply_quickfix`, `suppress_inspection`, `add_to_dictionary`, `get_indexing_status`, `open_in_editor`, `refactor`, `build_project`

### Testing (6 tools)
`list_tests`, `run_tests`, `get_test_results`, `get_coverage`, `get_run_configurations`, `run_command`

### Git (6 tools)
`get_git_status`, `git_log`, `git_diff`, `git_add`, `git_commit`, `git_branch`

### Project (6 tools)
`get_project_info`, `get_project_structure`, `get_module_dependencies`, `list_project_files`, `open_project_view`, `focus_editor`

### Other (6 tools)
`get_editor_state`, `navigate_to`, `close_file`, `get_terminal_sessions`, `send_terminal_input`, `get_recent_files`

---

## 8) When to Stop & Ask

- **Unclear requirements**: Ask before implementing
- **Multiple valid approaches**: Present options, let user choose
- **Time running out**: Commit what you have, tell user to continue in next prompt
- **50+ grammar issues**: Ask if user wants them fixed (time-consuming)
- **After each file in multi-file task**: Ask "Continue to next file?"

---

## 9) SonarQube / SonarLint Findings

- SonarLint runs in the IDE and reports via `get_highlights`
- Open file first: `open_in_editor(path)` → `get_highlights(path)`
- Fix SonarLint issues with same priority as other warnings
- Use constants for duplicate string literals
- Extract methods for cognitive complexity
- Add proper exception handling (don't leave empty catch blocks)

---

## 10) Implementation Decisions (Feb 2026)

This project uses:
- **Build**: Gradle 8.x with IntelliJ Platform Plugin 2.x
- **Protocol**: ACP (via Copilot CLI), MCP (stdio server)
- **Authentication**: GitHub OAuth via Copilot CLI
- **Development**: Sandbox-first with `./restart-sandbox.sh` for fast reload
- **Testing**: JUnit 5, 89 unit tests passing

See `PROJECT-SPEC.md` for full architecture details.

---

*This file contains agent behavior guidelines only. For project architecture, see `PROJECT-SPEC.md`.*
