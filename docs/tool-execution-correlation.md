# Tool Execution Correlation Architecture

## Problem Statement

Tool execution in the plugin happens through **two separate channels**:

### Channel 1: MCP Protocol (Tool Execution)
```
Agent → tools/call (JSON-RPC) → McpProtocolHandler → PsiBridgeService → tool.execute()
                                                                            ↓
                                                                    RAW RESULT
                                                                    (diffs, file contents, etc.)
```

### Channel 2: Agent Protocol (Result Updates)
```
Agent → tool_call_update event → AcpClient/ClaudeCliClient
                                    ↓
                              STATUS + DESCRIPTION
                              (may or may not include raw result)
```

## Agent Behavior Differences

### Claude-Based Agents (ClaudeCliClient, AnthropicDirectClient)
- ✅ Execute tools themselves and return raw results in `tool_result` events
- ✅ We receive both execution and result through the same channel
- ✅ **No correlation needed** - raw result is already available

### ACP Agents (Junie, OpenCode, Copilot)
- ⚠️ Request permission, execute via MCP, but may not forward raw results
- ⚠️ **Junie** only sends natural language summaries: "Two files staged for commit"
- ⚠️ **Need correlation** to show raw tool output alongside agent's description

## Solution: ToolExecutionCorrelator

### Core Design

```java
@Service(Service.Level.PROJECT)
public class ToolExecutionCorrelator {
    // Records every MCP tool execution
    Map<String, Queue<PendingExecution>> pendingByTool;
    
    // Per-tool locks for synchronous execution
    Map<String, ReentrantLock> toolLocks;
}
```

### Workflow

**1. Tool Execution (PsiBridgeService.callTool)**
```java
// Determine if tool requires synchronization
boolean requiresSync = correlator.requiresSync(def); // FILE, GIT, EDITING, REFACTOR

// Execute with optional sync + record result
String result = correlator.executeAndRecord(toolName, arguments, def, requiresSync);
// → Acquires per-tool lock if needed
// → Executes tool
// → Records: {toolName, args, argsHash, result, timestamp}
// → Releases lock
```

**2. Agent Update (JunieAcpClient.buildToolCallUpdateEvent)**
```java
// When Junie sends COMPLETED update with natural language summary
String rawResult = correlator.consumeResult(toolName, args);
// → Tries exact match: toolName + argsHash
// → Falls back to FIFO: first unconsumed execution for this tool
// → Returns raw result if found, null otherwise

if (rawResult != null) {
    // Success: Show both raw result AND summary
    return new ToolCallUpdate(id, COMPLETED, rawResult, null, summary);
} else {
    // Fallback: Show only summary as description
    return new ToolCallUpdate(id, COMPLETED, null, null, summary);
}
```

## Synchronization Strategy

### Synchronous Tools (One at a Time Per Tool Name)
**Categories:** `FILE`, `EDITING`, `REFACTOR`, `GIT`

**Why?**
- Prevents race conditions (multiple edits to same file)
- Guarantees execution order = update order
- Simplifies matching (FIFO fallback works perfectly)
- Prevents EDT queue saturation

**Example:** If agent calls `git_status` 3 times:
```
Execution: git_status#1 → blocks → git_status#2 → blocks → git_status#3
Updates:   git_status#1 ← matches → git_status#2 ← matches → git_status#3
```

### Asynchronous Tools (Concurrent)
**Categories:** `NAVIGATION`, `CODE_QUALITY`, `TESTING`, `PROJECT`, `INFRASTRUCTURE`

**Why?**
- Read-only tools are safe to run concurrently
- Slow tools (build, test) shouldn't block other operations
- Args-based matching still works for different queries

**Example:** Multiple file reads can run in parallel:
```
Execution: read_file(A.java) ─┬─ read_file(B.java) ─┬─ read_file(C.java)
                               ↓                      ↓                   ↓
Updates:   Matches by hash ────┴──────────────────────┴───────────────────┘
```

## Matching Strategy

### Priority 1: Exact Match (toolName + argsHash)
```java
for (PendingExecution exec : queue) {
    if (exec.toolName.equals(toolName) 
        && exec.argsHash.equals(argsHash)
        && exec.timestamp >= cutoff
        && exec.consumed.compareAndSet(false, true)) {
        return exec.result;  // ✓ Perfect match
    }
}
```

**When this works:**
- Concurrent read-only tools with different arguments
- Agent forwards exact arguments
- Multiple queries running in parallel

### Priority 2: FIFO Fallback (First Unconsumed)
```java
for (PendingExecution exec : queue) {
    if (exec.timestamp >= cutoff 
        && exec.consumed.compareAndSet(false, true)) {
        return exec.result;  // ⚠ Best effort
    }
}
```

**When this works:**
- Synchronous tools (execution order = update order)
- Agent modified arguments slightly
- Arguments not available in update

### Priority 3: No Match (Graceful Degradation)
```java
return null;  // ✗ Caller uses agent's description as fallback
```

**When this happens:**
- Update arrived >30s after execution (stale)
- Execution was already consumed by another update
- Tool wasn't executed through MCP (shouldn't happen)

## Configuration

### Time Windows
```java
RETENTION_MS = 60_000;      // Keep executions for 1 minute
MATCH_WINDOW_MS = 30_000;   // Match updates within 30 seconds
```

