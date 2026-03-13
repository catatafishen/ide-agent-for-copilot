package com.github.catatafishen.ideagentforcopilot.bridge;

import com.google.gson.JsonObject;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.Closeable;
import java.util.List;
import java.util.function.Consumer;

/**
 * Abstraction over AI agent communication transports.
 *
 * <p>Implementations:
 * <ul>
 *   <li>{@link AcpClient} — JSON-RPC over stdio to a CLI subprocess (Copilot, OpenCode, etc.)</li>
 *   <li>{@link AnthropicDirectClient} — direct HTTPS to api.anthropic.com (Claude Code)</li>
 * </ul>
 */
public interface AgentClient extends Closeable {

    /**
     * Start the client (launch process or verify API connectivity).
     * Must be called before any other method.
     */
    void start() throws AcpException;

    /**
     * Create a new conversation session. Returns a session ID used for subsequent calls.
     *
     * @param cwd the working directory for the session (may be null)
     */
    @NotNull
    String createSession(@Nullable String cwd) throws AcpException;

    /**
     * Switch the model for an existing session.
     */
    void setModel(@NotNull String sessionId, @NotNull String modelId) throws AcpException;

    /**
     * Send a prompt and run the full agentic loop until completion.
     * Streaming text chunks are delivered via {@code onChunk}; structured update events
     * (tool calls, plans) are delivered via {@code onUpdate} as JSON objects matching
     * the {@code sessionUpdate} shape consumed by the UI layer.
     *
     * @param sessionId  session ID from {@link #createSession}
     * @param prompt     the user prompt text
     * @param model      model ID to use (null = use session default)
     * @param references optional file/selection context references
     * @param onChunk    receives text chunks for streaming display (nullable)
     * @param onUpdate   receives raw update JSON (tool_call, plan, etc.) (nullable)
     * @param onRequest  called each time a request is sent (nullable)
     * @return the stop reason (e.g. "end_turn", "max_tokens")
     */
    @NotNull
    String sendPrompt(@NotNull String sessionId,
                      @NotNull String prompt,
                      @Nullable String model,
                      @Nullable List<ResourceReference> references,
                      @Nullable Consumer<String> onChunk,
                      @Nullable Consumer<JsonObject> onUpdate,
                      @Nullable Runnable onRequest) throws AcpException;

    /**
     * List models available for this agent/account.
     */
    @NotNull
    List<Model> listModels() throws AcpException;

    /**
     * Cancel an in-progress prompt turn for the given session.
     */
    void cancelSession(@NotNull String sessionId);

    /**
     * Returns true if the client is healthy and ready to accept requests.
     */
    boolean isHealthy();

    /**
     * Whether file/selection resource references need their content duplicated as plain text
     * in the prompt (some agents don't inline resource content natively).
     */
    boolean requiresResourceContentDuplication();

    /**
     * Register a listener that is called when a tool requires user permission.
     */
    void setPermissionRequestListener(@Nullable Consumer<PermissionRequest> listener);

    /**
     * Notify the client that a sub-agent (Task tool call) is active or has ended.
     * While active, built-in git/file write tools are blocked.
     */
    void setSubAgentActive(boolean active);

    /**
     * Returns the authentication method info for this agent, or null if not applicable.
     */
    @Nullable
    AuthMethod getAuthMethod();

    /**
     * Returns the pricing multiplier label for the given model ID (e.g. "1x", "2x"),
     * or "1x" if not available.
     */
    @NotNull
    String getModelMultiplier(@NotNull String modelId);

    /**
     * Closes and releases all resources.
     */
    @Override
    void close();
}
