package com.github.catatafishen.agentbridge.acp.client.intercept;

import com.github.catatafishen.agentbridge.psi.PsiBridgeService;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Intercepts ACP file system and terminal requests, redirecting them to equivalent
 * MCP tools when a safe 1:1 mapping exists.
 *
 * <p><b>Why this exists:</b> ACP agents (Copilot, Junie, Kiro, OpenCode) execute their
 * built-in tools by sending {@code fs/read_text_file}, {@code fs/write_text_file}, and
 * {@code terminal/create} requests <em>back</em> to the client. Some agents ignore tool
 * exclusion lists (Copilot bug #556) or have no exclusion mechanism at all (Junie).
 * By intercepting these requests at the client, we transparently redirect them to our
 * MCP equivalents — getting editor-buffer reads/writes, undo stack, VCS sync, etc. —
 * regardless of how the agent decides to call the tool.
 *
 * <p>For commands that don't map cleanly, the request falls through to the original
 * handler so the user can still see the command run in a visible IDE terminal.
 */
public final class AcpToolInterceptor {

    private static final Logger LOG = Logger.getInstance(AcpToolInterceptor.class);
    private static final String SYNTHETIC_TERMINAL_PREFIX = "intercept_";

    // Repeated JSON property names — extracted to avoid duplicate-literal warnings.
    private static final String FIELD_CONTENT = "content";
    private static final String FIELD_COMMAND = "command";

    private final @Nullable Project project;
    private final Map<String, InterceptedTerminal> terminals = new ConcurrentHashMap<>();

    /**
     * Cached tool result + exit code for a synthesized terminal.
     */
    private record InterceptedTerminal(String output, int exitCode) {
    }

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

    // ─── terminal/create ──────────────────────────────────────────────────

    public @Nullable JsonObject tryInterceptTerminalCreate(@NotNull JsonObject params) {
        List<String> argv = argvFromParams(params);
        if (argv == null || argv.isEmpty()) return null;

        RedirectPlan plan = ShellRedirectPlanner.plan(argv);
        if (plan == null) return null;

        String raw = callMcp(plan.toolName(), plan.args());
        boolean isError = isMcpError(raw);
        String processed = isError ? raw : plan.postProcess().apply(raw);
        int exitCode = isError ? 1 : plan.exitCodeFor().applyAsInt(processed);

        String terminalId = SYNTHETIC_TERMINAL_PREFIX + UUID.randomUUID().toString().substring(0, 12);
        terminals.put(terminalId, new InterceptedTerminal(processed, exitCode));
        LOG.info("Intercepted terminal/create: " + String.join(" ", argv) + " -> " + plan.toolName()
            + " (synthetic " + terminalId + ", exit=" + exitCode + ")");

        JsonObject result = new JsonObject();
        result.addProperty("terminalId", terminalId);
        return result;
    }

    /**
     * Build the argv list for an ACP {@code terminal/create} request.
     *
     * <p>When the agent passes an explicit {@code args} array we use it as-is — the
     * arguments have already been split, so re-tokenizing through
     * {@link ShellCommandSplitter} would corrupt quoted/space-containing values.
     * We still refuse to redirect when the binary is a shell wrapper (where the args
     * would be re-interpreted by the spawned shell).
     *
     * <p>When only {@code command} is present we fall back to the splitter, which
     * also rejects shell metacharacters that would change semantics.
     *
     * @return argv-style tokens, or {@code null} when redirection is unsafe
     */
    private static @Nullable List<String> argvFromParams(@NotNull JsonObject params) {
        String command = params.has(FIELD_COMMAND) && params.get(FIELD_COMMAND).isJsonPrimitive()
            ? params.get(FIELD_COMMAND).getAsString() : null;
        if (command == null || command.isBlank()) return null;

        if (params.has("args") && params.get("args").isJsonArray()) {
            if (looksLikeShellWrapper(command)) return null;
            List<String> argv = new ArrayList<>();
            argv.add(command);
            for (JsonElement el : params.getAsJsonArray("args")) {
                if (!el.isJsonPrimitive()) return null;
                argv.add(el.getAsString());
            }
            return argv;
        }

        return ShellCommandSplitter.tokenize(command);
    }

    private static boolean looksLikeShellWrapper(@NotNull String command) {
        String base = command;
        int slash = base.lastIndexOf('/');
        if (slash >= 0 && slash + 1 < base.length()) base = base.substring(slash + 1);
        return switch (base.toLowerCase(Locale.ROOT)) {
            case "sh", "bash", "zsh", "ksh", "dash", "fish", "csh", "tcsh", "pwsh", "powershell" -> true;
            default -> false;
        };
    }

    // ─── synthetic terminal lifecycle ────────────────────────────────────

    public boolean ownsTerminal(@NotNull String terminalId) {
        return terminals.containsKey(terminalId);
    }

    public @NotNull JsonObject output(@NotNull String terminalId) {
        InterceptedTerminal t = requireTerminal(terminalId);
        JsonObject result = new JsonObject();
        result.addProperty("output", t.output());
        result.addProperty("truncated", false);
        JsonObject exitStatus = new JsonObject();
        exitStatus.addProperty("exitCode", t.exitCode());
        exitStatus.add("signal", null);
        result.add("exitStatus", exitStatus);
        return result;
    }

    public @NotNull JsonObject waitForExit(@NotNull String terminalId) {
        InterceptedTerminal t = requireTerminal(terminalId);
        JsonObject result = new JsonObject();
        result.addProperty("exitCode", t.exitCode());
        result.add("signal", null);
        return result;
    }

    public @NotNull JsonObject kill(@NotNull String terminalId) {
        requireTerminal(terminalId);
        // Synthetic terminals complete synchronously during create; nothing to kill.
        return new JsonObject();
    }

    public @NotNull JsonObject release(@NotNull String terminalId) {
        if (terminals.remove(terminalId) == null) {
            throw new IllegalArgumentException("Unknown terminal: " + terminalId);
        }
        return new JsonObject();
    }

    public void releaseAll() {
        terminals.clear();
    }

    private @NotNull InterceptedTerminal requireTerminal(@NotNull String terminalId) {
        InterceptedTerminal t = terminals.get(terminalId);
        if (t == null) {
            throw new IllegalArgumentException("Unknown terminal: " + terminalId);
        }
        return t;
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
