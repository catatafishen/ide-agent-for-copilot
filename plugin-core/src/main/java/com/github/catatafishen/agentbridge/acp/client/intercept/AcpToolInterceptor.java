package com.github.catatafishen.agentbridge.acp.client.intercept;

import com.github.catatafishen.agentbridge.psi.PsiBridgeService;
import com.google.gson.JsonObject;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Intercepts ACP file system requests, redirecting them to equivalent MCP tools
 * when a safe 1:1 mapping exists.
 *
 * <p><b>Why this exists:</b> ACP agents (Copilot, Junie, Kiro, OpenCode) execute
 * their built-in tools by sending {@code fs/read_text_file} and
 * {@code fs/write_text_file} requests <em>back</em> to the client. Some agents
 * ignore tool exclusion lists (Copilot bug #556) or have no exclusion mechanism at
 * all (Junie). By intercepting these requests at the client, we transparently
 * redirect them to our MCP equivalents — getting editor-buffer reads/writes, undo
 * stack, VCS sync, etc. — regardless of how the agent decides to call the tool.
 *
 * <p><b>Shell commands are NOT intercepted here.</b> Empirically, Copilot CLI
 * spawns a long-lived bash via node-pty and feeds commands over stdin — those
 * commands never round-trip through ACP {@code terminal/create}. They are
 * intercepted at the PATH-shim layer instead (see
 * {@code com.github.catatafishen.agentbridge.shim}). Other agents that DO use
 * ACP {@code terminal/create} directly are still served by
 * {@link com.github.catatafishen.agentbridge.acp.client.AcpTerminalHandler} via
 * a visible IDE terminal.
 */
public final class AcpToolInterceptor {

    private static final Logger LOG = Logger.getInstance(AcpToolInterceptor.class);

    private static final String FIELD_CONTENT = "content";

    private final @Nullable Project project;

    public AcpToolInterceptor(@Nullable Project project) {
        this.project = project;
    }

    // ─── fs/read_text_file ────────────────────────────────────────────────

    /**
     * Tries to intercept {@code fs/read_text_file} by routing it through the
     * {@code read_file} MCP tool. The two are 1:1 in semantics, so this gives
     * the agent editor-buffer reads (with unsaved changes) and proper line-range
     * support for free.
     *
     * @return the synthesized response, or {@code null} when the MCP tool failed
     * and the caller should fall back to the original handler
     */
    public @Nullable JsonObject interceptRead(@NotNull JsonObject params) {
        JsonObject mcpArgs = new JsonObject();
        if (params.has("path")) mcpArgs.add("path", params.get("path"));
        if (params.has("line")) mcpArgs.addProperty("start_line", params.get("line").getAsInt());
        if (params.has("limit")) {
            int start = params.has("line") ? params.get("line").getAsInt() : 1;
            int end = start + params.get("limit").getAsInt() - 1;
            mcpArgs.addProperty("end_line", end);
        }

        String result = callMcp("read_file", mcpArgs);
        if (isMcpError(result)) {
            LOG.warn("read_file MCP redirect failed, falling back to direct VFS read: " + result);
            return null;
        }

        JsonObject response = new JsonObject();
        response.addProperty(FIELD_CONTENT, result);
        return response;
    }

    // ─── fs/write_text_file ───────────────────────────────────────────────

    /**
     * Tries to intercept {@code fs/write_text_file} by routing it through the
     * {@code write_file} MCP tool. This adds undo support, deferred auto-format,
     * and VFS notifications that direct file writes bypass.
     *
     * @return an empty response on success (ACP returns null but the dispatch layer
     * treats an empty {@link JsonObject} the same), or {@code null} when the
     * MCP tool failed and the caller should fall back to the original handler
     */
    public @Nullable JsonObject interceptWrite(@NotNull JsonObject params) {
        JsonObject mcpArgs = new JsonObject();
        if (params.has("path")) mcpArgs.add("path", params.get("path"));
        if (params.has(FIELD_CONTENT)) mcpArgs.add(FIELD_CONTENT, params.get(FIELD_CONTENT));

        String result = callMcp("write_file", mcpArgs);
        if (isMcpError(result)) {
            LOG.warn("write_file MCP redirect failed, falling back to direct VFS write: " + result);
            return null;
        }
        return new JsonObject();
    }

    // ─── MCP plumbing ────────────────────────────────────────────────────

    private @NotNull String callMcp(@NotNull String toolName, @NotNull JsonObject args) {
        if (project == null) {
            return "Error: AcpToolInterceptor has no Project — MCP redirection unavailable";
        }
        try {
            String result = PsiBridgeService.getInstance(project).callTool(toolName, args);
            return result != null ? result : "";
        } catch (Exception e) {
            LOG.warn("MCP tool '" + toolName + "' threw during ACP interception", e);
            return "Error: " + e.getMessage();
        }
    }

    /**
     * Returns true when an MCP tool result represents a failure. MCP tools signal errors
     * by prefixing the result with {@code "Error"} (see {@code McpProtocolHandler}).
     */
    static boolean isMcpError(@Nullable String result) {
        return result != null && result.startsWith("Error");
    }
}
