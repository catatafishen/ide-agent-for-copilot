# ACP Tool Interception

> Redirect built-in agent tools (`view`, `edit`, `bash`, `grep`, …) to MCP
> equivalents **before** they execute. Two layers cooperate:
>
> 1. **ACP layer** — intercepts `fs/read_text_file` / `fs/write_text_file`
>    requests round-tripped over JSON-RPC.
> 2. **PATH-shim layer** — intercepts shell commands (`cat`, `head`, `grep`,
>    `git …`) at the leaf-binary level by injecting a tiny shim onto `PATH`
>    in the agent subprocess.
>
> Two layers are needed because not every built-in tool round-trips through
> ACP; Copilot CLI in particular runs `bash` via a long-lived node-pty session
> that **bypasses** ACP `terminal/create` entirely.

**Applies to:** every ACP client we ship (Copilot, Junie, Kiro, OpenCode).
**Not applicable to:** Claude / Codex (non-ACP — they call MCP directly).

---

## Why

For months we tried to make ACP agents stop using their built-in tools because
those tools (a) read stale disk files instead of unsaved editor buffers and
(b) bypass the IDE's VCS/undo plumbing. Every approach failed:

| Mechanism | Result |
|---|---|
| `--excluded-tools` / `--available-tools` (Copilot) | Ignored in ACP mode — bug [copilot-cli#556](https://github.com/github/copilot-cli/issues/556). |
| `excludedTools` in `session/new` (custom ACP extension) | Ignored by Copilot, OpenCode. Junie ≥ v888.212 honours it. |
| `--deny-tool` (Copilot) | Ignored in ACP mode. |
| Per-agent `tools:` frontmatter | Ignored in ACP mode. |
| Permission denial (`DENIED_PERMISSION_KINDS`) | Only catches *write* tools — `view`, `grep`, `glob` auto-execute with no permission step. |
| Reprimand: auto-approve + inject "[System notice]" into next prompt | Lets the wrong action happen first; only corrects future turns. |
| Junie pre-launch `.junie/allowlist.json` | Works only for Junie ≥ v888.212. |
| **ACP `terminal/create` interception** | **Worked in unit tests, dead in production** — see below. |

See [`docs/bugs/CLI-BUG-556-WORKAROUND.md`](bugs/CLI-BUG-556-WORKAROUND.md)
and [`docs/JUNIE-TOOL-WORKAROUND.md`](JUNIE-TOOL-WORKAROUND.md) for the full
history.

## The two insights

**Insight 1 (`fs/*`):** When Copilot runs `view` or `edit`, it round-trips
to us as the standard ACP methods `fs/read_text_file` and `fs/write_text_file`.
We already dispatch those — we just have to redirect them to MCP before the
default handler runs. **This part works.**

**Insight 2 (shell):** When Copilot runs `bash`, it does **not** round-trip
through ACP `terminal/create`. Instead the CLI bundles `node-pty` and spawns
a long-lived `bash --norc --noprofile` directly via `IF.spawn("bash", …)`.
After the user (or auto-approve) approves the bash tool, every subsequent
bash command for the rest of the turn is fed to that same bash over stdin —
no further ACP round-trips happen. We tried intercepting `terminal/create`
and saw zero hits in production logs.

The fix exploits the fact that `IF.spawn("bash", …)` and any leaf command
bash itself runs (`cat`, `grep`, `git`, …) all use `execvp` — i.e. they
**resolve binaries via `$PATH`**. The plugin already controls the agent
subprocess environment, so we can prepend a directory full of shim binaries.
When bash spawns `cat foo.txt`, our shim is hit instead of `/usr/bin/cat`,
POSTs the argv to a localhost HTTP endpoint, and either prints an MCP-routed
result or transparently `exec`s the real binary.

## Architecture

```
                 ┌─────────────────────────────────┐
                 │  Plugin (JVM)                   │
                 │   • McpHttpServer               │
                 │     POST /shim-exec  ──┐        │
                 │   • PsiBridgeService    │       │
                 └────────┬────────────────┼───────┘
                          │ ProcessBuilder │ loopback
                          │ env[PATH]      │
                          ▼                │
              ┌─────────────────────┐      │
              │  ACP agent (node)   │      │
              │  PATH=<shim-dir>:…  │      │
              │  AGENTBRIDGE_SHIM_* │      │
              └────────┬────────────┘      │
                       │                   │
            ┌──────────┼─────────┐         │
            ▼                    ▼         │
   ACP terminal/create   node-pty.spawn    │
     (Junie, OpenCode,   ("bash", …)       │
      direct-tool        ↓                 │
      Copilot calls)     long-lived bash   │
            │            ↓                 │
            │            cat foo.txt       │
            │            └─ execvp("cat")  │
            │              → PATH lookup   │
            │              → <shim-dir>/cat│
            │                  │           │
            │                  ▼           │
            │            ┌──────────┐      │
            │            │  shim    │      │
            │            │  POST ───┼──────┘
            │            └──────────┘
            ▼                  │
   AcpToolInterceptor          ▼
     (fs/* only —              200 OK + EXIT N + body
      no terminal              │
      interception)            ▼
            │                  prints stdout, exits N
            │                  (or 204 → exec real binary)
            ▼
   AcpTerminalHandler          
     → VisibleProcessRunner    
     → IDE Run tool window     
```

Two interception points cover all four agents:

* **ACP `fs/*`** — Junie, Copilot's `view`/`edit`, OpenCode, Kiro all use this.
  Falls back to the default `fsHandler` on any MCP error.
* **PATH shim** — every command bash spawns inside any agent. Falls back to
  the real binary on HTTP failure, 4xx, 5xx, or 204 passthrough.

## What gets intercepted

| Trigger | Where | Redirected to | Win |
|---|---|---|---|
| ACP `fs/read_text_file` | `AcpToolInterceptor.interceptRead` | MCP `read_file` | Sees unsaved editor edits |
| ACP `fs/write_text_file` | `AcpToolInterceptor.interceptWrite` | MCP `write_file` | Undo + auto-format + VCS sync |
| `bash cat <file>` | shim → `/shim-exec` | MCP `read_file` | Editor buffer sync |
| `bash head [-n N] <file>` | shim → `/shim-exec` | MCP `read_file` (lines 1..N, default 10) | Same |
| `bash grep -F/-E <pat> [file]`, `egrep`, `fgrep` | shim → `/shim-exec` | MCP `search_text` | Regex + index, POSIX exit codes |
| `bash rg <pat>` (with `-F`/`-i`/`--glob`) | shim → `/shim-exec` | MCP `search_text` | Same |
| `bash git status` / `diff` / `log` / `branch` (read-only forms) | shim → `/shim-exec` | MCP `git_*` | Branch context, no shell parse |

The shim handler reuses the same `ShellRedirectPlanner` that previously powered
the (now-deleted) ACP `terminal/create` path — including all 51 unit tests.
That keeps the redirect rules and their POSIX exit-code semantics in one
testable Java class.

## What deliberately falls through

Anything `ShellRedirectPlanner` returns no plan for runs as a normal child of
bash — the shim re-`exec`s the real binary (with the shim dir stripped from
PATH so we don't recurse). User sees it in the same bash session as always.

- Any shell metacharacter in the original `bash` invocation (already inside
  bash by the time the shim runs — handled natively).
- Any flag we don't replicate: `cat -n`, plain `grep` (POSIX BRE), `tail`
  without explicit `-n N`, …
- Mutating git ops: `commit`, `push`, `rebase`, `merge`, `reset`, …
- Build tools, package managers, network, anything else.

## The shim wire protocol

```
POST http://127.0.0.1:$AGENTBRIDGE_SHIM_PORT/shim-exec
  X-Shim-Token: <per-IDE-start UUID>
  Content-Type:  application/x-www-form-urlencoded
  Body:          argv=<cmd>&argv=<arg1>&argv=<arg2>…  (URL-encoded)

200 → body "EXIT N\n<stdout-bytes>"
       shim prints stdout and exits with N
204 → passthrough
       shim execs the real binary on PATH (minus shim dir)
401/4xx/5xx/network failure → fail-open (passthrough)
```

The protocol is deliberately tiny so the bash shim can parse it with builtins
only — no `head`, `grep`, etc. (those names are themselves shimmed). The
per-process token blocks any other local process from injecting fake commands
even though `127.0.0.1` is reachable to the whole machine.

## Visible fall-through terminal

For agents that *do* use ACP `terminal/create` directly (rather than going
through bash), `AcpTerminalHandler` mirrors stdout/stderr to a tab in the IDE
**Run** tool window via `VisibleProcessRunner`. The user can watch the
command live and kill it with the standard stop button.

## Files

| Path | Role |
|---|---|
| `acp/client/intercept/AcpToolInterceptor.java` | Intercepts `fs/read_text_file` and `fs/write_text_file`. Returns `null` on MCP error so the default handler still runs. |
| `acp/client/intercept/ShellRedirectPlanner.java` | Pure static classifier: `argv → RedirectPlan?`. All per-command flag parsing lives here. Project-free for testability. Reused by both the (historic) ACP path and the new shim. |
| `acp/client/intercept/RedirectPlan.java` | Immutable record carrying tool name, args, post-processor, and exit-code function. |
| `acp/client/intercept/ShellCommandSplitter.java` | Argv tokenizer rejecting unsafe metacharacters. Used by tests and as a defensive splitter when ACP only sends `command` (no `args[]`). |
| `acp/client/intercept/VisibleProcessRunner.java` | Wraps `OSProcessHandler` + `ConsoleViewImpl` for ACP `terminal/create`. |
| `acp/client/AcpTerminalHandler.java` | Always uses `VisibleProcessRunner` for unredirected `terminal/create`. |
| `acp/client/AcpClient.java` | Holds the interceptor and calls `installShimEnv()` on every launched agent subprocess. |
| `shim/ShimManager.java` | `@Service(PROJECT)`. Extracts the shim script under `<systemPath>/agentbridge/shims/<projectHash>/<command>` once per IDE start. Owns the auth token. |
| `shim/ShimController.java` | `HttpHandler` for `/shim-exec`. Validates token, parses argv, delegates to `ShimRedirector`. |
| `shim/ShimRedirector.java` | Bridges argv → `ShellRedirectPlanner` → `PsiBridgeService.callTool` → `Result(stdout, exitCode)` or `null` for passthrough. |
| `services/McpHttpServer.java` | Registers `/shim-exec` next to `/health` on the existing loopback HTTP server. |
| `resources/agentbridge/shim/agentbridge-shim.sh` | The shim itself. POSIX bash, builtins only, copies into the system path under each command name (cat, grep, …). Falls open on every error. |

## Tests

* 14 `ShellCommandSplitterTest` cases — quoting, escaping, every rejected
  metacharacter, blank input, trailing whitespace.
* 3 `AcpToolInterceptorTest` cases — `isMcpError`.
* 51 `ShellRedirectPlannerTest` cases — happy paths, fall-throughs, flag
  parsing edge cases (combined short flags, `--lines=N`, `--glob=`, `--`
  separator), exit-code semantics, `search_text` header stripping.
* 7 `ShimControllerTest` cases — argv form parser, URL decoding, malformed
  pairs.
* 3 `ShimScriptE2eTest` cases (Linux/macOS) — runs the actual bash shim
  against an in-process HTTP server. Verifies HTTP 200 + `EXIT N\n<stdout>`,
  HTTP 204 passthrough to a fake binary on PATH, and missing-port skip.
* All 68 intercept + shim + ACP client + terminal-handler tests pass.

---

## Follow-ups

### A. Cross-platform completion

* **macOS** — same bash shim works. Needs codesign + notarize via the
  JetBrains plugin signing identity in CI.
* **Windows** — bash shim won't run there; need a real `bash.exe` /
  `cmd.exe` / `pwsh.exe` shim. Plan: tiny Go program (`main.go` ~150 LOC),
  cross-compiled per (OS, arch), Authenticode-signed.
* **GraalVM rejected** — well-known false-positive flagging by Windows
  Defender; binary size 10–15 MB vs 2 MB Go; cold-start 10–50 ms vs ~3 ms.

### B. Expanding the interception surface

Already shipped: `cat`, `head`, `grep -F/-E`, `egrep`, `fgrep`, `rg`,
`git status`, `git diff`, `git log`, `git branch`.

Still on the list:

* **More git read ops** — `git show`, `git remote -v`, `git tag`,
  `git stash list`. Each maps cleanly to an existing MCP `git_*` tool.
* **`rg <pat> <path>`** — currently rejected because `search_text` doesn't
  accept a directory scope. Could map `<path>` to a glob like `<path>/**`.
* **`find` / `fd` → `list_project_files`** — start with `find <dir> -name
  <pattern>`.
* **`tail -n N <file>` → `read_file` (last N lines)** — needs a 2-step
  approach because `read_file` truncates large files.
* **Plain `grep`** — needs BRE → Java-regex translation; defer until demand.

### C. Dead-code candidates that interception now obsoletes

These layers existed because we couldn't stop the agent from calling its
built-in tools. With both interception layers in place an offending call
either gets the right answer transparently (MCP) or runs visibly in the IDE.

* **`CopilotClient.misusedBuiltInTools` + `beforeSendPrompt()` reprimand**.
  Records intercepted built-in tool calls and prepends a "[System notice]
  use MCP instead" block to the next prompt. With shim interception in
  place these calls already succeed via MCP; nothing to reprimand. Candidate
  for full removal once telemetry shows zero misused-built-in events.
* **`PsiBridgeService.setPendingNudge` / `addOnNudgeConsumed` plumbing**.
  Used only by the reprimand path above.
* **`AgentProfile.excludeAgentBuiltInTools` setting**. The whole point of
  this knob was "stop the agent using its built-in tools". Either remove,
  re-purpose as a kill-switch for interception, or repurpose as "force
  fall-through to a visible terminal".
* **`JunieClient` `.junie/allowlist.json` writer**. Pre-launch deny-list
  for Junie ≥ v888.212. Still useful as belt-and-braces but no longer
  load-bearing.
* **`default-startup-instructions.md` aggressive tool-policy block**. Can
  be downgraded from "critical" to a short note since interception catches
  policy-ignoring agents anyway.
* **`detectSubAgentWriteTool` / `detectSubAgentGitWrite` guards**. Sub-agents
  that ignore the policy now succeed via MCP; guards remain useful only for
  destructive fall-through cases (e.g. `bash rm -rf`).
* **`CopilotClient.EXCLUDED_BUILTIN_TOOLS` + `--excluded-tools` flag**.
  No longer load-bearing. Keep as forward-compat for the day
  [#556](https://github.com/github/copilot-cli/issues/556) lands, or delete.
* **External-tool warning emoji (⚠ on tool chips)**. With interception, the
  built-in tool *was* caught. Either suppress the badge or flip it to
  ↻ "redirected".

### D. Validation matrix

* **Telemetry** — emit a counter per `(client, redirect_layer, intercepted_tool, outcome)`
  so we can quantify how often each layer fires per session. Required before
  removing any of section C.
* **OpenCode / Junie / Kiro shim verification** — confirm bash on each agent
  resolves the shim. Lookup is the same `execvp` path on Linux/macOS so it
  should, but verify with a probe.
* **Sub-agent reach** — confirm Copilot's `task` tool sub-agents inherit the
  shim PATH (they should, ProcessBuilder env propagates).
* **Cold-start overhead** — measure end-to-end latency of `cat /etc/hosts`
  via Copilot bash with the shim. Target <20 ms.

## References

* Branch: `feat/path-shim-bash` (this work). Original ACP-only attempt:
  `feat/acp-tool-interception-v2` (PR #319).
* Related docs:
  * [`docs/bugs/CLI-BUG-556-WORKAROUND.md`](bugs/CLI-BUG-556-WORKAROUND.md)
  * [`docs/JUNIE-TOOL-WORKAROUND.md`](JUNIE-TOOL-WORKAROUND.md)
  * [`docs/JUNIE-CLI-TOOL-FILTERING.md`](JUNIE-CLI-TOOL-FILTERING.md)
  * [`docs/ARCHITECTURE.md`](ARCHITECTURE.md) — overall ACP/MCP layering
* Upstream issues this sidesteps: copilot-cli
  [#556](https://github.com/github/copilot-cli/issues/556),
  [#1574](https://github.com/github/copilot-cli/issues/1574),
  [#2059](https://github.com/github/copilot-cli/issues/2059).
