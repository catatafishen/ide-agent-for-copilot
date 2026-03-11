package com.github.catatafishen.ideagentforcopilot.psi;

import com.github.catatafishen.ideagentforcopilot.services.ToolPermission;
import com.github.catatafishen.ideagentforcopilot.services.ToolRegistry;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.components.ComponentManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.util.messages.Topic;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Executes MCP tool calls inside IntelliJ, providing PSI/AST-backed code intelligence.
 * Called directly by {@link com.github.catatafishen.ideagentforcopilot.services.McpProtocolHandler}
 * via Java method call — no HTTP required.
 */
@SuppressWarnings("java:S112") // generic exceptions are caught at the JSON-RPC dispatch level
@Service(Service.Level.PROJECT)
public final class PsiBridgeService implements Disposable {
    private static final Logger LOG = Logger.getInstance(PsiBridgeService.class);

    /**
     * Listener notified after each MCP tool call completes.
     */
    public interface ToolCallListener {
        void toolCalled(String toolName, long durationMs, boolean success);
    }

    /**
     * Project-level message bus topic for tool call events (fire-and-forget notifications).
     */
    public static final Topic<ToolCallListener> TOOL_CALL_TOPIC =
        Topic.create("PsiBridgeService.ToolCall", ToolCallListener.class);

    private final Project project;
    private final RunConfigurationService runConfigService;
    private final Map<String, ToolHandler> toolRegistry = new LinkedHashMap<>();
    private final FileTools fileTools;
    private final java.util.concurrent.atomic.AtomicBoolean permissionPending =
        new java.util.concurrent.atomic.AtomicBoolean(false);
    private final java.util.Set<String> sessionAllowedTools =
        java.util.concurrent.ConcurrentHashMap.newKeySet();

    public PsiBridgeService(@NotNull Project project) {
        this.project = project;
        this.fileTools = new FileTools(project);
        GitToolHandler gitToolHandler = new GitToolHandler(project, fileTools);

        // Initialize handler groups
        RefactoringTools refactoringTools = new RefactoringTools(project);
        this.runConfigService = new RunConfigurationService(project, refactoringTools::resolveClass);

        // Register all tools from handler groups
        for (AbstractToolHandler handler : List.of(
            new CodeNavigationTools(project),
            fileTools,
            new CodeQualityTools(project),
            refactoringTools,
            new SymbolEditingTools(project),
            new TestTools(project, refactoringTools),
            new ProjectTools(project),
            new GitTools(project, gitToolHandler),
            new InfrastructureTools(project),
            new TerminalTools(project),
            new EditorTools(project)
        )) {
            toolRegistry.putAll(handler.getTools());
        }

        // RunConfigurationService tools (not an AbstractToolHandler)
        toolRegistry.put("list_run_configurations", args -> runConfigService.listRunConfigurations());
        toolRegistry.put("run_configuration", runConfigService::runConfiguration);
        toolRegistry.put("create_run_configuration", runConfigService::createRunConfiguration);
        toolRegistry.put("edit_run_configuration", runConfigService::editRunConfiguration);
        toolRegistry.put("delete_run_configuration", runConfigService::deleteRunConfiguration);
    }

    @SuppressWarnings("java:S1905") // Cast needed: IDE doesn't resolve Project→ComponentManager supertype
    public static PsiBridgeService getInstance(@NotNull Project project) {
        return ((ComponentManager) project).getService(PsiBridgeService.class);
    }

    /**
     * Clears the session-scoped "Allow for session" permission cache.
     * Called when the ACP session is closed or restarted.
     */
    public void clearSessionAllowedTools() {
        sessionAllowedTools.clear();
    }

    /**
     * Runs deferred auto-format and import optimization on all files modified during the turn.
     */
    public void flushPendingAutoFormat() {
        fileTools.flushPendingAutoFormat();
    }

    /**
     * Clears file access tracking (background tints in Project View) at end of turn.
     */
    public void clearFileAccessTracking() {
        FileAccessTracker.clear(project);
    }

