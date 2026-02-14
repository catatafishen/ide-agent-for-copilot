package com.github.copilot.intellij.bridge;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Client for GitHub Copilot CLI via the Agent Client Protocol (ACP).
 * Spawns "copilot --acp --stdio" and communicates via JSON-RPC 2.0 over stdin/stdout.
 */
public class CopilotAcpClient implements Closeable {
    private static final Logger LOG = Logger.getInstance(CopilotAcpClient.class);
    private static final long REQUEST_TIMEOUT_SECONDS = 30;

    /**
     * Permission kinds that are denied so the agent uses IntelliJ MCP tools instead.
     */
    private static final Set<String> DENIED_PERMISSION_KINDS = Set.of("edit", "create", "execute", "runInTerminal");

    private final Gson gson = new Gson();
    private final AtomicLong requestIdCounter = new AtomicLong(1);
    private final ConcurrentHashMap<Long, CompletableFuture<JsonObject>> pendingRequests = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<Consumer<JsonObject>> notificationListeners = new CopyOnWriteArrayList<>();

    private final Object writerLock = new Object();
    private Process process;
    private BufferedWriter writer;
    private Thread readerThread;
    private volatile boolean closed = false;

    // State from initialize
    private JsonArray authMethods;
    private boolean initialized = false;

    // Session state
    private String currentSessionId;
    private List<Model> availableModels;

    // Flag: set when a built-in permission (edit/create/runInTerminal) is denied during a prompt turn
    private volatile boolean builtInActionDeniedDuringTurn = false;
    private volatile String lastDeniedKind = "";

    /**
     * Start the copilot ACP process and perform the initialize handshake.
     */
    public synchronized void start() throws CopilotException {
        // Clean up previous process if it died
        if (process != null) {
            LOG.info("Restarting ACP client (previous process died)");
            closed = false; // Reset closed flag for restart
            try {
                if (writer != null) writer.close();
            } catch (IOException ignored) {
            }
            if (process.isAlive()) process.destroyForcibly();
            pendingRequests.clear();
            availableModels = null;
            currentSessionId = null;
        }

        try {
            String copilotPath = findCopilotCli();
            LOG.info("Starting Copilot ACP: " + copilotPath);

            ProcessBuilder pb = buildAcpCommand(copilotPath);
            pb.redirectErrorStream(false);
            process = pb.start();

            writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream()));

            // Start reader thread for responses and notifications
            readerThread = new Thread(this::readLoop, "copilot-acp-reader");
            readerThread.setDaemon(true);
            readerThread.start();

