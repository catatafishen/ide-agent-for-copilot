package com.github.catatafishen.ideagentforcopilot.agent.codex;

import com.github.catatafishen.ideagentforcopilot.acp.model.ContentBlock;
import com.github.catatafishen.ideagentforcopilot.acp.model.Model;
import com.github.catatafishen.ideagentforcopilot.acp.model.PromptRequest;
import com.github.catatafishen.ideagentforcopilot.acp.model.PromptResponse;
import com.github.catatafishen.ideagentforcopilot.acp.model.SessionUpdate;
import com.github.catatafishen.ideagentforcopilot.agent.AbstractAgentClient;
import com.github.catatafishen.ideagentforcopilot.agent.AgentException;
import com.github.catatafishen.ideagentforcopilot.bridge.AgentConfig;
import com.github.catatafishen.ideagentforcopilot.bridge.SessionOption;
import com.github.catatafishen.ideagentforcopilot.bridge.TransportType;
import com.github.catatafishen.ideagentforcopilot.services.AgentProfile;
import com.github.catatafishen.ideagentforcopilot.services.McpInjectionMethod;
import com.github.catatafishen.ideagentforcopilot.services.PermissionInjectionMethod;
import com.github.catatafishen.ideagentforcopilot.services.ToolDefinition;
import com.github.catatafishen.ideagentforcopilot.services.ToolRegistry;
import com.github.catatafishen.ideagentforcopilot.settings.ProjectFilesSettings;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Codex agent client backed by {@code codex app-server} (JSON-RPC 2.0 over stdio).
 *
 * <p>Starts a persistent {@code codex app-server} subprocess and communicates via
 * newline-delimited JSON (JSONL). Each plugin session maps to a Codex {@code thread},
 * and each user prompt starts a {@code turn} within that thread.</p>
 *
 * <p>Native tool approval requests ({@code item/commandExecution/requestApproval} and
 * {@code item/fileChange/requestApproval}) are intercepted and declined, routing all
 * file and code operations through the plugin's MCP server instead.</p>
 *
 * <p>Shell tools ({@code shell}, {@code shell_command}, {@code exec_command}, etc.) are
 * disabled at server-startup time via {@code --config features.shell_tool=false} and
 * {@code --config features.unified_exec=false}.</p>
 */
public final class CodexAppServerClient extends AbstractAgentClient {

    private static final Logger LOG = Logger.getInstance(CodexAppServerClient.class);

    public static final String PROFILE_ID = "codex";

    // ── JSON-RPC field names ─────────────────────────────────────────────────

    private static final String F_ID = "id";
    private static final String F_METHOD = "method";
    private static final String F_PARAMS = "params";
    private static final String F_RESULT = "result";
    private static final String F_ERROR = "error";
    private static final String F_TYPE = "type";
    private static final String F_TEXT = "text";
    private static final String F_ITEM = "item";
    private static final String F_DELTA = "delta";
    private static final String F_TURN = "turn";
    private static final String F_THREAD = "thread";
    private static final String F_STATUS = "status";
    private static final String F_USAGE = "usage";
    private static final String F_INPUT_TOKENS = "input_tokens";
    private static final String F_OUTPUT_TOKENS = "output_tokens";
    private static final String F_MESSAGE = "message";
    private static final String F_TOOL = "tool";
    private static final String F_ARGUMENTS = "arguments";
    private static final String F_COMMAND = "command";
    private static final String F_REASONING = "reasoning";
    private static final String AGENTS_MD = "AGENTS.md";

    // ── Known models ─────────────────────────────────────────────────────────

    private static final List<Model> KNOWN_MODELS = buildKnownModels();
    private static final String DEFAULT_MODEL = "gpt-4.1";

    private static List<Model> buildKnownModels() {
        Object[][] rows = {
            {"gpt-4.1", "GPT-4.1 (default)"},
            {"gpt-4.1-mini", "GPT-4.1 Mini (fast)"},
            {"gpt-4.1-nano", "GPT-4.1 Nano (fastest)"},
            {"gpt-5.4", "GPT-5.4"},
            {"o3", "o3 (deep reasoning)"},
            {"o4-mini", "o4-mini (fast reasoning)"},
        };
        List<Model> list = new ArrayList<>(rows.length);
        for (Object[] row : rows) {
            list.add(new Model((String) row[0], (String) row[1], null, null));
        }
        return Collections.unmodifiableList(list);
    }

    // ── Session options ──────────────────────────────────────────────────────

    private static final SessionOption EFFORT_OPTION = new SessionOption(
        "effort", "Effort",
        List.of("", "low", "medium", "high")
    );

    // ── Profile ──────────────────────────────────────────────────────────────

