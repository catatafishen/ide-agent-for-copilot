package com.github.catatafishen.ideagentforcopilot.services;

import com.google.gson.JsonObject;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Correlates MCP tool executions with ACP tool call notifications.
 *
 * <h2>Architecture</h2>
 * Tool execution flows through two independent channels:
 * <ol>
 *   <li><b>ACP channel:</b> Agent sends {@code tool_call} notification (with {@code toolCallId})
 *       before executing the tool, then {@code tool_call_update} when done.</li>
 *   <li><b>MCP channel:</b> Agent calls {@code POST /mcp tools/call} with an optional
 *       {@code _meta.progressToken} that matches the ACP {@code toolCallId}.</li>
 * </ol>
 *
 * <h2>Primary correlation (toolCallId = progressToken)</h2>
 * When an agent passes {@code _meta.progressToken} in the MCP request and it equals the ACP
 * {@code toolCallId}, we get a direct match. The MCP result is stored by toolCallId and a
 * UI callback is fired to transition the chip from "pending" to "running".
 *
 * <h2>Fallback correlation (args hash / FIFO)</h2>
 * If no progressToken is provided or it doesn't match, fall back to:
 * <ol>
 *   <li>Exact match on tool name + args hash</li>
 *   <li>FIFO: first unconsumed execution for this tool within 30s</li>
 * </ol>
 *
 * <h2>Visual chip states</h2>
 * <ul>
 *   <li><b>pending</b>: ACP tool_call received, MCP not yet handled (dotted border)</li>
 *   <li><b>running</b>: MCP has handled the tool (solid border, spinner)</li>
 *   <li><b>complete/failed</b>: Final state</li>
 *   <li><b>unverified</b>: ACP tool_call_update received but MCP never handled (dotted + ⚠)</li>
 * </ul>
 *
 * <h2>Orphan MCP calls</h2>
 * If an MCP tool call has no matching ACP registration, another client is connected to our MCP
 * server. These are tracked and shown as "?" chips at end of turn.
 */
@Service(Service.Level.PROJECT)
public final class ToolExecutionCorrelator {
    private static final Logger LOG = Logger.getInstance(ToolExecutionCorrelator.class);

    private static final Set<String> SYNC_TOOL_CATEGORIES = Set.of(
        "FILE", "EDITING", "REFACTOR", "GIT"
    );
    private static final long RETENTION_MS = 60_000;
    private static final long MATCH_WINDOW_MS = 30_000;

    // ── Primary: by toolCallId (ACP ↔ MCP progressToken) ─────────────────────

    /** IDs registered by ACP (tool_call notification received). */
    private final Set<String> acpRegisteredIds = ConcurrentHashMap.newKeySet();

    /** MCP results keyed by toolCallId (set when MCP matches via progressToken). */
    private final Map<String, String> resultsByToolCallId = new ConcurrentHashMap<>();

    /** Callbacks fired on EDT when MCP handles a registered ACP tool call. */
    private final Map<String, Runnable> mcpHandledCallbacks = new ConcurrentHashMap<>();

    /** IDs handled by MCP (progressToken matched an ACP registration). */
    private final Set<String> mcpHandledIds = ConcurrentHashMap.newKeySet();

    /** MCP tool names with no matching ACP registration (orphan calls from another client). */
    private final ConcurrentLinkedQueue<String> orphanMcpCalls = new ConcurrentLinkedQueue<>();

    // ── Fallback: by tool name (args hash / FIFO) ────────────────────────────

    record PendingExecution(
        String toolName,
        JsonObject args,
        String argsHash,
        String result,
        long timestamp,
        AtomicBoolean consumed
    ) {}

    private final Map<String, ConcurrentLinkedQueue<PendingExecution>> pendingByTool =
        new ConcurrentHashMap<>();
    private final Map<String, ReentrantLock> toolLocks = new ConcurrentHashMap<>();

    @SuppressWarnings("java:S1905")
    public static ToolExecutionCorrelator getInstance(@NotNull Project project) {
        return ((com.intellij.openapi.components.ComponentManager) project)
            .getService(ToolExecutionCorrelator.class);
    }