            // Initialize handshake
            doInitialize();

        } catch (IOException e) {
            throw new CopilotException("Failed to start Copilot ACP process", e);
        }
    }

    /**
     * Build the ProcessBuilder command with ACP flags and optional MCP server config.
     */
    private ProcessBuilder buildAcpCommand(String copilotPath) {
        java.util.List<String> cmd = new java.util.ArrayList<>();
        cmd.add(copilotPath);
        cmd.add("--acp");
        cmd.add("--stdio");

        String mcpJarPath = findMcpServerJar();
        if (mcpJarPath != null) {
            String javaExe = System.getProperty("os.name", "").toLowerCase().contains("win") ? "java.exe" : "java";
            String javaPath = System.getProperty("java.home") + File.separator + "bin" + File.separator + javaExe;
            if (new File(javaPath).exists()) {
                try {
                    JsonObject mcpConfig = new JsonObject();
                    JsonObject servers = new JsonObject();
                    JsonObject codeTools = new JsonObject();
                    codeTools.addProperty("command", javaPath);
                    JsonArray args = new JsonArray();
                    args.add("-jar");
                    args.add(mcpJarPath);
                    args.add(System.getProperty("user.home"));
                    codeTools.add("args", args);
                    codeTools.add("env", new JsonObject());
                    servers.add("intellij-code-tools", codeTools);
                    mcpConfig.add("mcpServers", servers);

                    // Write config to temp file — avoids Windows command-line JSON escaping issues
                    File mcpConfigFile = File.createTempFile("copilot-mcp-", ".json");
                    mcpConfigFile.deleteOnExit();
                    try (java.io.FileWriter fw = new java.io.FileWriter(mcpConfigFile)) {
                        fw.write(gson.toJson(mcpConfig));
                    }
                    cmd.add("--additional-mcp-config");
                    cmd.add("@" + mcpConfigFile.getAbsolutePath());
                    LOG.info("MCP code-tools configured via " + mcpConfigFile.getAbsolutePath());
                } catch (IOException e) {
                    LOG.warn("Failed to write MCP config file", e);
                }
            } else {
                LOG.warn("Java not found at: " + javaPath + ", MCP tools unavailable");
            }
        }

        return new ProcessBuilder(cmd);
    }

    /**
     * Perform the ACP initialize handshake.
     */
    private void doInitialize() throws CopilotException {
        JsonObject params = new JsonObject();
        params.addProperty("protocolVersion", 1);
        JsonObject clientCapabilities = new JsonObject();
        params.add("clientCapabilities", clientCapabilities);

        JsonObject clientInfo = new JsonObject();
        clientInfo.addProperty("name", "intellij-copilot");
        clientInfo.addProperty("version", "0.1.0");
        params.add("clientInfo", clientInfo);

        JsonObject result = sendRequest("initialize", params);

        JsonObject agentInfo = result.has("agentInfo") ? result.getAsJsonObject("agentInfo") : null;
        JsonObject agentCapabilities = result.has("agentCapabilities") ? result.getAsJsonObject("agentCapabilities") : null;
        authMethods = result.has("authMethods") ? result.getAsJsonArray("authMethods") : null;

        initialized = true;
        LOG.info("ACP initialized: " + (agentInfo != null ? agentInfo : "unknown agent")
            + " capabilities=" + (agentCapabilities != null ? agentCapabilities : "none"));
    }

    /**
     * Create a new ACP session. Returns the session ID and populates available models.
     */
    @NotNull
    public synchronized String createSession() throws CopilotException {
        return createSession(null);
    }

    /**
     * Create a new ACP session with optional working directory.
     *
     * @param cwd The working directory for the session, or null to use user.home
     */
    public synchronized String createSession(@Nullable String cwd) throws CopilotException {
        ensureStarted();

        JsonObject params = new JsonObject();
        params.addProperty("cwd", cwd != null ? cwd : System.getProperty("user.home"));

        // mcpServers must be an array in session/new (agent validates this)
        params.add("mcpServers", new JsonArray());

        JsonObject result = sendRequest("session/new", params);

        currentSessionId = result.get("sessionId").getAsString();

        // Parse available models
        if (result.has("models")) {
            JsonObject modelsObj = result.getAsJsonObject("models");
            availableModels = new ArrayList<>();
            if (modelsObj.has("availableModels")) {
                for (JsonElement elem : modelsObj.getAsJsonArray("availableModels")) {
                    JsonObject m = elem.getAsJsonObject();
                    Model model = new Model();
                    model.id = m.get("modelId").getAsString();
                    model.name = m.has("name") ? m.get("name").getAsString() : model.id;
                    model.description = m.has("description") ? m.get("description").getAsString() : "";
                    if (m.has("_meta")) {
                        JsonObject meta = m.getAsJsonObject("_meta");
                        model.usage = meta.has("copilotUsage") ? meta.get("copilotUsage").getAsString() : null;
                    }
                    availableModels.add(model);
                }
            }
        }

        LOG.info("ACP session created: " + currentSessionId + " with " +
            (availableModels != null ? availableModels.size() : 0) + " models");
        return currentSessionId;
    }

    /**
     * List available models. Creates a session if needed (models come from session/new).
     */
    @NotNull
    public List<Model> listModels() throws CopilotException {
        if (availableModels == null) {
            createSession();
        }
        return availableModels != null ? availableModels : List.of();
    }

    /**
     * Send a prompt and collect the full response (blocking).
     * Streaming chunks are delivered via the onChunk callback.
     *
     * @param sessionId Session ID (from createSession)
     * @param prompt    The prompt text
     * @param model     Model ID (or null for default)
     * @param onChunk   Optional callback for streaming text chunks
     * @return The stop reason
     */
    @NotNull
    public String sendPrompt(@NotNull String sessionId, @NotNull String prompt,
                             @Nullable String model, @Nullable Consumer<String> onChunk)
        throws CopilotException {
        return sendPrompt(sessionId, prompt, model, null, onChunk);
    }

    /**
     * Send a prompt with optional file/selection context references.
     * References are included as ACP "resource" content blocks alongside the text.
     */
    public String sendPrompt(@NotNull String sessionId, @NotNull String prompt,
                             @Nullable String model, @Nullable List<ResourceReference> references,
                             @Nullable Consumer<String> onChunk)
        throws CopilotException {
        return sendPrompt(sessionId, prompt, model, references, onChunk, null);
    }

    /**
     * Send a prompt with full control over ACP session/update notifications.
     *
     * @param onChunk  receives text chunks for streaming display
     * @param onUpdate receives raw update JSON objects for plan events, tool calls, etc.
     */
    public String sendPrompt(@NotNull String sessionId, @NotNull String prompt,
                             @Nullable String model, @Nullable List<ResourceReference> references,
                             @Nullable Consumer<String> onChunk,
                             @Nullable Consumer<JsonObject> onUpdate)
        throws CopilotException {
        ensureStarted();

        LOG.info("sendPrompt: sessionId=" + sessionId + " model=" + model + " refs=" + (references != null ? references.size() : 0));

        // Register notification listener for streaming chunks
        Consumer<JsonObject> listener = notification -> {
            String method = notification.has("method") ? notification.get("method").getAsString() : "";
            if (!"session/update".equals(method)) return;

            JsonObject params = notification.getAsJsonObject("params");
            if (params == null) return;

            String sid = params.has("sessionId") ? params.get("sessionId").getAsString() : "";
            if (!sessionId.equals(sid)) {
                LOG.info("sendPrompt: ignoring update for different session: " + sid + " (expected " + sessionId + ")");
                return;
            }

            if (params.has("update")) {
                JsonObject update = params.getAsJsonObject("update");
                String updateType = update.has("sessionUpdate") ? update.get("sessionUpdate").getAsString() : "";
                LOG.debug("sendPrompt: received update type=" + updateType);

                if ("agent_message_chunk".equals(updateType) && onChunk != null) {
                    JsonObject content = update.has("content") ? update.getAsJsonObject("content") : null;
                    if (content != null && "text".equals(content.has("type") ? content.get("type").getAsString() : "")) {
                        onChunk.accept(content.get("text").getAsString());
                    }
                }

                // Forward all updates to onUpdate listener (plan events, tool calls, etc.)
                if (onUpdate != null) {
                    onUpdate.accept(update);
                }
            }
        };
        notificationListeners.add(listener);

        try {
            JsonObject params = new JsonObject();
            params.addProperty("sessionId", sessionId);

            JsonArray promptArray = new JsonArray();

            // Add resource references before text prompt
            if (references != null) {
                for (ResourceReference ref : references) {
                    JsonObject resource = new JsonObject();
                    resource.addProperty("type", "resource");
                    JsonObject resourceData = new JsonObject();
                    resourceData.addProperty("uri", ref.uri());
                    if (ref.mimeType() != null) {
                        resourceData.addProperty("mimeType", ref.mimeType());
                    }
                    resourceData.addProperty("text", ref.text());
                    resource.add("resource", resourceData);
                    promptArray.add(resource);
                }
            }

            // Add text prompt
            JsonObject promptContent = new JsonObject();
            promptContent.addProperty("type", "text");
            promptContent.addProperty("text", prompt);
            promptArray.add(promptContent);
            params.add("prompt", promptArray);

            if (model != null) {
                params.addProperty("model", model);
            }

            // Send request - response comes after all streaming chunks
            builtInActionDeniedDuringTurn = false;
            LOG.info("sendPrompt: sending session/prompt request");
            JsonObject result = sendRequest("session/prompt", params, 300);
            LOG.info("sendPrompt: got result: " + result.toString().substring(0, Math.min(200, result.toString().length())));

            // If a built-in action was denied, send a retry prompt telling agent to use MCP tools
            if (builtInActionDeniedDuringTurn) {
                String deniedKind = lastDeniedKind;
                builtInActionDeniedDuringTurn = false;
                LOG.info("sendPrompt: built-in " + deniedKind + " denied, sending retry with MCP tool instruction");
                result = sendRetryPrompt(sessionId, model, deniedKind);
            }

            return result.has("stopReason") ? result.get("stopReason").getAsString() : "unknown";
        } finally {
            notificationListeners.remove(listener);
        }
    }

    /**
     * Send session/cancel notification to abort the current prompt turn.
     */
    public void cancelSession(@NotNull String sessionId) {
        if (closed) return;
        try {
            JsonObject notification = new JsonObject();
            notification.addProperty("jsonrpc", "2.0");
            notification.addProperty("method", "session/cancel");
            JsonObject params = new JsonObject();
            params.addProperty("sessionId", sessionId);
            notification.add("params", params);
            sendRawMessage(notification);
            LOG.info("Sent session/cancel for session " + sessionId);
        } catch (RuntimeException e) {
            LOG.warn("Failed to send session/cancel", e);
        }
    }

    /**
     * Check if the ACP process is alive and initialized.
     */
    public boolean isHealthy() {
        return process != null && process.isAlive() && initialized && !closed;
    }

    /**
     * Get the auth method info from the initialize response (for login button).
     */
    @Nullable
    public AuthMethod getAuthMethod() {
        if (authMethods == null || authMethods.isEmpty()) return null;
        JsonObject first = authMethods.get(0).getAsJsonObject();
        AuthMethod method = new AuthMethod();
        method.id = first.has("id") ? first.get("id").getAsString() : "";
        method.name = first.has("name") ? first.get("name").getAsString() : "";
        method.description = first.has("description") ? first.get("description").getAsString() : "";
        if (first.has("_meta")) {
            JsonObject meta = first.getAsJsonObject("_meta");
            if (meta.has("terminal-auth")) {
                JsonObject termAuth = meta.getAsJsonObject("terminal-auth");
                method.command = termAuth.has("command") ? termAuth.get("command").getAsString() : null;
                if (termAuth.has("args")) {
                    List<String> args = new ArrayList<>();
                    for (JsonElement a : termAuth.getAsJsonArray("args")) {
                        args.add(a.getAsString());
                    }
                    method.args = args;
                }
            }
        }
        return method;
    }

    /**
     * Send a JSON-RPC request and wait for the response.
     */
    @NotNull
    private JsonObject sendRequest(@NotNull String method, @NotNull JsonObject params) throws CopilotException {
        return sendRequest(method, params, REQUEST_TIMEOUT_SECONDS);
    }

    /**
     * Send a raw JSON-RPC message (used for responding to agent-to-client requests).
     */
    private void sendRawMessage(@NotNull JsonObject message) {
        try {
            String json = gson.toJson(message);
            synchronized (writerLock) {
                writer.write(json);
                writer.newLine();
                writer.flush();
            }
        } catch (IOException e) {
            LOG.warn("Failed to send raw message", e);
        }
    }

    @NotNull
    private JsonObject sendRequest(@NotNull String method, @NotNull JsonObject params, long timeoutSeconds) throws CopilotException {
        if (closed) throw new CopilotException("ACP client is closed", null, false);

        long id = requestIdCounter.getAndIncrement();
        CompletableFuture<JsonObject> future = new CompletableFuture<>();
        pendingRequests.put(id, future);

        try {
            JsonObject request = new JsonObject();
            request.addProperty("jsonrpc", "2.0");
            request.addProperty("id", id);
            request.addProperty("method", method);
            request.add("params", params);

            String json = gson.toJson(request);
            LOG.info("ACP request: " + method + " id=" + id + " params=" + gson.toJson(params));

            synchronized (writerLock) {
                writer.write(json);
                writer.newLine();
                writer.flush();
            }

            return future.get(timeoutSeconds, TimeUnit.SECONDS);

        } catch (TimeoutException e) {
            pendingRequests.remove(id);
            throw new CopilotException("ACP request timed out: " + method, e, true);
        } catch (ExecutionException e) {
            pendingRequests.remove(id);
            Throwable cause = e.getCause();
            if (cause instanceof CopilotException) throw (CopilotException) cause;
            throw new CopilotException("ACP request failed: " + method + " - " + cause.getMessage(), e, false);
        } catch (InterruptedException e) {
            pendingRequests.remove(id);
            Thread.currentThread().interrupt();
            throw new CopilotException("ACP request interrupted: " + method, e, true);
        } catch (IOException e) {
            pendingRequests.remove(id);
            throw new CopilotException("ACP write failed: " + method, e, true);
        }
    }

    /**
     * Background thread that reads JSON-RPC messages from the copilot process stdout.
     */
    private void readLoop() {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while (!closed && (line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;

                try {
                    JsonObject msg = JsonParser.parseString(line).getAsJsonObject();

                    boolean hasId = msg.has("id") && !msg.get("id").isJsonNull();
                    boolean hasMethod = msg.has("method");

                    if (hasId && hasMethod) {
                        // Agent-to-client request (e.g., session/request_permission)
                        handleAgentRequest(msg);

                        // Also forward to notification listeners for timeline tracking
                        for (Consumer<JsonObject> listener : notificationListeners) {
                            try {
                                listener.accept(msg);
                            } catch (RuntimeException e) {
                                LOG.warn("Listener error", e);
                            }
                        }
                    } else if (hasId) {
                        // Response to a request we sent
                        long id = msg.get("id").getAsLong();
                        CompletableFuture<JsonObject> future = pendingRequests.remove(id);
                        if (future != null) {
                            if (msg.has("error")) {
                                JsonObject error = msg.getAsJsonObject("error");
                                String errorMessage = error.has("message") ? error.get("message").getAsString() : "Unknown error";
                                LOG.warn("ACP error response for request id=" + id + ": " + gson.toJson(error));
                                if (error.has("data") && !error.get("data").isJsonNull()) {
                                    try {
                                        errorMessage = error.get("data").getAsString();
                                    } catch (ClassCastException | IllegalStateException ignored) {
                                    }
                                }
                                future.completeExceptionally(new CopilotException("ACP error: " + errorMessage, null, false));
                            } else if (msg.has("result")) {
                                future.complete(msg.getAsJsonObject("result"));
                            } else {
                                future.complete(new JsonObject());
                            }
                        }
                    } else if (hasMethod) {
                        // Notification (no id) — e.g., session/update
                        for (Consumer<JsonObject> listener : notificationListeners) {
                            try {
                                listener.accept(msg);
                            } catch (RuntimeException e) {
                                LOG.warn("Notification listener error", e);
                            }
                        }
                    }
                } catch (com.google.gson.JsonParseException | IllegalStateException e) {
                    LOG.warn("Failed to parse ACP message: " + line, e);
                }
            }
        } catch (IOException e) {
            if (!closed) {
                LOG.warn("ACP reader thread ended: " + e.getMessage());
            }
        }

        // Process ended — fail all pending requests
        for (Map.Entry<Long, CompletableFuture<JsonObject>> entry : pendingRequests.entrySet()) {
            entry.getValue().completeExceptionally(
                new CopilotException("ACP process terminated", null, false));
        }
        pendingRequests.clear();
    }

    private void ensureStarted() throws CopilotException {
        if (closed) {
            throw new CopilotException("ACP client is closed", null, false);
        }
        if (!initialized || process == null || !process.isAlive()) {
            initialized = false;
            start();
        }
    }

    // ---- Agent request handlers (extracted from readLoop) ----

    /**
     * Route an agent-to-client request to the appropriate handler.
     */
    private void handleAgentRequest(JsonObject msg) {
        String reqMethod = msg.get("method").getAsString();
        long reqId = msg.get("id").getAsLong();
        LOG.info("ACP agent request: " + reqMethod + " id=" + reqId);

        if ("session/request_permission".equals(reqMethod)) {
            handlePermissionRequest(reqId, msg.has("params") ? msg.getAsJsonObject("params") : null);
        } else {
            sendErrorResponse(reqId, -32601, "Method not supported: " + reqMethod);
        }
    }

    /**
     * Handle permission requests from the Copilot agent.
     * Denies built-in write operations (edit, create) so the agent retries with IntelliJ MCP tools.
     * Auto-approves everything else (MCP tool calls, shell, reads).
     */
    private void handlePermissionRequest(long reqId, @Nullable JsonObject reqParams) {
        String permKind = "";
        String permTitle = "";
        if (reqParams != null && reqParams.has("toolCall")) {
            JsonObject toolCall = reqParams.getAsJsonObject("toolCall");
            permKind = toolCall.has("kind") ? toolCall.get("kind").getAsString() : "";
            permTitle = toolCall.has("title") ? toolCall.get("title").getAsString() : "";
        }
        LOG.info("ACP request_permission: kind=" + permKind + " title=" + permTitle);

        if (DENIED_PERMISSION_KINDS.contains(permKind)) {
            String rejectOptionId = findRejectOption(reqParams);
            LOG.info("ACP request_permission: DENYING built-in " + permKind + ", option=" + rejectOptionId);
            builtInActionDeniedDuringTurn = true;
            lastDeniedKind = permKind;
            sendPermissionResponse(reqId, rejectOptionId);
        } else {
            String allowOptionId = findAllowOption(reqParams);
            LOG.info("ACP request_permission: auto-approving " + permKind + ", option=" + allowOptionId);
            sendPermissionResponse(reqId, allowOptionId);
        }
    }

    /**
     * Build and send a retry prompt after a built-in action was denied,
     * instructing the agent to use IntelliJ MCP tools instead.
     */
    private JsonObject sendRetryPrompt(@NotNull String sessionId, @Nullable String model, @NotNull String deniedKind) throws CopilotException {
        JsonObject retryParams = new JsonObject();
        retryParams.addProperty("sessionId", sessionId);
        JsonArray retryPrompt = new JsonArray();
        JsonObject retryContent = new JsonObject();
        retryContent.addProperty("type", "text");

        String instruction = switch (deniedKind) {
            case "execute" ->
                "The command execution was denied because this environment requires using IntelliJ tools. " +
                    "Please retry using the run_command MCP tool, which executes commands through IntelliJ's Run panel. " +
                    "The output will be visible to the user in IntelliJ and returned to you. " +
                    "You can also use read_run_output to read the Run panel content afterward.";
            case "runInTerminal" ->
                "The terminal command was denied because this environment requires using IntelliJ's built-in terminal. " +
                    "Please retry using the run_in_terminal MCP tool, which opens an IntelliJ Terminal tab. " +
                    "Use list_terminals to see available shells (PowerShell, cmd, bash, etc.).";
            default ->
                "The file operation was denied because this environment requires using IntelliJ tools for all project file changes. " +
                    "Please retry using MCP tools: use intellij_read_file to read files and intellij_write_file to write/create files. " +
                    "For edits, use intellij_write_file with old_str/new_str parameters. " +
                    "For new files, use intellij_write_file with the full content parameter.";
        };
        retryContent.addProperty("text", instruction);
        retryPrompt.add(retryContent);
        retryParams.add("prompt", retryPrompt);
        if (model != null) {
            retryParams.addProperty("model", model);
        }
        JsonObject result = sendRequest("session/prompt", retryParams, 300);
        LOG.info("sendPrompt: retry result: " + result.toString().substring(0, Math.min(200, result.toString().length())));
        return result;
    }

    private void sendErrorResponse(long reqId, int code, String message) {
        JsonObject response = new JsonObject();
        response.addProperty("jsonrpc", "2.0");
        response.addProperty("id", reqId);
        JsonObject error = new JsonObject();
        error.addProperty("code", code);
        error.addProperty("message", message);
        response.add("error", error);
        sendRawMessage(response);
    }

    // ---- End agent request handlers ----

    /**
     * Get the usage multiplier for a model ID (e.g., "1x", "3x", "0.33x").
     * Returns "1x" if model not found.
     */
    @NotNull
    public String getModelMultiplier(@NotNull String modelId) {
        if (availableModels == null) return "1x";
        return availableModels.stream()
            .filter(m -> modelId.equals(m.id))
            .findFirst()
            .map(m -> m.usage != null ? m.usage : "1x")
            .orElse("1x");
    }

    /**
     * Find the copilot CLI executable.
     */
    @NotNull
    private String findCopilotCli() throws CopilotException {
        boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");

        // Check PATH first
        try {
            String command = isWindows ? "where" : "which";
            Process check = new ProcessBuilder(command, "copilot").start();
            if (check.waitFor() == 0) {
                String path = new String(check.getInputStream().readAllBytes()).trim().split("\\r?\\n")[0];
                if (new File(path).exists()) return path;
            }
        } catch (IOException | InterruptedException ignored) {
        }

        // Check known Windows winget install location
        if (isWindows) {
            String wingetPath = System.getenv("LOCALAPPDATA") +
                "\\Microsoft\\WinGet\\Packages\\GitHub.Copilot_Microsoft.Winget.Source_8wekyb3d8bbwe\\copilot.exe";
            if (new File(wingetPath).exists()) return wingetPath;
        }

        String installInstructions = isWindows
            ? "Install with: winget install GitHub.Copilot"
            : "Install with: npm install -g @anthropic-ai/copilot-cli";
        throw new CopilotException("Copilot CLI not found. " + installInstructions, null, false);
    }

    /**
     * Find the bundled MCP server JAR in the plugin's lib directory.
     * Returns null if not found (MCP tools will be unavailable).
     */
    @Nullable
    private String findMcpServerJar() {
        try {
            // The JAR is bundled in the plugin's lib directory alongside plugin-core
            String pluginPath = com.intellij.ide.plugins.PluginManagerCore.getPlugins().length > 0 ?
                java.util.Arrays.stream(com.intellij.ide.plugins.PluginManagerCore.getPlugins())
                    .filter(p -> "com.github.copilot.intellij".equals(p.getPluginId().getIdString()))
                    .findFirst()
                    .map(p -> p.getPluginPath().resolve("lib").resolve("mcp-server.jar").toString())
                    .orElse(null) : null;
            if (pluginPath != null && new File(pluginPath).exists()) {
                LOG.info("Found MCP server JAR: " + pluginPath);
                return pluginPath;
            }

            // Fallback: check relative to this class's JAR
            java.net.URL url = getClass().getProtectionDomain().getCodeSource().getLocation();
            if (url != null) {
                File jarDir = new File(url.toURI()).getParentFile();
                File mcpJar = new File(jarDir, "mcp-server.jar");
                if (mcpJar.exists()) {
                    LOG.info("Found MCP server JAR (fallback): " + mcpJar.getAbsolutePath());
                    return mcpJar.getAbsolutePath();
                }
            }
            LOG.warn("MCP server JAR not found - MCP tools will be unavailable");
        } catch (java.net.URISyntaxException | SecurityException e) {
            LOG.warn("Could not find MCP server JAR: " + e.getMessage());
        }
        return null;
    }

    /**
     * Call a tool on the PSI bridge HTTP server.
     * Returns the result string, or null if bridge is unavailable.
     */
    private String callPsiBridge(String toolName, JsonObject arguments) {
        try {
            java.nio.file.Path bridgeFile = java.nio.file.Path.of(System.getProperty("user.home"), ".copilot", "psi-bridge.json");
            if (!java.nio.file.Files.exists(bridgeFile)) return null;

            String content = java.nio.file.Files.readString(bridgeFile);
            JsonObject bridge = JsonParser.parseString(content).getAsJsonObject();
            int port = bridge.get("port").getAsInt();

            java.net.URL url = java.net.URI.create("http://127.0.0.1:" + port + "/tools/call").toURL();
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(2000);
            conn.setReadTimeout(30000);
            conn.setRequestProperty("Content-Type", "application/json");

            JsonObject request = new JsonObject();
            request.addProperty("name", toolName);
            request.add("arguments", arguments);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(gson.toJson(request).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }

            if (conn.getResponseCode() == 200) {
                String resp = new String(conn.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                JsonObject result = JsonParser.parseString(resp).getAsJsonObject();
                return result.has("result") ? result.get("result").getAsString() : null;
            }
        } catch (IOException e) {
            LOG.debug("PSI bridge call failed for " + toolName + ": " + e.getMessage());
        }
        return null;
    }

    private static JsonObject createArgs(String key, String value) {
        JsonObject args = new JsonObject();
        args.addProperty(key, value);
        return args;
    }

    private String findAllowOption(JsonObject reqParams) {
        if (reqParams != null && reqParams.has("options")) {
            for (JsonElement opt : reqParams.getAsJsonArray("options")) {
                JsonObject option = opt.getAsJsonObject();
                String kind = option.has("kind") ? option.get("kind").getAsString() : "";
                if ("allow_once".equals(kind) || "allow_always".equals(kind)) {
                    return option.get("optionId").getAsString();
                }
            }
        }
        return "allow_once";
    }

    private String findRejectOption(JsonObject reqParams) {
        if (reqParams != null && reqParams.has("options")) {
            for (JsonElement opt : reqParams.getAsJsonArray("options")) {
                JsonObject option = opt.getAsJsonObject();
                String kind = option.has("kind") ? option.get("kind").getAsString() : "";
                if ("reject_once".equals(kind) || "reject_always".equals(kind)) {
                    return option.get("optionId").getAsString();
                }
            }
        }
        return "reject_once";
    }

    private void sendPermissionResponse(long reqId, String optionId) {
        JsonObject response = new JsonObject();
        response.addProperty("jsonrpc", "2.0");
        response.addProperty("id", reqId);
        JsonObject result = new JsonObject();
        JsonObject outcome = new JsonObject();
        outcome.addProperty("outcome", "selected");
        outcome.addProperty("optionId", optionId);
        result.add("outcome", outcome);
        response.add("result", result);
        sendRawMessage(response);
    }

    @Override
    public void close() {
        closed = true;
        if (writer != null) {
            try {
                writer.close();
            } catch (IOException ignored) {
            }
        }
        if (process != null && process.isAlive()) {
            process.destroy();
            try {
                if (!process.waitFor(5, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                }
            } catch (InterruptedException ignored) {
                process.destroyForcibly();
            }
        }
        if (readerThread != null) {
            readerThread.interrupt();
        }
        LOG.info("ACP client closed");
    }

    // DTOs

    public static class Model {
        public String id;
        public String name;
        public String description;
        public String usage; // e.g., "1x", "3x", "0.33x"
    }

    /**
     * ACP resource reference — file or selection context sent with prompts.
     */
    public record ResourceReference(@NotNull String uri, @Nullable String mimeType, @NotNull String text) {
    }

    public static class AuthMethod {
        public String id;
        public String name;
        public String description;
        public String command;
        public List<String> args;
    }
}
