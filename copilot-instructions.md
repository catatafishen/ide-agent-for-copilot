# Agentic GitHub Copilot for JetBrains — Specification (v1)

> **Goal**  
> A lightweight IntelliJ‑platform plugin (Java‑first) that embeds Copilot’s **agent loop** via the **GitHub Copilot SDK**, provides first‑class UI for prompts/contexts/plans, and can safely run **Git** actions (including Conventional Commit messages) under user control. The v1 works entirely in a **local branch** that you later push to GitHub.

---

## 1) Scope

### In‑scope (v1)
- Tool Window with:
    - **Prompt** editor (multi‑line), **Context bag**, **Plans** view, **Timeline** (reasoning summaries), **Settings** (models & tool permissions).
- **Formatting & imports** options (plugin‑level):
    - *Format on save*, *Optimize imports on save*, *Format changed ranges after agent edits*, *Pre‑commit reformat/optimize* (all user‑toggleable). These reuse IntelliJ’s native formatter/import optimizer for consistency and speed.
- **Agent integration via Copilot SDK** (technical preview):
    - Session lifecycle, model selection, plan/timeline event streaming, tool invocation (structured), MCP configuration.
    - We will supply **Git tools** to the agent (see §4.3) and surface explicit approvals in the UI.
- **Git operations** (via IntelliJ Git APIs): status, commit/amend, push/(force), branch create/switch; **Conventional Commits** helpers.
- **Conventional Commit** preset: enforce/assist the official types (`feat`, `fix`, `docs`, `style`, `refactor`, `perf`, `test`, `build`, `ci`, `chore`, `revert`) + optional `scope`, `!` breaking change, and body/footers.
- **Local‑only workflow**: v1 works in a **feature branch** you create locally; pushing to origin is user‑triggered.
- **High test coverage** with JUnit 5; minimal, judicious dependencies.

### Out‑of‑scope (v1)
- Cloud delegation / remote PR generation.
- Repo‑host operations beyond basic git (e.g., creating PRs/issues).
- CLI wrapping (no parsing of Copilot CLI output).

---

## 2) Target Platforms & Compatibility

- **IntelliJ Platform**: target **2024.3–2025.2**; set `sinceBuild`/`untilBuild` per platform timeline (e.g., `243` → `252.*`).
- **Runtime & language**:
    - Plugin source/target: **Java 21** to align with recent platform requirements for 2024.2+ plugin development.
    - The IDE ships with **JetBrains Runtime (JBR) 21** by default, but JBR 25 builds are available; users/projects can target **Java 25** features in newer IDEs.
- **Copilot SDK**: technical preview; official SDKs for **Node, Python, Go, .NET** (no native Java SDK yet).

---

## 3) Architecture

### 3.1 Modules
- `plugin-core` (Java): UI (Tool Window), settings, services, ACP client, PSI bridge.
- `mcp-server` (Java): Standalone MCP stdio server bundled as JAR, routes tool calls to PSI bridge.
- `integration-tests` (Java): UI‑less functional tests (placeholder).

### 3.2 ACP Integration (direct, no sidecar)
- The plugin spawns **Copilot CLI** (`copilot --acp --stdio`) and communicates via **JSON-RPC 2.0** over stdin/stdout using the **Agent Client Protocol (ACP)**.
- CopilotAcpClient responsibilities:
    - Initialize handshake, create/close **agent sessions**, stream **plan/timeline** events.
    - Send prompts with context references and model selection.
    - Handle **permission requests** from the agent (deny built-in edits, auto-approve MCP tools).
    - Auto-retry denied operations with instruction to use IntelliJ MCP tools.
- The plugin owns user policy (approvals, allow/deny), persists settings, and renders UI.

### 3.3 MCP Tools (IntelliJ-native)
- 19 tools registered via MCP stdio server, executed through PSI bridge HTTP server inside IntelliJ process.
- **File operations**: `intellij_read_file` (editor buffer), `intellij_write_file` (Document API with undo).
- **Code quality**: `get_problems`, `optimize_imports`, `format_code`.
- **Navigation**: `search_symbols`, `get_file_outline`, `find_references`, `list_project_files`.
- **Testing**: `list_tests`, `run_tests`, `get_test_results`, `get_coverage`.
- All writes auto-trigger optimize imports + reformat code.

### 3.4 Data flow
1. User prepares **Prompt** and **Context**; clicks **Send**.
2. Plugin → Copilot CLI: `session/prompt(...)` via ACP.
3. Agent plans; emits **events** (chunks/tool_calls/plan updates).
4. If agent requests built-in file edit → **denied** → auto-retry with MCP tools.
5. MCP tools execute through PSI bridge → IntelliJ Document API.
6. Auto-format runs after every write.

---

## 4) Features (v1)

### 4.1 Tool Window
- **Prompt**
    - `EditorTextField` (Markdown), history, soft‑wrap, token estimate.
- **Context**
    - A list of context items `{file, startLine..endLine, symbol?}`.
    - Editor action: “**Add selection to context**” captures file+range; optional PSI enrichment (e.g., symbol names).
- **Plans**
    - Step‑by‑step plan with statuses; read‑only diff previews.
- **Timeline**
    - Chronological stream of assistant messages and tool calls; expandable details (summaries, not chain‑of‑thought). The CLI’s timeline feature demonstrates the UX pattern; the SDK provides structured events.