    public String callTool(String toolName, JsonObject arguments) {
        ToolHandler handler = toolRegistry.get(toolName);
        if (handler == null) {
            fireToolCallEvent(toolName, System.currentTimeMillis(), false);
            return "Unknown tool: " + toolName;
        }
        long startMs = System.currentTimeMillis();
        boolean success = true;

        // Subscribe to daemon events BEFORE the write to avoid the race where
        // the daemon finishes before we subscribe and we miss the event entirely.
        String filePathForHighlights = isWriteToolName(toolName) ? extractFilePath(arguments) : null;
        DaemonWaiter daemonWaiter = filePathForHighlights != null ? new DaemonWaiter(project) : null;

        try {
            String denied = checkPluginToolPermission(toolName, arguments);
            if (denied != null) {
                fireToolCallEvent(toolName, startMs, false);
                if (daemonWaiter != null) daemonWaiter.close();
                return denied;
            }

            String result = handler.handle(arguments);

            // Piggyback highlights after successful write operations
            if (isSuccessfulWrite(toolName, result) && daemonWaiter != null) {
                LOG.info("Auto-highlights: piggybacking on write to " + filePathForHighlights);
                result = appendAutoHighlights(result, filePathForHighlights, daemonWaiter);
            } else if (daemonWaiter != null) {
                daemonWaiter.close();
            }
            return result;
        } catch (com.intellij.openapi.application.ex.ApplicationUtil.CannotRunReadActionException e) {
            success = false;
            if (daemonWaiter != null) daemonWaiter.close();
            return "Error: IDE is busy, please retry. " + e.getMessage();
        } catch (Exception e) {
            LOG.warn("Tool call error: " + toolName, e);
            success = false;
            if (daemonWaiter != null) daemonWaiter.close();
            return "Error: " + e.getMessage();
        } finally {
            fireToolCallEvent(toolName, startMs, success);
        }
    }

    /**
     * Returns the set of registered tool names (built-in + dynamically registered).
     * Used by MacroToolRegistrar in the experimental plugin variant.
     */
    public java.util.Set<String> getRegisteredToolNames() {
        return java.util.Collections.unmodifiableSet(toolRegistry.keySet());
    }

    /**
     * Dynamically registers a tool at runtime. Used by MacroToolRegistrar
     * (experimental plugin variant) to add user-recorded macros as MCP tools.
     */
    public void registerTool(String id, ToolHandler handler) {
        toolRegistry.put(id, handler);
    }

    /**
     * Removes a dynamically registered tool. Returns true if the tool existed.
     */
    public boolean unregisterTool(String id) {
        return toolRegistry.remove(id) != null;
    }

    private void fireToolCallEvent(String toolName, long startTimeMs, boolean success) {
        long duration = System.currentTimeMillis() - startTimeMs;
        try {
            project.getMessageBus().syncPublisher(TOOL_CALL_TOPIC)
                .toolCalled(toolName, duration, success);
        } catch (Exception e) {
            LOG.debug("Failed to fire tool call event", e);
        }
    }

    /**
     * Checks the configured permission for a plugin tool call.
     * Returns null if the call is allowed, or an error message string if it is denied/rejected.
     * For ASK, injects a permission bubble into the chat panel and blocks until user responds.
     * Falls back to a modal dialog if the chat panel is unavailable (no JCEF, etc.).
     */
    @Nullable
    private String checkPluginToolPermission(String toolName, JsonObject arguments) {
        ToolPermission perm = resolvePluginPermission(toolName, arguments);
        if (perm == ToolPermission.ALLOW) return null;

        if (perm == ToolPermission.DENY) {
            LOG.info("PSI Bridge: DENY for tool " + toolName);
            return "Permission denied: tool '" + toolName + "' is disabled in Tool Permissions settings.";
        }

        // Session-scoped allow: if user previously chose "Allow for session", skip the prompt
        if (sessionAllowedTools.contains(toolName)) {
            LOG.info("PSI Bridge: session-allowed for " + toolName);
            return null;
        }

        // ASK: show a permission bubble in the chat panel and block until user responds
        permissionPending.set(true);
        try {
            return askUserPermission(toolName, arguments);
        } finally {
            permissionPending.set(false);
        }
    }