    // ── ACP registration ─────────────────────────────────────────────────────

    /**
     * Register an ACP tool call. Called when ACP sends a {@code tool_call} notification.
     * Must be called before the MCP request arrives for primary correlation to work.
     */
    public void registerAcpToolCall(@NotNull String toolCallId, @NotNull String toolName) {
        acpRegisteredIds.add(toolCallId);
        // If MCP already handled it before ACP registered (race condition), fire any stored callback
        if (mcpHandledIds.contains(toolCallId)) {
            Runnable cb = mcpHandledCallbacks.remove(toolCallId);
            if (cb != null) ApplicationManager.getApplication().invokeLater(cb);
        }
        LOG.debug("ACP registered: " + toolCallId + " (" + toolName + ")");
    }

    /**
     * Register a UI callback to be fired when MCP handles this tool call.
     * If MCP already handled it (race condition), the callback fires immediately.
     */
    public void registerMcpHandledCallback(@NotNull String toolCallId,
                                            @NotNull Runnable onMcpHandled) {
        if (mcpHandledIds.contains(toolCallId)) {
            ApplicationManager.getApplication().invokeLater(onMcpHandled);
            return;
        }
        mcpHandledCallbacks.put(toolCallId, onMcpHandled);
    }

    /** Returns true if ACP registered this toolCallId. */
    public boolean isAcpRegistered(@NotNull String toolCallId) {
        return acpRegisteredIds.contains(toolCallId);
    }

    /** Returns true if MCP handled this toolCallId via progressToken match. */
    public boolean wasHandledByMcp(@NotNull String toolCallId) {
        return mcpHandledIds.contains(toolCallId);
    }

    // ── MCP execution ─────────────────────────────────────────────────────────

    /**
     * Execute a tool with optional synchronization, record the result, and correlate.
     *
     * @param progressToken Optional MCP {@code _meta.progressToken} — if it equals an ACP
     *                      {@code toolCallId}, the result is stored and the UI callback fires.
     */
    public String executeAndRecord(
        @NotNull String toolName,
        @NotNull JsonObject args,
        @NotNull ToolDefinition def,
        boolean requiresSync,
        @Nullable String progressToken
    ) throws Exception {
        String argsHash = computeArgsHash(args);
        ReentrantLock lock = requiresSync
            ? toolLocks.computeIfAbsent(toolName, k -> new ReentrantLock()) : null;

        if (lock != null) {
            lock.lock();
            LOG.debug("Acquired sync lock for " + toolName);
        }

        try {
            long startMs = System.currentTimeMillis();
            String result = def.execute(args);
            long durationMs = System.currentTimeMillis() - startMs;

            if (LOG.isDebugEnabled()) {
                LOG.debug(String.format(
                    "MCP executed: %s (progressToken=%s, hash=%s, duration=%dms, resultLen=%d)",
                    toolName, progressToken != null ? progressToken : "none",
                    argsHash.substring(0, Math.min(8, argsHash.length())),
                    durationMs, result.length()));
            }

            // Primary: correlate by progressToken == toolCallId
            if (progressToken != null) {
                resultsByToolCallId.put(progressToken, result);
                if (acpRegisteredIds.contains(progressToken)) {
                    mcpHandledIds.add(progressToken);
                    Runnable callback = mcpHandledCallbacks.remove(progressToken);
                    if (callback != null) {
                        ApplicationManager.getApplication().invokeLater(callback);
                    }
                    LOG.debug("✓ Primary match: progressToken=" + progressToken + " (" + toolName + ")");
                } else {
                    LOG.debug("⚡ progressToken not yet ACP-registered: " + progressToken);
                }
            } else if (!acpRegisteredIds.isEmpty()) {
                // No progressToken but we have active ACP registrations — track as orphan
                orphanMcpCalls.offer(toolName);
                LOG.debug("? Orphan MCP call (no progressToken in active ACP session): " + toolName);
            }

            // Fallback queue: record for args-hash / FIFO matching
            ConcurrentLinkedQueue<PendingExecution> queue = pendingByTool
                .computeIfAbsent(toolName, k -> new ConcurrentLinkedQueue<>());
            queue.offer(new PendingExecution(toolName, args, argsHash, result,
                System.currentTimeMillis(), new AtomicBoolean(false)));

            cleanupOldExecutions();
            return result;
        } finally {
            if (lock != null) {
                lock.unlock();
                LOG.debug("Released sync lock for " + toolName);
            }
        }
    }

