package com.github.copilot.intellij.bridge;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.ide.plugins.IdeaPluginDescriptor;
import com.intellij.ide.plugins.PluginManagerCore;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.extensions.PluginId;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.file.Path;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Client for GitHub Copilot CLI via the Agent Client Protocol (ACP).
 * Spawns "copilot --acp --stdio" and communicates via JSON-RPC 2.0 over stdin/stdout.
 */
public class CopilotAcpClient implements Closeable {
    private static final Logger LOG = Logger.getInstance(CopilotAcpClient.class);
    private static final long REQUEST_TIMEOUT_SECONDS = 30;

    // JSON-RPC field names
    private static final String JSONRPC = "jsonrpc";
    private static final String METHOD = "method";
    private static final String PARAMS = "params";
    private static final String RESULT = "result";
    private static final String ERROR = "error";
    private static final String MESSAGE = "message";
    private static final String COMMAND = "command";
    private static final String SESSION_ID = "sessionId";
    private static final String DESCRIPTION = "description";
    private static final String META = "_meta";
    private static final String OPTIONS = "options";
    private static final String OPTION_ID = "optionId";
    private static final String USER_HOME = "user.home";

    /**
     * Permission kinds that are denied, so the agent uses IntelliJ MCP tools instead.
     */
    private static final Set<String> DENIED_PERMISSION_KINDS = Set.of("edit", "create", "execute", "runInTerminal");

    private final Gson gson = new Gson();
    private final AtomicLong requestIdCounter = new AtomicLong(1);
    private final ConcurrentHashMap<Long, CompletableFuture<JsonObject>> pendingRequests = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<Consumer<JsonObject>> notificationListeners = new CopyOnWriteArrayList<>();

    private final Object writerLock = new Object();
    private final String projectBasePath; // Project path for config-dir
    private Process process;
    private BufferedWriter writer;
    private Thread readerThread;
    private volatile boolean closed = false;

    // Auto-restart state
    private final AtomicInteger restartAttempts = new AtomicInteger(0);
    private static final int MAX_RESTART_ATTEMPTS = 3;
    private static final long[] RESTART_DELAYS_MS = {1000, 2000, 4000}; // Exponential backoff

    // State from the initialization response
    private JsonArray authMethods;
    private boolean initialized = false;

    // Session state
    private String currentSessionId;
    private List<Model> availableModels;

    // Flag: set when a built-in permission (edit/create/runInTerminal) is denied during a prompt turn
    private volatile boolean builtInActionDeniedDuringTurn = false;
    private volatile String lastDeniedKind = "";

    /**
     * Create ACP client with optional project base path for config-dir.
     */
    public CopilotAcpClient(@Nullable String projectBasePath) {
        this.projectBasePath = projectBasePath;
    }