    @Nullable
    private String askUserPermission(String toolName, JsonObject arguments) {
        ToolRegistry.ToolEntry entry = ToolRegistry.findById(toolName);
        String displayName = entry != null ? entry.displayName : toolName;
        String argsJson = arguments.toString();
        String reqId = java.util.UUID.randomUUID().toString();

        com.github.catatafishen.ideagentforcopilot.ui.ChatConsolePanel chatPanel =
            com.github.catatafishen.ideagentforcopilot.ui.ChatConsolePanel.Companion.getInstance(project);

        com.github.catatafishen.ideagentforcopilot.bridge.PermissionResponse response;
        if (chatPanel != null) {
            java.util.concurrent.CompletableFuture<com.github.catatafishen.ideagentforcopilot.bridge.PermissionResponse> future =
                new java.util.concurrent.CompletableFuture<>();
            EdtUtil.invokeLater(() ->
                chatPanel.showPermissionRequest(reqId, displayName, argsJson, result -> {
                    future.complete(result);
                    return kotlin.Unit.INSTANCE;
                })
            );
            try {
                response = future.get(120, java.util.concurrent.TimeUnit.SECONDS);
            } catch (java.util.concurrent.TimeoutException e) {
                LOG.info("PSI Bridge: ASK timed out for " + toolName);
                return "Permission request timed out for tool '" + toolName + "'.";
            } catch (InterruptedException | java.util.concurrent.ExecutionException e) {
                Thread.currentThread().interrupt();
                return "Permission request interrupted for tool '" + toolName + "'.";
            }
        } else {
            // Fallback: modal dialog when JCEF / chat panel is unavailable
            boolean[] result = {false};
            EdtUtil.invokeAndWait(() -> {
                String message = "<html><b>Allow: " + StringUtil.escapeXmlEntities(displayName) + "</b><br><br>"
                    + buildArgSummary(arguments) + "</html>";
                int choice = Messages.showYesNoDialog(
                    project, message, "Tool Permission Request",
                    "Allow", "Deny", Messages.getQuestionIcon()
                );
                result[0] = choice == Messages.YES;
            });
            response = result[0]
                ? com.github.catatafishen.ideagentforcopilot.bridge.PermissionResponse.ALLOW_ONCE
                : com.github.catatafishen.ideagentforcopilot.bridge.PermissionResponse.DENY;
        }

        return switch (response) {
            case ALLOW_SESSION -> {
                sessionAllowedTools.add(toolName);
                LOG.info("PSI Bridge: ASK approved for session for " + toolName);
                yield null;
            }
            case ALLOW_ONCE -> {
                LOG.info("PSI Bridge: ASK approved (once) for " + toolName);
                yield null;
            }
            default -> {
                LOG.info("PSI Bridge: ASK denied by user for " + toolName);
                yield "Permission denied by user for tool '" + toolName + "'.";
            }
        };
    }

    private ToolPermission resolvePluginPermission(String toolName, JsonObject arguments) {
        ToolLayerSettings settings = ToolLayerSettings.getInstance(project);
        ToolRegistry.ToolEntry entry = ToolRegistry.findById(toolName);
        if (entry != null && entry.supportsPathSubPermissions) {
            String path = extractPathArg(arguments);
            if (path != null && !path.isEmpty()) {
                boolean inside = isInsideProject(path);
                return settings.resolveEffectivePermission(toolName, inside);
            }
        }
        return settings.getToolPermission(toolName);
    }

    @Nullable
    private static String extractPathArg(JsonObject args) {
        for (String key : new String[]{"path", "file", "file1", "file2"}) {
            if (args.has(key) && args.get(key).isJsonPrimitive()) {
                return args.get(key).getAsString();
            }
        }
        return null;
    }

    private boolean isInsideProject(String path) {
        String basePath = project.getBasePath();
        if (basePath == null) return true;
        java.io.File f = new java.io.File(path);
        if (!f.isAbsolute()) return true;
        try {
            return f.getCanonicalPath().startsWith(new java.io.File(basePath).getCanonicalPath());
        } catch (java.io.IOException e) {
            return true;
        }
    }

    private static String buildArgSummary(JsonObject args) {
        if (args.isEmpty()) return "No arguments.";
        StringBuilder sb = new StringBuilder("<table>");
        int count = 0;
        for (Map.Entry<String, JsonElement> e : args.entrySet()) {
            if (count++ >= 5) {
                sb.append("<tr><td colspan='2'>…</td></tr>");
                break;
            }
            String val = e.getValue().isJsonPrimitive()
                ? e.getValue().getAsString() : e.getValue().toString();
            if (val.length() > 100) val = val.substring(0, 97) + "…";
            sb.append("<tr><td><b>").append(StringUtil.escapeXmlEntities(e.getKey()))
                .append(":</b>&nbsp;</td><td>").append(StringUtil.escapeXmlEntities(val))
                .append("</td></tr>");
        }
        sb.append("</table>");
        return sb.toString();
    }

    /**
     * Returns true if the tool name is a write operation that should get auto-highlights.
     */
    private static boolean isWriteToolName(String toolName) {
        return switch (toolName) {
            case "write_file", "intellij_write_file", "edit_text",
                 "create_file", "replace_symbol_body",
                 "insert_before_symbol", "insert_after_symbol" -> true;
            default -> false;
        };
    }

    private static boolean isSuccessfulWrite(String toolName, String result) {
        return switch (toolName) {
            case "write_file", "intellij_write_file", "edit_text" ->
                result.startsWith("Edited:") || result.startsWith("Written:");
            case "create_file" -> result.startsWith("\u2713 Created file:");
            case "replace_symbol_body" -> result.startsWith("Replaced lines ");
            case "insert_before_symbol" -> result.startsWith("Inserted ") && result.contains(" before ");
            case "insert_after_symbol" -> result.startsWith("Inserted ") && result.contains(" after ");
            default -> false;
        };
    }

