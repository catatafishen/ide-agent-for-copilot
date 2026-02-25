package com.github.copilot.intellij.psi;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.ComponentManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * Lightweight HTTP bridge exposing IntelliJ PSI/AST analysis to the MCP server.
 * The MCP server (running as a separate process) delegates tool calls here for
 * accurate code intelligence instead of regex-based scanning.
 * <p>
 * Architecture: Copilot Agent → MCP Server (stdio) → PSI Bridge (HTTP) → IntelliJ PSI
 */
@SuppressWarnings("java:S112") // generic exceptions are caught at the JSON-RPC dispatch level
@Service(Service.Level.PROJECT)
public final class PsiBridgeService implements Disposable {
    private static final Logger LOG = Logger.getInstance(PsiBridgeService.class);
    private static final Gson GSON = new GsonBuilder().create();

    // HTTP Constants
    private static final String CONTENT_TYPE_HEADER = "Content-Type";
    private static final String APPLICATION_JSON = "application/json";

    private final Project project;
    private final RunConfigurationService runConfigService;
    private final Map<String, ToolHandler> toolRegistry = new LinkedHashMap<>();
    private final FileTools fileTools;
    private HttpServer httpServer;
    private int port;

    public PsiBridgeService(@NotNull Project project) {
        this.project = project;
        GitToolHandler gitToolHandler = new GitToolHandler(project);

        // Initialize handler groups
        RefactoringTools refactoringTools = new RefactoringTools(project);
        this.runConfigService = new RunConfigurationService(project, refactoringTools::resolveClass);
        this.fileTools = new FileTools(project);

        // Register all tools from handler groups
        for (AbstractToolHandler handler : List.of(
            new CodeNavigationTools(project),
            fileTools,
            new CodeQualityTools(project),
            refactoringTools,
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
    }

    @SuppressWarnings("java:S1905") // Cast needed: IDE doesn't resolve Project→ComponentManager supertype
    public static PsiBridgeService getInstance(@NotNull Project project) {
        return ((ComponentManager) project).getService(PsiBridgeService.class);
    }

    @SuppressWarnings("unused") // Public API - may be used by external integrations
    public int getPort() {
        return port;
    }

    /**
     * Runs deferred OptimizeImportsProcessor on files modified by partial edits.
     */
    public void optimizePendingImports() {
        fileTools.flushPendingImportOptimization();
    }

    public synchronized void start() {
        if (httpServer != null) return;
        try {
            httpServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            httpServer.createContext("/tools/call", this::handleToolCall);
            httpServer.createContext("/tools/list", this::handleToolsList);
            httpServer.createContext("/health", this::handleHealth);
            httpServer.setExecutor(Executors.newFixedThreadPool(8));
            httpServer.start();
            port = httpServer.getAddress().getPort();
            writeBridgeFile();
            LOG.info("PSI Bridge started on port " + port + " for project: " + project.getBasePath());
        } catch (Exception e) {
            LOG.error("Failed to start PSI Bridge", e);
        }
    }

    private void writeBridgeFile() {
        if (ApplicationManager.getApplication().isUnitTestMode()) {
            LOG.info("Skipping bridge file write in unit test mode");
            return;
        }
        try {
            Path bridgeDir = Path.of(System.getProperty("user.home"), ".copilot");
            Files.createDirectories(bridgeDir);
            Path bridgeFile = bridgeDir.resolve("psi-bridge.json");
            JsonObject info = new JsonObject();
            info.addProperty("port", port);
            info.addProperty("projectPath", project.getBasePath());
            Files.writeString(bridgeFile, GSON.toJson(info));
        } catch (IOException e) {
            LOG.error("Failed to write bridge file", e);
        }
    }

    private void handleHealth(HttpExchange exchange) throws IOException {
        boolean indexing = com.intellij.openapi.project.DumbService.getInstance(project).isDumb();
        JsonObject health = new JsonObject();
        health.addProperty("status", "ok");
        health.addProperty("indexing", indexing);
        byte[] resp = GSON.toJson(health).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set(CONTENT_TYPE_HEADER, APPLICATION_JSON);
        exchange.sendResponseHeaders(200, resp.length);
        exchange.getResponseBody().write(resp);
        exchange.getResponseBody().close();
    }

    private void handleToolsList(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            return;
        }

        JsonArray tools = new JsonArray();
        for (String name : toolRegistry.keySet()) {
            JsonObject tool = new JsonObject();
            tool.addProperty("name", name);
            tools.add(tool);
        }

        JsonObject response = new JsonObject();
        response.add("tools", tools);
        byte[] bytes = GSON.toJson(response).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set(CONTENT_TYPE_HEADER, APPLICATION_JSON);
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.getResponseBody().close();
    }

    private void handleToolCall(HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            return;
        }

        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        JsonObject request = JsonParser.parseString(body).getAsJsonObject();
        String toolName = request.get("name").getAsString();
        JsonObject arguments = request.has("arguments")
            ? request.getAsJsonObject("arguments") : new JsonObject();

        LOG.info("PSI Bridge tool call: " + toolName + " args=" + arguments);

        String result;
        try {
            ToolHandler handler = toolRegistry.get(toolName);
            result = handler != null ? handler.handle(arguments) : "Unknown tool: " + toolName;
        } catch (com.intellij.openapi.application.ex.ApplicationUtil.CannotRunReadActionException e) {
            result = "Error: IDE is busy, please retry. " + e.getMessage();
        } catch (Exception e) {
            LOG.warn("PSI tool error: " + toolName, e);
            result = "Error: " + e.getMessage();
        }

        // Piggyback highlights after successful write operations
        if (isSuccessfulWrite(toolName, result) && arguments.has("path")) {
            LOG.info("Auto-highlights: piggybacking on write to " + arguments.get("path").getAsString());
            result = appendAutoHighlights(result, arguments.get("path").getAsString());
        }

        JsonObject response = new JsonObject();
        response.addProperty("result", result);
        byte[] bytes = GSON.toJson(response).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set(CONTENT_TYPE_HEADER, APPLICATION_JSON);
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.getResponseBody().close();
    }