    @NotNull
    public static AgentProfile createDefaultProfile() {
        AgentProfile p = new AgentProfile();
        p.setId(PROFILE_ID);
        p.setDisplayName("Codex");
        p.setBuiltIn(true);
        p.setExperimental(true);
        p.setTransportType(TransportType.CODEX_APP_SERVER);
        p.setDescription("""
            OpenAI Codex CLI profile — drives the locally-installed 'codex' binary as a \
            persistent app-server subprocess (JSON-RPC 2.0 over stdio). \
            Supports streaming text, graceful tool-approval denial, and multi-turn threads. \
            Requires 'codex' to be installed and authenticated via 'codex login'.""");
        p.setBinaryName("codex");
        p.setAlternateNames(List.of());
        p.setInstallHint("Install with: npm install -g @openai/codex, then run 'codex login'.");
        p.setInstallUrl("https://developers.openai.com/codex/cli");
        p.setSupportsOAuthSignIn(false);
        p.setAcpArgs(List.of());
        // MCP injected via --config flags at server startup
        p.setMcpMethod(McpInjectionMethod.NONE);
        p.setSupportsMcpConfigFlag(false);
        p.setSupportsModelFlag(true);
        p.setSupportsConfigDir(false);
        p.setExcludeAgentBuiltInTools(true);
        p.setUsePluginPermissions(true);
        p.setPermissionInjectionMethod(PermissionInjectionMethod.NONE);
        p.setPrependInstructionsTo(AGENTS_MD);
        return p;
    }

    // ── Fields ───────────────────────────────────────────────────────────────

    private final AgentProfile profile;
    private final AgentConfig config;
    @Nullable
    private final ToolRegistry registry;
    @Nullable
    private final Project project;
    private final int mcpPort;

    private volatile Process appServerProcess;
    private volatile OutputStream stdin;
    private volatile boolean connected = false;

    private final AtomicInteger nextId = new AtomicInteger(1);
    private final Map<Integer, CompletableFuture<JsonObject>> pendingRequests = new ConcurrentHashMap<>();

    /**
     * Plugin session ID → Codex thread ID.
     */
    private final Map<String, String> sessionToThreadId = new ConcurrentHashMap<>();
    /**
     * Plugin session ID → cwd (captured at createSession time).
     */
    private final Map<String, String> sessionCwd = new ConcurrentHashMap<>();
    /**
     * Plugin session ID → model override.
     */
    private final Map<String, String> sessionModels = new ConcurrentHashMap<>();
    /**
     * Plugin session ID → session options (e.g. effort).
     */
    private final Map<String, Map<String, String>> sessionOptions = new ConcurrentHashMap<>();
    private final Map<String, AtomicBoolean> sessionCancelled = new ConcurrentHashMap<>();

    // Active turn state — one turn at a time over stdio
    private volatile String activeTurnId;
    private volatile Consumer<SessionUpdate> activeTurnCallback;
    private volatile CompletableFuture<String> activeTurnResult;
    private volatile String activeTurnSessionId;

    private String resolvedBinaryPath;

    public CodexAppServerClient(@NotNull AgentProfile profile,
                                @NotNull AgentConfig config,
                                @Nullable ToolRegistry registry,
                                @Nullable Project project,
                                int mcpPort) {
        this.profile = profile;
        this.config = config;
        this.registry = registry;
        this.project = project;
        this.mcpPort = mcpPort;
    }

    // ── Identity ─────────────────────────────────────────────────────────────

    @Override
    public String agentId() {
        return PROFILE_ID;
    }

    @Override
    public String displayName() {
        return profile.getDisplayName();
    }

    // ── Lifecycle ────────────────────────────────────────────────────────────

    @Override
    public void start() throws AgentException {
        resolvedBinaryPath = resolveBinary();
        CodexCredentials creds = CodexCredentials.read();
        if (!creds.isLoggedIn()) {
            throw new AgentException(
                "Not authenticated with Codex. Run 'codex login' in a terminal, then retry.",
                null, false);
        }
        launchAppServer();
        LOG.info("CodexAppServerClient started" +
            (creds.getDisplayName() != null ? " (account: " + creds.getDisplayName() + ")" : ""));
    }

    @Override
    public void stop() {
        connected = false;
        pendingRequests.forEach((id, f) -> f.completeExceptionally(new AgentException("Client stopped", null, false)));
        pendingRequests.clear();
        CompletableFuture<String> turn = activeTurnResult;
        if (turn != null) turn.completeExceptionally(new AgentException("Client stopped", null, false));
        activeTurnResult = null;
        activeTurnCallback = null;
        closeQuietly(stdin);
        stdin = null;
        if (appServerProcess != null) {
            appServerProcess.destroyForcibly();
            appServerProcess = null;
        }
        sessionToThreadId.clear();
        sessionCancelled.clear();
    }

    @Override
    public boolean isConnected() {
        return connected;
    }

    @Override
    public boolean isHealthy() {
        return isConnected() && appServerProcess != null && appServerProcess.isAlive();
    }

    @Override
    public @Nullable String checkAuthentication() {
        return checkAuthenticationPreStart();
    }

    @Override
    public @Nullable String checkAuthenticationPreStart() {
        return checkCredentials();
    }