    @Nullable
    private static String extractFilePath(JsonObject arguments) {
        if (arguments.has("path")) return arguments.get("path").getAsString();
        if (arguments.has("file")) return arguments.get("file").getAsString();
        return null;
    }

    /**
     * Auto-run get_highlights on the written file and append results to the write response.
     *
     * <p>The daemon only analyzes files that are open in an editor. Two cases:</p>
     * <ul>
     *   <li><b>File already open:</b> the write triggers an automatic daemon re-pass on that
     *       document. The {@link DaemonWaiter} (subscribed before the write) catches it.</li>
     *   <li><b>File not open</b> (new file, or edit to a closed file): the pre-write waiter may
     *       have already fired on an unrelated daemon pass for other open files. We close it,
     *       subscribe a fresh waiter, then silently open the file in the editor (no focus steal)
     *       to trigger a daemon pass that actually includes the target file.</li>
     * </ul>
     */
    private String appendAutoHighlights(String writeResult, String path, DaemonWaiter preWriteWaiter) {
        DaemonWaiter activeWaiter = preWriteWaiter;
        try {
            com.intellij.openapi.vfs.VirtualFile vf =
                ToolUtils.resolveVirtualFile(project, path);
            boolean alreadyOpen = vf != null
                && com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project).isFileOpen(vf);

            if (!alreadyOpen) {
                // Pre-write waiter may have fired on other open files, not on this one.
                // Subscribe fresh BEFORE opening so we can't miss the new daemon pass.
                preWriteWaiter.close();
                activeWaiter = new DaemonWaiter(project);
                openFileSilently(vf, path);
            }
            activeWaiter.await();

            ToolHandler highlightHandler = toolRegistry.get("get_highlights");
            if (highlightHandler == null) return writeResult;

            JsonObject highlightArgs = new JsonObject();
            highlightArgs.addProperty("path", path);
            highlightArgs.addProperty("include_unindexed", true);
            String highlights = highlightHandler.handle(highlightArgs);
            LOG.info("Auto-highlights: appended " + highlights.split("\n").length + " lines for " + path);

            return writeResult + "\n\n--- Highlights (auto) ---\n" + highlights;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return writeResult;
        } catch (Exception e) {
            LOG.info("Auto-highlights after write failed: " + e.getMessage());
            return writeResult;
        } finally {
            activeWaiter.close();
        }
    }

    /**
     * Opens {@code vf} in the editor without stealing focus, so the daemon will include it
     * in its next pass. Blocks until the EDT open call completes (max 5s).
     * Falls back to path-based resolution if {@code vf} is null.
     */
    private void openFileSilently(@Nullable com.intellij.openapi.vfs.VirtualFile vf, String path) {
        CompletableFuture<Void> opened = new CompletableFuture<>();
        com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater(() -> {
            try {
                com.intellij.openapi.vfs.VirtualFile target = vf != null
                    ? vf : ToolUtils.resolveVirtualFile(project, path);
                if (target != null) {
                    com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project)
                        .openFile(target, false);
                }
            } finally {
                opened.complete(null);
            }
        });
        try {
            opened.get(5, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception e) {
            LOG.info("openFileSilently timed out or failed for " + path + ": " + e.getMessage());
        }
    }

    /**
     * Subscribes to {@link com.intellij.codeInsight.daemon.DaemonCodeAnalyzer#DAEMON_EVENT_TOPIC}
     * immediately on construction so no daemon pass can be missed.
     * Call {@link #await()} to block until the daemon finishes, then {@link #close()} to disconnect.
     */
    private final class DaemonWaiter implements AutoCloseable {
        private final java.util.concurrent.CountDownLatch latch =
            new java.util.concurrent.CountDownLatch(1);
        private final com.intellij.util.messages.MessageBusConnection connection;

        DaemonWaiter(Project proj) {
            connection = proj.getMessageBus().connect();
            connection.subscribe(
                com.intellij.codeInsight.daemon.DaemonCodeAnalyzer.DAEMON_EVENT_TOPIC,
                new com.intellij.codeInsight.daemon.DaemonCodeAnalyzer.DaemonListener() {
                    @Override
                    public void daemonFinished() {
                        latch.countDown();
                    }
                }
            );
        }

        void await() throws InterruptedException {
            if (latch.await(5, java.util.concurrent.TimeUnit.SECONDS)) {
                LOG.info("Auto-highlights: daemon pass completed (event-driven)");
            } else {
                LOG.info("Auto-highlights: daemon wait timed out (5s)");
            }
        }

        @Override
        public void close() {
            connection.disconnect();
        }
    }

    @Override
    public void dispose() {
        // Nothing to dispose — tool handlers are stateless, lifecycle managed by IntelliJ
    }
}
