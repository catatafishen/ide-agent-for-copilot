# Retry Prompt Mechanism

## Problem

When the Copilot agent tries to use CLI built-in tools (view, edit, create, grep, glob, bash), we need to deny them and guide the agent to use our IntelliJ MCP tools instead. However, simply denying the permission causes the agent to see "Tool failed" without understanding what went wrong.

## Solution: Second Prompt Workaround

The retry mechanism uses a **two-phase approach**:

### Phase 1: Permission Denial
```java
// In handlePermissionRequest()
if (DENIED_PERMISSION_KINDS.contains(permKind)) {
    String rejectOptionId = findRejectOption(reqParams);
    builtInActionDeniedDuringTurn = true;  // Set flag
    lastDeniedKind = permKind;              // Remember what was denied
    sendPermissionResponse(reqId, rejectOptionId);  // Send rejection
}
```

### Phase 2: Automatic Follow-up Prompt
```java
// In sendPrompt() after the first session/prompt completes
if (builtInActionDeniedDuringTurn) {
    String deniedKind = lastDeniedKind;
    builtInActionDeniedDuringTurn = false;
    
    // Send a SECOND session/prompt with guidance
    result = sendRetryPrompt(sessionId, model, deniedKind);
}
```

The `sendRetryPrompt()` method sends a **completely new `session/prompt` JSON-RPC request** with a text message like:
```
❌ Tool denied. Use tools with 'intellij-code-tools-' prefix instead.
```

## Why This Works

The retry prompt is **not** part of the permission response. It's a separate session/prompt that:
1. Arrives **after** the original prompt completes
2. Gets processed by the agent as new context
3. Allows the agent to retry with corrected tool usage

This is the same as if the user manually sent a second message saying "use the other tool".

## History

- **Original implementation** (commit `4fbf2fb`, Feb 13 2026): Created for `edit` permission denial
- **Extended** (commits `fb35e02`, `a916a7f`): Added `create`, `read`, `execute`, `runInTerminal`
- **Abuse detection added** (commit `035e54a`, Feb 18 2026): Detects run_command being used for tests/grep/find/git

## Known Issues

### Issue: Retry messages not visible in UI

**Status:** Under investigation

**Symptoms:**
- Agent sees "⚠ Tool failed: tooluse_..." in UI
- Our retry message doesn't appear in the agent's visible context
- Agent sometimes retries correctly (proving it got the message)
- Sometimes agent gives up or tries wrong approach

**Hypothesis:**
- The retry prompt is being sent and received by the agent
- But the UI doesn't display it as visible context
- Agent can "see" it internally for decision-making
- Need to verify with debug logging

**Related GitHub Issues:**
- None found yet - may be IntelliJ plugin-specific UI behavior
- Could be related to how streaming responses are displayed

### Mitigation: Debug Tab

To diagnose this issue, we're adding a debug tab that shows:
- All permission requests (approved/denied)
- Tool calls with parameters
- Retry messages sent
- Agent responses received

This will help us understand:
1. Are retry prompts actually being sent?
2. What is the agent receiving vs what UI shows?
3. Is there a timing issue with streaming responses?

## Testing the Mechanism

To test if retry prompts are working:

1. **Enable debug logging:**
   ```bash
   grep "===" ide_launch.log
   ```

2. **Trigger a denial:**
   - Ask agent to "view a file"
   - Or "edit a file"
   - Or "run tests via ./gradlew test"

3. **Check logs for sequence:**
   ```
   ACP request_permission: kind=read ...
   ACP request_permission: DENYING built-in read
   sendPrompt: built-in read denied, sending retry with MCP tool instruction
   sendPrompt: retry result: {"stopReason":"..."}
   ```

4. **Check agent behavior:**
   - Does it retry with `intellij-code-tools-` prefix?
   - Or does it give up / try wrong tool?

## Code Locations

- **Permission handling:** `CopilotAcpClient.java:823-857` (handlePermissionRequest)
- **Retry trigger:** `CopilotAcpClient.java:404-410` (sendPrompt)
- **Retry implementation:** `CopilotAcpClient.java:917-953` (sendRetryPrompt)
- **Abuse detection:** `CopilotAcpClient.java:863-909` (detectCommandAbuse)

## Future Improvements

When GitHub fixes CLI bug #556 (tool filtering):
1. Remove permission denials for `edit`, `create`, `read`
2. Use proper `availableTools` session parameter
3. Keep abuse detection for `execute`/`runInTerminal` (custom tools)
4. Remove this workaround entirely