    /**
     * Start the copilot ACP process and perform the initialization handshake.
     */
    public synchronized void start() throws CopilotException {
        // Clean up the previous process if it died
        if (process != null) {
            LOG.info("Restarting ACP client (previous process died)");
            closed = false; // Reset closed flag for restart
            try {
                if (writer != null) writer.close();
            } catch (IOException e) {
                LOG.debug("Failed to close writer during restart", e);
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

            // Start a reader thread for responses and notifications
            readerThread = new Thread(this::readLoop, "copilot-acp-reader");
            readerThread.setDaemon(true);
            readerThread.start();

            // Start a thread to read stderr and capture process errors
            Thread stderrReaderThread = new Thread(this::readStderrLoop, "copilot-acp-stderr");
            stderrReaderThread.setDaemon(true);
            stderrReaderThread.start();

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

        // If copilot is in an NVM directory, explicitly use the same node binary
        // to avoid resolving to a different node version via /usr/bin/env node
        if (copilotPath.contains("/.nvm/versions/node/")) {
            String nodeDir = copilotPath.substring(0, copilotPath.indexOf("/bin/copilot"));
            String nodePath = nodeDir + "/bin/node";
            if (new File(nodePath).exists()) {
                cmd.add(nodePath);
                cmd.add(copilotPath);
            } else {
                cmd.add(copilotPath);
            }
        } else {
            cmd.add(copilotPath);
        }

        cmd.add("--acp");
        cmd.add("--stdio");
        
        // Configure Copilot CLI to use .agent-work/ for session state
        if (projectBasePath != null) {
            Path agentWorkPath = Path.of(projectBasePath, ".agent-work");
            cmd.add("--config-dir");
            cmd.add(agentWorkPath.toString());
            LOG.info("Copilot CLI config-dir set to: " + agentWorkPath);
        }

        String mcpJarPath = findMcpServerJar();
        if (mcpJarPath != null) {
            String javaExe = System.getProperty("os.name", "").toLowerCase().contains("win") ? "java.exe" : "java";
            String javaPath = System.getProperty("java.home") + File.separator + "bin" + File.separator + javaExe;
            if (new File(javaPath).exists()) {
                try {
                    JsonObject mcpConfig = new JsonObject();
                    JsonObject servers = new JsonObject();
                    JsonObject codeTools = new JsonObject();
                    codeTools.addProperty(COMMAND, javaPath);
                    JsonArray args = new JsonArray();
                    args.add("-jar");
                    args.add(mcpJarPath);
                    args.add(System.getProperty(USER_HOME));
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
     * Create a new ACP session with an optional working directory.
     *
     * @param cwd The working directory for the session, or null to use user.home.
     */
    public synchronized String createSession(@Nullable String cwd) throws CopilotException {
        ensureStarted();

        JsonObject params = new JsonObject();
        params.addProperty("cwd", cwd != null ? cwd : System.getProperty(USER_HOME));

        // mcpServers must be an array in session/new (agent validates this)
        params.add("mcpServers", new JsonArray());

        JsonObject result = sendRequest("session/new", params);

        currentSessionId = result.get(SESSION_ID).getAsString();

        // Parse available models
        parseAvailableModels(result);

        LOG.info("ACP session created: " + currentSessionId + " with " +
            (availableModels != null ? availableModels.size() : 0) + " models");
        return currentSessionId;
    }

    private void parseAvailableModels(JsonObject result) {
        if (result.has("models")) {
            JsonObject modelsObj = result.getAsJsonObject("models");
            availableModels = new ArrayList<>();
            if (modelsObj.has("availableModels")) {
                for (JsonElement elem : modelsObj.getAsJsonArray("availableModels")) {
                    JsonObject m = elem.getAsJsonObject();
                    Model model = parseModel(m);
                    availableModels.add(model);
                }
            }
        }
    }

    private Model parseModel(JsonObject m) {
        Model model = new Model();
        model.setId(m.get("modelId").getAsString());
        model.setName(m.has("name") ? m.get("name").getAsString() : model.getId());
        model.setDescription(m.has(DESCRIPTION) ? m.get(DESCRIPTION).getAsString() : "");
        if (m.has(META)) {
            JsonObject meta = m.getAsJsonObject(META);
            model.setUsage(meta.has("copilotUsage") ? meta.get("copilotUsage").getAsString() : null);
        }
        return model;
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
        Consumer<JsonObject> listener = createStreamingListener(sessionId, onChunk, onUpdate);
        notificationListeners.add(listener);

        try {
            JsonObject params = buildPromptParams(sessionId, prompt, model, references);

            // Send request - response comes after all streaming chunks
            builtInActionDeniedDuringTurn = false;
            LOG.info("sendPrompt: sending session/prompt request");
            JsonObject result = sendRequest("session/prompt", params, 600);
            LOG.info("sendPrompt: got result: " + result.toString().substring(0, Math.min(200, result.toString().length())));

            // If a built-in action was denied, send a retry prompt telling the agent to use MCP tools
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

    private Consumer<JsonObject> createStreamingListener(@NotNull String sessionId,
                                                         @Nullable Consumer<String> onChunk,
                                                         @Nullable Consumer<JsonObject> onUpdate) {
        return notification -> {
            String method = notification.has(METHOD) ? notification.get(METHOD).getAsString() : "";
            if (!"session/update".equals(method)) return;

            JsonObject params = notification.getAsJsonObject(PARAMS);
            if (params == null) return;

            String sid = params.has(SESSION_ID) ? params.get(SESSION_ID).getAsString() : "";
            if (!sessionId.equals(sid)) {
                LOG.info("sendPrompt: ignoring update for different session: " + sid + " (expected " + sessionId + ")");
                return;
            }

            if (params.has("update")) {
                JsonObject update = params.getAsJsonObject("update");
                handleSessionUpdate(update, onChunk, onUpdate);
            }
        };
    }

    private void handleSessionUpdate(JsonObject update,
                                     @Nullable Consumer<String> onChunk,
                                     @Nullable Consumer<JsonObject> onUpdate) {
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

    private JsonObject buildPromptParams(@NotNull String sessionId, @NotNull String prompt,
                                         @Nullable String model, @Nullable List<ResourceReference> references) {
        JsonObject params = new JsonObject();
        params.addProperty(SESSION_ID, sessionId);

        JsonArray promptArray = new JsonArray();

        // Add resource references before the text prompt
        if (references != null) {
            for (ResourceReference ref : references) {
                promptArray.add(createResourceReference(ref));
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

        return params;
    }

    private JsonObject createResourceReference(ResourceReference ref) {
        JsonObject resource = new JsonObject();
        resource.addProperty("type", "resource");
        JsonObject resourceData = new JsonObject();
        resourceData.addProperty("uri", ref.uri());
        if (ref.mimeType() != null) {
            resourceData.addProperty("mimeType", ref.mimeType());
        }
        resourceData.addProperty("text", ref.text());
        resource.add("resource", resourceData);
        return resource;
    }

    /**
     * Send session/cancel notification to abort the current prompt turn.
     */
    public void cancelSession(@NotNull String sessionId) {
        if (closed) return;
        try {
            JsonObject notification = new JsonObject();
            notification.addProperty(JSONRPC, "2.0");
            notification.addProperty(METHOD, "session/cancel");
            JsonObject params = new JsonObject();
            params.addProperty(SESSION_ID, sessionId);
            notification.add(PARAMS, params);
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
     * Get the auth method info from the initialization response (for the login button).
     */
    @Nullable
    public AuthMethod getAuthMethod() {
        if (authMethods == null || authMethods.isEmpty()) return null;
        JsonObject first = authMethods.get(0).getAsJsonObject();
        AuthMethod method = new AuthMethod();
        method.setId(first.has("id") ? first.get("id").getAsString() : "");
        method.setName(first.has("name") ? first.get("name").getAsString() : "");
        method.setDescription(first.has(DESCRIPTION) ? first.get(DESCRIPTION).getAsString() : "");
        parseTerminalAuth(first, method);
        return method;
    }

    private void parseTerminalAuth(JsonObject jsonObject, AuthMethod method) {
        if (jsonObject.has(META)) {
            JsonObject meta = jsonObject.getAsJsonObject(META);
            if (meta.has("terminal-auth")) {
                JsonObject termAuth = meta.getAsJsonObject("terminal-auth");
                method.setCommand(termAuth.has(COMMAND) ? termAuth.get(COMMAND).getAsString() : null);
                if (termAuth.has("args")) {
                    List<String> args = new ArrayList<>();
                    for (JsonElement a : termAuth.getAsJsonArray("args")) {
                        args.add(a.getAsString());
                    }
                    method.setArgs(args);
                }
            }
        }
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
            request.addProperty(JSONRPC, "2.0");
            request.addProperty("id", id);
            request.addProperty(METHOD, method);
            request.add(PARAMS, params);

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
            if (cause instanceof CopilotException copilotException) throw copilotException;
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
                processLine(line);
            }
        } catch (IOException e) {
            if (!closed) {
                LOG.warn("ACP reader thread ended: " + e.getMessage());
            }
        }

        // Process ended - attempt auto-restart if not intentionally closed
        if (!closed) {
            attemptAutoRestart();
        } else {
            failAllPendingRequests();
        }
    }

    private void attemptAutoRestart() {
        if (restartAttempts.get() >= MAX_RESTART_ATTEMPTS) {
            LOG.warn("ACP process terminated after " + MAX_RESTART_ATTEMPTS + " restart attempts");
            showNotification("Copilot Disconnected",
                "Could not reconnect after " + MAX_RESTART_ATTEMPTS + " attempts. Please restart the IDE.",
                com.intellij.notification.NotificationType.ERROR);
            failAllPendingRequests();
            return;
        }

        int attempts = restartAttempts.incrementAndGet();
        long delayMs = RESTART_DELAYS_MS[Math.min(attempts - 1, RESTART_DELAYS_MS.length - 1)];

        LOG.info("ACP process terminated. Attempting restart " + attempts + "/" + MAX_RESTART_ATTEMPTS +
            " after " + delayMs + "ms...");
        showNotification("Copilot Reconnecting...",
            "Attempt " + attempts + "/" + MAX_RESTART_ATTEMPTS,
            com.intellij.notification.NotificationType.INFORMATION);

        // Schedule restart on background thread
        new Thread(() -> {
            try {
                Thread.sleep(delayMs);
                start();
                LOG.info("ACP process successfully restarted");
                showNotification("Copilot Reconnected",
                    "Successfully reconnected to Copilot",
                    com.intellij.notification.NotificationType.INFORMATION);
                restartAttempts.set(0); // Reset counter on successful restart
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                LOG.warn("Restart attempt interrupted", e);
                failAllPendingRequests();
            } catch (CopilotException e) {
                LOG.warn("Failed to restart ACP process (attempt " + restartAttempts + ")", e);
                attemptAutoRestart(); // Try again
            }
        }, "CopilotACP-Restart").start();
    }

    private void showNotification(String title, String content, com.intellij.notification.NotificationType type) {
        com.intellij.notification.NotificationGroupManager.getInstance()
            .getNotificationGroup("Copilot Notifications")
            .createNotification(title, content, type)
            .notify(null);
    }

    private void failAllPendingRequests() {
        for (Map.Entry<Long, CompletableFuture<JsonObject>> entry : pendingRequests.entrySet()) {
            entry.getValue().completeExceptionally(
                new CopilotException("ACP process terminated", null, false));
        }
        pendingRequests.clear();
    }

    private void processLine(String line) {
        try {
            JsonObject msg = JsonParser.parseString(line).getAsJsonObject();
            handleJsonRpcMessage(msg);
        } catch (com.google.gson.JsonParseException | IllegalStateException e) {
            LOG.warn("Failed to parse ACP message: " + line, e);
        }
    }

    private void handleJsonRpcMessage(JsonObject msg) {
        boolean hasId = msg.has("id") && !msg.get("id").isJsonNull();
        boolean hasMethod = msg.has(METHOD);

        if (hasId && hasMethod) {
            handleAgentToClientRequest(msg);
        } else if (hasId) {
            handleResponseMessage(msg);
        } else if (hasMethod) {
            handleNotificationMessage(msg);
        }
    }

    private void handleAgentToClientRequest(JsonObject msg) {
        handleAgentRequest(msg);

        // Also forward to notification listeners for timeline tracking
        notifyListeners(msg);
    }

    private void handleResponseMessage(JsonObject msg) {
        long id = msg.get("id").getAsLong();
        CompletableFuture<JsonObject> future = pendingRequests.remove(id);
        if (future != null) {
            if (msg.has(ERROR)) {
                handleErrorResponse(msg, id, future);
            } else if (msg.has(RESULT)) {
                future.complete(msg.getAsJsonObject(RESULT));
            } else {
                future.complete(new JsonObject());
            }
        }
    }

    private void handleErrorResponse(JsonObject msg, long id, CompletableFuture<JsonObject> future) {
        JsonObject error = msg.getAsJsonObject(ERROR);
        String errorMessage = error.has(MESSAGE) ? error.get(MESSAGE).getAsString() : "Unknown error";
        LOG.warn("ACP error response for request id=" + id + ": " + gson.toJson(error));
        if (error.has("data") && !error.get("data").isJsonNull()) {
            try {
                errorMessage = error.get("data").getAsString();
            } catch (ClassCastException | IllegalStateException e) {
                LOG.debug("Error extracting error data as string", e);
            }
        }
        future.completeExceptionally(new CopilotException("ACP error: " + errorMessage, null, false));
    }

    private void handleNotificationMessage(JsonObject msg) {
        notifyListeners(msg);
    }

    private void notifyListeners(JsonObject msg) {
        for (Consumer<JsonObject> listener : notificationListeners) {
            try {
                listener.accept(msg);
            } catch (RuntimeException e) {
                LOG.debug("Error forwarding notification to listener", e);
            }
        }
    }

    private void readStderrLoop() {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getErrorStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                LOG.warn("Copilot CLI stderr: " + line);
            }
        } catch (IOException e) {
            if (!closed) {
                LOG.warn("Stderr reader thread ended: " + e.getMessage());
            }
        }
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
        String reqMethod = msg.get(METHOD).getAsString();
        long reqId = msg.get("id").getAsLong();
        LOG.info("ACP agent request: " + reqMethod + " id=" + reqId);

        if ("session/request_permission".equals(reqMethod)) {
            handlePermissionRequest(reqId, msg.has(PARAMS) ? msg.getAsJsonObject(PARAMS) : null);
        } else {
            sendErrorResponse(reqId, -32601, "Method not supported: " + reqMethod);
        }
    }

    /**
     * Handle permission requests from the Copilot agent.
     * Denies built-in write operations (edit, create), so the agent retries with IntelliJ MCP tools.
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
        retryParams.addProperty(SESSION_ID, sessionId);
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
        JsonObject result = sendRequest("session/prompt", retryParams, 600);
        LOG.info("sendPrompt: retry result: " + result.toString().substring(0, Math.min(200, result.toString().length())));
        return result;
    }

    @SuppressWarnings("SameParameterValue") // Error code is standard JSON-RPC -32_601 for "Method not found"
    private void sendErrorResponse(long reqId, int code, String message) {
        JsonObject response = new JsonObject();
        response.addProperty(JSONRPC, "2.0");
        response.addProperty("id", reqId);
        JsonObject error = new JsonObject();
        error.addProperty("code", code);
        error.addProperty(MESSAGE, message);
        response.add(ERROR, error);
        sendRawMessage(response);
    }

    // ---- End agent request handlers ----

    /**
     * Get the usage multiplier for a model ID (e.g., "1x", "3x", "0.33x").
     * Returns "1x" if the model is not found.
     */
    @NotNull
    @SuppressWarnings("unused") // Public API - may be used by external code
    public String getModelMultiplier(@NotNull String modelId) {
        if (availableModels == null) return "1x";
        return availableModels.stream()
            .filter(m -> modelId.equals(m.getId()))
            .findFirst()
            .map(m -> m.getUsage() != null ? m.getUsage() : "1x")
            .orElse("1x");
    }

    /**
     * Find the copilot CLI executable.
     */
    @NotNull
    private String findCopilotCli() throws CopilotException {
        boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");

        // Check PATH first
        String pathResult = checkCopilotInPath(isWindows);
        if (pathResult != null) return pathResult;

        // Check known Windows winget install location
        if (isWindows) {
            String wingetPath = checkWindowsWingetPath();
            if (wingetPath != null) return wingetPath;
        }

        // Check common Linux/macOS locations
        if (!isWindows) {
            String unixPath = checkUnixLocations();
            if (unixPath != null) return unixPath;
        }

        String installInstructions = isWindows
            ? "Install with: winget install GitHub.Copilot"
            : "Install with: npm install -g @anthropic-ai/copilot-cli";
        throw new CopilotException("Copilot CLI not found. " + installInstructions, null, false);
    }

    @Nullable
    private String checkCopilotInPath(boolean isWindows) {
        try {
            String command = isWindows ? "where" : "which";
            Process check = new ProcessBuilder(command, "copilot").start();
            if (check.waitFor() == 0) {
                String path = new String(check.getInputStream().readAllBytes()).trim().split("\\r?\\n")[0];
                if (new File(path).exists()) return path;
            }
        } catch (IOException e) {
            LOG.debug("Failed to check for copilot in PATH", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOG.debug("Interrupted while checking for copilot in PATH", e);
        }
        return null;
    }

    @Nullable
    private String checkWindowsWingetPath() {
        String wingetPath = System.getenv("LOCALAPPDATA") +
            "\\Microsoft\\WinGet\\Packages\\GitHub.Copilot_Microsoft.Winget.Source_8wekyb3d8bbwe\\copilot.exe";
        if (new File(wingetPath).exists()) return wingetPath;
        return null;
    }

    @Nullable
    private String checkUnixLocations() {
        String home = System.getProperty(USER_HOME);
        List<String> candidates = new ArrayList<>();

        // NVM-managed node installations
        addNvmCandidates(home, candidates);

        // Common global npm/yarn locations
        candidates.add(home + "/.local/bin/copilot");
        candidates.add("/usr/local/bin/copilot");
        candidates.add(home + "/.npm-global/bin/copilot");
        candidates.add(home + "/.yarn/bin/copilot");
        // Homebrew
        candidates.add("/opt/homebrew/bin/copilot");

        for (String path : candidates) {
            if (new File(path).exists()) {
                LOG.info("Found Copilot CLI at: " + path);
                return path;
            }
        }
        return null;
    }

    private void addNvmCandidates(String home, List<String> candidates) {
        File nvmDir = new File(home, ".nvm/versions/node");
        if (nvmDir.isDirectory()) {
            File[] nodeDirs = nvmDir.listFiles(File::isDirectory);
            if (nodeDirs != null) {
                // Sort descending to prefer the latest version
                java.util.Arrays.sort(nodeDirs, (a, b) -> b.getName().compareTo(a.getName()));
                for (File nodeDir : nodeDirs) {
                    candidates.add(new File(nodeDir, "bin/copilot").getAbsolutePath());
                }
            }
        }
    }

    /**
     * Find the bundled MCP server JAR in the plugin's lib directory.
     * Returns null if not found (MCP tools will be unavailable).
     */
    @Nullable
    private String findMcpServerJar() {
        try {
            // The JAR is bundled in the plugin's lib directory alongside plugin-core
            PluginId pluginId = PluginId.getId("com.github.copilot.intellij");
            IdeaPluginDescriptor[] plugins = PluginManagerCore.getPlugins();
            @SuppressWarnings("EqualsBetweenInconvertibleTypes")
            String pluginPath = plugins.length > 0 ?
                java.util.Arrays.stream(plugins)
                    // Cast needed - IDE fails to resolve inherited methods from PluginDescriptor interface
                    .filter(p -> pluginId.equals(((com.intellij.openapi.extensions.PluginDescriptor) p).getPluginId())) //NOSONAR
                    .findFirst()
                    .map(p -> ((com.intellij.openapi.extensions.PluginDescriptor) p).getPluginPath().resolve("lib").resolve("mcp-server.jar").toString()) //NOSONAR
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

    private String findAllowOption(JsonObject reqParams) {
        if (reqParams != null && reqParams.has(OPTIONS)) {
            for (JsonElement opt : reqParams.getAsJsonArray(OPTIONS)) {
                JsonObject option = opt.getAsJsonObject();
                String kind = option.has("kind") ? option.get("kind").getAsString() : "";
                if ("allow_once".equals(kind) || "allow_always".equals(kind)) {
                    return option.get(OPTION_ID).getAsString();
                }
            }
        }
        return "allow_once";
    }

    private String findRejectOption(JsonObject reqParams) {
        if (reqParams != null && reqParams.has(OPTIONS)) {
            for (JsonElement opt : reqParams.getAsJsonArray(OPTIONS)) {
                JsonObject option = opt.getAsJsonObject();
                String kind = option.has("kind") ? option.get("kind").getAsString() : "";
                if ("reject_once".equals(kind) || "reject_always".equals(kind)) {
                    return option.get(OPTION_ID).getAsString();
                }
            }
        }
        return "reject_once";
    }

    private void sendPermissionResponse(long reqId, String optionId) {
        JsonObject response = new JsonObject();
        response.addProperty(JSONRPC, "2.0");
        response.addProperty("id", reqId);
        JsonObject result = new JsonObject();
        JsonObject outcome = new JsonObject();
        outcome.addProperty("outcome", "selected");
        outcome.addProperty(OPTION_ID, optionId);
        result.add("outcome", outcome);
        response.add(RESULT, result);
        sendRawMessage(response);
    }

    @Override
    public void close() {
        closed = true;
        if (writer != null) {
            try {
                writer.close();
            } catch (IOException e) {
                LOG.debug("Failed to close writer during shutdown", e);
            }
        }
        if (process != null && process.isAlive()) {
            process.destroy();
            try {
                if (!process.waitFor(5, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                LOG.debug("Interrupted while waiting for process shutdown", e);
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
        private String id;
        private String name;
        private String description;
        private String usage; // e.g., "1x", "3x", "0.33x"

        public String getId() {
            return id;
        }

        @SuppressWarnings("unused") // Public API for external use
        public void setId(String id) {
            this.id = id;
        }

        public String getName() {
            return name;
        }

        @SuppressWarnings("unused") // Public API for external use
        public void setName(String name) {
            this.name = name;
        }

        @SuppressWarnings("unused") // Public API for external use
        public String getDescription() {
            return description;
        }

        @SuppressWarnings("unused") // Public API for external use
        public void setDescription(String description) {
            this.description = description;
        }

        public String getUsage() {
            return usage;
        }

        @SuppressWarnings("unused") // Public API for external use
        public void setUsage(String usage) {
            this.usage = usage;
        }
    }

    /**
     * ACP resource reference ? file or selection context sent with prompts.
     */
    public record ResourceReference(@NotNull String uri, @Nullable String mimeType, @NotNull String text) {
    }

    public static class AuthMethod {
        private String id;
        private String name;
        private String description;
        private String command;
        private List<String> args;

        public String getId() {
            return id;
        }

        @SuppressWarnings("unused") // Public API for external use
        public void setId(String id) {
            this.id = id;
        }

        public String getName() {
            return name;
        }

        @SuppressWarnings("unused") // Public API for external use
        public void setName(String name) {
            this.name = name;
        }

        @SuppressWarnings("unused") // Public API for external use
        public String getDescription() {
            return description;
        }

        @SuppressWarnings("unused") // Public API for external use
        public void setDescription(String description) {
            this.description = description;
        }

        public String getCommand() {
            return command;
        }

        @SuppressWarnings("unused") // Public API for external use
        public void setCommand(String command) {
            this.command = command;
        }

        public List<String> getArgs() {
            return args;
        }

        @SuppressWarnings("unused") // Public API for external use
        public void setArgs(List<String> args) {
            this.args = args;
        }
    }
}
