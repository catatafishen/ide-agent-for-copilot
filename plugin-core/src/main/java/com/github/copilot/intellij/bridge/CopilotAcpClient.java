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
        if (process != null && process.isAlive()) {
            LOG.debug("ACP client already running");
            return;
        }

        try {
            String copilotPath = findCopilotCli();
            LOG.info("Starting Copilot ACP: " + copilotPath);

            ProcessBuilder pb = new ProcessBuilder(copilotPath, "--acp", "--stdio");
            pb.redirectErrorStream(false);
            // Inherit environment (includes PATH with gh CLI)
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
     * Perform the ACP initialize handshake.
     */
    private void doInitialize() throws CopilotException {
        JsonObject params = new JsonObject();
        params.addProperty("protocolVersion", 1);
        params.add("clientCapabilities", new JsonObject());

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
        ensureStarted();

        JsonObject params = new JsonObject();
        params.addProperty("cwd", System.getProperty("user.dir"));
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
        ensureStarted();

        // Register notification listener for streaming chunks
        final CompletableFuture<Void> streamDone = new CompletableFuture<>();
        Consumer<JsonObject> listener = notification -> {
            String method = notification.has("method") ? notification.get("method").getAsString() : "";
            if (!"session/update".equals(method)) return;

            JsonObject params = notification.getAsJsonObject("params");
            if (params == null) return;

            String sid = params.has("sessionId") ? params.get("sessionId").getAsString() : "";
            if (!sessionId.equals(sid)) return;

            if (params.has("update")) {
                JsonObject update = params.getAsJsonObject("update");
                String updateType = update.has("sessionUpdate") ? update.get("sessionUpdate").getAsString() : "";

                if ("agent_message_chunk".equals(updateType) && onChunk != null) {
                    JsonObject content = update.has("content") ? update.getAsJsonObject("content") : null;
                    if (content != null && "text".equals(content.has("type") ? content.get("type").getAsString() : "")) {
                        onChunk.accept(content.get("text").getAsString());
                    }
                }
            }
        };
        notificationListeners.add(listener);

        try {
            JsonObject params = new JsonObject();
            params.addProperty("sessionId", sessionId);

            JsonArray promptArray = new JsonArray();
            JsonObject promptContent = new JsonObject();
            promptContent.addProperty("type", "text");
            promptContent.addProperty("text", prompt);
            promptArray.add(promptContent);
            params.add("prompt", promptArray);

            if (model != null) {
                params.addProperty("model", model);
            }

            // Send request - response comes after all streaming chunks
            JsonObject result = sendRequest("session/prompt", params, 300);

            return result.has("stopReason") ? result.get("stopReason").getAsString() : "unknown";
        } finally {
            notificationListeners.remove(listener);
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

                    if (msg.has("id") && !msg.get("id").isJsonNull()) {
                        // Response to a request
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
                    } else if (msg.has("method")) {
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
        if (!initialized || process == null || !process.isAlive()) {
            start();
        }
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

    public static class AuthMethod {
        public String id;
        public String name;
        public String description;
        public String command;
        public List<String> args;
    }
}