    /**
     * Static credential check — usable without instantiating the client.
     * Reads {@code ~/.codex/auth.json} to determine login state.
     */
    @Nullable
    public static String checkCredentials() {
        CodexCredentials creds = CodexCredentials.read();
        return creds.isLoggedIn() ? null
            : "Not authenticated with Codex. Run 'codex login' in a terminal, then retry.";
    }

    // ── Sessions ─────────────────────────────────────────────────────────────

    @Override
    public @NotNull String createSession(@Nullable String cwd) {
        String sessionId = UUID.randomUUID().toString();
        sessionCancelled.put(sessionId, new AtomicBoolean(false));
        if (cwd != null) sessionCwd.put(sessionId, cwd);
        return sessionId;
    }

    @Override
    public void cancelSession(@NotNull String sessionId) {
        AtomicBoolean flag = sessionCancelled.get(sessionId);
        if (flag != null) flag.set(true);
        // Interrupt the active turn if it belongs to this session
        String turnId = activeTurnId;
        if (turnId != null && sessionId.equals(activeTurnSessionId)) {
            sendInterrupt(turnId);
        }
    }

    // ── Session options ──────────────────────────────────────────────────────

    @Override
    public @NotNull List<SessionOption> listSessionOptions() {
        return List.of(EFFORT_OPTION);
    }

    @Override
    public void setSessionOption(@NotNull String sessionId, @NotNull String key, @NotNull String value) {
        sessionOptions.computeIfAbsent(sessionId, k -> new ConcurrentHashMap<>()).put(key, value);
    }

    @Override
    public void setModel(@NotNull String sessionId, @NotNull String modelId) {
        sessionModels.put(sessionId, modelId);
    }

    @Override
    public List<Model> getAvailableModels() {
        return KNOWN_MODELS;
    }

    @Override
    public ModelDisplayMode modelDisplayMode() {
        return ModelDisplayMode.NAME;
    }

    @Override
    @NotNull
    public List<ProjectFilesSettings.FileEntry> getDefaultProjectFiles() {
        return List.of(new ProjectFilesSettings.FileEntry(AGENTS_MD, AGENTS_MD, false, "Codex"));
    }

    // ── Prompts ──────────────────────────────────────────────────────────────

