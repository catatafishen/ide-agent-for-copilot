package com.github.copilot.intellij.bridge;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Client for GitHub Copilot CLI via the Agent Client Protocol (ACP).
 * Spawns "copilot --acp --stdio" and communicates via JSON-RPC 2.0 over stdin/stdout.
 */
public class CopilotAcpClient implements Closeable {
    private static final Logger LOG = Logger.getInstance(CopilotAcpClient.class);
    private static final long REQUEST_TIMEOUT_SECONDS = 30;

    private final Gson gson = new Gson();
    private final AtomicLong requestIdCounter = new AtomicLong(1);
    private final ConcurrentHashMap<Long, CompletableFuture<JsonObject>> pendingRequests = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<Consumer<JsonObject>> notificationListeners = new CopyOnWriteArrayList<>();

    private Process process;
    private BufferedWriter writer;
    private Thread readerThread;
    private volatile boolean closed = false;

    // State from initialize
    private JsonObject agentInfo;
    private JsonObject agentCapabilities;
    private JsonArray authMethods;
    private boolean initialized = false;

    // Session state
    private String currentSessionId;
    private List<Model> availableModels;
    private String currentModelId;

    /**
     * Start the copilot ACP process and perform the initialize handshake.
     */
    public synchronized void start() throws CopilotException {
        // Clean up previous process if it died
        if (process != null) {
            LOG.info("Restarting ACP client (previous process died)");
            closed = false; // Reset closed flag for restart
            try { if (writer != null) writer.close(); } catch (Exception ignored) {}
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

        } catch (CopilotException e) {
            throw e;
        } catch (Exception e) {
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
        JsonObject fsCapabilities = new JsonObject();
        fsCapabilities.addProperty("readTextFile", true);
        fsCapabilities.addProperty("writeTextFile", true);
        clientCapabilities.add("fs", fsCapabilities);
        params.add("clientCapabilities", clientCapabilities);

        JsonObject clientInfo = new JsonObject();
        clientInfo.addProperty("name", "intellij-copilot");
        clientInfo.addProperty("version", "0.1.0");
        params.add("clientInfo", clientInfo);

        JsonObject result = sendRequest("initialize", params);

        agentInfo = result.has("agentInfo") ? result.getAsJsonObject("agentInfo") : null;
        agentCapabilities = result.has("agentCapabilities") ? result.getAsJsonObject("agentCapabilities") : null;
        authMethods = result.has("authMethods") ? result.getAsJsonArray("authMethods") : null;

        initialized = true;
        LOG.info("ACP initialized: " + (agentInfo != null ? agentInfo : "unknown agent"));
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
     * @param cwd The working directory for the session, or null to use user.home
     */
    public synchronized String createSession(@Nullable String cwd) throws CopilotException {
        ensureStarted();

        JsonObject params = new JsonObject();
        params.addProperty("cwd", cwd != null ? cwd : System.getProperty("user.home"));

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
            if (modelsObj.has("currentModelId")) {
                currentModelId = modelsObj.get("currentModelId").getAsString();
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
     * @param onChunk receives text chunks for streaming display
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
        final CompletableFuture<Void> streamDone = new CompletableFuture<>();
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
                    resourceData.addProperty("uri", ref.uri);
                    if (ref.mimeType != null) {
                        resourceData.addProperty("mimeType", ref.mimeType);
                    }
                    resourceData.addProperty("text", ref.text);
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
            LOG.info("sendPrompt: sending session/prompt request");
            JsonObject result = sendRequest("session/prompt", params, 300);
            LOG.info("sendPrompt: got result: " + (result != null ? result.toString().substring(0, Math.min(200, result.toString().length())) : "null"));

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
        } catch (Exception e) {
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

    /** Send a raw JSON-RPC message (used for responding to agent-to-client requests). */
    private void sendRawMessage(@NotNull JsonObject message) {
        try {
            String json = gson.toJson(message);
            synchronized (writer) {
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
            LOG.debug("ACP request: " + method + " id=" + id);

            synchronized (writer) {
                writer.write(json);
                writer.newLine();
                writer.flush();
            }

            JsonObject result = future.get(timeoutSeconds, TimeUnit.SECONDS);
            return result;

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
                        String reqMethod = msg.get("method").getAsString();
                        long reqId = msg.get("id").getAsLong();
                        LOG.info("ACP agent request: " + reqMethod + " id=" + reqId);

                        if ("session/request_permission".equals(reqMethod)) {
                            // Auto-approve: select the first "allow" option
                            JsonObject reqParams = msg.has("params") ? msg.getAsJsonObject("params") : null;
                            String selectedOptionId = "allow-once"; // default
                            if (reqParams != null && reqParams.has("options")) {
                                for (JsonElement opt : reqParams.getAsJsonArray("options")) {
                                    JsonObject option = opt.getAsJsonObject();
                                    String kind = option.has("kind") ? option.get("kind").getAsString() : "";
                                    if ("allow_once".equals(kind) || "allow_always".equals(kind)) {
                                        selectedOptionId = option.get("optionId").getAsString();
                                        break;
                                    }
                                }
                            }
                            LOG.info("ACP request_permission: auto-approving with option=" + selectedOptionId);
                            JsonObject response = new JsonObject();
                            response.addProperty("jsonrpc", "2.0");
                            response.addProperty("id", reqId);
                            JsonObject result = new JsonObject();
                            JsonObject outcome = new JsonObject();
                            outcome.addProperty("outcome", "selected");
                            outcome.addProperty("optionId", selectedOptionId);
                            result.add("outcome", outcome);
                            response.add("result", result);
                            sendRawMessage(response);
                        } else if ("fs/read_text_file".equals(reqMethod)) {
                            // Read file for agent
                            JsonObject reqParams = msg.has("params") ? msg.getAsJsonObject("params") : null;
                            String filePath = reqParams != null && reqParams.has("path") ? reqParams.get("path").getAsString() : null;
                            JsonObject response = new JsonObject();
                            response.addProperty("jsonrpc", "2.0");
                            response.addProperty("id", reqId);
                            if (filePath != null) {
                                try {
                                    String content = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(filePath)));
                                    JsonObject result = new JsonObject();
                                    result.addProperty("content", content);
                                    response.add("result", result);
                                } catch (Exception e) {
                                    JsonObject error = new JsonObject();
                                    error.addProperty("code", -32000);
                                    error.addProperty("message", "File not found: " + filePath);
                                    response.add("error", error);
                                }
                            } else {
                                JsonObject error = new JsonObject();
                                error.addProperty("code", -32602);
                                error.addProperty("message", "Missing path parameter");
                                response.add("error", error);
                            }
                            sendRawMessage(response);
                        } else if ("fs/write_text_file".equals(reqMethod)) {
                            // Write file for agent
                            JsonObject reqParams = msg.has("params") ? msg.getAsJsonObject("params") : null;
                            String filePath = reqParams != null && reqParams.has("path") ? reqParams.get("path").getAsString() : null;
                            String fileContent = reqParams != null && reqParams.has("content") ? reqParams.get("content").getAsString() : null;
                            JsonObject response = new JsonObject();
                            response.addProperty("jsonrpc", "2.0");
                            response.addProperty("id", reqId);
                            if (filePath != null && fileContent != null) {
                                try {
                                    java.nio.file.Files.writeString(java.nio.file.Paths.get(filePath), fileContent);
                                    response.add("result", new JsonObject());
                                } catch (Exception e) {
                                    JsonObject error = new JsonObject();
                                    error.addProperty("code", -32000);
                                    error.addProperty("message", "Failed to write file: " + e.getMessage());
                                    response.add("error", error);
                                }
                            } else {
                                JsonObject error = new JsonObject();
                                error.addProperty("code", -32602);
                                error.addProperty("message", "Missing path or content parameter");
                                response.add("error", error);
                            }
                            sendRawMessage(response);
                        } else {
                            // Unknown request — respond with error
                            JsonObject response = new JsonObject();
                            response.addProperty("jsonrpc", "2.0");
                            response.addProperty("id", reqId);
                            JsonObject error = new JsonObject();
                            error.addProperty("code", -32601);
                            error.addProperty("message", "Method not supported: " + reqMethod);
                            response.add("error", error);
                            sendRawMessage(response);
                        }

                        // Also forward to notification listeners for timeline tracking
                        for (Consumer<JsonObject> listener : notificationListeners) {
                            try { listener.accept(msg); } catch (Exception e) { LOG.warn("Listener error", e); }
                        }
                    } else if (hasId) {
                        // Response to a request we sent
                        long id = msg.get("id").getAsLong();
                        CompletableFuture<JsonObject> future = pendingRequests.remove(id);
                        if (future != null) {
                            if (msg.has("error")) {
                                JsonObject error = msg.getAsJsonObject("error");
                                String errorMessage = error.has("message") ? error.get("message").getAsString() : "Unknown error";
                                if (error.has("data") && !error.get("data").isJsonNull()) {
                                    try {
                                        errorMessage = error.get("data").getAsString();
                                    } catch (Exception ignored) {}
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
                            } catch (Exception e) {
                                LOG.warn("Notification listener error", e);
                            }
                        }
                    }
                } catch (Exception e) {
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
        // Check PATH first
        try {
            Process check = new ProcessBuilder("where", "copilot").start();
            if (check.waitFor() == 0) {
                String path = new String(check.getInputStream().readAllBytes()).trim().split("\\r?\\n")[0];
                if (new File(path).exists()) return path;
            }
        } catch (Exception ignored) {}

        // Check known winget install location
        String wingetPath = System.getenv("LOCALAPPDATA") +
                "\\Microsoft\\WinGet\\Packages\\GitHub.Copilot_Microsoft.Winget.Source_8wekyb3d8bbwe\\copilot.exe";
        if (new File(wingetPath).exists()) return wingetPath;

        throw new CopilotException("Copilot CLI not found. Install with: winget install GitHub.Copilot", null, false);
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
            if (pluginPath != null && new File(pluginPath).exists()) return pluginPath;

            // Fallback: check relative to this class's JAR
            java.net.URL url = getClass().getProtectionDomain().getCodeSource().getLocation();
            if (url != null) {
                File jarDir = new File(url.toURI()).getParentFile();
                File mcpJar = new File(jarDir, "mcp-server.jar");
                if (mcpJar.exists()) return mcpJar.getAbsolutePath();
            }
        } catch (Exception e) {
            LOG.debug("Could not find MCP server JAR: " + e.getMessage());
        }
        return null;
    }

    @Override
    public void close() {
        closed = true;
        if (writer != null) {
            try { writer.close(); } catch (Exception ignored) {}
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

    /** ACP resource reference — file or selection context sent with prompts. */
    public static class ResourceReference {
        public final String uri;
        public final String mimeType;
        public final String text;

        public ResourceReference(@NotNull String uri, @Nullable String mimeType, @NotNull String text) {
            this.uri = uri;
            this.mimeType = mimeType;
            this.text = text;
        }
    }

    public static class AuthMethod {
        public String id;
        public String name;
        public String description;
        public String command;
        public List<String> args;
    }
}
