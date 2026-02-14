package com.github.copilot.intellij.psi;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.execution.ExecutionManager;
import com.intellij.execution.RunContentExecutor;
import com.intellij.execution.RunManager;
import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.execution.configurations.RunConfiguration;
import com.intellij.execution.executors.DefaultRunExecutor;
import com.intellij.execution.process.OSProcessHandler;
import com.intellij.execution.process.ProcessEvent;
import com.intellij.execution.process.ProcessListener;
import com.intellij.execution.runners.ExecutionEnvironmentBuilder;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.projectRoots.Sdk;
import com.intellij.openapi.roots.ModuleRootManager;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.openapi.roots.ProjectRootManager;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.psi.PsiNamedElement;
import com.intellij.psi.PsiRecursiveElementWalkingVisitor;
import com.intellij.psi.PsiReference;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.PsiSearchHelper;
import com.intellij.psi.search.UsageSearchContext;
import com.intellij.psi.search.searches.ReferencesSearch;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.jetbrains.annotations.NotNull;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Lightweight HTTP bridge exposing IntelliJ PSI/AST analysis to the MCP server.
 * The MCP server (running as a separate process) delegates tool calls here for
 * accurate code intelligence instead of regex-based scanning.
 * <p>
 * Architecture: Copilot Agent → MCP Server (stdio) → PSI Bridge (HTTP) → IntelliJ PSI
 */
@Service(Service.Level.PROJECT)
public final class PsiBridgeService implements Disposable {
    private static final Logger LOG = Logger.getInstance(PsiBridgeService.class);
    private static final Gson GSON = new GsonBuilder().create();

    private final Project project;
    private HttpServer httpServer;
    private int port;

    public PsiBridgeService(@NotNull Project project) {
        this.project = project;
    }

    public static PsiBridgeService getInstance(@NotNull Project project) {
        return project.getService(PsiBridgeService.class);
    }

    public int getPort() {
        return port;
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
        // Don't overwrite the bridge file when running inside unit tests
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
        byte[] resp = "{\"status\":\"ok\"}".getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
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
        String[] toolNames = {
            "search_symbols", "get_file_outline", "find_references",
            "list_project_files", "list_tests", "run_tests", "get_test_results",
            "get_coverage", "get_project_info", "list_run_configurations",
            "run_configuration", "create_run_configuration", "edit_run_configuration",
            "get_problems", "get_highlights", "run_inspections",
            "add_to_dictionary", "suppress_inspection", "run_qodana",
            "optimize_imports", "format_code", "read_file", "write_file",
            "git_status", "git_diff", "git_log", "git_blame", "git_commit",
            "git_stage", "git_unstage", "git_branch", "git_stash", "git_show",
            "http_request", "run_command", "read_ide_log", "get_notifications",
            "read_run_output", "run_in_terminal", "list_terminals",
            "read_terminal_output", "get_documentation", "download_sources",
            "create_scratch_file"
        };
        for (String name : toolNames) {
            JsonObject tool = new JsonObject();
            tool.addProperty("name", name);
            tools.add(tool);
        }

        JsonObject response = new JsonObject();
        response.add("tools", tools);
        byte[] bytes = GSON.toJson(response).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
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
            result = switch (toolName) {
                case "search_symbols" -> searchSymbols(arguments);
                case "get_file_outline" -> getFileOutline(arguments);
                case "find_references" -> findReferences(arguments);
                case "list_project_files" -> listProjectFiles(arguments);
                case "list_tests" -> listTests(arguments);
                case "run_tests" -> runTests(arguments);
                case "get_test_results" -> getTestResults(arguments);
                case "get_coverage" -> getCoverage(arguments);
                case "get_project_info" -> getProjectInfo();
                case "list_run_configurations" -> listRunConfigurations();
                case "run_configuration" -> runConfiguration(arguments);
                case "create_run_configuration" -> createRunConfiguration(arguments);
                case "edit_run_configuration" -> editRunConfiguration(arguments);
                case "get_problems" -> getProblems(arguments);
                case "get_highlights" -> getHighlights(arguments);
                case "run_inspections" -> runInspections(arguments);
                case "add_to_dictionary" -> addToDictionary(arguments);
                case "suppress_inspection" -> suppressInspection(arguments);
                case "run_qodana" -> runQodana(arguments);
                case "optimize_imports" -> optimizeImports(arguments);
                case "format_code" -> formatCode(arguments);
                case "read_file", "intellij_read_file" -> readFile(arguments);
                case "write_file", "intellij_write_file" -> writeFile(arguments);
                // Git tools
                case "git_status" -> gitStatus(arguments);
                case "git_diff" -> gitDiff(arguments);
                case "git_log" -> gitLog(arguments);
                case "git_blame" -> gitBlame(arguments);
                case "git_commit" -> gitCommit(arguments);
                case "git_stage" -> gitStage(arguments);
                case "git_unstage" -> gitUnstage(arguments);
                case "git_branch" -> gitBranch(arguments);
                case "git_stash" -> gitStash(arguments);
                case "git_show" -> gitShow(arguments);
                // Infrastructure tools
                case "http_request" -> httpRequest(arguments);
                case "run_command" -> runCommand(arguments);
                case "read_ide_log" -> readIdeLog(arguments);
                case "get_notifications" -> getNotifications();
                case "read_run_output" -> readRunOutput(arguments);
                // Terminal tools
                case "run_in_terminal" -> runInTerminal(arguments);
                case "list_terminals" -> listTerminals();
                case "read_terminal_output" -> readTerminalOutput(arguments);
                // Documentation tools
                case "get_documentation" -> getDocumentation(arguments);
                case "download_sources" -> downloadSources(arguments);
                // Scratch file tools
                case "create_scratch_file" -> createScratchFile(arguments);
                default -> "Unknown tool: " + toolName;
            };
        } catch (com.intellij.openapi.application.ex.ApplicationUtil.CannotRunReadActionException e) {
            // Control-flow exception — retry or report, but don't log (IntelliJ forbids logging these)
            result = "Error: IDE is busy, please retry. " + e.getMessage();
        } catch (Exception e) {
            LOG.warn("PSI tool error: " + toolName, e);
            result = "Error: " + e.getMessage();
        }