**Why 30 seconds?**
- Typical tool execution: <1s
- Typical agent processing: <5s
- Generous buffer for slow tools/network

### Tool Categories Requiring Sync
```java
SYNC_TOOL_CATEGORIES = Set.of("FILE", "EDITING", "REFACTOR", "GIT");
```

**To add a new category to sync:**
1. Update `SYNC_TOOL_CATEGORIES` in `ToolExecutionCorrelator`
2. Tool must use a category in `ToolRegistry.Category`

## Debugging

### Enable DEBUG Logging
```
Help → Diagnostic Tools → Debug Log Settings
Add: #com.github.catatafishen.ideagentforcopilot.services.ToolExecutionCorrelator
```

### Log Output Examples

**Tool Execution:**
```
[DEBUG] Acquired sync lock for git_status (waiting threads: 0)
[DEBUG] Recorded execution: git_status (hash=a3f5e1b2, duration=45ms, queueSize=1, resultLen=234)
[DEBUG] Released sync lock for git_status
```

**Exact Match:**
```
[DEBUG] ✓ Matched execution by args hash: git_status (hash=a3f5e1b2, age=127ms)
```

**FIFO Fallback:**
```
[DEBUG] ⚠ Matched execution by FIFO fallback: read_file (age=89ms, argsMatch=no)
```

**No Match:**
```
[DEBUG] ✗ No matching execution found for git_commit (recentInWindow=0, alreadyConsumed=1)
```

## Troubleshooting

### Issue: Tool results not showing (only summaries)

**Check:**
1. Is DEBUG logging enabled?
2. Look for correlation logs in IDE log
3. Verify tool execution was recorded: `Recorded execution: <toolName>`
4. Verify match attempt: `Matched execution` or `No matching execution`

**Common causes:**
- Update arrived >30s after execution (increase `MATCH_WINDOW_MS`)
- Execution wasn't recorded (check for errors in `executeAndRecord`)
- Args hash mismatch + FIFO queue empty (check queue size in logs)

### Issue: Tools executing slowly

**Check:**
1. Is the tool in `SYNC_TOOL_CATEGORIES`?
2. Look for `Acquired sync lock` with high `waiting threads` count
3. Check if previous execution is stuck (deadlock)

**Solutions:**
- Remove from `SYNC_TOOL_CATEGORIES` if safe to run concurrently
- Investigate why tool execution is slow (blocking I/O, etc.)

### Issue: Wrong result matched to wrong update

**Symptoms:**
- git_status shows git_diff output
- File A content shown for File B

**Check:**
1. Verify args hash is stable: `computeArgsHash()` uses TreeMap for consistent ordering
2. Check if FIFO fallback is triggering: `Matched execution by FIFO fallback`
3. Verify consumption tracking: `alreadyConsumed` count in logs

**Solutions:**
- Ensure tool is in `SYNC_TOOL_CATEGORIES` if order matters
- Check that args are passed correctly from agent to update
- Verify no race condition in parallel tool calls

## Testing

### Unit Test Structure
```java
@Test
void testCorrelation() {
    // 1. Execute tool
    String result = correlator.executeAndRecord("git_status", args, def, true);
    
    // 2. Simulate agent update
    String matched = correlator.consumeResult("git_status", args);
    
    // 3. Verify
    assertEquals(result, matched);
    
    // 4. Verify consumption
    String secondMatch = correlator.consumeResult("git_status", args);
    assertNull(secondMatch); // Already consumed
}
```

### Integration Test Scenarios
1. **Sync tool ordering:** 3 sequential git_status → verify FIFO matching
2. **Concurrent tools:** 10 parallel read_file → verify hash matching
3. **Timeout:** Execute, wait 31s, update → verify no match
4. **Cleanup:** Execute 200 tools → verify old entries removed

## Performance Considerations

### Memory Usage
- **Per execution:** ~1KB (toolName, args, result, metadata)
- **Max concurrent:** 100 executions (configurable)
- **Cleanup:** Every execution triggers cleanup of entries >60s old
- **Worst case:** 100 * 1KB = 100KB per project

### CPU Usage
- **Recording:** O(1) - append to queue
- **Matching:** O(n) where n = queue size for that tool (typically 1-5)
- **Cleanup:** O(m) where m = total executions (runs periodically)

### Lock Contention
- **Per-tool locks:** Only same tool competes
- **Example:** git_status won't block read_file
- **Fairness:** ReentrantLock ensures FIFO lock acquisition

## Future Enhancements

### 1. Correlation ID Protocol Extension
```json
// MCP tools/call with correlation hint
{
  "name": "git_status",
  "arguments": {...},
  "_meta": {
    "correlationId": "junie-tool-abc123"  // Agent passes toolCallId
  }
}
```
**Benefit:** Perfect matching, no heuristics needed

### 2. Adaptive Time Windows
```java
// Learn typical execution times per tool
Map<String, Long> avgExecutionTime;
long windowMs = avgExecutionTime.get(toolName) * 10; // 10x safety factor
```
**Benefit:** Tighter windows for fast tools, generous for slow tools

### 3. Metrics Dashboard
```java
// Track correlation success rates
Map<String, CorrelationStats> stats;
// Export to IntelliJ metrics UI
```
**Benefit:** Monitor correlation health, identify problematic tools