    @Override
    public @NotNull PromptResponse sendPrompt(@NotNull PromptRequest request,
                                              @NotNull Consumer<SessionUpdate> onUpdate) throws Exception {
        ensureConnected();
        String sessionId = request.sessionId();
        AtomicBoolean cancelled = sessionCancelled.computeIfAbsent(sessionId, k -> new AtomicBoolean(false));
        cancelled.set(false);

        String model = resolveModel(sessionId, request.modelId());
        String rawPrompt = extractPromptText(request.prompt());
        String fullPrompt = buildFullPrompt(rawPrompt, !sessionToThreadId.containsKey(sessionId));

        // Ensure a Codex thread exists for this session
        String threadId = sessionToThreadId.get(sessionId);
        if (threadId == null) {
            threadId = startThread(sessionId, model);
        }

        // Set up the active turn future
        CompletableFuture<String> turnResult = new CompletableFuture<>();
        activeTurnResult = turnResult;
        activeTurnCallback = onUpdate;
        activeTurnSessionId = sessionId;

        // Start the turn
        String turnId = startTurn(threadId, fullPrompt, model, sessionId);
        activeTurnId = turnId;

        // Wait for turn completion
        try {
            String stopReason = turnResult.get(300, TimeUnit.SECONDS);
            return new PromptResponse(stopReason, null);
        } catch (java.util.concurrent.TimeoutException e) {
            sendInterrupt(turnId);
            throw new AgentException("Codex turn timed out after 300 seconds", e, true);
        } catch (java.util.concurrent.ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof AgentException ae) throw ae;
            throw new AgentException("Codex turn failed: " + e.getMessage(), e, true);
        } finally {
            activeTurnId = null;
            activeTurnResult = null;
            activeTurnCallback = null;
            activeTurnSessionId = null;
        }
    }

    // ── App-server lifecycle ──────────────────────────────────────────────────

    private void launchAppServer() throws AgentException {
        List<String> cmd = buildServerCommand();
        try {
            LOG.info("Starting codex app-server: " + String.join(" ", cmd));
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.environment().putAll(
                com.github.catatafishen.ideagentforcopilot.settings.ShellEnvironment.getEnvironment());
            if (project != null && project.getBasePath() != null) {
                pb.directory(new File(project.getBasePath()));
            }
            pb.redirectErrorStream(false);
            appServerProcess = pb.start();
            stdin = appServerProcess.getOutputStream();

            // Drain stderr on a daemon thread
            startStderrDrainer(appServerProcess);

            // Start reader thread
            startReaderThread(appServerProcess);

            // Perform JSON-RPC initialize handshake
            initialize();
            connected = true;
            LOG.info("codex app-server ready");
        } catch (IOException e) {
            throw new AgentException("Failed to start codex app-server: " + e.getMessage(), e, true);
        }
    }

    @NotNull
    private List<String> buildServerCommand() {
        List<String> cmd = new ArrayList<>();
        cmd.add(resolvedBinaryPath);
        cmd.add("app-server");

        // Disable native shell execution tools; model must use MCP tools instead
        cmd.add("--config");
        cmd.add("features.shell_tool=false");
        cmd.add("--config");
        cmd.add("features.unified_exec=false");

        // Inject MCP server via --config if mcpPort is available
        if (mcpPort > 0) {
            cmd.add("--config");
            cmd.add("mcp_servers.agentbridge.url=http://localhost:" + mcpPort + "/mcp");
        }

        return cmd;
    }

    // ── JSON-RPC initialize handshake ────────────────────────────────────────

    private void initialize() throws AgentException {
        JsonObject clientInfo = new JsonObject();
        clientInfo.addProperty("name", "intellij-copilot-plugin");
        clientInfo.addProperty("title", "IntelliJ AgentBridge");
        clientInfo.addProperty("version", "1.0.0");
        JsonObject capabilities = new JsonObject();
        capabilities.addProperty("experimentalApi", true);

        JsonObject params = new JsonObject();
        params.add("clientInfo", clientInfo);
        params.add("capabilities", capabilities);

        try {
            sendRequest("initialize", params).get(15, TimeUnit.SECONDS);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new AgentException("codex app-server initialize interrupted", ie, true);
        } catch (Exception e) {
            throw new AgentException("codex app-server initialize failed: " + e.getMessage(), e, true);
        }

        // Send initialized notification (no ID, no response expected)
        sendNotification("initialized", new JsonObject());
    }

    // ── Thread management ─────────────────────────────────────────────────────

    /**
     * Creates a new Codex thread for a plugin session and returns its threadId.
     */
    @NotNull
    private String startThread(@NotNull String sessionId, @NotNull String model) throws AgentException {
        String cwd = sessionCwd.getOrDefault(sessionId,
            project != null && project.getBasePath() != null ? project.getBasePath() : ".");

        JsonObject params = new JsonObject();
        params.addProperty("model", model);
        params.addProperty("cwd", cwd);
        // on-request: server sends approval notifications we can decline
        params.addProperty("approvalPolicy", "on-request");

        try {
            JsonObject result = sendRequest("thread/start", params).get(15, TimeUnit.SECONDS);
            JsonObject thread = result.getAsJsonObject(F_THREAD);
            if (thread == null || !thread.has(F_ID)) {
                throw new AgentException("thread/start response missing thread.id", null, true);
            }
            String threadId = thread.get(F_ID).getAsString();
            sessionToThreadId.put(sessionId, threadId);
            LOG.info("Created Codex thread " + threadId + " for session " + sessionId);
            return threadId;
        } catch (AgentException ae) {
            throw ae;
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new AgentException("thread/start interrupted", ie, true);
        } catch (Exception e) {
            throw new AgentException("thread/start failed: " + e.getMessage(), e, true);
        }
    }

    /**
     * Starts a turn within a thread and returns the turnId.
     */
    @NotNull
    private String startTurn(@NotNull String threadId,
                             @NotNull String prompt,
                             @NotNull String model,
                             @NotNull String sessionId) throws AgentException {
        JsonObject textItem = new JsonObject();
        textItem.addProperty(F_TYPE, "text");
        textItem.addProperty(F_TEXT, prompt);
        JsonArray input = new JsonArray();
        input.add(textItem);

        JsonObject params = new JsonObject();
        params.addProperty("threadId", threadId);
        params.add("input", input);
        params.addProperty("model", model);

        String effort = getSessionOption(sessionId, EFFORT_OPTION.key());
        if (effort != null && !effort.isBlank()) {
            params.addProperty(EFFORT_OPTION.key(), effort);
        }

        try {
            JsonObject result = sendRequest("turn/start", params).get(15, TimeUnit.SECONDS);
            JsonObject turn = result.getAsJsonObject(F_TURN);
            if (turn == null || !turn.has(F_ID)) {
                throw new AgentException("turn/start response missing turn.id", null, true);
            }
            String turnId = turn.get(F_ID).getAsString();
            LOG.info("Started Codex turn " + turnId + " in thread " + threadId);
            return turnId;
        } catch (AgentException ae) {
            throw ae;
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new AgentException("turn/start interrupted", ie, true);
        } catch (Exception e) {
            throw new AgentException("turn/start failed: " + e.getMessage(), e, true);
        }
    }

    private void sendInterrupt(@NotNull String turnId) {
        JsonObject params = new JsonObject();
        params.addProperty("turnId", turnId);
        sendNotification("turn/interrupt", params);
    }

    // ── JSON-RPC messaging ────────────────────────────────────────────────────

    /**
     * Sends a JSON-RPC request and returns a CompletableFuture resolved by the reader thread.
     */
    @NotNull
    private CompletableFuture<JsonObject> sendRequest(@NotNull String method, @NotNull JsonObject params) {
        int id = nextId.getAndIncrement();
        CompletableFuture<JsonObject> future = new CompletableFuture<>();
        pendingRequests.put(id, future);

        JsonObject msg = new JsonObject();
        msg.addProperty(F_ID, id);
        msg.addProperty(F_METHOD, method);
        msg.add(F_PARAMS, params);

        if (!writeMessage(msg)) {
            pendingRequests.remove(id);
            future.completeExceptionally(new AgentException("Failed to write to codex app-server", null, true));
        }
        return future;
    }

    /**
     * Sends a JSON-RPC notification (no ID, no response expected).
     */
    private void sendNotification(@NotNull String method, @NotNull JsonObject params) {
        JsonObject msg = new JsonObject();
        msg.addProperty(F_METHOD, method);
        msg.add(F_PARAMS, params);
        writeMessage(msg);
    }

    /**
     * Sends a JSON-RPC response to a server-initiated request.
     */
    private void sendResponse(@NotNull JsonElement id, @NotNull JsonElement result) {
        JsonObject msg = new JsonObject();
        msg.add(F_ID, id);
        msg.add(F_RESULT, result);
        writeMessage(msg);
    }

    private boolean writeMessage(@NotNull JsonObject msg) {
        OutputStream out = stdin;
        if (out == null) return false;
        try {
            synchronized (out) {
                out.write(msg.toString().getBytes(StandardCharsets.UTF_8));
                out.write('\n');
                out.flush();
            }
            return true;
        } catch (IOException e) {
            LOG.debug("codex app-server: write failed: " + e.getMessage());
            return false;
        }
    }

    // ── Reader thread ─────────────────────────────────────────────────────────

    private void startReaderThread(@NotNull Process proc) {
        Thread reader = new Thread(() -> {
            try (BufferedReader br = new BufferedReader(
                new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty()) continue;
                    processLine(line);
                }
            } catch (IOException e) {
                if (connected) LOG.warn("codex app-server reader ended: " + e.getMessage());
            } finally {
                connected = false;
                CompletableFuture<String> turn = activeTurnResult;
                if (turn != null && !turn.isDone()) {
                    turn.completeExceptionally(new AgentException("codex app-server disconnected", null, true));
                }
                pendingRequests.forEach((id, f) ->
                    f.completeExceptionally(new AgentException("codex app-server disconnected", null, true)));
                pendingRequests.clear();
            }
        }, "codex-app-server-reader");
        reader.setDaemon(true);
        reader.start();
    }

    /**
     * Parses a single JSONL line from the app-server and dispatches it.
     */
    private void processLine(@NotNull String line) {
        try {
            JsonObject msg = JsonParser.parseString(line).getAsJsonObject();
            dispatchMessage(msg);
        } catch (RuntimeException e) {
            LOG.debug("codex app-server: could not parse line: " + line, e);
        }
    }

    /**
     * Routes an incoming JSONL message to the appropriate handler.
     *
     * <p>Three categories:
     * <ol>
     *   <li>Response to our request: has numeric {@code id} and {@code result}/{@code error} fields.</li>
     *   <li>Notification from server: has {@code method} but no {@code id}.</li>
     *   <li>Server-initiated request: has both {@code method} and {@code id} (string) — e.g. approval requests.</li>
     * </ol>
     */
    private void dispatchMessage(@NotNull JsonObject msg) {
        boolean hasId = msg.has(F_ID);
        boolean hasMethod = msg.has(F_METHOD);
        boolean hasResult = msg.has(F_RESULT) || msg.has(F_ERROR);

        if (hasId && hasResult && !hasMethod) {
            handleResponse(msg);
        } else if (hasMethod && hasId) {
            // Server-initiated request (approval or tool call) — has an id we must echo back
            handleServerRequest(msg);
        } else if (hasMethod) {
            // Notification from server
            handleNotification(msg);
        }
    }

    private void handleResponse(@NotNull JsonObject msg) {
        JsonElement idEl = msg.get(F_ID);
        if (idEl.isJsonPrimitive() && idEl.getAsJsonPrimitive().isNumber()) {
            int id = idEl.getAsInt();
            CompletableFuture<JsonObject> f = pendingRequests.remove(id);
            if (f != null) {
                completeResponseFuture(f, msg);
            }
        }
    }

    /**
     * Resolves a pending-request future from the given JSON-RPC result/error message.
     */
    private void completeResponseFuture(@NotNull CompletableFuture<JsonObject> f,
                                        @NotNull JsonObject msg) {
        if (msg.has(F_ERROR)) {
            JsonObject err = msg.getAsJsonObject(F_ERROR);
            String errMsg = err.has(F_MESSAGE) ? err.get(F_MESSAGE).getAsString() : err.toString();
            f.completeExceptionally(new AgentException("JSON-RPC error: " + errMsg, null, false));
        } else {
            JsonElement result = msg.get(F_RESULT);
            f.complete(result.isJsonObject() ? result.getAsJsonObject() : new JsonObject());
        }
    }

    // ── Server-initiated request handling ────────────────────────────────────

    /**
     * Handles server-initiated requests (approval prompts, dynamic tool calls).
     * Always declines native tool approvals; tool calls are routed to our MCP server
     * so there is no need to serve {@code item/tool/call} from this client.
     */
    private void handleServerRequest(@NotNull JsonObject msg) {
        String method = msg.get(F_METHOD).getAsString();
        JsonElement id = msg.get(F_ID);
        JsonObject params = msg.has(F_PARAMS) ? msg.getAsJsonObject(F_PARAMS) : new JsonObject();

        LOG.info("codex app-server request: " + method);

        switch (method) {
            case "item/commandExecution/requestApproval", "item/fileChange/requestApproval" -> {
                // Decline all native file/command approvals — routing is via MCP instead
                LOG.info("Declining native tool approval: " + method);
                sendResponse(id, new JsonPrimitive("decline"));
                emitToolDeclinedBanner(method, params);
            }
            case "item/tool/call" -> {
                // Dynamic client-side tool call — we don't register client-side tools,
                // so decline gracefully. Real tool calls come through MCP.
                String toolName = params.has(F_TOOL) ? params.get(F_TOOL).getAsString() : "unknown";
                LOG.info("Declining client-side tool call: " + toolName);
                JsonObject error = new JsonObject();
                error.addProperty(F_TYPE, F_ERROR);
                error.addProperty(F_TEXT, "Tool '" + toolName + "' is not available in this context.");
                JsonArray content = new JsonArray();
                content.add(error);
                JsonObject resp = new JsonObject();
                resp.add("content", content);
                sendResponse(id, resp);
            }
            default -> {
                // Unknown server request — send a generic error response
                LOG.debug("Unknown server request method: " + method);
                JsonObject error = new JsonObject();
                error.addProperty("code", -32601);
                error.addProperty(F_MESSAGE, "Method not found: " + method);
                JsonObject errResp = new JsonObject();
                errResp.add(F_ID, id);
                errResp.add(F_ERROR, error);
                writeMessage(errResp);
            }
        }
    }

    // ── Notification handling ─────────────────────────────────────────────────

    private void handleNotification(@NotNull JsonObject msg) {
        String method = msg.get(F_METHOD).getAsString();
        JsonObject params = msg.has(F_PARAMS) ? msg.getAsJsonObject(F_PARAMS) : new JsonObject();

        switch (method) {
            case "item/agentMessage/delta" -> handleTextDelta(params);
            case "item/started" -> handleItemStarted(params);
            case "item/completed" -> handleItemCompleted(params);
            case "turn/completed" -> handleTurnCompleted(params);
            case "turn/failed" -> handleTurnFailed(params);
            // turn/started, thread/started, etc. — no action needed
            default -> LOG.debug("codex notification: " + method);
        }
    }

    private void handleTextDelta(@NotNull JsonObject params) {
        Consumer<SessionUpdate> cb = activeTurnCallback;
        if (cb == null) return;
        JsonElement delta = params.get(F_DELTA);
        if (delta == null) return;
        String text;
        if (delta.isJsonPrimitive()) {
            text = delta.getAsString();
        } else if (delta.isJsonObject() && delta.getAsJsonObject().has(F_TEXT)) {
            text = delta.getAsJsonObject().get(F_TEXT).getAsString();
        } else {
            return;
        }
        if (!text.isEmpty()) {
            cb.accept(new SessionUpdate.AgentMessageChunk(List.of(new ContentBlock.Text(text))));
        }
    }

    private void handleItemStarted(@NotNull JsonObject params) {
        Consumer<SessionUpdate> cb = activeTurnCallback;
        if (cb == null || !params.has(F_ITEM)) return;
        JsonObject item = params.getAsJsonObject(F_ITEM);
        String type = item.has(F_TYPE) ? item.get(F_TYPE).getAsString() : "";
        if ("mcp_tool_call".equals(type)) {
            emitMcpToolCallStart(item, cb);
        }
    }

    private void handleItemCompleted(@NotNull JsonObject params) {
        Consumer<SessionUpdate> cb = activeTurnCallback;
        if (!params.has(F_ITEM)) return;
        JsonObject item = params.getAsJsonObject(F_ITEM);
        String type = item.has(F_TYPE) ? item.get(F_TYPE).getAsString() : "";

        switch (type) {
            case "mcp_tool_call" -> {
                if (cb != null) emitMcpToolCallEnd(item, cb);
            }
            case F_REASONING -> {
                // Emit reasoning as a thought block
                if (cb != null && item.has(F_REASONING)) {
                    String thinking = item.get(F_REASONING).getAsString();
                    if (!thinking.isEmpty()) {
                        cb.accept(new SessionUpdate.AgentThoughtChunk(List.of(new ContentBlock.Text(thinking))));
                    }
                }
            }
            case "command_execution" -> {
                // Native command attempted — emit as a tool call (already declined, just for UI)
                if (cb != null) emitNativeCommandItem(item, cb);
            }
            default -> { /* no other item types require handling */ }
        }
    }

    private void handleTurnCompleted(@NotNull JsonObject params) {
        // Emit usage stats if available
        Consumer<SessionUpdate> cb = activeTurnCallback;
        if (cb != null && params.has(F_TURN)) {
            JsonObject turn = params.getAsJsonObject(F_TURN);
            if (turn.has(F_USAGE)) {
                emitUsageStats(turn.getAsJsonObject(F_USAGE), cb);
            }
        }
        String status = params.has(F_TURN) && params.getAsJsonObject(F_TURN).has(F_STATUS)
            ? params.getAsJsonObject(F_TURN).get(F_STATUS).getAsString()
            : "completed";
        CompletableFuture<String> f = activeTurnResult;
        if (f != null) f.complete("interrupted".equals(status) ? "cancelled" : "end_turn");
    }

    private void handleTurnFailed(@NotNull JsonObject params) {
        String errorMsg = "Codex turn failed";
        if (params.has(F_TURN)) {
            JsonObject turn = params.getAsJsonObject(F_TURN);
            if (turn.has(F_ERROR)) {
                JsonObject err = turn.getAsJsonObject(F_ERROR);
                errorMsg = err.has(F_MESSAGE) ? err.get(F_MESSAGE).getAsString() : err.toString();
            }
        }
        Consumer<SessionUpdate> cb = activeTurnCallback;
        if (cb != null) {
            cb.accept(new SessionUpdate.AgentMessageChunk(
                List.of(new ContentBlock.Text("\n[Error: " + errorMsg + "]"))));
        }
        CompletableFuture<String> f = activeTurnResult;
        if (f != null) f.complete(F_ERROR);
    }

    // ── Tool call emission ────────────────────────────────────────────────────

    private void emitMcpToolCallStart(@NotNull JsonObject item, @NotNull Consumer<SessionUpdate> cb) {
        String id = item.has(F_ID) ? item.get(F_ID).getAsString() : UUID.randomUUID().toString();
        // MCP tool call fields: server, tool, arguments
        String rawTool = item.has(F_TOOL) ? item.get(F_TOOL).getAsString() : "tool";
        // Strip "agentbridge_" prefix if the server namespaces tool names
        String toolName = rawTool.startsWith("agentbridge_") ? rawTool.substring("agentbridge_".length()) : rawTool;
        JsonObject args = item.has(F_ARGUMENTS) && item.get(F_ARGUMENTS).isJsonObject()
            ? item.getAsJsonObject(F_ARGUMENTS) : new JsonObject();

        SessionUpdate.ToolKind kind = SessionUpdate.ToolKind.OTHER;
        if (registry != null) {
            ToolDefinition def = registry.findById(toolName);
            if (def != null) kind = SessionUpdate.ToolKind.fromCategory(def.category());
        }
        cb.accept(new SessionUpdate.ToolCall(id, toolName, kind, args.toString(), null, null, null, null, null));
    }

    private void emitMcpToolCallEnd(@NotNull JsonObject item, @NotNull Consumer<SessionUpdate> cb) {
        String id = item.has(F_ID) ? item.get(F_ID).getAsString() : "";
        boolean success = !F_ERROR.equals(item.has(F_STATUS) ? item.get(F_STATUS).getAsString() : "");
        String content = "";
        if (item.has("output")) {
            JsonElement out = item.get("output");
            content = out.isJsonPrimitive() ? out.getAsString() : out.toString();
        }
        SessionUpdate.ToolCallStatus status = success
            ? SessionUpdate.ToolCallStatus.COMPLETED
            : SessionUpdate.ToolCallStatus.FAILED;
        cb.accept(new SessionUpdate.ToolCallUpdate(id, status, success ? content : null, success ? null : content, null));
    }

    private void emitNativeCommandItem(@NotNull JsonObject item, @NotNull Consumer<SessionUpdate> cb) {
        String id = item.has(F_ID) ? item.get(F_ID).getAsString() : UUID.randomUUID().toString();
        String cmd = item.has(F_COMMAND) ? item.get(F_COMMAND).getAsString() : "shell";
        cb.accept(new SessionUpdate.ToolCall(id, "shell_command", SessionUpdate.ToolKind.OTHER,
            "{\"command\":\"" + cmd.replace("\"", "\\\"") + "\"}", null, null, null, null, null));
        cb.accept(new SessionUpdate.ToolCallUpdate(id, SessionUpdate.ToolCallStatus.FAILED,
            null, "Declined: native shell execution is not permitted. Use MCP tools instead.", null));
    }

    private void emitToolDeclinedBanner(@NotNull String method, @NotNull JsonObject params) {
        // Surface a soft warning if the model is trying to use native tools
        Consumer<SessionUpdate> cb = activeTurnCallback;
        if (cb == null) return;
        String detail;
        if (params.has(F_COMMAND)) {
            detail = params.get(F_COMMAND).toString();
        } else if (params.has("reason")) {
            detail = params.get("reason").getAsString();
        } else {
            detail = method;
        }
        cb.accept(new SessionUpdate.Banner(
            "Native tool declined: " + detail + ". Use MCP tools instead.",
            SessionUpdate.BannerLevel.WARNING,
            SessionUpdate.ClearOn.NEXT_SUCCESS));
    }

    private void emitUsageStats(@NotNull JsonObject usage, @NotNull Consumer<SessionUpdate> cb) {
        int inputTokens = safeGetInt(usage, F_INPUT_TOKENS);
        int outputTokens = safeGetInt(usage, F_OUTPUT_TOKENS);
        if (inputTokens == 0 && outputTokens == 0) return;
        cb.accept(new SessionUpdate.TurnUsage(inputTokens, outputTokens, 0.0));
    }

    private static int safeGetInt(@NotNull JsonObject obj, @NotNull String field) {
        if (!obj.has(field) || obj.get(field).isJsonNull()) return 0;
        return obj.get(field).getAsInt();
    }

    // ── Prompt building ───────────────────────────────────────────────────────

    @NotNull
    private String buildFullPrompt(@NotNull String prompt, boolean isNewSession) {
        if (!isNewSession) return prompt;
        StringBuilder sb = new StringBuilder();
        String instructions = config.getSessionInstructions();
        if (instructions != null && !instructions.isEmpty()) {
            sb.append("<system-reminder>\n").append(instructions).append("\n</system-reminder>\n\n");
        }
        sb.append(prompt);
        return sb.toString();
    }

    @NotNull
    private static String extractPromptText(@NotNull List<ContentBlock> blocks) {
        StringBuilder sb = new StringBuilder();
        for (ContentBlock block : blocks) {
            if (block instanceof ContentBlock.Text t) {
                sb.append(t.text());
            } else if (block instanceof ContentBlock.Resource r) {
                ContentBlock.ResourceLink rl = r.resource();
                if (rl.text() != null && !rl.text().isEmpty()) {
                    sb.append("File: ").append(rl.uri()).append("\n```\n").append(rl.text()).append("\n```\n\n");
                }
            }
        }
        return sb.toString();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    @NotNull
    private String resolveModel(@NotNull String sessionId, @Nullable String requestModel) {
        if (requestModel != null && !requestModel.isEmpty()) return requestModel;
        String stored = sessionModels.get(sessionId);
        return (stored != null && !stored.isEmpty()) ? stored : DEFAULT_MODEL;
    }

    @Nullable
    private String getSessionOption(@NotNull String sessionId, @NotNull String key) {
        Map<String, String> opts = sessionOptions.get(sessionId);
        return opts != null ? opts.get(key) : null;
    }

    private void ensureConnected() throws AgentException {
        if (!connected) throw new AgentException("Codex app-server not connected", null, false);
    }

    private static void closeQuietly(@Nullable OutputStream stream) {
        if (stream == null) return;
        try {
            stream.close();
        } catch (IOException ignored) {
            // Intentionally ignored — cleanup path, nothing to recover
        }
    }

    private static void startStderrDrainer(@NotNull Process proc) {
        Thread t = new Thread(() -> {
            try (BufferedReader err = new BufferedReader(
                new InputStreamReader(proc.getErrorStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = err.readLine()) != null) {
                    LOG.info("codex app-server stderr: " + line);
                }
            } catch (IOException ignored) {
                // Intentionally ignored — stderr drainer cleanup, nothing to recover
            }
        }, "codex-app-server-stderr");
        t.setDaemon(true);
        t.start();
    }

    // ── Binary resolution ─────────────────────────────────────────────────────

    @NotNull
    private String resolveBinary() throws AgentException {
        String custom = profile.getCustomBinaryPath();
        if (!custom.isEmpty()) {
            if (Files.isExecutable(Path.of(custom))) return custom;
            throw new AgentException("Codex binary not found at: " + custom, null, false);
        }
        for (String name : candidateNames()) {
            // Use BinaryDetector which spawns a login shell — finds npm global installs,
            // nvm-managed Node, etc. that are not on the JVM's inherited PATH.
            String found = com.github.catatafishen.ideagentforcopilot.settings.BinaryDetector.findBinaryPath(name);
            if (found != null) return found;
        }
        throw new AgentException(
            "Codex CLI not found. Install with: npm install -g @openai/codex, then run 'codex login'.",
            null, false);
    }

    @NotNull
    private List<String> candidateNames() {
        List<String> names = new ArrayList<>();
        String primary = profile.getBinaryName();
        if (!primary.isEmpty()) names.add(primary);
        names.addAll(profile.getAlternateNames());
        if (!names.contains("codex")) names.add("codex");
        return names;
    }
}