    private static boolean isSuccessfulWrite(String toolName, String result) {
        return ("write_file".equals(toolName) || "intellij_write_file".equals(toolName))
            && (result.startsWith("Edited:") || result.startsWith("Written:"));
    }

    /**
     * Auto-run get_highlights on the edited file and append results to the write response.
     * Waits for the DaemonCodeAnalyzer to complete a pass after the edit, then collects highlights.
     */
    private String appendAutoHighlights(String writeResult, String path) {
        try {
            ToolHandler highlightHandler = toolRegistry.get("get_highlights");
            if (highlightHandler == null) return writeResult;

            // Wait for daemon to finish re-analyzing after the edit
            waitForDaemonPass();

            JsonObject highlightArgs = new JsonObject();
            highlightArgs.addProperty("path", path);
            String highlights = highlightHandler.handle(highlightArgs);
            LOG.info("Auto-highlights: appended " + highlights.split("\n").length + " lines for " + path);

            return writeResult + "\n\n--- Highlights (auto) ---\n" + highlights;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return writeResult;
        } catch (Exception e) {
            LOG.info("Auto-highlights after write failed: " + e.getMessage());
            return writeResult;
        }
    }

    /**
     * Wait for the DaemonCodeAnalyzer to finish its current pass.
     * Polls isRunning() with short intervals instead of subscribing to MessageBus
     * (MessageBus/MessageBusConnection have IDE resolution issues with Kotlin-compiled platform APIs).
     */
    private void waitForDaemonPass() throws InterruptedException {
        var analyzer = com.intellij.codeInsight.daemon.DaemonCodeAnalyzer.getInstance(project);
        long deadline = System.currentTimeMillis() + 3000;
        // Wait for daemon to start (it may not have begun re-analyzing yet)
        Thread.sleep(200);
        // Poll until daemon finishes or timeout
        while (analyzer.isRunning() && System.currentTimeMillis() < deadline) {
            //noinspection BusyWait
            Thread.sleep(100);
        }
        if (System.currentTimeMillis() < deadline) {
            LOG.info("Auto-highlights: daemon pass completed, collecting highlights");
        } else {
            LOG.info("Auto-highlights: daemon pass wait timed out (3s), using cached highlights");
        }
    }

    public void dispose() {
        if (httpServer != null) {
            httpServer.stop(0);
            httpServer = null;
            LOG.info("PSI Bridge stopped");
        }
        if (ApplicationManager.getApplication().isUnitTestMode()) return;
        try {
            Path bridgeFile = Path.of(System.getProperty("user.home"), ".copilot", "psi-bridge.json");
            Files.deleteIfExists(bridgeFile);
        } catch (IOException e) {
            LOG.warn("Failed to clean up bridge file", e);
        }
    }
}
