# CLI Bug #556 Workaround

## The Bug

**GitHub Issue:** https://github.com/github/copilot-cli/issues/556  
**Title:** Copilot CLI --agent use does not respect tool filtering  
**Status:** OPEN (assigned, labeled as bug)  
**Reported:** Nov 13, 2025  
**Last Updated:** Jan 5, 2026

### Description

Tool filtering does not work in Copilot CLI's `--acp` mode (or `--agent` mode):
- CLI flags: `--available-tools`, `--excluded-tools` - IGNORED
- Session params: `availableTools`, `excludedTools` in `session/new` - IGNORED
- Agent configs: `tools: ["read"]` in agent Markdown files - IGNORED

**Result:** Agent receives ALL tools regardless of filtering attempts.

### Affects All SDKs

Since all SDKs (Python, Go, TypeScript, .NET, Java) communicate with the same CLI server underneath, none can work around this bug. The Java SDK's `setAvailableTools()` API is correct, but the CLI ignores it.

## Our Workaround

Since we can't filter tools at the CLI level, we enforce it at runtime via **permission denial**.

### Implementation

**File:** `plugin-core/src/main/java/com/github/copilot/intellij/bridge/CopilotAcpClient.java`

**Denied Permission Kinds:**
```java
private static final Set<String> DENIED_PERMISSION_KINDS = Set.of(
    "edit",          // CLI built-in view tool - deny to force intellij_write_file
    "create",        // CLI built-in create tool - deny to force intellij_write_file
    "read",          // CLI built-in view tool - deny to force intellij_read_file
    "execute",       // Generic execute - doesn't exist, agent invents it
    "runInTerminal"  // Generic name - actual tool is run_in_terminal
);
```

**Retry Message:**
```
❌ Tool denied. Use tools with 'intellij-code-tools-' prefix instead.
```

### Why This Works

1. Agent tries CLI built-in tool (e.g., `view`)
2. Plugin denies permission request
3. Agent receives simple guidance: "Use intellij-code-tools- prefix"
4. Agent retries with correct tool (e.g., `intellij-code-tools-intellij_read_file`)

### Benefits of IntelliJ Tools

- ✅ Read **live editor buffers** (with unsaved changes)
- ✅ Integrated with IntelliJ's Document API (undo/redo, VCS tracking)
- ✅ AST-based search (not pattern matching)
- ❌ CLI tools read **stale disk files** (don't see unsaved edits)

## Experiment: `--deny-tool` Flag (Feb 2026)

### Hypothesis

While `--available-tools` and `--excluded-tools` operate at the **tool filtering layer**
(broken in ACP mode per bug #556), `--deny-tool` operates at the **permission layer** —
it auto-denies permission requests for specified tools. This is the same mechanism as our
`DENIED_PERMISSION_KINDS` workaround, but enforced by the CLI process itself.

If `--deny-tool` works in ACP mode, it would be a stronger defense because:
- The CLI denies tools **before** they reach our permission handler
- It may also block platform-invoked tools that bypass `request_permission` entirely
- It's a single CLI flag vs. our multi-layer runtime workaround

### What We Added

```
--deny-tool view edit create grep glob bash
```

Added to `buildAcpCommand()` in `CopilotAcpClient.java`.

### What to Verify

1. **Does the flag work in ACP mode?** — Check if tools are denied without reaching `handlePermissionRequest()`
2. **Does it block platform-invoked tools?** — The key question: do `view`/`edit`/`bash` still bypass when the platform invokes them directly?
3. **Error messages** — Does the CLI provide useful guidance, or do we still need our custom retry messages?

### Outcome

**Status:** TESTING — Restart the plugin to apply. Observe IDE logs for `--deny-tool` behavior.

If it works:
- Keep `--deny-tool` as primary defense
- Keep `DENIED_PERMISSION_KINDS` as fallback (defense in depth)
- Update copilot-instructions.md to remove "Known Limitation" if bypass is fixed

If it doesn't work:
- Remove `--deny-tool` flags from launch command
- File a follow-up bug on the CLI for `--deny-tool` not working in ACP mode

## When to Remove This Workaround

Monitor https://github.com/github/copilot-cli/issues/556 for updates.

Once fixed:
1. **Test:** Verify `availableTools` session param actually filters tools
2. **Update:** Switch from permission denial to proper filtering
3. **Keep:** Still deny `execute`/`runInTerminal` (non-existent tools)
4. **Remove:** This documentation file

## References

- CLI Bug: https://github.com/github/copilot-cli/issues/556
- CLI `--deny-tool` flag: discovered in `copilot --help` output, not documented in bug #556
- Checkpoint: `.copilot/session-state/.../checkpoints/020-tool-filtering-investigation.md`
- Checkpoint: `.copilot/session-state/.../checkpoints/021-permission-denial-simplification.md`
