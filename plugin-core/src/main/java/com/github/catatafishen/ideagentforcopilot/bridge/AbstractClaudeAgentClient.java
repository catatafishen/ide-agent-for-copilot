package com.github.catatafishen.ideagentforcopilot.bridge;

import com.google.gson.JsonObject;
import com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Shared base for Claude-based {@link AgentClient} implementations.
 *
 * <p>Holds constants, session-lifecycle maps, and utility methods common to
 * {@link AnthropicDirectClient} (direct HTTPS) and {@link ClaudeCliClient}
 * (subprocess via {@code claude} CLI).
 */
abstract class AbstractClaudeAgentClient implements AgentClient {

    private static final Logger LOG = Logger.getInstance(AbstractClaudeAgentClient.class);

    // ── Claude model defaults ────────────────────────────────────────────────

    protected static final String DEFAULT_MODEL = "claude-sonnet-4-6";

    // ── JSON field names ─────────────────────────────────────────────────────

    protected static final String FIELD_TYPE = "type";
    protected static final String FIELD_CONTENT = "content";
    protected static final String FIELD_INPUT = "input";

    // ── sessionUpdate event protocol ─────────────────────────────────────────

    protected static final String FIELD_SESSION_UPDATE = "sessionUpdate";
    protected static final String SESSION_UPDATE_TOOL_CALL = "tool_call";
    protected static final String SESSION_UPDATE_TOOL_CALL_UPDATE = "tool_call_update";
    protected static final String SESSION_UPDATE_THOUGHT = "agent_thought_chunk";
    protected static final String STATUS_COMPLETED = "completed";
    protected static final String STATUS_FAILED = "failed";

    // ── Session state ────────────────────────────────────────────────────────

    /**
     * Per-session model override.
     */
    protected final Map<String, String> sessionModels = new ConcurrentHashMap<>();

    /**
     * Per-session cancellation flag.
     */
    protected final Map<String, AtomicBoolean> sessionCancelled = new ConcurrentHashMap<>();

    /**
     * True after {@link #start()} completes successfully.
     */
    protected volatile boolean started = false;

    // ── AgentClient — shared concrete implementations ────────────────────────

    @Override
    public void setModel(@NotNull String sessionId, @NotNull String modelId) {
        sessionModels.put(sessionId, modelId);
        LOG.info("Model set to " + modelId + " for session " + sessionId);
    }

    @Override
    public boolean requiresResourceContentDuplication() {
        return false;
    }

    @Override
    public void setPermissionRequestListener(@Nullable Consumer<PermissionRequest> listener) {
        // Claude-based clients manage permissions outside the plugin.
    }

    @Override
    public void setSubAgentActive(boolean active) {
        // Not applicable — Claude manages its own sub-agent lifecycle.
    }

    @Override
    @NotNull
    public String getModelMultiplier(@NotNull String modelId) {
        return "1x";
    }

    @Override
    @Nullable
    public AuthMethod getAuthMethod() {
        return null;
    }

    // ── Shared utilities ─────────────────────────────────────────────────────

    /**
     * Resolves the effective model for a session, preferring the per-call {@code model}
     * parameter, then the session-level override, then {@link #DEFAULT_MODEL}.
     */
    @NotNull
    protected String resolveModel(@NotNull String sessionId, @Nullable String model) {
        if (model != null && !model.isEmpty()) return model;
        String stored = sessionModels.get(sessionId);
        return (stored != null && !stored.isEmpty()) ? stored : DEFAULT_MODEL;
    }

    /**
     * Throws if {@link #start()} has not been called yet.
     */
    protected void ensureStarted() throws AcpException {
        if (!started) throw new AcpException(getClass().getSimpleName() + " not started", null, false);
    }

    /**
     * Emits a {@code tool_call} sessionUpdate event to the UI layer.
     *
     * @param toolUseId unique tool-call ID
     * @param toolName  tool name (displayed in UI)
     * @param input     tool input arguments
     * @param onUpdate  update consumer (no-op if null)
     */
    protected void emitToolCallStart(@NotNull String toolUseId, @NotNull String toolName,
                                     @NotNull JsonObject input,
                                     @Nullable Consumer<JsonObject> onUpdate) {
        if (onUpdate == null) return;
        JsonObject update = new JsonObject();
        update.addProperty(FIELD_SESSION_UPDATE, SESSION_UPDATE_TOOL_CALL);
        update.addProperty("toolCallId", toolUseId);
        update.addProperty("title", toolName);
        update.addProperty("status", "in_progress");
        update.addProperty("kind", "other");
        update.add(FIELD_INPUT, input);
        onUpdate.accept(update);
    }

    /**
     * Emits a {@code tool_call_update} sessionUpdate event signalling tool completion.
     *
     * @param toolUseId unique tool-call ID (must match the corresponding {@link #emitToolCallStart})
     * @param result    result text (may be null)
     * @param success   whether the tool succeeded
     * @param onUpdate  update consumer (no-op if null)
     */
    protected void emitToolCallEnd(@NotNull String toolUseId, @Nullable String result,
                                   boolean success, @Nullable Consumer<JsonObject> onUpdate) {
        if (onUpdate == null) return;
        JsonObject update = new JsonObject();
        update.addProperty(FIELD_SESSION_UPDATE, SESSION_UPDATE_TOOL_CALL_UPDATE);
        update.addProperty("toolCallId", toolUseId);
        update.addProperty("status", success ? STATUS_COMPLETED : STATUS_FAILED);
        if (result != null) update.addProperty("result", result);
        onUpdate.accept(update);
    }

    /**
     * Emits an {@code agent_thought_chunk} sessionUpdate event carrying a thinking text chunk.
     *
     * @param text     thinking text to surface in the UI
     * @param onUpdate update consumer (no-op if null)
     */
    protected void emitThought(@NotNull String text, @Nullable Consumer<JsonObject> onUpdate) {
        if (onUpdate == null || text.isEmpty()) return;
        JsonObject update = new JsonObject();
        update.addProperty(FIELD_SESSION_UPDATE, SESSION_UPDATE_THOUGHT);
        JsonObject content = new JsonObject();
        content.addProperty("text", text);
        update.add(FIELD_CONTENT, content);
        onUpdate.accept(update);
    }
}