- **Settings**
    - **Model** picker (from SDK), **Permissions** (allow/ask/deny per tool), **Formatting** options.

### 4.2 Formatting & Imports (user‑configurable)
- **Format on Save** (toggle): leverage IDE’s *Actions on Save* → **Reformat code** and **Optimize imports**.
- **Format after agent edits** (default **on**): after file changes from the agent, run **format‑changed‑ranges** + **optimize imports** on affected files. (Keeps diffs tidy and predictable.)
- **Pre‑commit reformat/optimize** (default **on**): belt‑and‑braces before commit.
- **Auto‑import behavior**: expose quick‑links to **Add unambiguous imports on the fly** for users who want automatic imports; we keep it optional to avoid import churn across teams.

### 4.3 Git tools (exposed to the agent; executed via IDE)
All operations require user approval unless allowed in Settings.

- `git.status()` → branch, ahead/behind, staged/unstaged summary.
- `git.commitConventional({type, scope?, description, body?, breaking?, amend?})`
    - Formats: `type(scope)!: description` + body/footers, with **amend** when requested.
- `git.push({force?, setUpstream?})`
    - Always prompt if `force==true`.
- `git.createBranch({name, from?})`, `git.switchBranch({name})`.

These map to IntelliJ Git APIs and respect protected branches & commit checks.

### 4.4 Inspection‑→‑Fix loop (optional behind a toggle)
- **Option A (v1)**: invoke IntelliJ **command‑line inspector** (`inspect.sh`) with a project profile, output **JSON**, pass issues (e.g., weak warnings) to the agent for targeted fixes, apply patches, then format.
- **Option B (later)**: query in‑IDE highlights for a tighter loop.

---

## 5) Non‑functional requirements

- **Performance**: first Tool Window open ≤ 200 ms post‑index; UI streams events < 150 ms cadence.
- **Footprint**: minimal dependencies; sidecar **Go** binary ~10–20 MB; lean plugin JARs.
- **Privacy/Security**: tool calls require approval unless explicitly allowed; no telemetry in v1; optional local debug logs.
- **Accessibility**: keyboard navigation, high‑contrast aware.

---

## 6) Repository layout

```
agentic-copilot-intellij/
  build.gradle.kts
  settings.gradle.kts
  gradle/
  plugin-core/
    src/main/java/...       # UI, services, Git, formatting hooks (Java 21)
    src/main/resources/META-INF/plugin.xml
    src/test/java/...
  copilot-bridge/
    protocol/               # .proto / JSON schema
    src/main/go/...         # Go sidecar using Copilot SDK (static builds)
    Makefile
  docs/
    ARCHITECTURE.md
    CONTRIBUTING.md
  .github/workflows/
    ci.yml                   # build, test, package
```

*(Compatibility & template guidance: IntelliJ Platform Gradle Plugin 2.x with `sinceBuild`/`untilBuild` range.)*

---

## 7) Build & toolchain

- **Plugin**: Java 21, Gradle 8.x, IntelliJ Platform Gradle Plugin 2.x (target IDEA 2024.3–2025.2).
- **Sidecar**: Go 1.22+ using **Copilot SDK** (tech preview).
- **Tests**: JUnit 5; Mockito/AssertJ optional (keep deps minimal).

---

## 8) Settings model (project‑level, JSON)

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

---

## 9) UX flows

- **Add selection to context**: user selects code → context menu → item appears with file+range; token estimate updates.
- **Run plan**: prompt → chooI'm developing a copilot plugin for intellij. I'm talking with you through the plugin
---

## 11) CI/CD

- **GitHub Actions**: matrix (Linux/macOS/Windows); unit tests, sidecar builds, plugin ZIP artifact.
- **Static analysis**: SpotBugs or Error Prone (optional), Qodana optional.
- **Release**: draft GitHub Release with plugin ZIP; Marketplace publish post‑v1.

---

## 12) Roadmap
Create commits per feature increment until v1 is done.
---

## 13) Risks & mitigations

- **SDK tech preview**: wrap all SDK calls behind a `CopilotBridge` interface; pin SDK version; feature‑flag advanced tools.
- **Runtime mismatch (Java 25)**: keep plugin at Java 21 until IDE baselines standardize on JBR 25 for plugins; revisit once platform guidance changes.
- **Footprint**: choose **Go** sidecar for small static binaries; lazy‑start; shutdown on IDE exit.

---

## 14) Acceptance criteria (v1)

- Can create a **local feature branch**, collect **context** from selections, run a **Copilot plan**, apply edits, **format changed ranges**, and commit via **Conventional Commit** with preview & undo.
- Git tool calls require explicit **user approval** unless allowed in Settings.
- Tests pass; coverage ≥85% (core); plugin build ZIP < 10 MB (excluding sidecar binaries).
- Works on Windows/macOS/Linux across IDEA 2024.3–2025.2.

---

## Notes on Java & Kotlin

All plugin code will be **Java 21**. If a Kotlin‑only API is unavoidable, we’ll add a **thin Kotlin shim** behind a Java interface to keep the codebase Java‑centric.



---

## 15) Implementation Decisions (Feb 2026)

See README.md for detailed decisions on build system, protocol, authentication, and development strategy.