    /** Backward-compatible overload (no progressToken). */
    public String executeAndRecord(
        @NotNull String toolName,
        @NotNull JsonObject args,
        @NotNull ToolDefinition def,
        boolean requiresSync
    ) throws Exception {
        return executeAndRecord(toolName, args, def, requiresSync, null);
    }

    // ── Result consumption ────────────────────────────────────────────────────

    /**
     * Consume the MCP result for a specific ACP toolCallId (primary correlation).
     * Returns null if MCP never handled this tool call (or handled it via fallback).
     */
    @Nullable
    public String consumeResultByToolCallId(@NotNull String toolCallId) {
        return resultsByToolCallId.remove(toolCallId);
    }

    /**
     * Find and consume the matching execution result by tool name + args hash / FIFO (fallback).
     */
    @Nullable
    public String consumeResult(@NotNull String toolName, @Nullable JsonObject args) {
        ConcurrentLinkedQueue<PendingExecution> queue = pendingByTool.get(toolName);
        if (queue == null || queue.isEmpty()) {
            LOG.debug("No pending executions for " + toolName);
            return null;
        }

        String argsHash = args != null ? computeArgsHash(args) : null;
        long cutoffTimestamp = System.currentTimeMillis() - MATCH_WINDOW_MS;

        if (argsHash != null) {
            for (PendingExecution exec : queue) {
                if (exec.timestamp >= cutoffTimestamp
                    && exec.argsHash.equals(argsHash)
                    && exec.consumed.compareAndSet(false, true)) {
                    LOG.debug("✓ Matched by args hash: " + toolName);
                    return exec.result;
                }
            }
        }

        for (PendingExecution exec : queue) {
            if (exec.timestamp >= cutoffTimestamp && exec.consumed.compareAndSet(false, true)) {
                LOG.debug("⚠ Matched by FIFO: " + toolName);
                return exec.result;
            }
        }

        LOG.debug("✗ No fallback match for " + toolName);
        return null;
    }

    // ── Orphan tracking ───────────────────────────────────────────────────────

    /**
     * Drain and return orphan MCP tool names (calls with no ACP counterpart).
     * Call at end of turn to show "?" chips for external MCP clients.
     */
    @NotNull
    public List<String> drainOrphanMcpCalls() {
        List<String> orphans = new ArrayList<>();
        String call;
        while ((call = orphanMcpCalls.poll()) != null) {
            orphans.add(call);
        }
        return orphans;
    }

    // ── Utility ───────────────────────────────────────────────────────────────

    public boolean requiresSync(@NotNull ToolDefinition def) {
        return SYNC_TOOL_CATEGORIES.contains(def.category().name());
    }

    /** Clear all state. Called on session reset. */
    public void clear() {
        acpRegisteredIds.clear();
        resultsByToolCallId.clear();
        mcpHandledCallbacks.clear();
        mcpHandledIds.clear();
        orphanMcpCalls.clear();
        int total = pendingByTool.values().stream().mapToInt(ConcurrentLinkedQueue::size).sum();
        pendingByTool.clear();
        if (total > 0) LOG.debug("Cleared " + total + " pending executions");
    }

    @NotNull
    private String computeArgsHash(@NotNull JsonObject args) {
        try {
            Map<String, Object> sorted = new TreeMap<>();
            for (String key : args.keySet()) {
                sorted.put(key, args.get(key).toString());
            }
            return Integer.toHexString(sorted.toString().hashCode());
        } catch (Exception e) {
            LOG.warn("Failed to compute args hash", e);
            return "00000000";
        }
    }

    private void cleanupOldExecutions() {
        long cutoffTimestamp = System.currentTimeMillis() - RETENTION_MS;
        for (var entry : pendingByTool.entrySet()) {
            entry.getValue().removeIf(exec -> exec.timestamp < cutoffTimestamp);
        }
    }
}