        JsonObject response = new JsonObject();
        response.addProperty("result", result);
        byte[] bytes = GSON.toJson(response).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.getResponseBody().close();
    }

    // ---- PSI Tool Implementations ----

    private String listProjectFiles(JsonObject args) {
        String dir = args.has("directory") ? args.get("directory").getAsString() : "";
        String pattern = args.has("pattern") ? args.get("pattern").getAsString() : "";

        return ReadAction.compute(() -> {
            String basePath = project.getBasePath();
            if (basePath == null) return "No project base path";

            List<String> files = new ArrayList<>();
            ProjectFileIndex fileIndex = ProjectFileIndex.getInstance(project);
            fileIndex.iterateContent(vf -> {
                if (!vf.isDirectory()) {
                    String relPath = relativize(basePath, vf.getPath());
                    if (relPath == null) return true;
                    if (!dir.isEmpty() && !relPath.startsWith(dir)) return true;
                    if (!pattern.isEmpty() && !matchGlob(vf.getName(), pattern)) return true;
                    String tag = fileIndex.isInTestSourceContent(vf) ? "test " : "";
                    files.add(String.format("%s [%s%s]", relPath, tag, fileType(vf.getName())));
                }
                return files.size() < 500;
            });

            if (files.isEmpty()) return "No files found";
            Collections.sort(files);
            return files.size() + " files:\n" + String.join("\n", files);
        });
    }

    private String getFileOutline(JsonObject args) {
        if (!args.has("path") || args.get("path").isJsonNull())
            return "Error: 'path' parameter is required";
        String pathStr = args.get("path").getAsString();

        return ReadAction.compute(() -> {
            VirtualFile vf = resolveVirtualFile(pathStr);
            if (vf == null) return "File not found: " + pathStr;

            PsiFile psiFile = PsiManager.getInstance(project).findFile(vf);
            if (psiFile == null) return "Cannot parse file: " + pathStr;

            Document document = FileDocumentManager.getInstance().getDocument(vf);
            if (document == null) return "Cannot read file: " + pathStr;

            List<String> outline = new ArrayList<>();
            psiFile.accept(new PsiRecursiveElementWalkingVisitor() {
                @Override
                public void visitElement(@NotNull PsiElement element) {
                    if (element instanceof PsiNamedElement named) {
                        String name = named.getName();
                        if (name != null && !name.isEmpty()) {
                            String type = classifyElement(element);
                            if (type != null) {
                                int line = document.getLineNumber(element.getTextOffset()) + 1;
                                outline.add(String.format("  %d: %s %s", line, type, name));
                            }
                        }
                    }
                    super.visitElement(element);
                }
            });

            if (outline.isEmpty()) return "No structural elements found in " + pathStr;
            String basePath = project.getBasePath();
            String display = basePath != null ? relativize(basePath, vf.getPath()) : pathStr;
            return "Outline of " + (display != null ? display : pathStr) + ":\n"
                + String.join("\n", outline);
        });
    }

    private String searchSymbols(JsonObject args) {
        String query = args.has("query") ? args.get("query").getAsString() : "";
        String typeFilter = args.has("type") ? args.get("type").getAsString() : "";

        return ReadAction.compute(() -> {
            List<String> results = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            String basePath = project.getBasePath();
            GlobalSearchScope scope = GlobalSearchScope.projectScope(project);

            // Wildcard/empty query: iterate all project files to collect symbols by type
            if (query.isEmpty() || "*".equals(query)) {
                if (typeFilter.isEmpty())
                    return "Provide a 'type' filter (class, interface, method, field) when using wildcard query";
                int[] fileCount = {0};
                ProjectFileIndex.getInstance(project).iterateContent(vf -> {
                    if (vf.isDirectory() || (!vf.getName().endsWith(".java") && !vf.getName().endsWith(".kt")))
                        return true;
                    fileCount[0]++;
                    PsiFile psiFile = PsiManager.getInstance(project).findFile(vf);
                    if (psiFile == null) return true;
                    Document doc = FileDocumentManager.getInstance().getDocument(vf);
                    if (doc == null) return true;

                    psiFile.accept(new PsiRecursiveElementWalkingVisitor() {
                        @Override
                        public void visitElement(@NotNull PsiElement element) {
                            if (results.size() >= 200) return;
                            if (element instanceof PsiNamedElement named) {
                                String name = named.getName();
                                String type = classifyElement(element);
                                if (name != null && type != null && type.equals(typeFilter)) {
                                    int line = doc.getLineNumber(element.getTextOffset()) + 1;
                                    String relPath = relativize(basePath, vf.getPath());
                                    String key = (relPath != null ? relPath : vf.getPath()) + ":" + line;
                                    if (seen.add(key)) {
                                        results.add(String.format("%s:%d [%s] %s",
                                            relPath != null ? relPath : vf.getPath(), line, type, name));
                                    }
                                }
                            }
                            super.visitElement(element);
                        }
                    });
                    return results.size() < 200;
                });
                if (results.isEmpty())
                    return "No " + typeFilter + " symbols found (scanned " + fileCount[0]
                        + " source files using AST analysis). This is a definitive result — no grep needed.";
                return results.size() + " " + typeFilter + " symbols:\n" + String.join("\n", results);
            }

            // Exact word search via PSI index

            PsiSearchHelper.getInstance(project).processElementsWithWord(
                (element, offsetInElement) -> {
                    PsiElement parent = element.getParent();
                    if (parent instanceof PsiNamedElement named && query.equals(named.getName())) {
                        String type = classifyElement(parent);
                        if (type != null && (typeFilter.isEmpty() || type.equals(typeFilter))) {
                            PsiFile file = parent.getContainingFile();
                            if (file != null && file.getVirtualFile() != null) {
                                Document doc = FileDocumentManager.getInstance()
                                    .getDocument(file.getVirtualFile());
                                if (doc != null) {
                                    int line = doc.getLineNumber(parent.getTextOffset()) + 1;
                                    String relPath = basePath != null
                                        ? relativize(basePath, file.getVirtualFile().getPath())
                                        : file.getVirtualFile().getPath();
                                    String key = relPath + ":" + line;
                                    if (seen.add(key)) {
                                        String lineText = getLineText(doc, line - 1);
                                        results.add(String.format("%s:%d [%s] %s",
                                            relPath, line, type, lineText));
                                    }
                                }
                            }
                        }
                    }
                    return results.size() < 50;
                },
                scope, query, UsageSearchContext.IN_CODE, true
            );

            if (results.isEmpty()) return "No symbols found matching '" + query + "'";
            return String.join("\n", results);
        });
    }

    private String findReferences(JsonObject args) {
        if (!args.has("symbol") || args.get("symbol").isJsonNull())
            return "Error: 'symbol' parameter is required";
        String symbol = args.get("symbol").getAsString();
        String filePattern = args.has("file_pattern") ? args.get("file_pattern").getAsString() : "";

        return ReadAction.compute(() -> {
            List<String> results = new ArrayList<>();
            String basePath = project.getBasePath();
            GlobalSearchScope scope = GlobalSearchScope.projectScope(project);

            // Try to find the definition first for accurate ReferencesSearch
            PsiElement definition = findDefinition(symbol, scope);

            if (definition != null) {
                for (PsiReference ref : ReferencesSearch.search(definition, scope).findAll()) {
                    PsiElement refEl = ref.getElement();
                    PsiFile file = refEl.getContainingFile();
                    if (file == null || file.getVirtualFile() == null) continue;
                    if (!filePattern.isEmpty() && !matchGlob(file.getName(), filePattern)) continue;

                    Document doc = FileDocumentManager.getInstance().getDocument(file.getVirtualFile());
                    if (doc != null) {
                        int line = doc.getLineNumber(refEl.getTextOffset()) + 1;
                        String relPath = basePath != null
                            ? relativize(basePath, file.getVirtualFile().getPath())
                            : file.getVirtualFile().getPath();
                        String lineText = getLineText(doc, line - 1);
                        results.add(String.format("%s:%d: %s", relPath, line, lineText));
                    }
                    if (results.size() >= 100) break;
                }
            }

            // Fall back to word search if no PSI references found
            if (results.isEmpty()) {
                PsiSearchHelper.getInstance(project).processElementsWithWord(
                    (element, offsetInElement) -> {
                        PsiFile file = element.getContainingFile();
                        if (file == null || file.getVirtualFile() == null) return true;
                        if (!filePattern.isEmpty() && !matchGlob(file.getName(), filePattern))
                            return true;

                        Document doc = FileDocumentManager.getInstance()
                            .getDocument(file.getVirtualFile());
                        if (doc != null) {
                            int line = doc.getLineNumber(element.getTextOffset()) + 1;
                            String relPath = basePath != null
                                ? relativize(basePath, file.getVirtualFile().getPath())
                                : file.getVirtualFile().getPath();
                            String lineText = getLineText(doc, line - 1);
                            String entry = String.format("%s:%d: %s", relPath, line, lineText);
                            if (!results.contains(entry)) results.add(entry);
                        }
                        return results.size() < 100;
                    },
                    scope, symbol, UsageSearchContext.IN_CODE, true
                );
            }

            if (results.isEmpty()) return "No references found for '" + symbol + "'";
            return results.size() + " references found:\n" + String.join("\n", results);
        });
    }

    // ---- Project Environment Tools ----

    private String getProjectInfo() {
        return ReadAction.compute(() -> {
            StringBuilder sb = new StringBuilder();
            String basePath = project.getBasePath();
            sb.append("Project: ").append(project.getName()).append("\n");
            sb.append("Path: ").append(basePath).append("\n");

            // SDK / JDK
            try {
                Sdk sdk = ProjectRootManager.getInstance(project).getProjectSdk();
                if (sdk != null) {
                    sb.append("SDK: ").append(sdk.getName()).append("\n");
                    sb.append("SDK Path: ").append(sdk.getHomePath()).append("\n");
                    sb.append("SDK Version: ").append(sdk.getVersionString()).append("\n");
                }
            } catch (Exception e) {
                sb.append("SDK: unavailable (").append(e.getMessage()).append(")\n");
            }

            // Modules
            try {
                Module[] modules = ModuleManager.getInstance(project).getModules();
                sb.append("\nModules (").append(modules.length).append("):\n");
                for (Module module : modules) {
                    sb.append("  - ").append(module.getName());
                    try {
                        Sdk moduleSdk = ModuleRootManager.getInstance(module).getSdk();
                        if (moduleSdk != null) {
                            sb.append(" [SDK: ").append(moduleSdk.getName()).append("]");
                        }
                        VirtualFile[] sourceRoots = ModuleRootManager.getInstance(module)
                            .getSourceRoots(false);
                        if (sourceRoots.length > 0) {
                            sb.append(" (").append(sourceRoots.length).append(" source roots)");
                        }
                    } catch (Exception ignored) {
                    }
                    sb.append("\n");
                }
            } catch (Exception e) {
                sb.append("Modules: unavailable\n");
            }

            // Build system
            if (basePath != null) {
                if (Files.exists(Path.of(basePath, "build.gradle.kts"))
                    || Files.exists(Path.of(basePath, "build.gradle"))) {
                    sb.append("\nBuild System: Gradle\n");
                    Path gradlew = Path.of(basePath,
                        System.getProperty("os.name").contains("Win") ? "gradlew.bat" : "gradlew");
                    sb.append("Gradle Wrapper: ").append(gradlew).append("\n");
                } else if (Files.exists(Path.of(basePath, "pom.xml"))) {
                    sb.append("\nBuild System: Maven\n");
                }
            }

            // Run configurations
            try {
                var configs = RunManager.getInstance(project).getAllSettings();
                if (!configs.isEmpty()) {
                    sb.append("\nRun Configurations (").append(configs.size()).append("):\n");
                    for (var config : configs) {
                        sb.append("  - ").append(config.getName())
                            .append(" [").append(config.getType().getDisplayName()).append("]\n");
                    }
                }
            } catch (Exception e) {
                sb.append("Run Configurations: unavailable\n");
            }

            return sb.toString().trim();
        });
    }

    private String listRunConfigurations() {
        return ReadAction.compute(() -> {
            try {
                var configs = RunManager.getInstance(project).getAllSettings();
                if (configs.isEmpty()) return "No run configurations found";

                List<String> results = new ArrayList<>();
                for (var config : configs) {
                    String entry = String.format("%s [%s]%s",
                        config.getName(),
                        config.getType().getDisplayName(),
                        config.isTemporary() ? " (temporary)" : "");
                    results.add(entry);
                }
                return results.size() + " run configurations:\n" + String.join("\n", results);
            } catch (Exception e) {
                return "Error listing run configurations: " + e.getMessage();
            }
        });
    }

    private String runConfiguration(JsonObject args) throws Exception {
        String name = args.get("name").getAsString();

        CompletableFuture<String> resultFuture = new CompletableFuture<>();

        ApplicationManager.getApplication().invokeLater(() -> {
            try {
                var settings = RunManager.getInstance(project).findConfigurationByName(name);
                if (settings == null) {
                    resultFuture.complete("Run configuration not found: '" + name
                        + "'. Use list_run_configurations to see available configs.");
                    return;
                }

                var executor = DefaultRunExecutor.getRunExecutorInstance();
                var envBuilder = ExecutionEnvironmentBuilder.createOrNull(executor, settings);
                if (envBuilder == null) {
                    resultFuture.complete("Cannot create execution environment for: " + name);
                    return;
                }

                var env = envBuilder.build();
                ExecutionManager.getInstance(project).restartRunProfile(env);
                resultFuture.complete("Started run configuration: " + name
                    + " [" + settings.getType().getDisplayName() + "]"
                    + "\nResults will appear in the IntelliJ Run panel."
                    + "\nUse get_test_results to check results after completion.");
            } catch (Exception e) {
                resultFuture.complete("Error running configuration: " + e.getMessage());
            }
        });

        return resultFuture.get(10, TimeUnit.SECONDS);
    }

    private String createRunConfiguration(JsonObject args) throws Exception {
        String name = args.get("name").getAsString();
        String type = args.get("type").getAsString().toLowerCase();

        CompletableFuture<String> resultFuture = new CompletableFuture<>();

        ApplicationManager.getApplication().invokeLater(() -> {
            try {
                RunManager runManager = RunManager.getInstance(project);

                // Find the configuration type
                var configType = findConfigurationType(type);
                if (configType == null) {
                    List<String> available = new ArrayList<>();
                    for (var ct : com.intellij.execution.configurations.ConfigurationType.CONFIGURATION_TYPE_EP.getExtensionList()) {
                        available.add(ct.getDisplayName());
                    }
                    resultFuture.complete("Unknown configuration type: '" + type
                        + "'. Available types: " + String.join(", ", available));
                    return;
                }

                var factory = configType.getConfigurationFactories()[0];
                var settings = runManager.createConfiguration(name, factory);
                RunConfiguration config = settings.getConfiguration();

                // Apply common properties
                applyConfigProperties(config, args);

                // Apply type-specific properties
                applyTypeSpecificProperties(config, type, args);

                runManager.addConfiguration(settings);
                runManager.setSelectedConfiguration(settings);

                resultFuture.complete("Created run configuration: " + name
                    + " [" + configType.getDisplayName() + "]"
                    + "\nUse run_configuration to execute it, or edit_run_configuration to modify it.");
            } catch (Exception e) {
                resultFuture.complete("Error creating run configuration: " + e.getMessage());
            }
        });

        return resultFuture.get(10, TimeUnit.SECONDS);
    }

    private String editRunConfiguration(JsonObject args) throws Exception {
        String name = args.get("name").getAsString();

        CompletableFuture<String> resultFuture = new CompletableFuture<>();

        ApplicationManager.getApplication().invokeLater(() -> {
            try {
                var settings = RunManager.getInstance(project).findConfigurationByName(name);
                if (settings == null) {
                    resultFuture.complete("Run configuration not found: '" + name + "'");
                    return;
                }

                RunConfiguration config = settings.getConfiguration();
                List<String> changes = new ArrayList<>();

                // Apply common properties
                if (args.has("env")) {
                    applyEnvVars(config, args.getAsJsonObject("env"), changes);
                }
                if (args.has("jvm_args")) {
                    setViaReflection(config, "setVMParameters",
                        args.get("jvm_args").getAsString(), changes, "JVM args");
                }
                if (args.has("program_args")) {
                    setViaReflection(config, "setProgramParameters",
                        args.get("program_args").getAsString(), changes, "program args");
                }
                if (args.has("working_dir")) {
                    setViaReflection(config, "setWorkingDirectory",
                        args.get("working_dir").getAsString(), changes, "working directory");
                }

                // Apply type-specific properties
                String typeName = settings.getType().getDisplayName().toLowerCase();
                applyTypeSpecificProperties(config, typeName, args);
                if (args.has("main_class")) changes.add("main class");
                if (args.has("test_class")) changes.add("test class");
                if (args.has("tasks")) changes.add("Gradle tasks");

                if (changes.isEmpty()) {
                    resultFuture.complete("No changes applied. Available properties: "
                        + "env (object), jvm_args, program_args, working_dir, "
                        + "main_class, test_class, test_method, tasks");
                } else {
                    resultFuture.complete("Updated run configuration '" + name + "': "
                        + String.join(", ", changes));
                }
            } catch (Exception e) {
                resultFuture.complete("Error editing run configuration: " + e.getMessage());
            }
        });

        return resultFuture.get(10, TimeUnit.SECONDS);
    }

    // ---- Run Config Helper Methods ----

    private com.intellij.execution.configurations.ConfigurationType findConfigurationType(String type) {
        for (var ct : com.intellij.execution.configurations.ConfigurationType.CONFIGURATION_TYPE_EP.getExtensionList()) {
            String displayName = ct.getDisplayName().toLowerCase();
            if (displayName.equals(type) || displayName.contains(type)
                || ct.getId().toLowerCase().contains(type)) {
                return ct;
            }
        }
        return null;
    }

    private void applyConfigProperties(RunConfiguration config, JsonObject args) {
        List<String> ignore = new ArrayList<>();
        if (args.has("env")) applyEnvVars(config, args.getAsJsonObject("env"), ignore);
        if (args.has("jvm_args"))
            setViaReflection(config, "setVMParameters", args.get("jvm_args").getAsString(), ignore, null);
        if (args.has("program_args"))
            setViaReflection(config, "setProgramParameters", args.get("program_args").getAsString(), ignore, null);
        if (args.has("working_dir"))
            setViaReflection(config, "setWorkingDirectory", args.get("working_dir").getAsString(), ignore, null);
    }

    private void applyTypeSpecificProperties(RunConfiguration config, String type, JsonObject args) {
        List<String> ignore = new ArrayList<>();
        if (args.has("main_class"))
            setViaReflection(config, "setMainClassName", args.get("main_class").getAsString(), ignore, null);

        // JUnit: test class/method via getPersistentData() which has public fields
        if (args.has("test_class") || args.has("test_method")) {
            try {
                var getData = config.getClass().getMethod("getPersistentData");
                Object data = getData.invoke(config);
                if (args.has("test_class")) {
                    String testClass = args.get("test_class").getAsString();
                    // Resolve class name to FQN and module via PSI
                    ClassInfo classInfo = resolveClass(testClass);
                    data.getClass().getField("MAIN_CLASS_NAME").set(data, classInfo.fqn());
                    data.getClass().getField("TEST_OBJECT").set(data,
                        args.has("test_method") ? "method" : "class");
                    // Auto-set module if not explicitly provided
                    if (!args.has("module_name") && classInfo.module() != null) {
                        try {
                            var setModule = config.getClass().getMethod("setModule", Module.class);
                            setModule.invoke(config, classInfo.module());
                        } catch (NoSuchMethodException e) {
                            LOG.warn("Cannot set module on config: " + config.getClass().getName(), e);
                        }
                    }
                }
                if (args.has("test_method")) {
                    data.getClass().getField("METHOD_NAME").set(data,
                        args.get("test_method").getAsString());
                    data.getClass().getField("TEST_OBJECT").set(data, "method");
                }
            } catch (Exception e) {
                LOG.warn("Failed to set JUnit test class/method via getPersistentData", e);
                // Fallback: try direct setter
                setViaReflection(config, "setMainClassName",
                    args.has("test_class") ? args.get("test_class").getAsString() : "", ignore, null);
            }
        }

        if (args.has("module_name")) {
            try {
                Module module = ModuleManager.getInstance(project)
                    .findModuleByName(args.get("module_name").getAsString());
                if (module != null) {
                    var setModule = config.getClass().getMethod("setModule", Module.class);
                    setModule.invoke(config, module);
                }
            } catch (Exception ignored) {
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void applyEnvVars(RunConfiguration config, JsonObject envObj, List<String> changes) {
        try {
            // Get existing env vars
            Map<String, String> envs;
            try {
                var getEnvs = config.getClass().getMethod("getEnvs");
                envs = new HashMap<>((Map<String, String>) getEnvs.invoke(config));
            } catch (Exception e) {
                envs = new HashMap<>();
            }

            // Merge new values (null value removes the key)
            for (var entry : envObj.entrySet()) {
                if (entry.getValue().isJsonNull()) {
                    envs.remove(entry.getKey());
                    changes.add("removed env " + entry.getKey());
                } else {
                    envs.put(entry.getKey(), entry.getValue().getAsString());
                    changes.add("env " + entry.getKey());
                }
            }

            var setEnvs = config.getClass().getMethod("setEnvs", Map.class);
            setEnvs.invoke(config, envs);
        } catch (Exception e) {
            changes.add("env vars (failed: " + e.getMessage() + ")");
        }
    }

    private void setViaReflection(Object target, String methodName, String value,
                                  List<String> changes, String label) {
        try {
            var method = target.getClass().getMethod(methodName, String.class);
            method.invoke(target, value);
            if (label != null) changes.add(label);
        } catch (Exception ignored) {
        }
    }

    // ---- Code Quality Tools ----

    private String getProblems(JsonObject args) throws Exception {
        String pathStr = args.has("path") ? args.get("path").getAsString() : "";

        CompletableFuture<String> resultFuture = new CompletableFuture<>();

        ApplicationManager.getApplication().invokeLater(() -> {
            try {
                ReadAction.run(() -> {
                    List<String> problems = new ArrayList<>();
                    String basePath = project.getBasePath();
                    List<VirtualFile> filesToCheck = new ArrayList<>();

                    if (!pathStr.isEmpty()) {
                        VirtualFile vf = resolveVirtualFile(pathStr);
                        if (vf != null) filesToCheck.add(vf);
                        else {
                            resultFuture.complete("File not found: " + pathStr);
                            return;
                        }
                    } else {
                        // Check all open files with highlights
                        var fem = com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project);
                        filesToCheck.addAll(List.of(fem.getOpenFiles()));
                    }

                    for (VirtualFile vf : filesToCheck) {
                        PsiFile psiFile = PsiManager.getInstance(project).findFile(vf);
                        if (psiFile == null) continue;
                        Document doc = FileDocumentManager.getInstance().getDocument(vf);
                        if (doc == null) continue;

                        String relPath = basePath != null ? relativize(basePath, vf.getPath()) : vf.getName();
                        List<com.intellij.codeInsight.daemon.impl.HighlightInfo> highlights = new ArrayList<>();
                        com.intellij.codeInsight.daemon.impl.DaemonCodeAnalyzerEx.processHighlights(
                            doc, project,
                            com.intellij.lang.annotation.HighlightSeverity.WARNING,
                            0, doc.getTextLength(),
                            highlights::add
                        );

                        for (var h : highlights) {
                            if (h.getDescription() == null) continue;
                            int line = doc.getLineNumber(h.getStartOffset()) + 1;
                            String severity = h.getSeverity().getName();
                            problems.add(String.format("%s:%d [%s] %s",
                                relPath, line, severity, h.getDescription()));
                        }
                    }

                    if (problems.isEmpty()) {
                        resultFuture.complete("No problems found"
                            + (pathStr.isEmpty() ? " in open files" : " in " + pathStr)
                            + ". Analysis is based on IntelliJ's inspections — file must be open in editor for highlights to be available.");
                    } else {
                        resultFuture.complete(problems.size() + " problems:\n" + String.join("\n", problems));
                    }
                });
            } catch (Exception e) {
                resultFuture.complete("Error getting problems: " + e.getMessage());
            }
        });

        return resultFuture.get(10, TimeUnit.SECONDS);
    }

    @SuppressWarnings("UnstableApiUsage")
    /**
     * Get syntax highlights and daemon-level diagnostics for project files.
     * This reads the cached results from IntelliJ's on-the-fly analysis (DaemonCodeAnalyzer).
     * Useful for quick checks on files already open/analyzed by the IDE.
     * Does NOT run full code inspections — use run_inspections for that.
     */
    private String getHighlights(JsonObject args) throws Exception {
        String scope = args.has("scope") ? args.get("scope").getAsString() : "project";
        int limit = args.has("limit") ? args.get("limit").getAsInt() : 100;

        if (!com.intellij.diagnostic.LoadingState.COMPONENTS_LOADED.isOccurred()) {
            return "{\"error\": \"IDE is still initializing. Please wait a moment and try again.\"}";
        }

        CompletableFuture<String> resultFuture = new CompletableFuture<>();
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try {
                getHighlightsCached(scope, limit, resultFuture);
            } catch (Exception e) {
                LOG.error("Error getting highlights", e);
                resultFuture.complete("Error getting highlights: " + e.getMessage());
            }
        });
        return resultFuture.get(30, TimeUnit.SECONDS);
    }

    @SuppressWarnings("UnstableApiUsage")
    /**
     * Run IntelliJ's full code inspections on the project using the inspection engine.
     * This triggers the same analysis as "Analyze > Inspect Code" in the IDE.
     * Results appear in the Problems tool window and are returned here.
     * Finds code quality issues, security problems, typos, complexity warnings,
     * and third-party inspection results (e.g. SonarQube).
     */
    private String runInspections(JsonObject args) throws Exception {
        int limit = args.has("limit") ? args.get("limit").getAsInt() : 100;
        int offset = args.has("offset") ? args.get("offset").getAsInt() : 0;
        String minSeverity = args.has("min_severity") ? args.get("min_severity").getAsString() : null;

        if (!com.intellij.diagnostic.LoadingState.COMPONENTS_LOADED.isOccurred()) {
            return "{\"error\": \"IDE is still initializing. Please wait a moment and try again.\"}";
        }

        CompletableFuture<String> resultFuture = new CompletableFuture<>();

        // doInspections() internally uses invokeLater + Task.Backgroundable,
        // so it can be called from any thread
        try {
            runInspectionAnalysis(limit, offset, minSeverity, resultFuture);
        } catch (Exception e) {
            LOG.error("Error running inspections", e);
            resultFuture.complete("Error running inspections: " + e.getMessage());
        }

        // Full inspection can take a while
        return resultFuture.get(600, TimeUnit.SECONDS);
    }

    /**
     * Get highlights by reading cached daemon analysis (fast but may miss unanalyzed files).
     */
    private void getHighlightsCached(String scope, int limit, CompletableFuture<String> resultFuture) {
        ReadAction.run(() -> {
            List<String> problems = new ArrayList<>();
            String basePath = project.getBasePath();

            // Get all project source files
            ProjectFileIndex fileIndex = ProjectRootManager.getInstance(project).getFileIndex();
            Collection<VirtualFile> allFiles = new ArrayList<>();

            if ("project".equals(scope)) {
                // Iterate all source roots
                fileIndex.iterateContent(file -> {
                    if (!file.isDirectory() && fileIndex.isInSourceContent(file)) {
                        allFiles.add(file);
                    }
                    return true;
                });
            }

            LOG.info("Analyzing " + allFiles.size() + " files for inspections (cached mode)");

            // Analyze each file for problems using existing highlights
            int count = 0;
            int filesWithProblems = 0;
            for (VirtualFile vf : allFiles) {
                if (count >= limit) break;

                PsiFile psiFile = PsiManager.getInstance(project).findFile(vf);
                if (psiFile == null) continue;

                Document doc = FileDocumentManager.getInstance().getDocument(vf);
                if (doc == null) continue;

                String relPath = basePath != null ? relativize(basePath, vf.getPath()) : vf.getName();
                List<com.intellij.codeInsight.daemon.impl.HighlightInfo> highlights = new ArrayList<>();

                try {
                    // Get ALL severity levels
                    com.intellij.codeInsight.daemon.impl.DaemonCodeAnalyzerEx.processHighlights(
                        doc, project,
                        null,  // Get all severities
                        0, doc.getTextLength(),
                        highlights::add
                    );

                    if (!highlights.isEmpty()) {
                        filesWithProblems++;
                    }

                    for (var h : highlights) {
                        if (h.getDescription() == null) continue;

                        // Filter to only show actual problems (not info/hints)
                        var severity = h.getSeverity();
                        if (severity == com.intellij.lang.annotation.HighlightSeverity.INFORMATION ||
                            severity.myVal < com.intellij.lang.annotation.HighlightSeverity.WEAK_WARNING.myVal) {
                            continue;
                        }

                        int line = doc.getLineNumber(h.getStartOffset()) + 1;
                        String severityName = severity.getName();
                        problems.add(String.format("%s:%d [%s] %s",
                            relPath, line, severityName, h.getDescription()));
                        count++;
                        if (count >= limit) break;
                    }
                } catch (Exception e) {
                    LOG.warn("Failed to analyze file: " + relPath, e);
                }
            }

            if (problems.isEmpty()) {
                resultFuture.complete(String.format("No highlights found in %d files analyzed (0 files with issues). " +
                        "Note: This reads cached daemon analysis results from already-analyzed files. " +
                        "For comprehensive code quality analysis, use run_inspections instead.",
                    allFiles.size()));
            } else {
                String summary = String.format("Found %d problems across %d files (showing up to %d):\n\n",
                    count, filesWithProblems, limit);
                resultFuture.complete(summary + String.join("\n", problems));
            }
        });
    }

    /**
     * Run full IntelliJ code inspections using the proper inspection engine.
     * This is the same as "Analyze > Inspect Code" in the IDE menu.
     * Uses doInspections() which handles ProgressWindow, threading, and UI automatically.
     * Results appear in the IDE's Inspection Results view AND are returned as text.
     * <p>
     * Implementation follows JetBrains' own InspectionCommandEx pattern.
     */
    @SuppressWarnings({"TestOnlyProblems", "UnstableApiUsage"})
    private void runInspectionAnalysis(int limit, int offset, String minSeverity,
                                       CompletableFuture<String> resultFuture) {
        // Severity ranking for filtering
        Map<String, Integer> severityRank = Map.of(
            "ERROR", 4, "WARNING", 3, "WEAK_WARNING", 2,
            "LIKE_UNUSED_SYMBOL", 2, "INFORMATION", 1, "INFO", 1,
            "TEXT_ATTRIBUTES", 0, "GENERIC_SERVER_ERROR_OR_WARNING", 3
        );
        int minRank = 0;
        if (minSeverity != null && !minSeverity.isEmpty()) {
            minRank = severityRank.getOrDefault(minSeverity.toUpperCase(), 0);
        }
        final int requiredRank = minRank;
        try {
            LOG.info("Starting full inspection analysis...");

            var inspectionManagerEx = (com.intellij.codeInspection.ex.InspectionManagerEx)
                com.intellij.codeInspection.InspectionManager.getInstance(project);
            var profileManager = com.intellij.profile.codeInspection.InspectionProjectProfileManager.getInstance(project);
            var currentProfile = profileManager.getCurrentProfile();
            var scope = new com.intellij.analysis.AnalysisScope(project);

            LOG.info("Using inspection profile: " + currentProfile.getName());
            LOG.info("Analysis scope: entire project");

            String basePath = project.getBasePath();
            String profileName = currentProfile.getName();

            // Create context following JetBrains' InspectionCommandEx pattern:
            // Use GlobalInspectionContextImpl with contentManager, override notifyInspectionsFinished
            var context = new com.intellij.codeInspection.ex.GlobalInspectionContextImpl(
                project, inspectionManagerEx.getContentManager()) {

                @Override
                protected void notifyInspectionsFinished(@NotNull com.intellij.analysis.AnalysisScope scope) {
                    super.notifyInspectionsFinished(scope);

                    LOG.info("Inspection analysis completed, collecting results...");

                    try {
                        List<String> allProblems = new ArrayList<>();
                        Set<String> filesSet = new HashSet<>();

                        var usedTools = getUsedTools();
                        for (var tools : usedTools) {
                            var toolWrapper = tools.getTool();
                            String toolId = toolWrapper.getShortName();

                            var presentation = getPresentation(toolWrapper);
                            if (presentation == null) continue;

                            var problemElements = presentation.getProblemElements();
                            if (problemElements == null || problemElements.isEmpty()) continue;

                            for (var refEntity : problemElements.keys()) {
                                var descriptors = problemElements.get(refEntity);
                                if (descriptors == null) continue;

                                for (var descriptor : descriptors) {
                                    String description = descriptor.getDescriptionTemplate();
                                    if (description == null || description.isEmpty()) continue;

                                    // Resolve #ref placeholder with actual element text
                                    String refText = "";
                                    if (descriptor instanceof com.intellij.codeInspection.ProblemDescriptor pd) {
                                        var psiEl = pd.getPsiElement();
                                        if (psiEl != null) {
                                            refText = psiEl.getText();
                                            if (refText != null && refText.length() > 80) {
                                                refText = refText.substring(0, 80) + "...";
                                            }
                                        }
                                    }

                                    // Clean up HTML/template markers from description
                                    description = description.replaceAll("<[^>]+>", "")
                                        .replaceAll("&lt;", "<")
                                        .replaceAll("&gt;", ">")
                                        .replaceAll("&amp;", "&")
                                        .replace("#ref", refText != null ? refText : "")
                                        .replaceAll("#loc", "")
                                        .trim();

                                    int line = -1;
                                    String filePath = "";
                                    String severity = "WARNING";

                                    if (descriptor instanceof com.intellij.codeInspection.ProblemDescriptor pd) {
                                        line = pd.getLineNumber() + 1;
                                        var psiElement = pd.getPsiElement();
                                        if (psiElement != null) {
                                            var containingFile = psiElement.getContainingFile();
                                            if (containingFile != null) {
                                                var vf = containingFile.getVirtualFile();
                                                if (vf != null) {
                                                    filePath = basePath != null
                                                        ? relativize(basePath, vf.getPath())
                                                        : vf.getName();
                                                    filesSet.add(filePath);
                                                }
                                            }
                                        }
                                        if (pd.getHighlightType() != null) {
                                            severity = pd.getHighlightType().toString();
                                        }
                                    }

                                    // Filter by minimum severity
                                    if (requiredRank > 0) {
                                        int rank = severityRank.getOrDefault(severity.toUpperCase(), 0);
                                        if (rank < requiredRank) continue;
                                    }

                                    allProblems.add(String.format("%s:%d [%s/%s] %s",
                                        filePath, line, severity, toolId, description));
                                }
                            }
                        }

                        int total = allProblems.size();
                        int filesWithProblems = filesSet.size();

                        if (total == 0) {
                            resultFuture.complete("No inspection problems found. " +
                                "The code passed all enabled inspections in the current profile (" +
                                profileName + "). Results are also visible in the IDE's Inspection Results view.");
                        } else {
                            int effectiveOffset = Math.min(offset, total);
                            int end = Math.min(effectiveOffset + limit, total);
                            List<String> page = allProblems.subList(effectiveOffset, end);
                            boolean hasMore = end < total;

                            String summary = String.format(
                                "Found %d total problems across %d files (profile: %s).\n" +
                                    "Showing %d-%d of %d.%s\n" +
                                    "Results are also visible in the IDE's Inspection Results view.\n\n",
                                total, filesWithProblems, profileName,
                                effectiveOffset + 1, end, total,
                                hasMore ? String.format(" Use offset=%d to see more.", end) : "");
                            resultFuture.complete(summary + String.join("\n", page));
                        }
                    } catch (Exception e) {
                        LOG.error("Error collecting inspection results", e);
                        resultFuture.complete("Error collecting results: " + e.getMessage());
                    }
                }
            };

            // doInspections handles everything: EDT dispatch, ProgressWindow creation,
            // background thread execution, and UI view creation
            context.doInspections(scope);

        } catch (Exception e) {
            LOG.error("Error setting up inspections", e);
            resultFuture.complete("Error setting up inspections: " + e.getMessage());
        }
    }

    private String addToDictionary(JsonObject args) throws Exception {
        String word = args.get("word").getAsString().trim().toLowerCase();
        if (word.isEmpty()) {
            return "Error: word cannot be empty";
        }

        CompletableFuture<String> resultFuture = new CompletableFuture<>();
        ApplicationManager.getApplication().invokeLater(() -> {
            try {
                var spellChecker = com.intellij.spellchecker.SpellCheckerManager.getInstance(project);
                spellChecker.acceptWordAsCorrect(word, project);
                resultFuture.complete("Added '" + word + "' to project dictionary. " +
                    "It will no longer be flagged as a typo in future inspections.");
            } catch (Exception e) {
                LOG.error("Error adding word to dictionary", e);
                resultFuture.complete("Error adding word to dictionary: " + e.getMessage());
            }
        });
        return resultFuture.get(10, TimeUnit.SECONDS);
    }

    private String suppressInspection(JsonObject args) throws Exception {
        String pathStr = args.get("path").getAsString();
        int line = args.get("line").getAsInt();
        String inspectionId = args.get("inspection_id").getAsString().trim();

        if (inspectionId.isEmpty()) {
            return "Error: inspection_id cannot be empty";
        }

        CompletableFuture<String> resultFuture = new CompletableFuture<>();
        ApplicationManager.getApplication().invokeLater(() -> {
            try {
                var vf = resolveVirtualFile(pathStr);
                if (vf == null) {
                    resultFuture.complete("Error: file not found: " + pathStr);
                    return;
                }

                var psiFile = com.intellij.psi.PsiManager.getInstance(project).findFile(vf);
                if (psiFile == null) {
                    resultFuture.complete("Error: could not parse file: " + pathStr);
                    return;
                }

                var document = com.intellij.psi.PsiDocumentManager.getInstance(project).getDocument(psiFile);
                if (document == null) {
                    resultFuture.complete("Error: could not get document for: " + pathStr);
                    return;
                }

                // Find the PSI element at the given line
                int zeroLine = line - 1;
                if (zeroLine < 0 || zeroLine >= document.getLineCount()) {
                    resultFuture.complete("Error: line " + line + " is out of range (file has " +
                        document.getLineCount() + " lines)");
                    return;
                }

                int offset = document.getLineStartOffset(zeroLine);
                var element = psiFile.findElementAt(offset);
                if (element == null) {
                    resultFuture.complete("Error: no code element found at line " + line);
                    return;
                }

                // Walk up to find the statement/declaration to annotate
                var target = findSuppressTarget(element);
                String fileName = vf.getName();

                if (fileName.endsWith(".java")) {
                    resultFuture.complete(suppressJava(target, inspectionId, document, psiFile));
                } else if (fileName.endsWith(".kt") || fileName.endsWith(".kts")) {
                    resultFuture.complete(suppressKotlin(target, inspectionId, document, psiFile));
                } else {
                    // For other file types, add a noinspection comment
                    resultFuture.complete(suppressWithComment(target, inspectionId, document, psiFile));
                }
            } catch (Exception e) {
                LOG.error("Error suppressing inspection", e);
                resultFuture.complete("Error suppressing inspection: " + e.getMessage());
            }
        });
        return resultFuture.get(10, TimeUnit.SECONDS);
    }

    private com.intellij.psi.PsiElement findSuppressTarget(com.intellij.psi.PsiElement element) {
        var current = element;
        while (current != null) {
            // For Java: stop at method, field, class, or local variable declaration
            if (current instanceof com.intellij.psi.PsiMethod ||
                current instanceof com.intellij.psi.PsiField ||
                current instanceof com.intellij.psi.PsiClass ||
                current instanceof com.intellij.psi.PsiLocalVariable) {
                return current;
            }
            // For statements
            if (current instanceof com.intellij.psi.PsiStatement) {
                return current;
            }
            current = current.getParent();
        }
        return element;
    }

    private String suppressJava(com.intellij.psi.PsiElement target, String inspectionId,
                                com.intellij.openapi.editor.Document document,
                                com.intellij.psi.PsiFile psiFile) {
        // Find the line to insert the annotation before
        int targetOffset = target.getTextRange().getStartOffset();
        int targetLine = document.getLineNumber(targetOffset);
        int lineStart = document.getLineStartOffset(targetLine);

        // Get the indentation of the target line
        String lineText = document.getText(
            new com.intellij.openapi.util.TextRange(lineStart, document.getLineEndOffset(targetLine)));
        String indent = "";
        for (char c : lineText.toCharArray()) {
            if (c == ' ' || c == '\t') indent += c;
            else break;
        }

        // Check if there's already a @SuppressWarnings on this element
        if (target instanceof com.intellij.psi.PsiModifierListOwner modListOwner) {
            var modList = modListOwner.getModifierList();
            if (modList != null) {
                var existing = modList.findAnnotation("java.lang.SuppressWarnings");
                if (existing != null) {
                    // Annotation exists — add the new ID to it
                    return addToExistingSuppressWarnings(existing, inspectionId, document, psiFile);
                }
            }
        }

        String annotation = indent + "@SuppressWarnings(\"" + inspectionId + "\")\n";
        ApplicationManager.getApplication().runWriteAction(() -> {
            com.intellij.openapi.command.CommandProcessor.getInstance().executeCommand(project, () -> {
                document.insertString(lineStart, annotation);
                com.intellij.psi.PsiDocumentManager.getInstance(project).commitDocument(document);
            }, "Suppress inspection", null);
        });

        return "Added @SuppressWarnings(\"" + inspectionId + "\") at line " + (targetLine + 1);
    }

    private String addToExistingSuppressWarnings(com.intellij.psi.PsiAnnotation annotation,
                                                 String inspectionId,
                                                 com.intellij.openapi.editor.Document document,
                                                 com.intellij.psi.PsiFile psiFile) {
        String text = annotation.getText();
        // Check if already suppressed
        if (text.contains(inspectionId)) {
            return "Inspection '" + inspectionId + "' is already suppressed at this location";
        }

        ApplicationManager.getApplication().runWriteAction(() -> {
            com.intellij.openapi.command.CommandProcessor.getInstance().executeCommand(project, () -> {
                var value = annotation.findAttributeValue("value");
                if (value != null) {
                    if (value instanceof com.intellij.psi.PsiArrayInitializerMemberValue) {
                        // Already an array: {"X", "Y"} → {"X", "Y", "inspectionId"}
                        int endBrace = value.getTextRange().getEndOffset() - 1;
                        document.insertString(endBrace, ", \"" + inspectionId + "\"");
                    } else {
                        // Single value: "X" → {"X", "inspectionId"}
                        var range = value.getTextRange();
                        String existing = document.getText(range);
                        document.replaceString(range.getStartOffset(), range.getEndOffset(),
                            "{" + existing + ", \"" + inspectionId + "\"}");
                    }
                    com.intellij.psi.PsiDocumentManager.getInstance(project).commitDocument(document);
                }
            }, "Suppress inspection", null);
        });

        return "Added '" + inspectionId + "' to existing @SuppressWarnings annotation";
    }

    private String suppressKotlin(com.intellij.psi.PsiElement target, String inspectionId,
                                  com.intellij.openapi.editor.Document document,
                                  com.intellij.psi.PsiFile psiFile) {
        int targetOffset = target.getTextRange().getStartOffset();
        int targetLine = document.getLineNumber(targetOffset);
        int lineStart = document.getLineStartOffset(targetLine);

        String lineText = document.getText(
            new com.intellij.openapi.util.TextRange(lineStart, document.getLineEndOffset(targetLine)));
        String indent = "";
        for (char c : lineText.toCharArray()) {
            if (c == ' ' || c == '\t') indent += c;
            else break;
        }

        // Check if preceding line already has @Suppress
        if (targetLine > 0) {
            int prevStart = document.getLineStartOffset(targetLine - 1);
            int prevEnd = document.getLineEndOffset(targetLine - 1);
            String prevLine = document.getText(
                new com.intellij.openapi.util.TextRange(prevStart, prevEnd)).trim();
            if (prevLine.startsWith("@Suppress(") && prevLine.contains(inspectionId)) {
                return "Inspection '" + inspectionId + "' is already suppressed at this location";
            }
        }

        String annotation = indent + "@Suppress(\"" + inspectionId + "\")\n";
        ApplicationManager.getApplication().runWriteAction(() -> {
            com.intellij.openapi.command.CommandProcessor.getInstance().executeCommand(project, () -> {
                document.insertString(lineStart, annotation);
                com.intellij.psi.PsiDocumentManager.getInstance(project).commitDocument(document);
            }, "Suppress inspection", null);
        });

        return "Added @Suppress(\"" + inspectionId + "\") at line " + (targetLine + 1);
    }

    private String suppressWithComment(com.intellij.psi.PsiElement target, String inspectionId,
                                       com.intellij.openapi.editor.Document document,
                                       com.intellij.psi.PsiFile psiFile) {
        int targetOffset = target.getTextRange().getStartOffset();
        int targetLine = document.getLineNumber(targetOffset);
        int lineStart = document.getLineStartOffset(targetLine);

        String lineText = document.getText(
            new com.intellij.openapi.util.TextRange(lineStart, document.getLineEndOffset(targetLine)));
        String indent = "";
        for (char c : lineText.toCharArray()) {
            if (c == ' ' || c == '\t') indent += c;
            else break;
        }

        String comment = indent + "//noinspection " + inspectionId + "\n";
        ApplicationManager.getApplication().runWriteAction(() -> {
            com.intellij.openapi.command.CommandProcessor.getInstance().executeCommand(project, () -> {
                document.insertString(lineStart, comment);
                com.intellij.psi.PsiDocumentManager.getInstance(project).commitDocument(document);
            }, "Suppress inspection", null);
        });

        return "Added //noinspection " + inspectionId + " comment at line " + (targetLine + 1);
    }

    @SuppressWarnings("OverrideOnly")
    private String runQodana(JsonObject args) throws Exception {
        int limit = args.has("limit") ? args.get("limit").getAsInt() : 100;

        CompletableFuture<String> resultFuture = new CompletableFuture<>();

        // Trigger Qodana's Run action via the IDE action system
        ApplicationManager.getApplication().invokeLater(() -> {
            try {
                var actionManager = com.intellij.openapi.actionSystem.ActionManager.getInstance();
                var qodanaAction = actionManager.getAction("Qodana.RunQodanaAction");

                if (qodanaAction == null) {
                    resultFuture.complete("Error: Qodana plugin is not installed or not available. " +
                        "Install it from Settings > Plugins > Marketplace.");
                    return;
                }

                // Create a synthetic action event for the current project
                var dataContext = com.intellij.openapi.actionSystem.impl.SimpleDataContext.builder()
                    .add(com.intellij.openapi.actionSystem.CommonDataKeys.PROJECT, project)
                    .build();
                var presentation = qodanaAction.getTemplatePresentation().clone();
                var event = com.intellij.openapi.actionSystem.AnActionEvent.createEvent(
                    dataContext, presentation, "QodanaTool",
                    com.intellij.openapi.actionSystem.ActionUiKind.NONE, null);

                // Check if the action is available
                qodanaAction.update(event);
                if (!event.getPresentation().isEnabled()) {
                    resultFuture.complete("Error: Qodana action is not available. " +
                        "The project may not be fully loaded yet, or Qodana may already be running.");
                    return;
                }

                LOG.info("Triggering Qodana local analysis...");
                qodanaAction.actionPerformed(event);

                // Poll for results in background thread
                ApplicationManager.getApplication().executeOnPooledThread(() -> {
                    try {
                        pollQodanaResults(limit, resultFuture);
                    } catch (Exception e) {
                        LOG.error("Error polling Qodana results", e);
                        resultFuture.complete("Qodana analysis was triggered but result polling failed: " +
                            e.getMessage() + ". Check the Qodana tab in the Problems tool window for results.");
                    }
                });

            } catch (Exception e) {
                LOG.error("Error triggering Qodana", e);
                resultFuture.complete("Error triggering Qodana: " + e.getMessage());
            }
        });

        // Qodana analysis can take a long time
        return resultFuture.get(600, TimeUnit.SECONDS);
    }

    private void pollQodanaResults(int limit, CompletableFuture<String> resultFuture) {
        try {
            // Use reflection to access Qodana's service — it's an optional plugin dependency
            Class<?> serviceClass;
            try {
                serviceClass = Class.forName("org.jetbrains.qodana.run.QodanaRunInIdeService");
            } catch (ClassNotFoundException e) {
                resultFuture.complete("Qodana analysis triggered. Check the Qodana tab in Problems for results. " +
                    "(Qodana service class not available for result polling)");
                return;
            }

            var qodanaService = project.getService(serviceClass);
            if (qodanaService == null) {
                resultFuture.complete("Qodana analysis triggered. Check the Qodana tab in Problems for results. " +
                    "(Could not access Qodana service to poll results)");
                return;
            }

            // Get the runState StateFlow
            var getRunState = serviceClass.getMethod("getRunState");
            var runStateFlow = getRunState.invoke(qodanaService);
            var getValueMethod = runStateFlow.getClass().getMethod("getValue");

            // Poll until Qodana finishes (up to 8 minutes)
            int maxPolls = 480;
            boolean wasRunning = false;
            for (int i = 0; i < maxPolls; i++) {
                var state = getValueMethod.invoke(runStateFlow);
                String stateName = state.getClass().getSimpleName();

                if (stateName.contains("Running")) {
                    wasRunning = true;
                    if (i % 30 == 0) {
                        LOG.info("Qodana analysis still running... (" + i + "s)");
                    }
                } else if (wasRunning) {
                    // Transitioned from Running to NotRunning — analysis complete
                    LOG.info("Qodana analysis completed after ~" + i + "s");
                    break;
                } else if (i > 10) {
                    // Never started running — may have shown a dialog or failed
                    resultFuture.complete("Qodana analysis was triggered but may require user interaction. " +
                        "Check the IDE for any Qodana dialogs or the Qodana tab in Problems for results.");
                    return;
                }

                Thread.sleep(1000);
            }

            // Try to read SARIF results from the output
            var getRunsResults = serviceClass.getMethod("getRunsResults");
            var runsResultsFlow = getRunsResults.invoke(qodanaService);
            var outputs = (Set<?>) getValueMethod.invoke(runsResultsFlow);
            if (outputs != null && !outputs.isEmpty()) {
                var latest = outputs.iterator().next();
                var getSarifPath = latest.getClass().getMethod("getSarifPath");
                var sarifPath = (java.nio.file.Path) getSarifPath.invoke(latest);
                if (sarifPath != null && java.nio.file.Files.exists(sarifPath)) {
                    String sarif = java.nio.file.Files.readString(sarifPath);
                    resultFuture.complete(parseSarifResults(sarif, limit));
                    return;
                }
            }

            resultFuture.complete("Qodana analysis completed. Results are visible in the Qodana tab " +
                "of the Problems tool window. (SARIF output file not found for programmatic reading)");

        } catch (Exception e) {
            LOG.error("Error polling Qodana results", e);
            resultFuture.complete("Qodana analysis was triggered. Check the Qodana tab for results. " +
                "Polling error: " + e.getMessage());
        }
    }

    private String parseSarifResults(String sarifJson, int limit) {
        try {
            var sarif = com.google.gson.JsonParser.parseString(sarifJson).getAsJsonObject();
            var runs = sarif.getAsJsonArray("runs");
            if (runs == null || runs.isEmpty()) {
                return "Qodana completed but no analysis runs found in SARIF output.";
            }

            List<String> problems = new ArrayList<>();
            Set<String> filesSet = new HashSet<>();
            String basePath = project.getBasePath();
            int count = 0;

            for (var runElement : runs) {
                var run = runElement.getAsJsonObject();
                var results = run.getAsJsonArray("results");
                if (results == null) continue;

                for (var resultElement : results) {
                    if (count >= limit) break;
                    var result = resultElement.getAsJsonObject();

                    String ruleId = result.has("ruleId") ? result.get("ruleId").getAsString() : "unknown";
                    String level = result.has("level") ? result.get("level").getAsString() : "warning";
                    String message = "";
                    if (result.has("message") && result.getAsJsonObject("message").has("text")) {
                        message = result.getAsJsonObject("message").get("text").getAsString();
                    }

                    String filePath = "";
                    int line = -1;
                    if (result.has("locations")) {
                        var locations = result.getAsJsonArray("locations");
                        if (!locations.isEmpty()) {
                            var loc = locations.get(0).getAsJsonObject();
                            if (loc.has("physicalLocation")) {
                                var phys = loc.getAsJsonObject("physicalLocation");
                                if (phys.has("artifactLocation") &&
                                    phys.getAsJsonObject("artifactLocation").has("uri")) {
                                    filePath = phys.getAsJsonObject("artifactLocation").get("uri").getAsString();
                                    // Remove file:// prefix if present
                                    if (filePath.startsWith("file://")) filePath = filePath.substring(7);
                                    if (basePath != null) filePath = relativize(basePath, filePath);
                                    filesSet.add(filePath);
                                }
                                if (phys.has("region") &&
                                    phys.getAsJsonObject("region").has("startLine")) {
                                    line = phys.getAsJsonObject("region").get("startLine").getAsInt();
                                }
                            }
                        }
                    }

                    problems.add(String.format("%s:%d [%s/%s] %s", filePath, line, level, ruleId, message));
                    count++;
                }
            }

            if (problems.isEmpty()) {
                return "Qodana analysis completed: no problems found. " +
                    "Results are also visible in the Qodana tab of the Problems tool window.";
            }

            String summary = String.format(
                "Qodana found %d problems across %d files (showing up to %d).\n" +
                    "Results are also visible in the Qodana tab of the Problems tool window.\n\n",
                problems.size(), filesSet.size(), limit);
            return summary + String.join("\n", problems);

        } catch (Exception e) {
            LOG.error("Error parsing SARIF results", e);
            return "Qodana analysis completed but SARIF parsing failed: " + e.getMessage() +
                ". Check the Qodana tab in the Problems tool window for results.";
        }
    }

    private String optimizeImports(JsonObject args) throws Exception {
        String pathStr = args.get("path").getAsString();

        CompletableFuture<String> resultFuture = new CompletableFuture<>();

        ApplicationManager.getApplication().invokeLater(() -> {
            try {
                VirtualFile vf = resolveVirtualFile(pathStr);
                if (vf == null) {
                    resultFuture.complete("File not found: " + pathStr);
                    return;
                }

                PsiFile psiFile = PsiManager.getInstance(project).findFile(vf);
                if (psiFile == null) {
                    resultFuture.complete("Cannot parse file: " + pathStr);
                    return;
                }

                ApplicationManager.getApplication().runWriteAction(() -> {
                    com.intellij.openapi.command.CommandProcessor.getInstance().executeCommand(project, () -> {
                        com.intellij.psi.PsiDocumentManager.getInstance(project).commitAllDocuments();
                        new com.intellij.codeInsight.actions.OptimizeImportsProcessor(project, psiFile).run();
                    }, "Optimize Imports", null);
                });

                String relPath = project.getBasePath() != null
                    ? relativize(project.getBasePath(), vf.getPath()) : pathStr;
                resultFuture.complete("Imports optimized: " + relPath);
            } catch (Exception e) {
                resultFuture.complete("Error optimizing imports: " + e.getMessage());
            }
        });

        return resultFuture.get(10, TimeUnit.SECONDS);
    }

    private String formatCode(JsonObject args) throws Exception {
        String pathStr = args.get("path").getAsString();

        CompletableFuture<String> resultFuture = new CompletableFuture<>();

        ApplicationManager.getApplication().invokeLater(() -> {
            try {
                VirtualFile vf = resolveVirtualFile(pathStr);
                if (vf == null) {
                    resultFuture.complete("File not found: " + pathStr);
                    return;
                }

                PsiFile psiFile = PsiManager.getInstance(project).findFile(vf);
                if (psiFile == null) {
                    resultFuture.complete("Cannot parse file: " + pathStr);
                    return;
                }

                ApplicationManager.getApplication().runWriteAction(() -> {
                    com.intellij.openapi.command.CommandProcessor.getInstance().executeCommand(project, () -> {
                        com.intellij.psi.PsiDocumentManager.getInstance(project).commitAllDocuments();
                        new com.intellij.codeInsight.actions.ReformatCodeProcessor(psiFile, false).run();
                    }, "Reformat Code", null);
                });

                String relPath = project.getBasePath() != null
                    ? relativize(project.getBasePath(), vf.getPath()) : pathStr;
                resultFuture.complete("Code formatted: " + relPath);
            } catch (Exception e) {
                resultFuture.complete("Error formatting code: " + e.getMessage());
            }
        });

        return resultFuture.get(30, TimeUnit.SECONDS);
    }

    private String readFile(JsonObject args) {
        if (!args.has("path") || args.get("path").isJsonNull())
            return "Error: 'path' parameter is required";
        String pathStr = args.get("path").getAsString();
        int startLine = args.has("start_line") ? args.get("start_line").getAsInt() : -1;
        int endLine = args.has("end_line") ? args.get("end_line").getAsInt() : -1;

        return ReadAction.compute(() -> {
            VirtualFile vf = resolveVirtualFile(pathStr);
            if (vf == null) return "File not found: " + pathStr;

            // Read from Document (editor buffer) if available, otherwise from VFS
            Document doc = FileDocumentManager.getInstance().getDocument(vf);
            String content;
            if (doc != null) {
                content = doc.getText();
            } else {
                try {
                    content = new String(vf.contentsToByteArray(), StandardCharsets.UTF_8);
                } catch (IOException e) {
                    return "Error reading file: " + e.getMessage();
                }
            }

            if (startLine > 0 || endLine > 0) {
                String[] lines = content.split("\n", -1);
                int from = Math.max(0, (startLine > 0 ? startLine - 1 : 0));
                int to = Math.min(lines.length, (endLine > 0 ? endLine : lines.length));
                StringBuilder sb = new StringBuilder();
                for (int i = from; i < to; i++) {
                    sb.append(i + 1).append(": ").append(lines[i]).append("\n");
                }
                return sb.toString();
            }
            return content;
        });
    }

    private String writeFile(JsonObject args) throws Exception {
        if (!args.has("path") || args.get("path").isJsonNull())
            return "Error: 'path' parameter is required";
        String pathStr = args.get("path").getAsString();

        CompletableFuture<String> resultFuture = new CompletableFuture<>();

        ApplicationManager.getApplication().invokeLater(() -> {
            try {
                VirtualFile vf = resolveVirtualFile(pathStr);

                if (args.has("content")) {
                    // Full file write
                    String newContent = args.get("content").getAsString();
                    if (vf == null) {
                        // Create new file via VFS
                        ApplicationManager.getApplication().runWriteAction(() -> {
                            try {
                                String normalized = pathStr.replace('\\', '/');
                                String basePath = project.getBasePath();
                                String fullPath = normalized.startsWith("/") ? normalized
                                    : (basePath != null ? basePath + "/" + normalized : normalized);
                                Path filePath = Path.of(fullPath);
                                Files.createDirectories(filePath.getParent());
                                Files.writeString(filePath, newContent);
                                LocalFileSystem.getInstance().refreshAndFindFileByPath(fullPath);
                                resultFuture.complete("Created: " + pathStr);
                            } catch (IOException e) {
                                resultFuture.complete("Error creating file: " + e.getMessage());
                            }
                        });
                    } else {
                        // Overwrite existing file via Document API for undo support
                        Document doc = FileDocumentManager.getInstance().getDocument(vf);
                        if (doc != null) {
                            ApplicationManager.getApplication().runWriteAction(() -> {
                                com.intellij.openapi.command.CommandProcessor.getInstance().executeCommand(
                                    project, () -> doc.setText(newContent), "Write File", null);
                            });
                            autoFormatAfterWrite(pathStr);
                            resultFuture.complete("Written: " + pathStr + " (" + newContent.length() + " chars)");
                        } else {
                            // Fallback: write to VFS directly
                            ApplicationManager.getApplication().runWriteAction(() -> {
                                try (var os = vf.getOutputStream(this)) {
                                    os.write(newContent.getBytes(StandardCharsets.UTF_8));
                                } catch (IOException e) {
                                    resultFuture.complete("Error writing: " + e.getMessage());
                                }
                            });
                            resultFuture.complete("Written: " + pathStr);
                        }
                    }
                } else if (args.has("old_str") && args.has("new_str")) {
                    // Partial edit: replace old_str with new_str in the file
                    if (vf == null) {
                        resultFuture.complete("File not found: " + pathStr);
                        return;
                    }
                    Document doc = FileDocumentManager.getInstance().getDocument(vf);
                    if (doc == null) {
                        resultFuture.complete("Cannot open document: " + pathStr);
                        return;
                    }
                    String oldStr = args.get("old_str").getAsString();
                    String newStr = args.get("new_str").getAsString();
                    String text = doc.getText();
                    int idx = text.indexOf(oldStr);
                    int matchLen = oldStr.length();
                    if (idx == -1) {
                        // Fallback: normalize Unicode chars and retry
                        String normText = normalizeForMatch(text);
                        String normOld = normalizeForMatch(oldStr);
                        idx = normText.indexOf(normOld);
                        if (idx != -1) {
                            // Find the actual end position in the original text
                            matchLen = findOriginalLength(text, idx, normOld.length());
                        }
                    }
                    if (idx == -1) {
                        // Show a snippet of the document to help debug
                        String preview = text.length() > 200 ? text.substring(0, 200) + "..." : text;
                        resultFuture.complete("old_str not found in " + pathStr +
                            ". Ensure the text matches exactly (check special characters, whitespace, line endings)." +
                            "\nFile starts with: " + preview.replace("\n", "\\n").substring(0, Math.min(preview.length(), 150)));
                        return;
                    }
                    // Check for multiple matches using same strategy
                    String checkText = (matchLen == oldStr.length()) ? text : normalizeForMatch(text);
                    String checkOld = (matchLen == oldStr.length()) ? oldStr : normalizeForMatch(oldStr);
                    if (checkText.indexOf(checkOld, idx + 1) != -1) {
                        resultFuture.complete("old_str matches multiple locations in " + pathStr + ". Make it more specific.");
                        return;
                    }
                    final int finalIdx = idx;
                    final int finalLen = matchLen;
                    ApplicationManager.getApplication().runWriteAction(() -> {
                        com.intellij.openapi.command.CommandProcessor.getInstance().executeCommand(
                            project, () -> doc.replaceString(finalIdx, finalIdx + finalLen, newStr),
                            "Edit File", null);
                    });
                    autoFormatAfterWrite(pathStr);
                    resultFuture.complete("Edited: " + pathStr + " (replaced " + finalLen + " chars with " + newStr.length() + " chars)");
                } else {
                    resultFuture.complete("write_file requires either 'content' (full write) or 'old_str'+'new_str' (partial edit)");
                }
            } catch (Exception e) {
                resultFuture.complete("Error: " + e.getMessage());
            }
        });

        return resultFuture.get(15, TimeUnit.SECONDS);
    }

    /**
     * Auto-format and optimize imports on a file after a write operation.
     * Runs asynchronously on EDT — does not block the caller.
     */
    private void autoFormatAfterWrite(String pathStr) {
        ApplicationManager.getApplication().invokeLater(() -> {
            try {
                VirtualFile vf = resolveVirtualFile(pathStr);
                if (vf == null) return;
                PsiFile psiFile = PsiManager.getInstance(project).findFile(vf);
                if (psiFile == null) return;

                ApplicationManager.getApplication().runWriteAction(() -> {
                    com.intellij.openapi.command.CommandProcessor.getInstance().executeCommand(project, () -> {
                        com.intellij.psi.PsiDocumentManager.getInstance(project).commitAllDocuments();
                        new com.intellij.codeInsight.actions.OptimizeImportsProcessor(project, psiFile).run();
                        new com.intellij.codeInsight.actions.ReformatCodeProcessor(psiFile, false).run();
                    }, "Auto-format after write", null);
                });
                LOG.info("Auto-formatted after write: " + pathStr);
            } catch (Exception e) {
                LOG.warn("Auto-format failed for " + pathStr + ": " + e.getMessage());
            }
        });
    }

    /**
     * Normalize text for fuzzy matching: replace common Unicode variants with ASCII equivalents.
     * This handles em-dashes, smart quotes, non-breaking spaces, etc. that LLMs often can't reproduce exactly.
     */
    private static String normalizeForMatch(String s) {
        return s.replace('\u2014', '-')   // em-dash → hyphen
                .replace('\u2013', '-')   // en-dash → hyphen
                .replace('\u2018', '\'')  // left single quote
                .replace('\u2019', '\'')  // right single quote
                .replace('\u201C', '"')   // left double quote
                .replace('\u201D', '"')   // right double quote
                .replace('\u00A0', ' ')   // non-breaking space
                .replace("\r\n", "\n")    // CRLF → LF
                .replace('\r', '\n');     // CR → LF
    }

    /**
     * Find the length in the original text that corresponds to a given length in the normalized text,
     * starting from the given position. This accounts for multi-byte chars that normalize to single chars.
     */
    private static int findOriginalLength(String original, int startIdx, int normalizedLen) {
        int origPos = startIdx;
        int normCount = 0;
        while (normCount < normalizedLen && origPos < original.length()) {
            char c = original.charAt(origPos);
            // CRLF counts as 1 normalized char
            if (c == '\r' && origPos + 1 < original.length() && original.charAt(origPos + 1) == '\n') {
                origPos += 2;
            } else {
                origPos++;
            }
            normCount++;
        }
        return origPos - startIdx;
    }

    // ---- Git tools ----

    /**
     * Execute a git command in the project root directory.
     * Returns stdout on success, or "Error: ..." on failure.
     */
    private String runGit(String... args) throws Exception {
        String basePath = project.getBasePath();
        if (basePath == null) return "Error: no project base path";

        List<String> cmd = new ArrayList<>();
        cmd.add("git");
        cmd.add("--no-pager");
        cmd.addAll(Arrays.asList(args));

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(new java.io.File(basePath));
        pb.redirectErrorStream(false);
        Process p = pb.start();

        String stdout = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String stderr = new String(p.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);

        boolean finished = p.waitFor(30, TimeUnit.SECONDS);
        if (!finished) {
            p.destroyForcibly();
            return "Error: git command timed out";
        }

        if (p.exitValue() != 0) {
            return "Error (exit " + p.exitValue() + "): " + stderr.trim();
        }
        return stdout;
    }

    private String gitStatus(JsonObject args) throws Exception {
        boolean verbose = args.has("verbose") && args.get("verbose").getAsBoolean();
        if (verbose) {
            return runGit("status");
        }
        return runGit("status", "--short", "--branch");
    }

    private String gitDiff(JsonObject args) throws Exception {
        List<String> gitArgs = new ArrayList<>();
        gitArgs.add("diff");

        if (args.has("staged") && args.get("staged").getAsBoolean()) {
            gitArgs.add("--cached");
        }
        if (args.has("commit")) {
            gitArgs.add(args.get("commit").getAsString());
        }
        if (args.has("path")) {
            gitArgs.add("--");
            gitArgs.add(args.get("path").getAsString());
        }
        if (args.has("stat_only") && args.get("stat_only").getAsBoolean()) {
            gitArgs.add(1, "--stat");
        }
        return runGit(gitArgs.toArray(new String[0]));
    }

    private String gitLog(JsonObject args) throws Exception {
        List<String> gitArgs = new ArrayList<>();
        gitArgs.add("log");

        int maxCount = args.has("max_count") ? args.get("max_count").getAsInt() : 20;
        gitArgs.add("-" + maxCount);

        String format = args.has("format") ? args.get("format").getAsString() : "medium";
        switch (format) {
            case "oneline" -> gitArgs.add("--oneline");
            case "short" -> gitArgs.add("--format=%h %s (%an, %ar)");
            case "full" -> gitArgs.add("--format=commit %H%nAuthor: %an <%ae>%nDate:   %ad%n%n    %s%n%n%b");
            default -> {
            } // "medium" is git default
        }

        if (args.has("author")) {
            gitArgs.add("--author=" + args.get("author").getAsString());
        }
        if (args.has("since")) {
            gitArgs.add("--since=" + args.get("since").getAsString());
        }
        if (args.has("path")) {
            gitArgs.add("--");
            gitArgs.add(args.get("path").getAsString());
        }
        if (args.has("branch")) {
            gitArgs.add(2, args.get("branch").getAsString());
        }
        return runGit(gitArgs.toArray(new String[0]));
    }

    private String gitBlame(JsonObject args) throws Exception {
        if (!args.has("path")) return "Error: 'path' parameter is required";

        List<String> gitArgs = new ArrayList<>();
        gitArgs.add("blame");

        if (args.has("line_start") && args.has("line_end")) {
            gitArgs.add("-L");
            gitArgs.add(args.get("line_start").getAsInt() + "," + args.get("line_end").getAsInt());
        }

        gitArgs.add(args.get("path").getAsString());
        return runGit(gitArgs.toArray(new String[0]));
    }

    private String gitCommit(JsonObject args) throws Exception {
        if (!args.has("message")) return "Error: 'message' parameter is required";

        // Save all documents before committing to ensure disk matches editor state
        ApplicationManager.getApplication().invokeAndWait(() ->
            ApplicationManager.getApplication().runWriteAction(() ->
                FileDocumentManager.getInstance().saveAllDocuments()));

        List<String> gitArgs = new ArrayList<>();
        gitArgs.add("commit");

        if (args.has("amend") && args.get("amend").getAsBoolean()) {
            gitArgs.add("--amend");
        }
        if (args.has("all") && args.get("all").getAsBoolean()) {
            gitArgs.add("--all");
        }

        gitArgs.add("-m");
        gitArgs.add(args.get("message").getAsString());

        return runGit(gitArgs.toArray(new String[0]));
    }

    private String gitStage(JsonObject args) throws Exception {
        List<String> gitArgs = new ArrayList<>();
        gitArgs.add("add");

        if (args.has("all") && args.get("all").getAsBoolean()) {
            gitArgs.add("--all");
        } else if (args.has("paths")) {
            for (var elem : args.getAsJsonArray("paths")) {
                gitArgs.add(elem.getAsString());
            }
        } else if (args.has("path")) {
            gitArgs.add(args.get("path").getAsString());
        } else {
            return "Error: 'path', 'paths', or 'all' parameter is required";
        }

        return runGit(gitArgs.toArray(new String[0]));
    }

    private String gitUnstage(JsonObject args) throws Exception {
        List<String> gitArgs = new ArrayList<>();
        gitArgs.add("restore");
        gitArgs.add("--staged");

        if (args.has("paths")) {
            for (var elem : args.getAsJsonArray("paths")) {
                gitArgs.add(elem.getAsString());
            }
        } else if (args.has("path")) {
            gitArgs.add(args.get("path").getAsString());
        } else {
            return "Error: 'path' or 'paths' parameter is required";
        }

        return runGit(gitArgs.toArray(new String[0]));
    }

    private String gitBranch(JsonObject args) throws Exception {
        String action = args.has("action") ? args.get("action").getAsString() : "list";

        return switch (action) {
            case "list" -> {
                boolean all = args.has("all") && args.get("all").getAsBoolean();
                yield runGit("branch", all ? "--all" : "--list", "-v");
            }
            case "create" -> {
                if (!args.has("name")) yield "Error: 'name' required for create";
                String base = args.has("base") ? args.get("base").getAsString() : "HEAD";
                yield runGit("branch", args.get("name").getAsString(), base);
            }
            case "switch", "checkout" -> {
                if (!args.has("name")) yield "Error: 'name' required for switch";
                yield runGit("switch", args.get("name").getAsString());
            }
            case "delete" -> {
                if (!args.has("name")) yield "Error: 'name' required for delete";
                boolean force = args.has("force") && args.get("force").getAsBoolean();
                yield runGit("branch", force ? "-D" : "-d", args.get("name").getAsString());
            }
            default -> "Error: unknown action '" + action + "'. Use: list, create, switch, delete";
        };
    }

    private String gitStash(JsonObject args) throws Exception {
        String action = args.has("action") ? args.get("action").getAsString() : "list";

        return switch (action) {
            case "list" -> runGit("stash", "list");
            case "push", "save" -> {
                List<String> gitArgs = new ArrayList<>(List.of("stash", "push"));
                if (args.has("message")) {
                    gitArgs.add("-m");
                    gitArgs.add(args.get("message").getAsString());
                }
                if (args.has("include_untracked") && args.get("include_untracked").getAsBoolean()) {
                    gitArgs.add("--include-untracked");
                }
                yield runGit(gitArgs.toArray(new String[0]));
            }
            case "pop" -> {
                String index = args.has("index") ? args.get("index").getAsString() : "";
                yield index.isEmpty() ? runGit("stash", "pop") : runGit("stash", "pop", "stash@{" + index + "}");
            }
            case "apply" -> {
                String index = args.has("index") ? args.get("index").getAsString() : "";
                yield index.isEmpty() ? runGit("stash", "apply") : runGit("stash", "apply", "stash@{" + index + "}");
            }
            case "drop" -> {
                String index = args.has("index") ? args.get("index").getAsString() : "";
                yield index.isEmpty() ? runGit("stash", "drop") : runGit("stash", "drop", "stash@{" + index + "}");
            }
            default -> "Error: unknown stash action '" + action + "'. Use: list, push, pop, apply, drop";
        };
    }

    private String gitShow(JsonObject args) throws Exception {
        List<String> gitArgs = new ArrayList<>();
        gitArgs.add("show");

        String ref = args.has("ref") ? args.get("ref").getAsString() : "HEAD";
        gitArgs.add(ref);

        if (args.has("stat_only") && args.get("stat_only").getAsBoolean()) {
            gitArgs.add("--stat");
        }
        if (args.has("path")) {
            gitArgs.add("--");
            gitArgs.add(args.get("path").getAsString());
        }
        return runGit(gitArgs.toArray(new String[0]));
    }

    // ---- End git tools ----

    // ---- Infrastructure tools ----

    private String httpRequest(JsonObject args) throws Exception {
        String urlStr = args.get("url").getAsString();
        String method = args.has("method") ? args.get("method").getAsString().toUpperCase() : "GET";
        String body = args.has("body") ? args.get("body").getAsString() : null;

        URL url = URI.create(urlStr).toURL();
        java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
        conn.setRequestMethod(method);
        conn.setConnectTimeout(10_000);
        conn.setReadTimeout(30_000);

        // Set headers
        if (args.has("headers")) {
            JsonObject headers = args.getAsJsonObject("headers");
            for (String key : headers.keySet()) {
                conn.setRequestProperty(key, headers.get(key).getAsString());
            }
        }

        // Write body
        if (body != null && ("POST".equals(method) || "PUT".equals(method) || "PATCH".equals(method))) {
            if (!args.has("headers") || !args.getAsJsonObject("headers").has("Content-Type")) {
                conn.setRequestProperty("Content-Type", "application/json");
            }
            conn.setDoOutput(true);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.getBytes(StandardCharsets.UTF_8));
            }
        }

        int status = conn.getResponseCode();
        StringBuilder result = new StringBuilder();
        result.append("HTTP ").append(status).append(" ").append(conn.getResponseMessage()).append("\n");

        // Response headers
        result.append("\n--- Headers ---\n");
        conn.getHeaderFields().forEach((k, v) -> {
            if (k != null) result.append(k).append(": ").append(String.join(", ", v)).append("\n");
        });

        // Response body
        result.append("\n--- Body ---\n");
        try (InputStream is = status >= 400 ? conn.getErrorStream() : conn.getInputStream()) {
            if (is != null) {
                String responseBody = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                result.append(truncateOutput(responseBody));
            }
        }
        return result.toString();
    }

    private String runCommand(JsonObject args) throws Exception {
        String command = args.get("command").getAsString();
        String basePath = project.getBasePath();
        if (basePath == null) return "No project base path";
        int timeoutSec = args.has("timeout") ? args.get("timeout").getAsInt() : 60;

        GeneralCommandLine cmd;
        if (System.getProperty("os.name").contains("Win")) {
            cmd = new GeneralCommandLine("cmd", "/c", command);
        } else {
            cmd = new GeneralCommandLine("sh", "-c", command);
        }
        cmd.setWorkDirectory(basePath);

        // Set JAVA_HOME from project SDK if available
        String javaHome = getProjectJavaHome();
        if (javaHome != null) {
            cmd.withEnvironment("JAVA_HOME", javaHome);
        }

        CompletableFuture<Integer> exitFuture = new CompletableFuture<>();
        StringBuilder output = new StringBuilder();

        OSProcessHandler processHandler = new OSProcessHandler(cmd);
        processHandler.addProcessListener(new ProcessListener() {
            @Override
            public void onTextAvailable(@NotNull ProcessEvent event, @NotNull com.intellij.openapi.util.Key outputType) {
                output.append(event.getText());
            }

            @Override
            public void processTerminated(@NotNull ProcessEvent event) {
                exitFuture.complete(event.getExitCode());
            }
        });

        // Show in IntelliJ Run panel
        ApplicationManager.getApplication().invokeLater(() -> {
            try {
                new RunContentExecutor(project, processHandler)
                    .withTitle("Command: " + truncateForTitle(command))
                    .withActivateToolWindow(true)
                    .run();
            } catch (Exception e) {
                LOG.warn("Could not show in Run panel", e);
                processHandler.startNotify();
            }
        });

        int exitCode;
        try {
            exitCode = exitFuture.get(timeoutSec, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            processHandler.destroyProcess();
            return "Command timed out after " + timeoutSec + " seconds.\n\n" + truncateOutput(output.toString());
        }

        return (exitCode == 0 ? "✓ Command succeeded" : "✗ Command failed (exit code " + exitCode + ")")
            + "\n\n" + truncateOutput(output.toString());
    }

    private static String truncateForTitle(String command) {
        return command.length() > 40 ? command.substring(0, 37) + "..." : command;
    }

    private String readIdeLog(JsonObject args) throws IOException {
        int lines = args.has("lines") ? args.get("lines").getAsInt() : 50;
        String filter = args.has("filter") ? args.get("filter").getAsString() : null;
        String level = args.has("level") ? args.get("level").getAsString().toUpperCase() : null;

        Path logFile = Path.of(System.getProperty("idea.log.path", ""), "idea.log");
        if (!Files.exists(logFile)) {
            // Try standard location
            String logDir = System.getProperty("idea.system.path");
            if (logDir != null) {
                logFile = Path.of(logDir, "..", "log", "idea.log");
            }
        }
        if (!Files.exists(logFile)) {
            // Try via PathManager
            try {
                Class<?> pm = Class.forName("com.intellij.openapi.application.PathManager");
                String logPath = (String) pm.getMethod("getLogPath").invoke(null);
                logFile = Path.of(logPath, "idea.log");
            } catch (Exception ignored) {
            }
        }
        if (!Files.exists(logFile)) {
            return "Could not locate idea.log";
        }

        List<String> filtered = Files.readAllLines(logFile);

        if (level != null) {
            final String lvl = level;
            filtered = filtered.stream()
                .filter(l -> l.contains(lvl))
                .toList();
        }
        if (filter != null) {
            final String f = filter;
            filtered = filtered.stream()
                .filter(l -> l.contains(f))
                .toList();
        }

        int start = Math.max(0, filtered.size() - lines);
        List<String> result = filtered.subList(start, filtered.size());
        return String.join("\n", result);
    }

    private String getNotifications() {
        StringBuilder result = new StringBuilder();
        try {
            // Get notifications via EventLog / NotificationsManager
            var notifications = com.intellij.notification.NotificationsManager.getNotificationsManager()
                .getNotificationsOfType(com.intellij.notification.Notification.class, project);
            if (notifications.length == 0) {
                return "No recent notifications.";
            }
            for (var notification : notifications) {
                result.append("[").append(notification.getType()).append("] ");
                if (notification.getTitle() != null && !notification.getTitle().isEmpty()) {
                    result.append(notification.getTitle()).append(": ");
                }
                result.append(notification.getContent()).append("\n");
            }
        } catch (Exception e) {
            return "Could not read notifications: " + e.getMessage();
        }
        return result.toString();
    }

    // ---- Terminal tools ----

    private String runInTerminal(JsonObject args) {
        String command = args.get("command").getAsString();
        String tabName = args.has("tab_name") ? args.get("tab_name").getAsString() : null;
        boolean newTab = args.has("new_tab") && args.get("new_tab").getAsBoolean();
        String shell = args.has("shell") ? args.get("shell").getAsString() : null;

        CompletableFuture<String> resultFuture = new CompletableFuture<>();

        ApplicationManager.getApplication().invokeLater(() -> {
            try {
                var managerClass = Class.forName("org.jetbrains.plugins.terminal.TerminalToolWindowManager");
                var manager = managerClass.getMethod("getInstance", Project.class).invoke(null, project);

                Object widget = null;
                String usedTab = null;

                // Try to reuse existing terminal tab via Content userData
                if (tabName != null && !newTab) {
                    widget = findTerminalWidgetByTabName(managerClass, tabName);
                    if (widget != null) usedTab = tabName;
                }

                // Create new tab if no existing tab found or new_tab requested
                if (widget == null) {
                    String title = tabName != null ? tabName : "Agent: " + truncateForTitle(command);
                    List<String> shellCommand = shell != null ? List.of(shell) : null;
                    var createSession = managerClass.getMethod("createNewSession",
                        String.class, String.class, List.class, boolean.class, boolean.class);
                    widget = createSession.invoke(manager, project.getBasePath(), title, shellCommand, true, true);
                    usedTab = title + " (new)";
                }

                // Send command via TerminalWidget.sendCommandToExecute (works for both classic and block terminal)
                sendTerminalCommand(widget, command);
                resultFuture.complete("Command sent to terminal '" + usedTab + "': " + command +
                    "\n\nNote: Use read_terminal_output to read terminal content, or run_command if you need output returned directly.");

            } catch (ClassNotFoundException e) {
                resultFuture.complete("Terminal plugin not available. Use run_command tool instead.");
            } catch (Exception e) {
                LOG.warn("Failed to open terminal", e);
                resultFuture.complete("Failed to open terminal: " + e.getMessage() + ". Use run_command tool instead.");
            }
        });

        try {
            return resultFuture.get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            return "Terminal opened (response timed out, but command was likely sent).";
        }
    }

    /**
     * Send a command to a TerminalWidget, using the interface method to avoid IllegalAccessException.
     */
    private void sendTerminalCommand(Object widget, String command) throws Exception {
        // Resolve method via the interface class, not the implementation (avoids IllegalAccessException on inner classes)
        var widgetInterface = Class.forName("com.intellij.terminal.ui.TerminalWidget");
        try {
            widgetInterface.getMethod("sendCommandToExecute", String.class).invoke(widget, command);
        } catch (NoSuchMethodException e) {
            widget.getClass().getMethod("executeCommand", String.class).invoke(widget, command);
        }
    }

    /**
     * Find a TerminalWidget by tab name using Content userData.
     */
    private Object findTerminalWidgetByTabName(Class<?> managerClass, String tabName) {
        try {
            var toolWindow = com.intellij.openapi.wm.ToolWindowManager.getInstance(project).getToolWindow("Terminal");
            if (toolWindow == null) return null;

            var findWidgetByContent = managerClass.getMethod("findWidgetByContent",
                com.intellij.ui.content.Content.class);

            for (var content : toolWindow.getContentManager().getContents()) {
                String displayName = content.getDisplayName();
                if (displayName != null && displayName.contains(tabName)) {
                    Object widget = findWidgetByContent.invoke(null, content);
                    if (widget != null) {
                        LOG.info("Reusing terminal tab '" + displayName + "'");
                        return widget;
                    }
                    // Reworked terminal (IntelliJ 2025+) may not set userData — tab not reusable
                }
            }
        } catch (Exception e) {
            LOG.warn("Could not find terminal tab: " + tabName, e);
        }
        return null;
    }

    /**
     * Read terminal output from a named tab using TerminalWidget.getText().
     */
    private String readTerminalOutput(JsonObject args) {
        String tabName = args.has("tab_name") ? args.get("tab_name").getAsString() : null;

        CompletableFuture<String> resultFuture = new CompletableFuture<>();

        ApplicationManager.getApplication().invokeLater(() -> {
            try {
                var managerClass = Class.forName("org.jetbrains.plugins.terminal.TerminalToolWindowManager");
                var toolWindow = com.intellij.openapi.wm.ToolWindowManager.getInstance(project).getToolWindow("Terminal");
                if (toolWindow == null) {
                    resultFuture.complete("Terminal tool window not available.");
                    return;
                }

                var contentManager = toolWindow.getContentManager();

                // Find the target content - by name or use selected
                com.intellij.ui.content.Content targetContent = null;
                if (tabName != null) {
                    for (var content : contentManager.getContents()) {
                        String displayName = content.getDisplayName();
                        if (displayName != null && displayName.contains(tabName)) {
                            targetContent = content;
                            break;
                        }
                    }
                }
                if (targetContent == null) {
                    targetContent = contentManager.getSelectedContent();
                }
                if (targetContent == null) {
                    resultFuture.complete("No terminal tab found" +
                        (tabName != null ? " matching '" + tabName + "'" : "") + ".");
                    return;
                }

                // Find widget via findWidgetByContent
                var findWidgetByContent = managerClass.getMethod("findWidgetByContent",
                    com.intellij.ui.content.Content.class);
                Object widget = findWidgetByContent.invoke(null, targetContent);
                if (widget == null) {
                    resultFuture.complete("No terminal widget found for tab '" + targetContent.getDisplayName() +
                        "'. The auto-created default tab may not be readable — use agent-created tabs instead.");
                    return;
                }

                // Call getText() via the TerminalWidget interface to avoid IllegalAccessException
                try {
                    var widgetInterface = Class.forName("com.intellij.terminal.ui.TerminalWidget");
                    var getText = widgetInterface.getMethod("getText");
                    CharSequence text = (CharSequence) getText.invoke(widget);
                    String output = text != null ? text.toString().strip() : "";
                    if (output.isEmpty()) {
                        resultFuture.complete("Terminal '" + targetContent.getDisplayName() + "' has no output.");
                    } else {
                        resultFuture.complete("Terminal '" + targetContent.getDisplayName() + "' output:\n" +
                            truncateOutput(output));
                    }
                } catch (NoSuchMethodException e) {
                    resultFuture.complete("getText() not available on this terminal type (" +
                        widget.getClass().getSimpleName() + "). Terminal output reading not supported.");
                }

            } catch (Exception e) {
                LOG.warn("Failed to read terminal output", e);
                resultFuture.complete("Failed to read terminal output: " + e.getMessage());
            }
        });

        try {
            return resultFuture.get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            return "Timed out reading terminal output.";
        }
    }

    private String listTerminals() {
        StringBuilder result = new StringBuilder();

        // 1. Show currently open terminal tabs
        result.append("Open terminal tabs:\n");
        try {
            var toolWindowManager = com.intellij.openapi.wm.ToolWindowManager.getInstance(project);
            var toolWindow = toolWindowManager.getToolWindow("Terminal");
            if (toolWindow != null) {
                var contentManager = toolWindow.getContentManager();
                var contents = contentManager.getContents();
                if (contents.length == 0) {
                    result.append("  (none)\n");
                } else {
                    for (var content : contents) {
                        String name = content.getDisplayName();
                        boolean selected = content == contentManager.getSelectedContent();
                        result.append(selected ? "  ▸ " : "  • ").append(name).append("\n");
                    }
                }
            } else {
                result.append("  (Terminal tool window not available)\n");
            }
        } catch (Exception e) {
            result.append("  (Could not list open terminals)\n");
        }

        // 2. Available shells
        result.append("\nAvailable shells:\n");
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("win")) {
            checkShell(result, "PowerShell", "C:\\Windows\\System32\\WindowsPowerShell\\v1.0\\powershell.exe");
            checkShell(result, "PowerShell 7", "C:\\Program Files\\PowerShell\\7\\pwsh.exe");
            checkShell(result, "Command Prompt", "C:\\Windows\\System32\\cmd.exe");
            checkShell(result, "Git Bash", "C:\\Program Files\\Git\\bin\\bash.exe");
            checkShell(result, "WSL", "C:\\Windows\\System32\\wsl.exe");
        } else {
            checkShell(result, "Bash", "/bin/bash");
            checkShell(result, "Zsh", "/bin/zsh");
            checkShell(result, "Fish", "/usr/bin/fish");
            checkShell(result, "sh", "/bin/sh");
        }

        // 3. IntelliJ default shell
        try {
            var settingsClass = Class.forName("org.jetbrains.plugins.terminal.TerminalProjectOptionsProvider");
            var getInstance = settingsClass.getMethod("getInstance", Project.class);
            var settings = getInstance.invoke(null, project);
            var getShellPath = settings.getClass().getMethod("getShellPath");
            String defaultShell = (String) getShellPath.invoke(settings);
            result.append("\nIntelliJ default shell: ").append(defaultShell);
        } catch (Exception e) {
            result.append("\nCould not determine IntelliJ default shell.");
        }

        result.append("\n\nTip: Use run_in_terminal with tab_name to reuse an existing tab, or new_tab=true to force a new one.");
        return result.toString();
    }

    private void checkShell(StringBuilder result, String name, String path) {
        java.io.File file = new java.io.File(path);
        if (file.exists()) {
            result.append("  ✓ ").append(name).append(" — ").append(path).append("\n");
        }
    }

    // ---- End terminal tools ----

    // ---- End infrastructure tools ----

    private String readRunOutput(JsonObject args) {
        int maxChars = args.has("max_chars") ? args.get("max_chars").getAsInt() : 8000;
        String tabName = args.has("tab_name") ? args.get("tab_name").getAsString() : null;

        return ApplicationManager.getApplication().runReadAction((Computable<String>) () -> {
            try {
                var manager = com.intellij.execution.ui.RunContentManager.getInstance(project);
                var descriptors = new java.util.ArrayList<>(manager.getAllDescriptors());

                // Also include debug session descriptors
                try {
                    var debugManager = com.intellij.xdebugger.XDebuggerManager.getInstance(project);
                    for (var session : debugManager.getDebugSessions()) {
                        var rd = session.getRunContentDescriptor();
                        if (rd != null && !descriptors.contains(rd)) {
                            descriptors.add(rd);
                        }
                    }
                } catch (Exception ignored) {
                    // XDebugger may not be available
                }

                if (descriptors.isEmpty()) {
                    return "No Run or Debug panel tabs available.";
                }

                // Find the matching descriptor (by tab name or most recent)
                com.intellij.execution.ui.RunContentDescriptor target = null;
                if (tabName != null) {
                    for (var d : descriptors) {
                        if (d.getDisplayName() != null && d.getDisplayName().contains(tabName)) {
                            target = d;
                            break;
                        }
                    }
                    if (target == null) {
                        StringBuilder available = new StringBuilder("No tab matching '").append(tabName).append("'. Available tabs:\n");
                        for (var d : descriptors) {
                            available.append("  - ").append(d.getDisplayName()).append("\n");
                        }
                        return available.toString();
                    }
                } else {
                    target = descriptors.getLast();
                }

                var console = target.getExecutionConsole();
                if (console == null) {
                    return "Tab '" + target.getDisplayName() + "' has no console.";
                }

                String text = extractConsoleText(console);

                if (text == null || text.isEmpty()) {
                    return "Tab '" + target.getDisplayName() + "' has no text content (console may still be loading or is an unsupported type).";
                }

                StringBuilder result = new StringBuilder();
                result.append("Tab: ").append(target.getDisplayName()).append("\n");
                result.append("Total length: ").append(text.length()).append(" chars\n\n");

                if (text.length() > maxChars) {
                    result.append("...(truncated, showing last ").append(maxChars).append(" of ").append(text.length()).append(" chars. Use max_chars parameter to read more.)\n");
                    result.append(text.substring(text.length() - maxChars));
                } else {
                    result.append(text);
                }

                return result.toString();
            } catch (Exception e) {
                return "Error reading Run output: " + e.getMessage();
            }
        });
    }

    /**
     * Extract text from any type of ExecutionConsole (regular, test runner, etc.)
     */
    private String extractConsoleText(com.intellij.execution.ui.ExecutionConsole console) {
        // 1. Try SMTRunnerConsoleView (test runner) — get both test tree and console output
        try {
            var getResultsViewer = console.getClass().getMethod("getResultsViewer");
            var viewer = getResultsViewer.invoke(console);
            if (viewer != null) {
                StringBuilder testOutput = new StringBuilder();
                // Get test tree summary via reflection
                var getAllTests = viewer.getClass().getMethod("getAllTests");
                var tests = (java.util.List<?>) getAllTests.invoke(viewer);
                if (tests != null && !tests.isEmpty()) {
                    testOutput.append("=== Test Results ===\n");
                    for (var test : tests) {
                        var getName = test.getClass().getMethod("getPresentableName");
                        var isPassed = test.getClass().getMethod("isPassed");
                        var isDefect = test.getClass().getMethod("isDefect");
                        String name = (String) getName.invoke(test);
                        boolean passed = (boolean) isPassed.invoke(test);
                        boolean defect = (boolean) isDefect.invoke(test);
                        String status = passed ? "✓ PASSED" : (defect ? "✗ FAILED" : "? UNKNOWN");
                        testOutput.append("  ").append(status).append(" ").append(name).append("\n");

                        // For failed tests, try to get the error message
                        if (defect) {
                            try {
                                var getErrorMessage = test.getClass().getMethod("getErrorMessage");
                                String errorMsg = (String) getErrorMessage.invoke(test);
                                if (errorMsg != null && !errorMsg.isEmpty()) {
                                    testOutput.append("    Error: ").append(errorMsg).append("\n");
                                }
                                var getStacktrace = test.getClass().getMethod("getStacktrace");
                                String stacktrace = (String) getStacktrace.invoke(test);
                                if (stacktrace != null && !stacktrace.isEmpty()) {
                                    testOutput.append("    Stacktrace:\n").append(stacktrace).append("\n");
                                }
                            } catch (NoSuchMethodException ignored) {
                            }
                        }
                    }
                }

                // Also get the console text portion of the test runner
                try {
                    var getConsole = console.getClass().getMethod("getConsole");
                    var innerConsole = getConsole.invoke(console);
                    if (innerConsole != null) {
                        String consoleText = extractPlainConsoleText(innerConsole);
                        if (consoleText != null && !consoleText.isEmpty()) {
                            testOutput.append("\n=== Console Output ===\n").append(consoleText);
                        }
                    }
                } catch (NoSuchMethodException ignored) {
                }

                if (!testOutput.isEmpty()) return testOutput.toString();
            }
        } catch (NoSuchMethodException ignored) {
            // Not an SMTRunnerConsoleView
        } catch (Exception e) {
            LOG.warn("Failed to extract test runner output", e);
        }

        // 2. Try plain ConsoleView getText()
        return extractPlainConsoleText(console);
    }

    /**
     * Extract plain text from a ConsoleView via getText() or editor document.
     */
    private String extractPlainConsoleText(Object console) {
        // Try getText()
        try {
            var getTextMethod = console.getClass().getMethod("getText");
            String text = (String) getTextMethod.invoke(console);
            if (text != null && !text.isEmpty()) return text;
        } catch (NoSuchMethodException ignored) {
        } catch (Exception e) {
            LOG.warn("getText() failed", e);
        }

        // Try editor → document
        try {
            var getEditorMethod = console.getClass().getMethod("getEditor");
            var editor = getEditorMethod.invoke(console);
            if (editor != null) {
                var getDocMethod = editor.getClass().getMethod("getDocument");
                var doc = getDocMethod.invoke(editor);
                if (doc instanceof Document document) {
                    return document.getText();
                }
            }
        } catch (Exception ignored) {
        }

        return null;
    }

    private record ClassInfo(String fqn, Module module) {
    }

    private ClassInfo resolveClass(String className) {
        return ReadAction.compute(() -> {
            String searchName = className.contains(".") ? className.substring(className.lastIndexOf('.') + 1) : className;
            List<ClassInfo> matches = new ArrayList<>();
            PsiSearchHelper.getInstance(project).processElementsWithWord(
                (element, offset) -> {
                    String type = classifyElement(element);
                    if ("class".equals(type) && element instanceof PsiNamedElement named
                        && searchName.equals(named.getName())) {
                        try {
                            var getQualifiedName = element.getClass().getMethod("getQualifiedName");
                            String fqn = (String) getQualifiedName.invoke(element);
                            if (fqn != null && (!className.contains(".") || fqn.equals(className))) {
                                VirtualFile vf = element.getContainingFile().getVirtualFile();
                                Module mod = vf != null
                                    ? ProjectFileIndex.getInstance(project).getModuleForFile(vf)
                                    : null;
                                matches.add(new ClassInfo(fqn, mod));
                            }
                        } catch (NoSuchMethodException | java.lang.reflect.InvocationTargetException
                                 | IllegalAccessException ignored) {
                        }
                    }
                    return true;
                },
                GlobalSearchScope.projectScope(project),
                searchName,
                UsageSearchContext.IN_CODE,
                true
            );
            return matches.isEmpty() ? new ClassInfo(className, null) : matches.getFirst();
        });
    }

    // ---- Test Tools ----

    private String listTests(JsonObject args) {
        String filePattern = args.has("file_pattern") ? args.get("file_pattern").getAsString() : "";

        return ReadAction.compute(() -> {
            List<String> tests = new ArrayList<>();
            String basePath = project.getBasePath();
            ProjectFileIndex fileIndex = ProjectFileIndex.getInstance(project);

            fileIndex.iterateContent(vf -> {
                if (vf.isDirectory()) return true;
                String name = vf.getName();
                if (!name.endsWith(".java") && !name.endsWith(".kt")) return true;
                if (!filePattern.isEmpty() && !matchGlob(name, filePattern)) return true;

                // Use IntelliJ's own test source classification (green background in project view)
                if (!fileIndex.isInTestSourceContent(vf)) return true;

                PsiFile psiFile = PsiManager.getInstance(project).findFile(vf);
                if (psiFile == null) return true;
                Document doc = FileDocumentManager.getInstance().getDocument(vf);

                psiFile.accept(new PsiRecursiveElementWalkingVisitor() {
                    @Override
                    public void visitElement(@NotNull PsiElement element) {
                        if (element instanceof PsiNamedElement named) {
                            String type = classifyElement(element);
                            if (("method".equals(type) || "function".equals(type))
                                && hasTestAnnotation(element)) {
                                String methodName = named.getName();
                                String className = getContainingClassName(element);
                                String relPath = relativize(basePath, vf.getPath());
                                int line = doc != null
                                    ? doc.getLineNumber(element.getTextOffset()) + 1 : 0;
                                tests.add(String.format("%s.%s (%s:%d)",
                                    className, methodName, relPath, line));
                            }
                        }
                        super.visitElement(element);
                    }
                });
                return tests.size() < 500;
            });

            if (tests.isEmpty()) return "No tests found";
            return tests.size() + " tests:\n" + String.join("\n", tests);
        });
    }

    private String runTests(JsonObject args) throws Exception {
        String target = args.get("target").getAsString();
        String module = args.has("module") ? args.get("module").getAsString() : "";
        String basePath = project.getBasePath();
        if (basePath == null) return "No project base path";

        // Try to find matching run config first
        String configResult = tryRunTestConfig(target);
        if (configResult != null) return configResult;

        // Fall back to Gradle
        String gradlew = basePath + (System.getProperty("os.name").contains("Win")
            ? "\\gradlew.bat" : "/gradlew");
        String taskPrefix = module.isEmpty() ? "" : ":" + module + ":";

        // Get JAVA_HOME from project SDK
        String javaHome = getProjectJavaHome();

        GeneralCommandLine cmd = new GeneralCommandLine();
        cmd.setExePath(gradlew);
        cmd.addParameters(taskPrefix + "test", "--tests", target);
        cmd.setWorkDirectory(basePath);
        if (javaHome != null) {
            cmd.withEnvironment("JAVA_HOME", javaHome);
        }

        CompletableFuture<Integer> exitFuture = new CompletableFuture<>();
        StringBuilder output = new StringBuilder();

        OSProcessHandler processHandler = new OSProcessHandler(cmd);
        processHandler.addProcessListener(new ProcessListener() {
            @Override
            public void onTextAvailable(@NotNull ProcessEvent event, @NotNull com.intellij.openapi.util.Key outputType) {
                output.append(event.getText());
            }

            @Override
            public void processTerminated(@NotNull ProcessEvent event) {
                exitFuture.complete(event.getExitCode());
            }
        });

        // Show in IntelliJ Run panel
        ApplicationManager.getApplication().invokeLater(() -> {
            try {
                new RunContentExecutor(project, processHandler)
                    .withTitle("Test: " + target)
                    .withActivateToolWindow(true)
                    .run();
            } catch (Exception e) {
                LOG.warn("Could not show in Run panel, starting headless", e);
                processHandler.startNotify();
            }
        });

        // Wait for completion (up to 120 seconds)
        int exitCode;
        try {
            exitCode = exitFuture.get(120, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            processHandler.destroyProcess();
            return "Tests timed out after 120 seconds. Partial output:\n"
                + truncateOutput(output.toString());
        }

        // Only use JUnit XML results if the build actually succeeded or tests ran
        // (exit code 0 or XML was freshly generated)
        if (exitCode == 0) {
            String xmlResults = parseJunitXmlResults(basePath, module);
            if (!xmlResults.isEmpty()) {
                return xmlResults;
            }
        }

        // Report failure with process output
        return (exitCode == 0 ? "✓ Tests PASSED" : "✗ Tests FAILED (exit code " + exitCode + ")")
            + "\n\n" + truncateOutput(output.toString());
    }

    private String getTestResults(JsonObject args) {
        String module = args.has("module") ? args.get("module").getAsString() : "";
        String basePath = project.getBasePath();
        if (basePath == null) return "No project base path";

        String results = parseJunitXmlResults(basePath, module);
        return results.isEmpty() ? "No test results found. Run tests first." : results;
    }

    private String getCoverage(JsonObject args) {
        String file = args.has("file") ? args.get("file").getAsString() : "";
        String basePath = project.getBasePath();
        if (basePath == null) return "No project base path";

        // Try JaCoCo XML report
        for (String module : List.of("", "plugin-core", "mcp-server")) {
            Path jacocoXml = module.isEmpty()
                ? Path.of(basePath, "build", "reports", "jacoco", "test", "jacocoTestReport.xml")
                : Path.of(basePath, module, "build", "reports", "jacoco", "test", "jacocoTestReport.xml");
            if (Files.exists(jacocoXml)) {
                return parseJacocoXml(jacocoXml, file);
            }
        }

        // Try IntelliJ's CoverageDataManager via reflection
        try {
            Class<?> cdmClass = Class.forName("com.intellij.coverage.CoverageDataManager");
            Object manager = project.getService(cdmClass);
            if (manager != null) {
                var getCurrentBundle = cdmClass.getMethod("getCurrentSuitesBundle");
                Object bundle = getCurrentBundle.invoke(manager);
                if (bundle != null) {
                    return "Coverage data available in IntelliJ. Use View > Tool Windows > Coverage to inspect.";
                }
            }
        } catch (Exception ignored) {
        }

        return "No coverage data found. Run tests with coverage first:\n"
            + "  - IntelliJ: Right-click test → Run with Coverage\n"
            + "  - Gradle: Add jacoco plugin and run `gradlew jacocoTestReport`";
    }

    // ---- Test & Run Helper Methods ----

    private String tryRunTestConfig(String target) {
        try {
            // Look for a matching test run configuration
            var configs = RunManager.getInstance(project).getAllSettings();
            for (var config : configs) {
                String typeName = config.getType().getDisplayName().toLowerCase();
                if ((typeName.contains("junit") || typeName.contains("test"))
                    && config.getName().contains(target)) {
                    return runConfiguration(createJsonWithName(config.getName()));
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private String getProjectJavaHome() {
        try {
            Sdk sdk = ProjectRootManager.getInstance(project).getProjectSdk();
            if (sdk != null && sdk.getHomePath() != null) {
                return sdk.getHomePath();
            }
        } catch (Exception ignored) {
        }
        // Don't fall back to IDE's JBR — let the system JAVA_HOME take effect
        return System.getenv("JAVA_HOME");
    }

    private static JsonObject createJsonWithName(String name) {
        JsonObject obj = new JsonObject();
        obj.addProperty("name", name);
        return obj;
    }

    private boolean hasTestAnnotation(PsiElement element) {
        // Use reflection to access PsiModifierListOwner (Java PSI, not compile-time available)
        try {
            var getModifierList = element.getClass().getMethod("getModifierList");
            Object modList = getModifierList.invoke(element);
            if (modList != null) {
                var getAnnotations = modList.getClass().getMethod("getAnnotations");
                Object[] annotations = (Object[]) getAnnotations.invoke(modList);
                for (Object anno : annotations) {
                    var getQualifiedName = anno.getClass().getMethod("getQualifiedName");
                    String qname = (String) getQualifiedName.invoke(anno);
                    if (qname != null && (qname.endsWith(".Test")
                        || qname.endsWith(".ParameterizedTest")
                        || qname.endsWith(".RepeatedTest"))) {
                        return true;
                    }
                }
            }
        } catch (Exception ignored) {
        }

        // Text-based fallback (catches Kotlin and edge cases)
        PsiElement prev = element.getPrevSibling();
        int depth = 0;
        while (prev != null && depth < 5) {
            // Stop at previous method/class/field declaration (don't look past it)
            if (prev instanceof PsiNamedElement && classifyElement(prev) != null) break;
            String text = prev.getText().trim();
            if (text.startsWith("@Test") || text.startsWith("@ParameterizedTest")
                || text.startsWith("@RepeatedTest")
                || text.startsWith("@org.junit")) {
                return true;
            }
            prev = prev.getPrevSibling();
            depth++;
        }
        return false;
    }

    private String getContainingClassName(PsiElement element) {
        PsiElement parent = element.getParent();
        while (parent != null) {
            if (parent instanceof PsiNamedElement named) {
                String type = classifyElement(parent);
                if ("class".equals(type)) return named.getName();
            }
            parent = parent.getParent();
        }
        return "UnknownClass";
    }

    private String parseJunitXmlResults(String basePath, String module) {
        List<Path> reportDirs = new ArrayList<>();
        if (module.isEmpty()) {
            // Search all modules
            try (var dirs = Files.walk(Path.of(basePath), 4)) {
                dirs.filter(p -> p.endsWith("test-results/test") && Files.isDirectory(p))
                    .forEach(reportDirs::add);
            } catch (IOException ignored) {
            }
        } else {
            Path dir = Path.of(basePath, module, "build", "test-results", "test");
            if (Files.isDirectory(dir)) reportDirs.add(dir);
        }

        if (reportDirs.isEmpty()) return "";

        int totalTests = 0, totalFailed = 0, totalErrors = 0, totalSkipped = 0;
        double totalTime = 0;
        List<String> failures = new ArrayList<>();

        for (Path reportDir : reportDirs) {
            try (var xmlFiles = Files.list(reportDir)) {
                for (Path xmlFile : xmlFiles.filter(p -> p.toString().endsWith(".xml")).toList()) {
                    try {
                        var doc = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                            .parse(xmlFile.toFile());
                        var suites = doc.getElementsByTagName("testsuite");
                        for (int i = 0; i < suites.getLength(); i++) {
                            var suite = suites.item(i);
                            totalTests += intAttr(suite, "tests");
                            totalFailed += intAttr(suite, "failures");
                            totalErrors += intAttr(suite, "errors");
                            totalSkipped += intAttr(suite, "skipped");
                            totalTime += doubleAttr(suite, "time");

                            // Collect failure details
                            var testcases = ((org.w3c.dom.Element) suite)
                                .getElementsByTagName("testcase");
                            for (int j = 0; j < testcases.getLength(); j++) {
                                var tc = testcases.item(j);
                                var failNodes = ((org.w3c.dom.Element) tc)
                                    .getElementsByTagName("failure");
                                if (failNodes.getLength() > 0) {
                                    String tcName = tc.getAttributes().getNamedItem("name")
                                        .getNodeValue();
                                    String cls = tc.getAttributes().getNamedItem("classname")
                                        .getNodeValue();
                                    String msg = failNodes.item(0).getAttributes()
                                        .getNamedItem("message").getNodeValue();
                                    failures.add(String.format("  ✗ %s.%s: %s", cls, tcName, msg));
                                }
                            }
                        }
                    } catch (Exception ignored) {
                    }
                }
            } catch (IOException ignored) {
            }
        }

        if (totalTests == 0) return "";

        int passed = totalTests - totalFailed - totalErrors - totalSkipped;
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("Test Results: %d tests, %d passed, %d failed, %d errors, %d skipped (%.1fs)\n",
            totalTests, passed, totalFailed, totalErrors, totalSkipped, totalTime));

        if (!failures.isEmpty()) {
            sb.append("\nFailures:\n");
            failures.forEach(f -> sb.append(f).append("\n"));
        }
        return sb.toString().trim();
    }

    private String parseJacocoXml(Path xmlPath, String fileFilter) {
        try {
            var doc = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(xmlPath.toFile());
            var packages = doc.getElementsByTagName("package");
            List<String> lines = new ArrayList<>();
            int totalLines = 0, coveredLines = 0;

            for (int i = 0; i < packages.getLength(); i++) {
                var pkg = (org.w3c.dom.Element) packages.item(i);
                var classes = pkg.getElementsByTagName("class");
                for (int j = 0; j < classes.getLength(); j++) {
                    var cls = (org.w3c.dom.Element) classes.item(j);
                    String name = cls.getAttribute("name").replace('/', '.');
                    if (!fileFilter.isEmpty() && !name.contains(fileFilter)) continue;

                    var counters = cls.getElementsByTagName("counter");
                    for (int k = 0; k < counters.getLength(); k++) {
                        var counter = counters.item(k);
                        if ("LINE".equals(counter.getAttributes().getNamedItem("type")
                            .getNodeValue())) {
                            int missed = intAttr(counter, "missed");
                            int covered = intAttr(counter, "covered");
                            totalLines += missed + covered;
                            coveredLines += covered;
                            double pct = covered * 100.0 / Math.max(1, missed + covered);
                            lines.add(String.format("  %s: %.1f%% (%d/%d lines)",
                                name, pct, covered, missed + covered));
                        }
                    }
                }
            }

            if (lines.isEmpty()) return "No line coverage data in JaCoCo report";
            double totalPct = coveredLines * 100.0 / Math.max(1, totalLines);
            return String.format("Coverage: %.1f%% overall (%d/%d lines)\n\n%s",
                totalPct, coveredLines, totalLines, String.join("\n", lines));
        } catch (Exception e) {
            return "Error parsing JaCoCo report: " + e.getMessage();
        }
    }

    private static int intAttr(org.w3c.dom.Node node, String attr) {
        var item = node.getAttributes().getNamedItem(attr);
        return item != null ? Integer.parseInt(item.getNodeValue()) : 0;
    }

    private static double doubleAttr(org.w3c.dom.Node node, String attr) {
        var item = node.getAttributes().getNamedItem(attr);
        return item != null ? Double.parseDouble(item.getNodeValue()) : 0.0;
    }

    private static String truncateOutput(String output) {
        if (output.length() <= 8000) return output;
        return "...(truncated)\n" + output.substring(output.length() - 8000);
    }

    // ---- Helper Methods ----

    private PsiElement findDefinition(String name, GlobalSearchScope scope) {
        PsiElement[] result = {null};
        PsiSearchHelper.getInstance(project).processElementsWithWord(
            (element, offsetInElement) -> {
                PsiElement parent = element.getParent();
                if (parent instanceof PsiNamedElement named && name.equals(named.getName())) {
                    String type = classifyElement(parent);
                    if (type != null && !type.equals("field")) {
                        result[0] = parent;
                        return false; // found one, stop
                    }
                }
                return true;
            },
            scope, name, UsageSearchContext.IN_CODE, true
        );
        return result[0];
    }

    /**
     * Classify a PsiElement by inspecting its runtime class name.
     * This avoids compile-time dependencies on Java/Kotlin plugin APIs
     * while still providing accurate type detection at runtime.
     */
    private static String classifyElement(PsiElement element) {
        String cls = element.getClass().getSimpleName();

        // Java PSI
        if (cls.contains("PsiClass") && !cls.contains("Initializer")) {
            // Distinguish interfaces and enums from regular classes via reflection
            try {
                if ((boolean) element.getClass().getMethod("isInterface").invoke(element)) return "interface";
                if ((boolean) element.getClass().getMethod("isEnum").invoke(element)) return "enum";
            } catch (NoSuchMethodException | java.lang.reflect.InvocationTargetException
                     | IllegalAccessException ignored) {
            }
            return "class";
        }
        if (cls.contains("PsiMethod")) return "method";
        if (cls.contains("PsiField")) return "field";
        if (cls.contains("PsiEnumConstant")) return "field";

        // Kotlin PSI
        if (cls.equals("KtClass") || cls.equals("KtObjectDeclaration")) {
            // KtClass can be class, interface, or enum — check via hasModifier or text
            try {
                // KtClass has isInterface() and isEnum() methods
                var isInterface = element.getClass().getMethod("isInterface");
                if ((boolean) isInterface.invoke(element)) return "interface";
                var isEnum = element.getClass().getMethod("isEnum");
                if ((boolean) isEnum.invoke(element)) return "enum";
            } catch (NoSuchMethodException | java.lang.reflect.InvocationTargetException
                     | IllegalAccessException ignored) {
            }
            return "class";
        }
        if (cls.equals("KtNamedFunction")) return "function";
        if (cls.equals("KtProperty")) return "field";
        if (cls.equals("KtParameter")) return null; // skip parameters
        if (cls.equals("KtTypeAlias")) return "class";

        // Generic patterns
        if (cls.contains("Interface") && !cls.contains("Reference")) return "interface";
        if (cls.contains("Enum") && cls.contains("Class")) return "class";

        return null;
    }

    private VirtualFile resolveVirtualFile(String path) {
        String normalized = path.replace('\\', '/');
        VirtualFile vf = LocalFileSystem.getInstance().findFileByPath(normalized);
        if (vf != null) return vf;

        String basePath = project.getBasePath();
        if (basePath != null) {
            vf = LocalFileSystem.getInstance().findFileByPath(basePath + "/" + normalized);
        }
        return vf;
    }

    private static String relativize(String basePath, String filePath) {
        String base = basePath.replace('\\', '/');
        String file = filePath.replace('\\', '/');
        return file.startsWith(base + "/") ? file.substring(base.length() + 1) : file;
    }

    private static String getLineText(Document doc, int lineIndex) {
        if (lineIndex < 0 || lineIndex >= doc.getLineCount()) return "";
        int start = doc.getLineStartOffset(lineIndex);
        int end = doc.getLineEndOffset(lineIndex);
        return doc.getText().substring(start, end).trim();
    }

    private static boolean matchGlob(String fileName, String pattern) {
        String regex = pattern.replace(".", "\\.").replace("*", ".*").replace("?", ".");
        return fileName.matches(regex);
    }

    private static String fileType(String name) {
        String l = name.toLowerCase();
        if (l.endsWith(".java")) return "Java";
        if (l.endsWith(".kt") || l.endsWith(".kts")) return "Kotlin";
        if (l.endsWith(".py")) return "Python";
        if (l.endsWith(".js") || l.endsWith(".jsx")) return "JavaScript";
        if (l.endsWith(".ts") || l.endsWith(".tsx")) return "TypeScript";
        if (l.endsWith(".go")) return "Go";
        if (l.endsWith(".xml")) return "XML";
        if (l.endsWith(".json")) return "JSON";
        if (l.endsWith(".gradle") || l.endsWith(".gradle.kts")) return "Gradle";
        if (l.endsWith(".yaml") || l.endsWith(".yml")) return "YAML";
        return "Other";
    }

    // ---- Documentation Tools ----

    private String getDocumentation(JsonObject args) {
        String symbol = args.has("symbol") ? args.get("symbol").getAsString() : "";
        if (symbol.isEmpty())
            return "Error: 'symbol' parameter required (e.g. java.util.List, com.google.gson.Gson.fromJson)";

        return ReadAction.compute(() -> {
            try {
                // Try to resolve as a fully qualified class name first
                GlobalSearchScope scope = GlobalSearchScope.allScope(project);
                PsiElement element;

                // Split into class and member parts
                String className;
                String memberName = null;

                // Check if symbol contains a member reference (e.g. java.util.List.add)
                // Try progressively shorter class names to find the class part
                Class<?> javaPsiFacadeClass = Class.forName("com.intellij.psi.JavaPsiFacade");
                Object facade = javaPsiFacadeClass.getMethod("getInstance", Project.class).invoke(null, project);

                // Try the full symbol as a class first
                PsiElement resolvedClass = (PsiElement) javaPsiFacadeClass.getMethod("findClass", String.class, GlobalSearchScope.class)
                    .invoke(facade, symbol, scope);

                className = symbol;

                if (resolvedClass == null) {
                    // Try splitting at the last dot to find class + member
                    int lastDot = symbol.lastIndexOf('.');
                    if (lastDot > 0) {
                        className = symbol.substring(0, lastDot);
                        memberName = symbol.substring(lastDot + 1);
                        resolvedClass = (PsiElement) javaPsiFacadeClass.getMethod("findClass", String.class, GlobalSearchScope.class)
                            .invoke(facade, className, scope);
                    }
                }

                if (resolvedClass == null) {
                    return "Symbol not found: " + symbol + ". Use a fully qualified name (e.g. java.util.List).";
                }

                element = resolvedClass;

                // If member name specified, find the member within the class
                if (memberName != null) {
                    PsiElement foundMember = null;
                    // Look for methods and fields
                    for (PsiElement child : resolvedClass.getChildren()) {
                        if (child instanceof PsiNamedElement named && memberName.equals(named.getName())) {
                            foundMember = child;
                            break;
                        }
                    }
                    if (foundMember != null) {
                        element = foundMember;
                    } else {
                        // Try inner classes
                        for (PsiElement child : resolvedClass.getChildren()) {
                            if (child instanceof PsiNamedElement) {
                                for (PsiElement grandchild : child.getChildren()) {
                                    if (grandchild instanceof PsiNamedElement named && memberName.equals(named.getName())) {
                                        foundMember = grandchild;
                                        break;
                                    }
                                }
                                if (foundMember != null) break;
                            }
                        }
                        if (foundMember != null) {
                            element = foundMember;
                        }
                    }
                }

                // Use DocumentationProvider to generate documentation
                Class<?> langDocClass = Class.forName("com.intellij.lang.LanguageDocumentation");
                Object langDocInstance = langDocClass.getField("INSTANCE").get(null);
                Object provider = langDocClass.getMethod("forLanguage", com.intellij.lang.Language.class)
                    .invoke(langDocInstance, element.getLanguage());

                if (provider == null) {
                    // Fallback: extract PsiDocComment directly for Java elements
                    return extractDocComment(element, symbol);
                }

                String doc = (String) provider.getClass().getMethod("generateDoc", PsiElement.class, PsiElement.class)
                    .invoke(provider, element, null);

                if (doc == null || doc.isEmpty()) {
                    return extractDocComment(element, symbol);
                }

                // Strip HTML tags for clean text output
                String text = doc.replaceAll("<[^>]+>", "")
                    .replaceAll("&nbsp;", " ")
                    .replaceAll("&lt;", "<")
                    .replaceAll("&gt;", ">")
                    .replaceAll("&amp;", "&")
                    .replaceAll("&#\\d+;", "")
                    .replaceAll("\n{3,}", "\n\n")
                    .trim();

                return truncateOutput("Documentation for " + symbol + ":\n\n" + text);
            } catch (Exception e) {
                LOG.warn("get_documentation error", e);
                return "Error retrieving documentation: " + e.getMessage();
            }
        });
    }

    private String extractDocComment(PsiElement element, String symbol) {
        // Fallback: try to get raw PsiDocComment for Java elements
        try {
            Class<?> docOwnerClass = Class.forName("com.intellij.psi.PsiDocCommentOwner");
            if (docOwnerClass.isInstance(element)) {
                Object docComment = docOwnerClass.getMethod("getDocComment").invoke(element);
                if (docComment != null) {
                    String text = ((PsiElement) docComment).getText();
                    // Clean up the comment markers
                    text = text.replaceAll("/\\*\\*", "")
                        .replaceAll("\\*/", "")
                        .replaceAll("(?m)^\\s*\\*\\s?", "")
                        .trim();
                    return "Documentation for " + symbol + ":\n\n" + text;
                }
            }
        } catch (ClassNotFoundException ignored) {
            // Not a Java environment
        } catch (Exception e) {
            LOG.warn("extractDocComment error", e);
        }

        // Last resort: show element text signature
        String elementText = element.getText();
        if (elementText.length() > 500) elementText = elementText.substring(0, 500) + "...";
        return "No documentation available for " + symbol + ". Element found:\n" + elementText;
    }

    @SuppressWarnings({"JavaReflectionMemberAccess", "JavaReflectionInvocation"})
    private String downloadSources(JsonObject args) {
        String library = args.has("library") ? args.get("library").getAsString() : "";

        try {
            java.util.concurrent.CompletableFuture<String> future = new java.util.concurrent.CompletableFuture<>();

            ApplicationManager.getApplication().invokeLater(() -> {
                try {
                    StringBuilder sb = new StringBuilder();

                    // Step 1: Enable "Download sources" in Gradle/Maven settings via ExternalSystem API
                    boolean settingChanged = enableDownloadSources(sb);

                    // Step 2: List current library source status
                    Module[] modules = ModuleManager.getInstance(project).getModules();
                    List<String> withSources = new ArrayList<>();
                    List<String> withoutSources = new ArrayList<>();

                    for (Module module : modules) {
                        ModuleRootManager rootManager = ModuleRootManager.getInstance(module);
                        for (var entry : rootManager.getOrderEntries()) {
                            if (entry instanceof com.intellij.openapi.roots.LibraryOrderEntry libEntry) {
                                var lib = libEntry.getLibrary();
                                if (lib == null) continue;
                                String entryName = entry.getPresentableName();

                                if (!library.isEmpty() && !entryName.toLowerCase().contains(library.toLowerCase())) {
                                    continue;
                                }

                                VirtualFile[] sources = lib.getFiles(com.intellij.openapi.roots.OrderRootType.SOURCES);
                                if (sources.length > 0) {
                                    withSources.add(entryName);
                                } else {
                                    withoutSources.add(entryName);
                                }
                            }
                        }
                    }

                    sb.append("Libraries with sources: ").append(withSources.size()).append("\n");
                    sb.append("Libraries without sources: ").append(withoutSources.size()).append("\n");

                    if (!withoutSources.isEmpty()) {
                        sb.append("\nMissing sources:\n");
                        for (String lib : withoutSources) {
                            sb.append("  - ").append(lib).append("\n");
                        }
                    }

                    if (!withSources.isEmpty() && !library.isEmpty()) {
                        sb.append("\nWith sources:\n");
                        for (String lib : withSources) {
                            sb.append("  ✓ ").append(lib).append("\n");
                        }
                    }

                    // Step 3: Trigger Gradle/Maven re-sync if setting was changed
                    if (settingChanged) {
                        triggerProjectResync(sb);
                    }

                    future.complete(truncateOutput(sb.toString()));
                } catch (Exception e) {
                    future.complete("Error: " + e.getMessage());
                }
            });

            return future.get(30, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception e) {
            LOG.warn("download_sources error", e);
            return "Error: " + e.getMessage();
        }
    }

    private boolean enableDownloadSources(StringBuilder sb) {
        try {
            // Access GradleSettings or ExternalSystemSettings to enable source download
            Class<?> gradleSettingsClass = Class.forName(
                "org.jetbrains.plugins.gradle.settings.GradleSettings");
            Object gradleSettings = gradleSettingsClass.getMethod("getInstance", Project.class)
                .invoke(null, project);

            // Get linked project settings
            java.util.Collection<?> linkedSettings = (java.util.Collection<?>)
                gradleSettingsClass.getMethod("getLinkedProjectsSettings").invoke(gradleSettings);

            if (linkedSettings == null || linkedSettings.isEmpty()) {
                sb.append("No Gradle project settings found.\n");
                return false;
            }

            boolean anyChanged = false;
            for (Object projectSettings : linkedSettings) {
                // Check current state
                Class<?> settingsClass = projectSettings.getClass();
                // The method is on ExternalProjectSettings (parent class)
                Class<?> externalSettingsClass = Class.forName(
                    "com.intellij.openapi.externalSystem.settings.ExternalProjectSettings");

                boolean currentDownloadSources = (boolean) externalSettingsClass
                    .getMethod("isResolveExternalAnnotations").invoke(projectSettings);

                // Try the Gradle-specific isDownloadSources if available,
                // otherwise use the resolve annotations approach
                try {
                    // In newer IntelliJ, the setting is "Resolve external annotations" + separate download
                    // For Gradle projects, try setResolveExternalAnnotations
                    Method resolveMethod = externalSettingsClass.getMethod("setResolveExternalAnnotations", boolean.class);
                    if (!currentDownloadSources) {
                        resolveMethod.invoke(projectSettings, true);
                        sb.append("Enabled 'Resolve external annotations' for Gradle project.\n");
                        anyChanged = true;
                    }
                } catch (NoSuchMethodException ignored) {
                }

                // Also try the direct download sources setting (may be in AdvancedSettings or GradleProjectSettings)
                try {
                    // GradleProjectSettings has isResolveModulePerSourceSet etc.
                    // Check for download sources via AdvancedSettings registry
                    Class<?> advancedSettingsClass = Class.forName(
                        "com.intellij.openapi.options.advanced.AdvancedSettings");
                    Method getBoolean = advancedSettingsClass.getMethod("getBoolean", String.class);
                    boolean currentValue = (boolean) getBoolean.invoke(null, "gradle.download.sources.on.sync");

                    if (!currentValue) {
                        Method setBoolean = advancedSettingsClass.getMethod("setBoolean", String.class, boolean.class);
                        setBoolean.invoke(null, "gradle.download.sources.on.sync", true);
                        sb.append("Enabled 'Download sources on sync' in Advanced Settings.\n");
                        anyChanged = true;
                    } else {
                        sb.append("'Download sources on sync' is already enabled.\n");
                    }
                } catch (Exception e) {
                    LOG.info("AdvancedSettings download sources not available: " + e.getMessage());
                    // Try older API path
                    try {
                        Method setDownload = settingsClass.getMethod("setDownloadSources", boolean.class);
                        Method getDownload = settingsClass.getMethod("isDownloadSources");
                        boolean current = (boolean) getDownload.invoke(projectSettings);
                        if (!current) {
                            setDownload.invoke(projectSettings, true);
                            sb.append("Enabled 'Download sources' for Gradle project.\n");
                            anyChanged = true;
                        } else {
                            sb.append("'Download sources' is already enabled.\n");
                        }
                    } catch (NoSuchMethodException ex) {
                        sb.append("Download sources setting not found in this IntelliJ version.\n");
                    }
                }
            }
            return anyChanged;
        } catch (ClassNotFoundException e) {
            // Not a Gradle project or Gradle plugin not available
            sb.append("Gradle plugin not available. ");
            // Try Maven
            return enableMavenDownloadSources(sb);
        } catch (Exception e) {
            LOG.warn("enableDownloadSources error", e);
            sb.append("Error enabling download sources: ").append(e.getMessage()).append("\n");
            return false;
        }
    }

    private boolean enableMavenDownloadSources(StringBuilder sb) {
        try {
            Class<?> mavenSettingsClass = Class.forName(
                "org.jetbrains.idea.maven.project.MavenImportingSettings");
            // Maven has a different settings path
            Class<?> mavenProjectsManagerClass = Class.forName(
                "org.jetbrains.idea.maven.project.MavenProjectsManager");
            Object manager = mavenProjectsManagerClass.getMethod("getInstance", Project.class)
                .invoke(null, project);
            Object importingSettings = mavenProjectsManagerClass.getMethod("getImportingSettings")
                .invoke(manager);

            Method setDownloadSources = mavenSettingsClass.getMethod("setDownloadSourcesAutomatically", boolean.class);
            Method getDownloadSources = mavenSettingsClass.getMethod("isDownloadSourcesAutomatically");
            Method setDownloadDocs = mavenSettingsClass.getMethod("setDownloadDocsAutomatically", boolean.class);

            boolean current = (boolean) getDownloadSources.invoke(importingSettings);
            if (!current) {
                setDownloadSources.invoke(importingSettings, true);
                setDownloadDocs.invoke(importingSettings, true);
                sb.append("Enabled 'Download sources and docs automatically' for Maven project.\n");
                return true;
            } else {
                sb.append("Maven 'Download sources automatically' is already enabled.\n");
                return false;
            }
        } catch (ClassNotFoundException e) {
            sb.append("Neither Gradle nor Maven plugin available.\n");
            return false;
        } catch (Exception e) {
            LOG.warn("enableMavenDownloadSources error", e);
            sb.append("Error enabling Maven source download: ").append(e.getMessage()).append("\n");
            return false;
        }
    }

    @SuppressWarnings({"JavaReflectionMemberAccess", "JavaReflectionInvocation"})
    private void triggerProjectResync(StringBuilder sb) {
        try {
            // Trigger ExternalSystem project refresh (works for both Gradle and Maven)
            Class<?> gradleConstantsClass = Class.forName(
                "org.jetbrains.plugins.gradle.util.GradleConstants");
            Object gradleSystemId = gradleConstantsClass.getField("SYSTEM_ID").get(null);

            // Trigger refresh
            Class<?> externalSystemUtil = Class.forName(
                "com.intellij.openapi.externalSystem.util.ExternalSystemUtil");
            externalSystemUtil.getMethod("refreshProject", Project.class,
                    Class.forName("com.intellij.openapi.externalSystem.model.ProjectSystemId"),
                    String.class, boolean.class, boolean.class)
                .invoke(null, project, gradleSystemId, project.getBasePath(), false, true);

            sb.append("\nTriggered Gradle project re-sync to download sources.\n");
            sb.append("Sources will be downloaded in the background. Check back shortly.\n");
        } catch (Exception e) {
            // Fallback: suggest manual resync
            LOG.info("Auto-resync not available: " + e.getMessage());
            sb.append("\nTo download sources, please re-sync the project:\n");
            sb.append("  Gradle: click 'Reload All Gradle Projects' in the Gradle tool window\n");
            sb.append("  Or: File → Reload All from Disk\n");
        }
    }

    /**
     * Create a scratch file with the given content and open it in the editor.
     * Supports syntax highlighting based on file extension.
     */
    private String createScratchFile(JsonObject args) {
        String name = args.has("name") ? args.get("name").getAsString() : "scratch.txt";
        String content = args.has("content") ? args.get("content").getAsString() : "";

        try {
            // Execute on EDT using invokeAndWait to block until completion
            final com.intellij.openapi.vfs.VirtualFile[] resultFile = new com.intellij.openapi.vfs.VirtualFile[1];
            final String[] errorMsg = new String[1];

            ApplicationManager.getApplication().invokeAndWait(() -> {
                try {
                    // Get scratch file service
                    com.intellij.ide.scratch.ScratchFileService scratchService =
                        com.intellij.ide.scratch.ScratchFileService.getInstance();
                    com.intellij.ide.scratch.ScratchRootType scratchRoot =
                        com.intellij.ide.scratch.ScratchRootType.getInstance();

                    // Create scratch file in write action (now on EDT)
                    resultFile[0] = ApplicationManager.getApplication().runWriteAction(
                        (Computable<com.intellij.openapi.vfs.VirtualFile>) () -> {
                            try {
                                com.intellij.openapi.vfs.VirtualFile file = scratchService.findFile(
                                    scratchRoot,
                                    name,
                                    com.intellij.ide.scratch.ScratchFileService.Option.create_if_missing
                                );

                                if (file != null) {
                                    java.io.OutputStream out = file.getOutputStream(null);
                                    out.write(content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                                    out.close();
                                }
                                return file;
                            } catch (java.io.IOException e) {
                                LOG.warn("Failed to create/write scratch file", e);
                                errorMsg[0] = e.getMessage();
                                return null;
                            }
                        }
                    );

                    // Open in editor (already on EDT)
                    if (resultFile[0] != null) {
                        com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project)
                            .openFile(resultFile[0], true);
                    }
                } catch (Exception e) {
                    LOG.warn("Failed in EDT execution", e);
                    errorMsg[0] = e.getMessage();
                }
            });

            if (resultFile[0] == null) {
                return "Error: Failed to create scratch file" +
                    (errorMsg[0] != null ? ": " + errorMsg[0] : "");
            }

            return "Created scratch file: " + resultFile[0].getPath() + " (" + content.length() + " chars)";
        } catch (Exception e) {
            LOG.warn("Failed to create scratch file", e);
            return "Error creating scratch file: " + e.getMessage();
        }
    }

    public void dispose() {
        if (httpServer != null) {
            httpServer.stop(0);
            httpServer = null;
            LOG.info("PSI Bridge stopped");
        }
        // Don't delete the bridge file in unit test mode (we didn't write it)
        if (ApplicationManager.getApplication().isUnitTestMode()) return;
        try {
            Path bridgeFile = Path.of(System.getProperty("user.home"), ".copilot", "psi-bridge.json");
            Files.deleteIfExists(bridgeFile);
        } catch (IOException e) {
            LOG.warn("Failed to clean up bridge file", e);
        }
    }
}
