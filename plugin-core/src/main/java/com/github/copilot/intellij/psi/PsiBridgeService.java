package com.github.copilot.intellij.psi;

import com.google.gson.*;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.projectRoots.Sdk;
import com.intellij.openapi.roots.ModuleRootManager;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.openapi.roots.ProjectRootManager;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.execution.*;
import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.execution.configurations.RunConfiguration;
import com.intellij.execution.executors.DefaultRunExecutor;
import com.intellij.execution.process.OSProcessHandler;
import com.intellij.execution.process.ProcessAdapter;
import com.intellij.execution.process.ProcessEvent;
import com.intellij.execution.runners.ExecutionEnvironmentBuilder;
import com.intellij.execution.runners.ExecutionUtil;
import com.intellij.psi.*;
import com.intellij.psi.search.*;
import com.intellij.psi.search.searches.ReferencesSearch;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.jetbrains.annotations.NotNull;

import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import javax.xml.parsers.DocumentBuilderFactory;

/**
 * Lightweight HTTP bridge exposing IntelliJ PSI/AST analysis to the MCP server.
 * The MCP server (running as a separate process) delegates tool calls here for
 * accurate code intelligence instead of regex-based scanning.
 *
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
                default -> "Unknown tool: " + toolName;
            };
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
                if (typeFilter.isEmpty()) return "Provide a 'type' filter (class, interface, method, field) when using wildcard query";
                ProjectFileIndex.getInstance(project).iterateContent(vf -> {
                    if (vf.isDirectory() || (!vf.getName().endsWith(".java") && !vf.getName().endsWith(".kt")))
                        return true;
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
                if (results.isEmpty()) return "No " + typeFilter + " symbols found in project";
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
                    RunConfiguration rc = config.getConfiguration();
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

    /** Resolve a simple class name like "McpServerTest" to its FQN and containing module. */
    private record ClassInfo(String fqn, Module module) {}

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
                                if (fqn != null && (className.contains(".") ? fqn.equals(className) : true)) {
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
            return matches.isEmpty() ? new ClassInfo(className, null) : matches.get(0);
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
        processHandler.addProcessListener(new ProcessAdapter() {
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

        // Parse JUnit XML results
        String xmlResults = parseJunitXmlResults(basePath, module);
        if (!xmlResults.isEmpty()) {
            return xmlResults;
        }

        // Fall back to process output summary
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
        return System.getProperty("java.home", System.getenv("JAVA_HOME"));
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
        if (output.length() <= 2000) return output;
        return "..." + output.substring(output.length() - 2000);
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

    @Override
    public void dispose() {
        if (httpServer != null) {
            httpServer.stop(0);
            httpServer = null;
            LOG.info("PSI Bridge stopped");
        }
        try {
            Path bridgeFile = Path.of(System.getProperty("user.home"), ".copilot", "psi-bridge.json");
            Files.deleteIfExists(bridgeFile);
        } catch (IOException e) {
            LOG.warn("Failed to clean up bridge file", e);
        }
    }
}
