package com.github.copilot.intellij.psi;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.codeInspection.ex.GlobalInspectionContextImpl;
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
import com.intellij.openapi.options.advanced.AdvancedSettings;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.projectRoots.Sdk;
import com.intellij.openapi.roots.ModuleRootManager;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.openapi.roots.ProjectRootManager;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiDirectory;
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
import com.intellij.util.concurrency.AppExecutorUtil;
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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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

    // HTTP Constants
    private static final String CONTENT_TYPE_HEADER = "Content-Type";
    private static final String APPLICATION_JSON = "application/json";
    private static final String STATUS_PARAM = "status";

    // Error Messages
    private static final String ERROR_PREFIX = "Error: ";
    private static final String ERROR_NO_PROJECT_PATH = "No project base path";
    private static final String ERROR_PATH_REQUIRED = "Error: 'path' parameter is required";
    private static final String ERROR_FILE_NOT_FOUND = "File not found: ";
    private static final String ERROR_CANNOT_PARSE = "Cannot parse file: ";
    private static final String ERROR_QODANA_POLLING = "Error polling Qodana results";

    // Common Parameters
    private static final String PARAM_SYMBOL = "symbol";
    private static final String PARAM_FILE_PATTERN = "file_pattern";
    private static final String PARAM_TIMEOUT = "timeout";
    private static final String PARAM_LIMIT = "limit";
    private static final String PARAM_INSPECTION_ID = "inspection_id";
    private static final String PARAM_LEVEL = "level";
    private static final String PARAM_MESSAGE = "message";
    private static final String PARAM_CONTENT = "content";
    private static final String PARAM_COMMIT = "commit";
    private static final String PARAM_STAT_ONLY = "stat_only";
    private static final String PARAM_BRANCH = "branch";
    private static final String PARAM_METHOD = "method";

    // Test Type Values
    private static final String TEST_TYPE_METHOD = "method";
    private static final String TEST_TYPE_CLASS = "class";

    // Element Type Classifications
    private static final String ELEMENT_TYPE_CLASS = "class";
    private static final String ELEMENT_TYPE_INTERFACE = "interface";
    private static final String ELEMENT_TYPE_ENUM = "enum";
    private static final String ELEMENT_TYPE_FIELD = "field";
    private static final String ELEMENT_TYPE_FUNCTION = "function";
    private static final String ELEMENT_TYPE_METHOD = "method";

    // File Extensions
    private static final String JAVA_EXTENSION = ".java";

    // Directory Names
    private static final String BUILD_DIR = "build";

    // Format Strings
    private static final String FORMAT_LOCATION = "%s:%d [%s] %s";
    private static final String FORMAT_LINES_SUFFIX = " lines)";
    private static final String FORMAT_CHARS_SUFFIX = " chars)";

    // Diff Labels
    private static final String DIFF_LABEL_CURRENT = "Current";

    // Display Strings
    private static final String LABEL_SUPPRESS_INSPECTION = "Suppress Inspection";

    // JSON Field Names
    private static final String JSON_ARTIFACT_LOCATION = "artifactLocation";
    private static final String JSON_REGION = "region";
    private static final String JSON_PATHS = "paths";
    private static final String JSON_ACTION = "action";
    private static final String JSON_STASH = "stash";
    private static final String JSON_INDEX = "index";
    private static final String JSON_STASH_PREFIX = "stash@{";
    private static final String JSON_APPLY = "apply";
    private static final String JSON_HEADERS = "headers";
    private static final String JSON_TITLE = "title";
    private static final String JSON_MODULE = "module";
    private static final String JSON_TAB_NAME = "tab_name";

    // Git Constants
    private static final String GIT_FLAG_ALL = "--all";

    // System Properties
    private static final String OS_NAME_PROPERTY = "os.name";
    private static final String JAVA_HOME_ENV = "JAVA_HOME";

    // Log/Terminal Constants
    private static final String IDEA_LOG_FILENAME = "idea.log";
    private static final String TERMINAL_TOOL_WINDOW_ID = "Terminal";
    private static final String GET_INSTANCE_METHOD = "getInstance";

    private final Project project;
    private final RunConfigurationService runConfigService;
    private HttpServer httpServer;
    private int port;

    // Cached inspection results for pagination - avoids rerunning the full inspection engine
    private List<String> cachedInspectionResults;
    private int cachedInspectionFileCount;
    private String cachedInspectionProfile;
    private long cachedInspectionTimestamp;

    public PsiBridgeService(@NotNull Project project) {
        this.project = project;
        this.runConfigService = new RunConfigurationService(project, this::resolveClass);
    }

    public static PsiBridgeService getInstance(@NotNull Project project) {
        return project.getService(PsiBridgeService.class);
    }

    @SuppressWarnings("unused") // Public API - may be used by external integrations
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
        boolean indexing = com.intellij.openapi.project.DumbService.getInstance(project).isDumb();
        JsonObject health = new JsonObject();
        health.addProperty(STATUS_PARAM, "ok");
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
            "create_scratch_file", "list_scratch_files", "get_indexing_status",
            "apply_quickfix", "refactor", "go_to_declaration",
            "get_type_hierarchy", "create_file", "delete_file", "build_project",
            "open_in_editor", "show_diff"
        };
        for (String name : toolNames) {
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
                case "list_run_configurations" -> runConfigService.listRunConfigurations();
                case "run_configuration" -> runConfigService.runConfiguration(arguments);
                case "create_run_configuration" -> runConfigService.createRunConfiguration(arguments);
                case "edit_run_configuration" -> runConfigService.editRunConfiguration(arguments);
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
                case "list_scratch_files" -> listScratchFiles(arguments);
                // IDE status tools
                case "get_indexing_status" -> getIndexingStatus(arguments);
                // Refactoring & code modification tools
                case "apply_quickfix" -> applyQuickfix(arguments);
                case "refactor" -> refactor(arguments);
                case "go_to_declaration" -> goToDeclaration(arguments);
                case "get_type_hierarchy" -> getTypeHierarchy(arguments);
                case "create_file" -> createFile(arguments);
                case "delete_file" -> deleteFile(arguments);
                case "build_project" -> buildProject(arguments);
                case "open_in_editor" -> openInEditor(arguments);
                case "show_diff" -> showDiff(arguments);
                default -> "Unknown tool: " + toolName;
            };
        } catch (com.intellij.openapi.application.ex.ApplicationUtil.CannotRunReadActionException e) {
            // Control-flow exception — retry or report, but don't log (IntelliJ forbids logging these)
            result = "Error: IDE is busy, please retry. " + e.getMessage();
        } catch (Exception e) {
            LOG.warn("PSI tool error: " + toolName, e);
            result = ERROR_PREFIX + e.getMessage();
        }

        JsonObject response = new JsonObject();
        response.addProperty("result", result);
        byte[] bytes = GSON.toJson(response).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set(CONTENT_TYPE_HEADER, APPLICATION_JSON);
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.getResponseBody().close();
    }

    // ---- PSI Tool Implementations ----

    private String listProjectFiles(JsonObject args) {
        String dir = args.has("directory") ? args.get("directory").getAsString() : "";
        String pattern = args.has("pattern") ? args.get("pattern").getAsString() : "";
        return ReadAction.compute(() -> computeProjectFilesList(dir, pattern));
    }

    private String computeProjectFilesList(String dir, String pattern) {
        String basePath = project.getBasePath();
        if (basePath == null) return ERROR_NO_PROJECT_PATH;

        List<String> files = new ArrayList<>();
        ProjectFileIndex fileIndex = ProjectFileIndex.getInstance(project);
        fileIndex.iterateContent(vf -> {
            if (vf.isDirectory()) return true;
            String relPath = relativize(basePath, vf.getPath());
            if (relPath == null) return true;
            if (!dir.isEmpty() && !relPath.startsWith(dir)) return true;
            if (!pattern.isEmpty() && doesNotMatchGlob(vf.getName(), pattern)) return true;
            String tag = fileIndex.isInTestSourceContent(vf) ? "test " : "";
            files.add(String.format("%s [%s%s]", relPath, tag, fileType(vf.getName())));
            return files.size() < 500;
        });

        if (files.isEmpty()) return "No files found";
        Collections.sort(files);
        return files.size() + " files:\n" + String.join("\n", files);
    }

    private String getFileOutline(JsonObject args) {
        if (!args.has("path") || args.get("path").isJsonNull())
            return ERROR_PATH_REQUIRED;
        String pathStr = args.get("path").getAsString();

        return ReadAction.compute(() -> {
            VirtualFile vf = resolveVirtualFile(pathStr);
            if (vf == null) return ERROR_FILE_NOT_FOUND + pathStr;

            PsiFile psiFile = PsiManager.getInstance(project).findFile(vf);
            if (psiFile == null) return ERROR_CANNOT_PARSE + pathStr;

            Document document = FileDocumentManager.getInstance().getDocument(vf);
            if (document == null) return "Cannot read file: " + pathStr;

            List<String> outline = collectOutlineEntries(psiFile, document);

            if (outline.isEmpty()) return "No structural elements found in " + pathStr;
            String basePath = project.getBasePath();
            String display = basePath != null ? relativize(basePath, vf.getPath()) : pathStr;
            return "Outline of " + (display != null ? display : pathStr) + ":\n"
                + String.join("\n", outline);
        });
    }

    private List<String> collectOutlineEntries(PsiFile psiFile, Document document) {
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
        return outline;
    }

    private String searchSymbols(JsonObject args) {
        String query = args.has("query") ? args.get("query").getAsString() : "";
        String typeFilter = args.has("type") ? args.get("type").getAsString() : "";

        return ReadAction.compute(() -> {
            if (query.isEmpty() || "*".equals(query)) {
                return searchSymbolsWildcard(typeFilter);
            }
            return searchSymbolsExact(query, typeFilter);
        });
    }

    private String searchSymbolsWildcard(String typeFilter) {
        if (typeFilter.isEmpty())
            return "Provide a 'type' filter (class, interface, method, field) when using wildcard query";

        List<String> results = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        String basePath = project.getBasePath();
        int[] fileCount = {0};

        ProjectFileIndex.getInstance(project).iterateContent(vf -> {
            if (vf.isDirectory() || (!vf.getName().endsWith(JAVA_EXTENSION) && !vf.getName().endsWith(".kt")))
                return true;
            fileCount[0]++;
            PsiFile psiFile = PsiManager.getInstance(project).findFile(vf);
            if (psiFile == null) return true;
            Document doc = FileDocumentManager.getInstance().getDocument(vf);
            if (doc == null) return true;

            collectSymbolsFromFile(psiFile, doc, vf, typeFilter, basePath, seen, results);
            return results.size() < 200;
        });

        if (results.isEmpty())
            return "No " + typeFilter + " symbols found (scanned " + fileCount[0]
                + " source files using AST analysis). This is a definitive result ? no grep needed.";
        return results.size() + " " + typeFilter + " symbols:\n" + String.join("\n", results);
    }

    private void collectSymbolsFromFile(PsiFile psiFile, Document doc, VirtualFile vf,
                                        String typeFilter, String basePath,
                                        Set<String> seen, List<String> results) {
        String relPath = basePath != null ? relativize(basePath, vf.getPath()) : vf.getPath();
        psiFile.accept(new PsiRecursiveElementWalkingVisitor() {
            @Override
            public void visitElement(@NotNull PsiElement element) {
                if (results.size() >= 200) return;
                if (!(element instanceof PsiNamedElement named)) {
                    super.visitElement(element);
                    return;
                }
                String name = named.getName();
                String type = classifyElement(element);
                if (name != null && type != null && type.equals(typeFilter)) {
                    int line = doc.getLineNumber(element.getTextOffset()) + 1;
                    if (seen.add(relPath + ":" + line)) {
                        results.add(String.format(FORMAT_LOCATION, relPath, line, type, name));
                    }
                }
                super.visitElement(element);
            }
        });
    }

    private String searchSymbolsExact(String query, String typeFilter) {
        List<String> results = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        String basePath = project.getBasePath();
        GlobalSearchScope scope = GlobalSearchScope.projectScope(project);

        PsiSearchHelper.getInstance(project).processElementsWithWord(
            (element, offsetInElement) -> {
                PsiElement parent = element.getParent();
                if (parent instanceof PsiNamedElement named && query.equals(named.getName())) {
                    String type = classifyElement(parent);
                    if (type != null && (typeFilter.isEmpty() || type.equals(typeFilter))) {
                        addSymbolResult(parent, basePath, seen, results);
                    }
                }
                return results.size() < 50;
            },
            scope, query, UsageSearchContext.IN_CODE, true
        );

        if (results.isEmpty()) return "No symbols found matching '" + query + "'";
        return String.join("\n", results);
    }

    private void addSymbolResult(PsiElement element, String basePath,
                                 Set<String> seen, List<String> results) {
        PsiFile file = element.getContainingFile();
        if (file == null || file.getVirtualFile() == null) return;

        Document doc = FileDocumentManager.getInstance().getDocument(file.getVirtualFile());
        if (doc == null) return;

        int line = doc.getLineNumber(element.getTextOffset()) + 1;
        String relPath = basePath != null
            ? relativize(basePath, file.getVirtualFile().getPath())
            : file.getVirtualFile().getPath();
        if (seen.add(relPath + ":" + line)) {
            String lineText = getLineText(doc, line - 1);
            String type = classifyElement(element);
            results.add(String.format(FORMAT_LOCATION, relPath, line, type, lineText));
        }
    }

    private String findReferences(JsonObject args) {
        if (!args.has(PARAM_SYMBOL) || args.get(PARAM_SYMBOL).isJsonNull())
            return "Error: 'symbol' parameter is required";
        String symbol = args.get(PARAM_SYMBOL).getAsString();
        String filePattern = args.has(PARAM_FILE_PATTERN) ? args.get(PARAM_FILE_PATTERN).getAsString() : "";

        return ReadAction.compute(() -> {
            List<String> results = new ArrayList<>();
            String basePath = project.getBasePath();
            GlobalSearchScope scope = GlobalSearchScope.projectScope(project);

            // Try to find the definition first for accurate ReferencesSearch
            PsiElement definition = findDefinition(symbol, scope);

            if (definition != null) {
                collectPsiReferences(definition, scope, filePattern, basePath, results);
            }

            // Fall back to word search if no PSI references found
            if (results.isEmpty()) {
                collectWordReferences(symbol, scope, filePattern, basePath, results);
            }

            if (results.isEmpty()) return "No references found for '" + symbol + "'";
            return results.size() + " references found:\n" + String.join("\n", results);
        });
    }

    private void collectPsiReferences(PsiElement definition, GlobalSearchScope scope,
                                      String filePattern, String basePath, List<String> results) {
        for (PsiReference ref : ReferencesSearch.search(definition, scope).findAll()) {
            if (results.size() >= 100) break;

            PsiElement refEl = ref.getElement();
            PsiFile file = refEl.getContainingFile();
            if (file == null || file.getVirtualFile() == null) continue;
            if (!filePattern.isEmpty() && doesNotMatchGlob(file.getName(), filePattern)) continue;

            Document doc = FileDocumentManager.getInstance().getDocument(file.getVirtualFile());
            if (doc != null) {
                int line = doc.getLineNumber(refEl.getTextOffset()) + 1;
                String relPath = basePath != null
                    ? relativize(basePath, file.getVirtualFile().getPath())
                    : file.getVirtualFile().getPath();
                String lineText = getLineText(doc, line - 1);
                results.add(String.format("%s:%d: %s", relPath, line, lineText));
            }
        }
    }

    private void collectWordReferences(String symbol, GlobalSearchScope scope,
                                       String filePattern, String basePath, List<String> results) {
        PsiSearchHelper.getInstance(project).processElementsWithWord(
            (element, offsetInElement) -> {
                PsiFile file = element.getContainingFile();
                if (file == null || file.getVirtualFile() == null) return true;
                if (!filePattern.isEmpty() && doesNotMatchGlob(file.getName(), filePattern))
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

    // ---- Project Environment Tools ----

    private String getIndexingStatus(JsonObject args) throws Exception {
        boolean wait = args.has("wait") && args.get("wait").getAsBoolean();
        int timeoutSec = args.has(PARAM_TIMEOUT) ? args.get(PARAM_TIMEOUT).getAsInt() : 60;

        var dumbService = com.intellij.openapi.project.DumbService.getInstance(project);
        boolean indexing = dumbService.isDumb();

        if (indexing && wait) {
            CompletableFuture<Void> done = new CompletableFuture<>();
            dumbService.runWhenSmart(() -> done.complete(null));
            try {
                done.get(timeoutSec, TimeUnit.SECONDS);
                return "Indexing finished. IDE is ready.";
            } catch (java.util.concurrent.TimeoutException e) {
                return "Indexing still in progress after " + timeoutSec + "s timeout. Try again later.";
            }
        }

        if (indexing) {
            return "Indexing is in progress. Use wait=true to block until finished. " +
                "Some tools (inspections, find_references, search_symbols) may return incomplete results while indexing.";
        }
        return "IDE is ready. Indexing is complete.";
    }

    private String getProjectInfo() {
        return ReadAction.compute(() -> {
            StringBuilder sb = new StringBuilder();
            String basePath = project.getBasePath();
            sb.append("Project: ").append(project.getName()).append("\n");
            sb.append("Path: ").append(basePath).append("\n");
            sb.append("Agent Workspace: ").append(basePath).append("/.agent-work/ (for temp/working files)\n");

            appendSdkInfo(sb);
            appendModulesInfo(sb);
            appendBuildSystemInfo(sb, basePath);
            appendRunConfigsInfo(sb);

            return sb.toString().trim();
        });
    }

    private void appendSdkInfo(StringBuilder sb) {
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
    }

    private void appendModulesInfo(StringBuilder sb) {
        try {
            Module[] modules = ModuleManager.getInstance(project).getModules();
            sb.append("\nModules (").append(modules.length).append("):\n");
            for (Module module : modules) {
                sb.append("  - ").append(module.getName());
                appendModuleDetails(sb, module);
                sb.append("\n");
            }
        } catch (Exception e) {
            sb.append("Modules: unavailable\n");
        }
    }

    private void appendBuildSystemInfo(StringBuilder sb, String basePath) {
        if (basePath == null) return;
        if (Files.exists(Path.of(basePath, "build.gradle.kts"))
            || Files.exists(Path.of(basePath, "build.gradle"))) {
            sb.append("\nBuild System: Gradle\n");
            Path gradlew = Path.of(basePath,
                System.getProperty(OS_NAME_PROPERTY).contains("Win") ? "gradlew.bat" : "gradlew");
            sb.append("Gradle Wrapper: ").append(gradlew).append("\n");
        } else if (Files.exists(Path.of(basePath, "pom.xml"))) {
            sb.append("\nBuild System: Maven\n");
        }
    }

    private void appendRunConfigsInfo(StringBuilder sb) {
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
    }

    private void appendModuleDetails(StringBuilder sb, Module module) {
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
            // Module may not support source roots
        }
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

    private static final String JUNIT_TYPE_ID = "junit";

    // ---- JUnit Helper ----

    private com.intellij.execution.configurations.ConfigurationType findJUnitConfigurationType() {
        for (var ct : com.intellij.execution.configurations.ConfigurationType.CONFIGURATION_TYPE_EP.getExtensionList()) {
            String displayName = ct.getDisplayName().toLowerCase();
            if (displayName.contains(JUNIT_TYPE_ID)
                || ct.getId().toLowerCase().contains(JUNIT_TYPE_ID)) {
                return ct;
            }
        }
        return null;
    }

// ---- Code Quality Tools ----

    private String getProblems(JsonObject args) throws Exception {
        String pathStr = args.has("path") ? args.get("path").getAsString() : "";

        CompletableFuture<String> resultFuture = new CompletableFuture<>();

        ApplicationManager.getApplication().invokeLater(() -> {
            try {
                ReadAction.run(() -> collectProblems(pathStr, resultFuture));
            } catch (Exception e) {
                resultFuture.complete("Error getting problems: " + e.getMessage());
            }
        });

        return resultFuture.get(10, TimeUnit.SECONDS);
    }

    private void collectProblems(String pathStr, CompletableFuture<String> resultFuture) {
        List<VirtualFile> filesToCheck = new ArrayList<>();
        if (!pathStr.isEmpty()) {
            VirtualFile vf = resolveVirtualFile(pathStr);
            if (vf == null) {
                resultFuture.complete(ERROR_FILE_NOT_FOUND + pathStr);
                return;
            }
            filesToCheck.add(vf);
        } else {
            var fem = com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project);
            filesToCheck.addAll(List.of(fem.getOpenFiles()));
        }

        String basePath = project.getBasePath();
        List<String> problems = new ArrayList<>();
        for (VirtualFile vf : filesToCheck) {
            collectProblemsForFile(vf, basePath, problems);
        }

        if (problems.isEmpty()) {
            resultFuture.complete("No problems found"
                + (pathStr.isEmpty() ? " in open files" : " in " + pathStr)
                + ". Analysis is based on IntelliJ's inspections — file must be open in editor for highlights to be available.");
        } else {
            resultFuture.complete(problems.size() + " problems:\n" + String.join("\n", problems));
        }
    }

    private void collectProblemsForFile(VirtualFile vf, String basePath, List<String> problems) {
        PsiFile psiFile = PsiManager.getInstance(project).findFile(vf);
        if (psiFile == null) return;
        Document doc = FileDocumentManager.getInstance().getDocument(vf);
        if (doc == null) return;

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
            problems.add(String.format(FORMAT_LOCATION,
                relPath, line, severity, h.getDescription()));
        }
    }

    /**
     * Get syntax highlights and daemon-level diagnostics for project files.
     * This reads the cached results from IntelliJ's on-the-fly analysis (DaemonCodeAnalyzer).
     * Useful for quick checks on files already open/analyzed by the IDE.
     * Does NOT run full code inspections - use run_inspections for that.
     */
    @SuppressWarnings("UnstableApiUsage")
    private String getHighlights(JsonObject args) throws Exception {
        String pathStr = args.has("path") ? args.get("path").getAsString() : null;
        int limit = args.has(PARAM_LIMIT) ? args.get(PARAM_LIMIT).getAsInt() : 100;

        if (!com.intellij.diagnostic.LoadingState.COMPONENTS_LOADED.isOccurred()) {
            return "{\"error\": \"IDE is still initializing. Please wait a moment and try again.\"}";
        }

        CompletableFuture<String> resultFuture = new CompletableFuture<>();
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try {
                getHighlightsCached(pathStr, limit, resultFuture);
            } catch (Exception e) {
                LOG.error("Error getting highlights", e);
                resultFuture.complete("Error getting highlights: " + e.getMessage());
            }
        });
        return resultFuture.get(30, TimeUnit.SECONDS);
    }

    /**
     * Run IntelliJ's full code inspections on the project using the inspection engine.
     * This triggers the same analysis as "Analyze > Inspect Code" in the IDE.
     * Results appear in the Problems tool window and are returned here.
     * Finds code quality issues, security problems, typos, complexity warnings,
     * and third-party inspection results (e.g., SonarQube).
     */
    @SuppressWarnings("UnstableApiUsage")
    private String runInspections(JsonObject args) throws Exception {
        int limit = args.has(PARAM_LIMIT) ? args.get(PARAM_LIMIT).getAsInt() : 100;
        int offset = args.has("offset") ? args.get("offset").getAsInt() : 0;
        String minSeverity = args.has("min_severity") ? args.get("min_severity").getAsString() : null;
        String scopePath = args.has("scope") ? args.get("scope").getAsString() : null;

        if (!com.intellij.diagnostic.LoadingState.COMPONENTS_LOADED.isOccurred()) {
            return "{\"error\": \"IDE is still initializing. Please wait a moment and try again.\"}";
        }

        // Serve from cache if available and fresh (5 min TTL) — avoids re-running the full inspection
        // Only use cache for same-scope paginated requests (offset > 0)
        long cacheAge = System.currentTimeMillis() - cachedInspectionTimestamp;
        if (offset > 0 && cachedInspectionResults != null && cacheAge < 300_000) {
            LOG.info("Serving inspection page from cache (offset=" + offset + ", cache age=" + cacheAge + "ms)");
            return formatInspectionPage(cachedInspectionResults, cachedInspectionFileCount,
                cachedInspectionProfile, offset, limit);
        }

        CompletableFuture<String> resultFuture = new CompletableFuture<>();

        // doInspections() internally uses invokeLater + Task.Backgroundable,
        // so it can be called from any thread
        try {
            runInspectionAnalysis(limit, offset, minSeverity, scopePath, resultFuture);
        } catch (Exception e) {
            LOG.error("Error running inspections", e);
            resultFuture.complete("Error running inspections: " + e.getMessage());
        }

        // Full inspection can take a while
        return resultFuture.get(600, TimeUnit.SECONDS);
    }

    /**
     * Format a page of cached inspection results.
     */
    private String formatInspectionPage(List<String> allProblems, int filesWithProblems,
                                        String profileName, int offset, int limit) {
        int total = allProblems.size();
        if (total == 0) {
            return "No inspection problems found (cached result).";
        }
        int effectiveOffset = Math.min(offset, total);
        int end = Math.min(effectiveOffset + limit, total);
        List<String> page = allProblems.subList(effectiveOffset, end);
        boolean hasMore = end < total;

        String summary = String.format(
            """
                Found %d total problems across %d files (profile: %s).
                Showing %d-%d of %d.%s
                Results are also visible in the IDE's Inspection Results view.

                """,
            total, filesWithProblems, profileName,
            effectiveOffset + 1, end, total,
            hasMore ? String.format(
                " WARNING: %d more problems not shown! Call run_inspections with offset=%d to see the rest.",
                total - end, end) : "");
        return summary + String.join("\n", page);
    }

    /**
     * Get highlights by reading cached daemon analysis (fast but may miss unanalyzed files).
     */
    private void getHighlightsCached(String pathStr, int limit, CompletableFuture<String> resultFuture) {
        ReadAction.run(() -> {
            ProjectFileIndex fileIndex = ProjectRootManager.getInstance(project).getFileIndex();
            Collection<VirtualFile> allFiles = collectFilesForHighlightAnalysis(pathStr, fileIndex, resultFuture);
            if (resultFuture.isDone()) return;

            LOG.info("Analyzing " + allFiles.size() + " files for highlights (cached mode)");

            List<String> problems = new ArrayList<>();
            int[] counts = analyzeFilesForHighlights(allFiles, limit, problems);

            if (problems.isEmpty()) {
                resultFuture.complete(String.format("No highlights found in %d files analyzed (0 files with issues). " +
                        "Note: This reads cached daemon analysis results from already-analyzed files. " +
                        "For comprehensive code quality analysis, use run_inspections instead.",
                    allFiles.size()));
            } else {
                String summary = String.format("Found %d problems across %d files (showing up to %d):%n%n",
                    counts[0], counts[1], limit);
                resultFuture.complete(summary + String.join("\n", problems));
            }
        });
    }

    private int[] analyzeFilesForHighlights(Collection<VirtualFile> files, int limit, List<String> problems) {
        String basePath = project.getBasePath();
        int totalCount = 0;
        int filesWithProblems = 0;
        for (VirtualFile vf : files) {
            if (totalCount >= limit) break;
            Document doc = FileDocumentManager.getInstance().getDocument(vf);
            if (doc == null) continue;

            String relPath = basePath != null ? relativize(basePath, vf.getPath()) : vf.getName();
            int added = collectFileHighlights(doc, relPath, limit - totalCount, problems);
            if (added > 0) filesWithProblems++;
            totalCount += added;
        }
        return new int[]{totalCount, filesWithProblems};
    }

    private Collection<VirtualFile> collectFilesForHighlightAnalysis(
        String pathStr, ProjectFileIndex fileIndex, CompletableFuture<String> resultFuture) {
        Collection<VirtualFile> files = new ArrayList<>();
        if (pathStr != null && !pathStr.isEmpty()) {
            VirtualFile vf = resolveVirtualFile(pathStr);
            if (vf != null && fileIndex.isInSourceContent(vf)) {
                files.add(vf);
            } else {
                resultFuture.complete("Error: File not found or not in source content: " + pathStr);
                return Collections.emptyList();
            }
        } else {
            fileIndex.iterateContent(file -> {
                if (!file.isDirectory() && fileIndex.isInSourceContent(file)) {
                    files.add(file);
                }
                return true;
            });
        }
        return files;
    }

    private int collectFileHighlights(Document doc, String relPath, int remaining, List<String> problems) {
        List<com.intellij.codeInsight.daemon.impl.HighlightInfo> highlights = new ArrayList<>();
        int added = 0;
        try {
            com.intellij.codeInsight.daemon.impl.DaemonCodeAnalyzerEx.processHighlights(
                doc, project,
                null,
                0, doc.getTextLength(),
                highlights::add
            );

            for (var h : highlights) {
                if (added >= remaining) break;
                if (h.getDescription() == null) continue;

                var severity = h.getSeverity();
                if (severity == com.intellij.lang.annotation.HighlightSeverity.INFORMATION ||
                    severity.myVal < com.intellij.lang.annotation.HighlightSeverity.WEAK_WARNING.myVal) {
                    continue;
                }

                int line = doc.getLineNumber(h.getStartOffset()) + 1;
                problems.add(String.format(FORMAT_LOCATION, relPath, line, severity.getName(), h.getDescription()));
                added++;
            }
        } catch (Exception e) {
            LOG.warn("Failed to analyze file: " + relPath, e);
        }
        return added;
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
                                       String scopePath,
                                       CompletableFuture<String> resultFuture) {
        Map<String, Integer> severityRank = Map.of(
            "ERROR", 4, "WARNING", 3, "WEAK_WARNING", 2,
            "LIKE_UNUSED_SYMBOL", 2, "INFORMATION", 1, "INFO", 1,
            "TEXT_ATTRIBUTES", 0, "GENERIC_SERVER_ERROR_OR_WARNING", 3
        );
        final int requiredRank = (minSeverity != null && !minSeverity.isEmpty())
            ? severityRank.getOrDefault(minSeverity.toUpperCase(), 0) : 0;
        try {
            LOG.info("Starting inspection analysis...");

            var inspectionManagerEx = (com.intellij.codeInspection.ex.InspectionManagerEx)
                com.intellij.codeInspection.InspectionManager.getInstance(project);
            var profileManager = com.intellij.profile.codeInspection.InspectionProjectProfileManager.getInstance(project);
            var currentProfile = profileManager.getCurrentProfile();

            com.intellij.analysis.AnalysisScope scope = createAnalysisScope(scopePath, resultFuture);
            if (scope == null) return;

            LOG.info("Using inspection profile: " + currentProfile.getName());

            String basePath = project.getBasePath();
            String profileName = currentProfile.getName();

            GlobalInspectionContextImpl context = new GlobalInspectionContextImpl(
                project, inspectionManagerEx.getContentManager()) {

                @Override
                protected void notifyInspectionsFinished(@NotNull com.intellij.analysis.AnalysisScope scope) {
                    super.notifyInspectionsFinished(scope);
                    LOG.info("Inspection analysis completed, collecting results...");
                    final GlobalInspectionContextImpl ctx = this;

                    ReadAction.nonBlocking(() -> {
                            try {
                                InspectionCollectionResult collected = collectInspectionProblems(
                                    ctx, severityRank, requiredRank, basePath);

                                cachedInspectionResults = new ArrayList<>(collected.problems);
                                cachedInspectionFileCount = collected.fileCount;
                                cachedInspectionProfile = profileName;
                                cachedInspectionTimestamp = System.currentTimeMillis();
                                LOG.info("Cached " + collected.problems.size() + " inspection results for pagination" +
                                    " (skipped: " + collected.skippedNoDescription + " no-description, " +
                                    collected.skippedNoFile + " no-file)");

                                if (collected.problems.isEmpty()) {
                                    resultFuture.complete("No inspection problems found. " +
                                        "The code passed all enabled inspections in the current profile (" +
                                        profileName + "). Results are also visible in the IDE's Inspection Results view.");
                                } else {
                                    resultFuture.complete(formatInspectionPage(
                                        collected.problems, collected.fileCount, profileName, offset, limit));
                                }
                            } catch (Exception e) {
                                LOG.error("Error collecting inspection results", e);
                                resultFuture.completeExceptionally(e);
                            }
                            return null;
                        }).inSmartMode(project)
                        .submit(AppExecutorUtil.getAppExecutorService());
                }
            };

            context.doInspections(scope);

        } catch (Exception e) {
            LOG.error("Error setting up inspections", e);
            resultFuture.complete("Error setting up inspections: " + e.getMessage());
        }
    }

    private com.intellij.analysis.AnalysisScope createAnalysisScope(
        String scopePath, CompletableFuture<String> resultFuture) {
        if (scopePath == null || scopePath.isEmpty()) {
            LOG.info("Analysis scope: entire project");
            return new com.intellij.analysis.AnalysisScope(project);
        }
        VirtualFile scopeFile = resolveVirtualFile(scopePath);
        if (scopeFile == null) {
            resultFuture.complete("Error: File or directory not found: " + scopePath);
            return null;
        }
        if (scopeFile.isDirectory()) {
            PsiDirectory psiDir = com.intellij.openapi.application.ReadAction.compute(() ->
                PsiManager.getInstance(project).findDirectory(scopeFile)
            );
            if (psiDir == null) {
                resultFuture.complete("Error: Cannot resolve directory: " + scopePath);
                return null;
            }
            LOG.info("Analysis scope: directory " + scopePath);
            return new com.intellij.analysis.AnalysisScope(psiDir);
        }
        PsiFile psiFile = com.intellij.openapi.application.ReadAction.compute(() ->
            PsiManager.getInstance(project).findFile(scopeFile)
        );
        if (psiFile == null) {
            resultFuture.complete(ERROR_PREFIX + ERROR_CANNOT_PARSE + scopePath);
            return null;
        }
        LOG.info("Analysis scope: file " + scopePath);
        return new com.intellij.analysis.AnalysisScope(psiFile);
    }

    private record InspectionCollectionResult(List<String> problems, int fileCount,
                                              int skippedNoDescription, int skippedNoFile) {
    }

    @SuppressWarnings("UnstableApiUsage")
    private InspectionCollectionResult collectInspectionProblems(
        GlobalInspectionContextImpl ctx, Map<String, Integer> severityRank,
        int requiredRank, String basePath) {
        List<String> allProblems = new ArrayList<>();
        Set<String> filesSet = new HashSet<>();
        int skippedNoDescription = 0;
        int skippedNoFile = 0;

        for (var tools : ctx.getUsedTools()) {
            var toolWrapper = tools.getTool();
            String toolId = toolWrapper.getShortName();

            var presentation = ctx.getPresentation(toolWrapper);
            //noinspection ConstantValue - presentation can be null at runtime despite @NotNull annotation
            if (presentation == null) continue;

            int[] skipped = collectProblemsFromTool(presentation, toolId, basePath,
                filesSet, severityRank, requiredRank, allProblems);
            skippedNoDescription += skipped[0];
            skippedNoFile += skipped[1];
        }
        return new InspectionCollectionResult(allProblems, filesSet.size(), skippedNoDescription, skippedNoFile);
    }

    @SuppressWarnings("UnstableApiUsage")
    private int[] collectProblemsFromTool(
        com.intellij.codeInspection.ui.InspectionToolPresentation presentation,
        String toolId, String basePath, Set<String> filesSet,
        Map<String, Integer> severityRank, int requiredRank, List<String> allProblems) {
        int skippedNoDescription = 0;
        int skippedNoFile = 0;

        var problemElements = presentation.getProblemElements();
        //noinspection ConstantValue - problemElements can be null at runtime despite @NotNull annotation
        if (problemElements == null || problemElements.isEmpty()) {
            return new int[]{0, 0};
        }

        for (var refEntity : problemElements.keys()) {
            var descriptors = problemElements.get(refEntity);
            if (descriptors == null) continue;

            for (var descriptor : descriptors) {
                String formatted = formatInspectionDescriptor(descriptor, toolId, basePath, filesSet);
                if (formatted == null) {
                    skippedNoDescription++;
                    continue;
                }
                if (formatted.isEmpty()) {
                    skippedNoFile++;
                    continue;
                }

                String severity = (descriptor instanceof com.intellij.codeInspection.ProblemDescriptor pd)
                    ? pd.getHighlightType().toString() : "WARNING";
                if (requiredRank > 0) {
                    int rank = severityRank.getOrDefault(severity.toUpperCase(), 0);
                    if (rank < requiredRank) continue;
                }

                allProblems.add(formatted);
            }
        }
        return new int[]{skippedNoDescription, skippedNoFile};
    }

    private String formatInspectionDescriptor(
        com.intellij.codeInspection.CommonProblemDescriptor descriptor,
        String toolId, String basePath, Set<String> filesSet) {
        if (!(descriptor instanceof com.intellij.codeInspection.ProblemDescriptor pd)) {
            return ""; // sentinel for skippedNoFile
        }
        String description = descriptor.getDescriptionTemplate();
        //noinspection ConstantValue - description can be null at runtime despite @NotNull annotation
        if (description == null || description.isEmpty()) return null;

        String refText = "";
        var psiEl = pd.getPsiElement();
        if (psiEl != null) {
            refText = psiEl.getText();
            if (refText != null && refText.length() > 80) {
                refText = refText.substring(0, 80) + "...";
            }
        }

        description = description.replaceAll("<[^>]+>", "")
            .replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")
            .replace("#ref", refText != null ? refText : "").replace("#loc", "").trim();

        if (description.isEmpty()) return null;

        int line = pd.getLineNumber() + 1;
        String severity = pd.getHighlightType().toString();
        String filePath = resolveDescriptorFilePath(pd, basePath, filesSet);

        return String.format("%s:%d [%s/%s] %s", filePath, line, severity, toolId, description);
    }

    private String resolveDescriptorFilePath(com.intellij.codeInspection.ProblemDescriptor pd,
                                             String basePath, Set<String> filesSet) {
        var psiElement = pd.getPsiElement();
        if (psiElement == null) return "";
        var containingFile = psiElement.getContainingFile();
        if (containingFile == null) return "";
        var vf = containingFile.getVirtualFile();
        if (vf == null) return "";
        String filePath = basePath != null ? relativize(basePath, vf.getPath()) : vf.getName();
        filesSet.add(filePath);
        return filePath;
    }

    private String addToDictionary(JsonObject args) throws Exception {
        String word = args.get("word").getAsString().trim().toLowerCase();
        if (word.isEmpty()) {
            return "Error: word cannot be empty";
        }

        CompletableFuture<String> resultFuture = new CompletableFuture<>();
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try {
                // Writing to dictionary is a slow operation - must not run on EDT
                var spellChecker = com.intellij.spellchecker.SpellCheckerManager.getInstance(project);
                spellChecker.acceptWordAsCorrect(word, project);
                resultFuture.complete("Added '" + word + "' to project dictionary. " +
                    "It will no longer be flagged as a typo in future inspections.");
            } catch (Exception e) {
                LOG.error("Error adding word to dictionary", e);
                resultFuture.complete(ERROR_PREFIX + "adding word to dictionary: " + e.getMessage());
            }
        });
        return resultFuture.get(10, TimeUnit.SECONDS);
    }

    private String suppressInspection(JsonObject args) throws Exception {
        String pathStr = args.get("path").getAsString();
        int line = args.get("line").getAsInt();
        String inspectionId = args.get(PARAM_INSPECTION_ID).getAsString().trim();

        if (inspectionId.isEmpty()) {
            return ERROR_PREFIX + PARAM_INSPECTION_ID + " cannot be empty";
        }

        CompletableFuture<String> resultFuture = new CompletableFuture<>();
        ApplicationManager.getApplication().invokeLater(() -> {
            try {
                processSuppressInspection(pathStr, line, inspectionId, resultFuture);
            } catch (Exception e) {
                LOG.error("Error suppressing inspection", e);
                resultFuture.complete("Error suppressing inspection: " + e.getMessage());
            }
        });
        return resultFuture.get(10, TimeUnit.SECONDS);
    }

    private void processSuppressInspection(String pathStr, int line, String inspectionId,
                                           CompletableFuture<String> resultFuture) {
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
                document.getLineCount() + FORMAT_LINES_SUFFIX);
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

        if (fileName.endsWith(JAVA_EXTENSION)) {
            resultFuture.complete(suppressJava(target, inspectionId, document));
        } else if (fileName.endsWith(".kt") || fileName.endsWith(".kts")) {
            resultFuture.complete(suppressKotlin(target, inspectionId, document));
        } else {
            // For other file types, add a noinspection comment
            resultFuture.complete(suppressWithComment(target, inspectionId, document));
        }
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
                                com.intellij.openapi.editor.Document document) {
        // Find the line to insert the annotation before
        int targetOffset = target.getTextRange().getStartOffset();
        int targetLine = document.getLineNumber(targetOffset);
        int lineStart = document.getLineStartOffset(targetLine);

        // Get the indentation of the target line
        String lineText = document.getText(
            new com.intellij.openapi.util.TextRange(lineStart, document.getLineEndOffset(targetLine)));
        StringBuilder indent = new StringBuilder();
        for (char c : lineText.toCharArray()) {
            if (c == ' ' || c == '\t') indent.append(c);
            else break;
        }

        // Check if there's already a @SuppressWarnings on this element
        if (target instanceof com.intellij.psi.PsiModifierListOwner modListOwner) {
            var modList = modListOwner.getModifierList();
            if (modList != null) {
                var existing = modList.findAnnotation("java.lang.SuppressWarnings");
                if (existing != null) {
                    // Annotation exists ? add the new ID to it
                    return addToExistingSuppressWarnings(existing, inspectionId, document);
                }
            }
        }

        String annotation = indent + "@SuppressWarnings(\"" + inspectionId + "\")\n";
        ApplicationManager.getApplication().runWriteAction(() ->
            com.intellij.openapi.command.CommandProcessor.getInstance().executeCommand(project, () -> {
                document.insertString(lineStart, annotation);
                com.intellij.psi.PsiDocumentManager.getInstance(project).commitDocument(document);
            }, LABEL_SUPPRESS_INSPECTION, null)
        );

        return "Added @SuppressWarnings(\"" + inspectionId + "\") at line " + (targetLine + 1);
    }

    private String addToExistingSuppressWarnings(com.intellij.psi.PsiAnnotation annotation,
                                                 String inspectionId,
                                                 com.intellij.openapi.editor.Document document) {
        String text = annotation.getText();
        // Check if already suppressed
        if (text.contains(inspectionId)) {
            return "Inspection '" + inspectionId + "' is already suppressed at this location";
        }

        ApplicationManager.getApplication().runWriteAction(() ->
            com.intellij.openapi.command.CommandProcessor.getInstance().executeCommand(project, () -> {
                var value = annotation.findAttributeValue("value");
                if (value != null) {
                    if (value instanceof com.intellij.psi.PsiArrayInitializerMemberValue) {
                        // Already an array: {"X", "Y"} -- add "inspectionId"
                        int endBrace = value.getTextRange().getEndOffset() - 1;
                        document.insertString(endBrace, ", \"" + inspectionId + "\"");
                    } else {
                        // Single value -- wrap in array with new inspection ID
                        var range = value.getTextRange();
                        String existing = document.getText(range);
                        document.replaceString(range.getStartOffset(), range.getEndOffset(),
                            "{" + existing + ", \"" + inspectionId + "\"}");
                    }
                    com.intellij.psi.PsiDocumentManager.getInstance(project).commitDocument(document);
                }
            }, LABEL_SUPPRESS_INSPECTION, null)
        );

        return "Added '" + inspectionId + "' to existing @SuppressWarnings annotation";
    }

    private String suppressKotlin(com.intellij.psi.PsiElement target, String inspectionId,
                                  com.intellij.openapi.editor.Document document) {
        int targetOffset = target.getTextRange().getStartOffset();
        int targetLine = document.getLineNumber(targetOffset);
        int lineStart = document.getLineStartOffset(targetLine);

        String lineText = document.getText(
            new com.intellij.openapi.util.TextRange(lineStart, document.getLineEndOffset(targetLine)));
        StringBuilder indent = new StringBuilder();
        for (char c : lineText.toCharArray()) {
            if (c == ' ' || c == '\t') indent.append(c);
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
        ApplicationManager.getApplication().runWriteAction(() ->
            com.intellij.openapi.command.CommandProcessor.getInstance().executeCommand(project, () -> {
                document.insertString(lineStart, annotation);
                com.intellij.psi.PsiDocumentManager.getInstance(project).commitDocument(document);
            }, LABEL_SUPPRESS_INSPECTION, null)
        );

        return "Added @Suppress(\"" + inspectionId + "\") at line " + (targetLine + 1);
    }

    private String suppressWithComment(com.intellij.psi.PsiElement target, String inspectionId,
                                       com.intellij.openapi.editor.Document document) {
        int targetOffset = target.getTextRange().getStartOffset();
        int targetLine = document.getLineNumber(targetOffset);
        int lineStart = document.getLineStartOffset(targetLine);

        String lineText = document.getText(
            new com.intellij.openapi.util.TextRange(lineStart, document.getLineEndOffset(targetLine)));
        StringBuilder indent = new StringBuilder();
        for (char c : lineText.toCharArray()) {
            if (c == ' ' || c == '\t') indent.append(c);
            else break;
        }

        String comment = indent + "//noinspection " + inspectionId + "\n";
        ApplicationManager.getApplication().runWriteAction(() ->
            com.intellij.openapi.command.CommandProcessor.getInstance().executeCommand(project, () -> {
                document.insertString(lineStart, comment);
                com.intellij.psi.PsiDocumentManager.getInstance(project).commitDocument(document);
            }, LABEL_SUPPRESS_INSPECTION, null)
        );

        return "Added //noinspection " + inspectionId + " comment at line " + (targetLine + 1);
    }

    @SuppressWarnings("OverrideOnly")
    private String runQodana(JsonObject args) throws Exception {
        int limit = args.has(PARAM_LIMIT) ? args.get(PARAM_LIMIT).getAsInt() : 100;

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
                        LOG.error(ERROR_QODANA_POLLING, e);
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
            Class<?> serviceClass = loadQodanaServiceClass(limit, resultFuture);
            if (serviceClass == null) return; // Already completed with fallback

            Object qodanaService = getQodanaServiceInstance(serviceClass, limit, resultFuture);
            if (qodanaService == null) return; // Already completed with fallback

            waitForQodanaCompletion(qodanaService, serviceClass, limit, resultFuture);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOG.error(ERROR_QODANA_POLLING, e);
            resultFuture.complete("Qodana analysis was triggered. Check the Qodana tab for results. " +
                "Polling error: " + e.getMessage());
        } catch (Exception e) {
            LOG.error(ERROR_QODANA_POLLING, e);
            resultFuture.complete("Qodana analysis was triggered. Check the Qodana tab for results. " +
                "Polling error: " + e.getMessage());
        }
    }

    private Class<?> loadQodanaServiceClass(int limit, CompletableFuture<String> resultFuture)
        throws InterruptedException {
        try {
            return Class.forName("org.jetbrains.qodana.run.QodanaRunInIdeService");
        } catch (ClassNotFoundException e) {
            LOG.info("QodanaRunInIdeService not available, waiting for SARIF output...");
            // Wait up to 5 minutes for SARIF output to appear
            for (int i = 0; i < 300; i++) {
                String fallbackResult = tryFindSarifOutput(limit);
                if (fallbackResult != null) {
                    resultFuture.complete(fallbackResult);
                    return null;
                }
                Thread.sleep(1000);
            }
            resultFuture.complete("Qodana analysis triggered. Check the Qodana tab in Problems for results. " +
                "(Qodana service class not available for result polling)");
            return null;
        }
    }

    private Object getQodanaServiceInstance(Class<?> serviceClass, int limit,
                                            CompletableFuture<String> resultFuture) {
        Object qodanaService = project.getService(serviceClass);
        if (qodanaService == null) {
            String fallbackResult = tryFindSarifOutput(limit);
            resultFuture.complete(Objects.requireNonNullElse(fallbackResult,
                "Qodana analysis triggered. Check the Qodana tab in Problems for results. " +
                    "(Could not access Qodana service to poll results)"));
            return null;
        }
        return qodanaService;
    }

    private void waitForQodanaCompletion(Object qodanaService, Class<?> serviceClass,
                                         int limit, CompletableFuture<String> resultFuture)
        throws Exception {
        var getRunState = serviceClass.getMethod("getRunState");
        var runStateFlow = getRunState.invoke(qodanaService);
        var getValueMethod = runStateFlow.getClass().getMethod("getValue");

        boolean completed = pollQodanaRunState(getValueMethod, runStateFlow, resultFuture);
        if (!completed) return; // Already set result

        tryReadQodanaSarifResults(qodanaService, serviceClass, getValueMethod, limit, resultFuture);
    }

    private boolean pollQodanaRunState(java.lang.reflect.Method getValueMethod, Object runStateFlow,
                                       CompletableFuture<String> resultFuture) throws Exception {
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
                LOG.info("Qodana analysis completed after ~" + i + "s");
                return true;
            } else if (i > 10) {
                resultFuture.complete("Qodana analysis was triggered but may require user interaction. " +
                    "Check the IDE for any Qodana dialogs or the Qodana tab in Problems for results.");
                return false;
            }
            Thread.sleep(1000);
        }
        return true;
    }

    private void tryReadQodanaSarifResults(Object qodanaService, Class<?> serviceClass,
                                           java.lang.reflect.Method getValueMethod, int limit,
                                           CompletableFuture<String> resultFuture) throws Exception {
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
    }

    private String tryFindSarifOutput(int limit) {
        String basePath = project.getBasePath();

        // Try common locations first
        String result = tryCommonSarifLocations(basePath, limit);
        if (result != null) return result;

        // Search recursively under project .qodana directory
        return searchQodanaDirForSarif(basePath, limit);
    }

    private String tryCommonSarifLocations(String basePath, int limit) {
        java.nio.file.Path[] candidates = {
            basePath != null ? java.nio.file.Path.of(basePath, ".qodana", "results", "qodana.sarif.json") : null,
            java.nio.file.Path.of("/tmp/qodana_output/qodana.sarif.json"),
            java.nio.file.Path.of(System.getProperty("java.io.tmpdir"), "qodana_output", "qodana.sarif.json"),
        };
        for (var candidate : candidates) {
            if (candidate != null && java.nio.file.Files.exists(candidate)) {
                try {
                    String sarif = java.nio.file.Files.readString(candidate);
                    if (sarif.length() > 10) {
                        LOG.info("Found Qodana SARIF output at candidate path: " + candidate);
                        return parseSarifResults(sarif, limit);
                    }
                } catch (Exception e) {
                    LOG.warn("Failed to read SARIF file at " + candidate, e);
                }
            }
        }
        return null;
    }

    private String searchQodanaDirForSarif(String basePath, int limit) {
        if (basePath == null) return null;

        try {
            var qodanaDir = java.nio.file.Path.of(basePath, ".qodana");
            if (!java.nio.file.Files.isDirectory(qodanaDir)) return null;

            try (var stream = java.nio.file.Files.walk(qodanaDir, 5)) {
                var sarifFile = stream
                    .filter(p -> p.getFileName().toString().endsWith(".sarif.json"))
                    .min((a, b) -> {
                        try {
                            return java.nio.file.Files.getLastModifiedTime(b)
                                .compareTo(java.nio.file.Files.getLastModifiedTime(a));
                        } catch (Exception e) {
                            return 0;
                        }
                    });
                if (sarifFile.isPresent()) {
                    String sarif = java.nio.file.Files.readString(sarifFile.get());
                    LOG.info("Found Qodana SARIF output via recursive search: " + sarifFile.get());
                    return parseSarifResults(sarif, limit);
                }
            }
        } catch (Exception e) {
            LOG.warn("Error searching for SARIF files", e);
        }
        return null;
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

            for (var runElement : runs) {
                collectSarifRunProblems(runElement.getAsJsonObject(), basePath, limit, problems, filesSet);
            }

            if (problems.isEmpty()) {
                return "Qodana analysis completed: no problems found. " +
                    "Results are also visible in the Qodana tab of the Problems tool window.";
            }

            String summary = String.format(
                """
                    Qodana found %d problems across %d files (showing up to %d).
                    Results are also visible in the Qodana tab of the Problems tool window.

                    """,
                problems.size(), filesSet.size(), limit);
            return summary + String.join("\n", problems);

        } catch (Exception e) {
            LOG.error("Error parsing SARIF results", e);
            return "Qodana analysis completed but SARIF parsing failed: " + e.getMessage() +
                ". Check the Qodana tab in the Problems tool window for results.";
        }
    }

    private void collectSarifRunProblems(com.google.gson.JsonObject run, String basePath,
                                         int limit, List<String> problems, Set<String> filesSet) {
        var results = run.getAsJsonArray("results");
        if (results == null) return;

        for (var resultElement : results) {
            if (problems.size() >= limit) break;
            var result = resultElement.getAsJsonObject();

            String ruleId = result.has("ruleId") ? result.get("ruleId").getAsString() : "unknown";
            String level = result.has(PARAM_LEVEL) ? result.get(PARAM_LEVEL).getAsString() : "warning";
            String message = extractSarifMessage(result);
            SarifLocation loc = extractSarifLocation(result, basePath);

            if (!loc.filePath.isEmpty()) filesSet.add(loc.filePath);
            problems.add(String.format("%s:%d [%s/%s] %s", loc.filePath, loc.line, level, ruleId, message));
        }
    }

    private record SarifLocation(String filePath, int line) {
    }

    private String extractSarifMessage(JsonObject result) {
        if (result.has(PARAM_MESSAGE) && result.getAsJsonObject(PARAM_MESSAGE).has("text")) {
            return result.getAsJsonObject(PARAM_MESSAGE).get("text").getAsString();
        }
        return "";
    }

    private SarifLocation extractSarifLocation(JsonObject result, String basePath) {
        String filePath = "";
        int line = -1;
        if (!result.has("locations")) return new SarifLocation(filePath, line);

        var locations = result.getAsJsonArray("locations");
        if (locations.isEmpty()) return new SarifLocation(filePath, line);

        var loc = locations.get(0).getAsJsonObject();
        if (!loc.has("physicalLocation")) return new SarifLocation(filePath, line);

        var phys = loc.getAsJsonObject("physicalLocation");
        if (phys.has(JSON_ARTIFACT_LOCATION) &&
            phys.getAsJsonObject(JSON_ARTIFACT_LOCATION).has("uri")) {
            filePath = phys.getAsJsonObject(JSON_ARTIFACT_LOCATION).get("uri").getAsString();
            if (filePath.startsWith("file://")) filePath = filePath.substring(7);
            if (basePath != null) filePath = relativize(basePath, filePath);
        }
        if (phys.has(JSON_REGION) &&
            phys.getAsJsonObject(JSON_REGION).has("startLine")) {
            line = phys.getAsJsonObject(JSON_REGION).get("startLine").getAsInt();
        }
        return new SarifLocation(filePath, line);
    }

    private String optimizeImports(JsonObject args) throws Exception {
        String pathStr = args.get("path").getAsString();

        CompletableFuture<String> resultFuture = new CompletableFuture<>();

        ApplicationManager.getApplication().invokeLater(() -> {
            try {
                VirtualFile vf = resolveVirtualFile(pathStr);
                if (vf == null) {
                    resultFuture.complete(ERROR_FILE_NOT_FOUND + pathStr);
                    return;
                }

                PsiFile psiFile = PsiManager.getInstance(project).findFile(vf);
                if (psiFile == null) {
                    resultFuture.complete(ERROR_CANNOT_PARSE + pathStr);
                    return;
                }

                ApplicationManager.getApplication().runWriteAction(() ->
                    com.intellij.openapi.command.CommandProcessor.getInstance().executeCommand(project, () -> {
                        com.intellij.psi.PsiDocumentManager.getInstance(project).commitAllDocuments();
                        new com.intellij.codeInsight.actions.OptimizeImportsProcessor(project, psiFile).run();
                    }, "Optimize Imports", null)
                );

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
                    resultFuture.complete(ERROR_FILE_NOT_FOUND + pathStr);
                    return;
                }

                PsiFile psiFile = PsiManager.getInstance(project).findFile(vf);
                if (psiFile == null) {
                    resultFuture.complete(ERROR_CANNOT_PARSE + pathStr);
                    return;
                }

                ApplicationManager.getApplication().runWriteAction(() ->
                    com.intellij.openapi.command.CommandProcessor.getInstance().executeCommand(project, () -> {
                        com.intellij.psi.PsiDocumentManager.getInstance(project).commitAllDocuments();
                        new com.intellij.codeInsight.actions.ReformatCodeProcessor(psiFile, false).run();
                    }, "Reformat Code", null)
                );

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
            return ERROR_PATH_REQUIRED;
        String pathStr = args.get("path").getAsString();
        int startLine = args.has("start_line") ? args.get("start_line").getAsInt() : -1;
        int endLine = args.has("end_line") ? args.get("end_line").getAsInt() : -1;

        return ReadAction.compute(() -> {
            VirtualFile vf = resolveVirtualFile(pathStr);
            if (vf == null) return ERROR_FILE_NOT_FOUND + pathStr;

            String content = readFileContent(vf);
            if (content.startsWith("Error")) return content;

            if (startLine > 0 || endLine > 0) {
                return extractLineRange(content, startLine, endLine);
            }
            return content;
        });
    }

    private String readFileContent(VirtualFile vf) {
        Document doc = FileDocumentManager.getInstance().getDocument(vf);
        if (doc != null) {
            return doc.getText();
        }
        try {
            return new String(vf.contentsToByteArray(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "Error reading file: " + e.getMessage();
        }
    }

    private String extractLineRange(String content, int startLine, int endLine) {
        String[] lines = content.split("\n", -1);
        int from = Math.max(0, (startLine > 0 ? startLine - 1 : 0));
        int to = Math.min(lines.length, (endLine > 0 ? endLine : lines.length));
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < to; i++) {
            sb.append(i + 1).append(": ").append(lines[i]).append("\n");
        }
        return sb.toString();
    }

    private String writeFile(JsonObject args) throws Exception {
        if (!args.has("path") || args.get("path").isJsonNull())
            return ERROR_PATH_REQUIRED;
        String pathStr = args.get("path").getAsString();
        boolean autoFormat = !args.has("auto_format") || args.get("auto_format").getAsBoolean();

        CompletableFuture<String> resultFuture = new CompletableFuture<>();

        ApplicationManager.getApplication().invokeLater(() -> {
            try {
                VirtualFile vf = resolveVirtualFile(pathStr);

                if (args.has(PARAM_CONTENT)) {
                    writeFileFullContent(vf, pathStr, args.get(PARAM_CONTENT).getAsString(),
                        autoFormat, resultFuture);
                } else if (args.has("old_str") && args.has("new_str")) {
                    writeFilePartialEdit(vf, pathStr, args.get("old_str").getAsString(),
                        args.get("new_str").getAsString(), autoFormat, resultFuture);
                } else {
                    resultFuture.complete("write_file requires either 'content' (full write) or 'old_str'+'new_str' (partial edit)");
                }
            } catch (Exception e) {
                resultFuture.complete(ERROR_PREFIX + e.getMessage());
            }
        });

        return resultFuture.get(15, TimeUnit.SECONDS);
    }

    private void writeFileFullContent(VirtualFile vf, String pathStr, String newContent,
                                      boolean autoFormat, CompletableFuture<String> resultFuture) {
        if (vf == null) {
            createNewFile(pathStr, newContent, resultFuture);
            return;
        }
        Document doc = FileDocumentManager.getInstance().getDocument(vf);
        if (doc != null) {
            ApplicationManager.getApplication().runWriteAction(() ->
                com.intellij.openapi.command.CommandProcessor.getInstance().executeCommand(
                    project, () -> doc.setText(newContent), "Write File", null)
            );
            if (autoFormat) autoFormatAfterWrite(pathStr);
            resultFuture.complete("Written: " + pathStr + " (" + newContent.length() + FORMAT_CHARS_SUFFIX);
        } else {
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

    private void createNewFile(String pathStr, String content, CompletableFuture<String> resultFuture) {
        ApplicationManager.getApplication().runWriteAction(() -> {
            try {
                String normalized = pathStr.replace('\\', '/');
                String basePath = project.getBasePath();
                String fullPath;
                if (normalized.startsWith("/")) {
                    fullPath = normalized;
                } else if (basePath != null) {
                    fullPath = Path.of(basePath, normalized).toString();
                } else {
                    fullPath = normalized;
                }
                Path filePath = Path.of(fullPath);
                Files.createDirectories(filePath.getParent());
                Files.writeString(filePath, content);
                LocalFileSystem.getInstance().refreshAndFindFileByPath(fullPath);
                resultFuture.complete("Created: " + pathStr);
            } catch (IOException e) {
                resultFuture.complete("Error creating file: " + e.getMessage());
            }
        });
    }

    private void writeFilePartialEdit(VirtualFile vf, String pathStr, String oldStr, String newStr,
                                      boolean autoFormat, CompletableFuture<String> resultFuture) {
        if (vf == null) {
            resultFuture.complete(ERROR_FILE_NOT_FOUND + pathStr);
            return;
        }
        Document doc = FileDocumentManager.getInstance().getDocument(vf);
        if (doc == null) {
            resultFuture.complete("Cannot open document: " + pathStr);
            return;
        }
        String text = doc.getText();
        int idx = text.indexOf(oldStr);
        int matchLen = oldStr.length();
        if (idx == -1) {
            // Fallback: normalize Unicode chars and retry
            String normText = normalizeForMatch(text);
            String normOld = normalizeForMatch(oldStr);
            idx = normText.indexOf(normOld);
            if (idx != -1) {
                LOG.info("write_file: normalized match succeeded for " + pathStr);
                matchLen = findOriginalLength(text, idx, normOld.length());
            } else {
                LOG.warn("write_file: old_str not found in " + pathStr +
                    " (exact and normalized both failed)");
            }
        }
        if (idx == -1) {
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
        ApplicationManager.getApplication().runWriteAction(() ->
            com.intellij.openapi.command.CommandProcessor.getInstance().executeCommand(
                project, () -> doc.replaceString(finalIdx, finalIdx + finalLen, newStr),
                "Edit File", null)
        );
        if (autoFormat) autoFormatAfterWrite(pathStr);
        resultFuture.complete("Edited: " + pathStr + " (replaced " + finalLen + " chars with " + newStr.length() + FORMAT_CHARS_SUFFIX);
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

                ApplicationManager.getApplication().runWriteAction(() ->
                    com.intellij.openapi.command.CommandProcessor.getInstance().executeCommand(project, () -> {
                        com.intellij.psi.PsiDocumentManager.getInstance(project).commitAllDocuments();
                        new com.intellij.codeInsight.actions.OptimizeImportsProcessor(project, psiFile).run();
                        new com.intellij.codeInsight.actions.ReformatCodeProcessor(psiFile, false).run();
                    }, "Auto-Format After Write", null)
                );
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
        // First normalize line endings.
        s = s.replace("\r\n", "\n").replace('\r', '\n');
        // Replace ALL non-ASCII chars with '?' - this matches what LLMs naturally do
        // when they can't reproduce em-dashes, smart quotes, etc.
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            sb.append(c > 127 ? '?' : c);
        }
        return sb.toString();
    }

    /**
     * Finds the length in the original text that corresponds to a given length in the normalized text,
     * starting from the given position. This accounts for multibyte chars that normalize to single chars.
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
            return runGit(STATUS_PARAM);
        }
        return runGit(STATUS_PARAM, "--short", "--branch");
    }

    private String gitDiff(JsonObject args) throws Exception {
        List<String> gitArgs = new ArrayList<>();
        gitArgs.add("diff");

        if (args.has("staged") && args.get("staged").getAsBoolean()) {
            gitArgs.add("--cached");
        }
        if (args.has(PARAM_COMMIT)) {
            gitArgs.add(args.get(PARAM_COMMIT).getAsString());
        }
        if (args.has("path")) {
            gitArgs.add("--");
            gitArgs.add(args.get("path").getAsString());
        }
        if (args.has(PARAM_STAT_ONLY) && args.get(PARAM_STAT_ONLY).getAsBoolean()) {
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
                // "medium" is git default - no flag needed
            }
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
        if (args.has(PARAM_BRANCH)) {
            gitArgs.add(2, args.get(PARAM_BRANCH).getAsString());
        }
        return runGit(gitArgs.toArray(new String[0]));
    }

    private String gitBlame(JsonObject args) throws Exception {
        if (!args.has("path")) return ERROR_PATH_REQUIRED;

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
        if (!args.has(PARAM_MESSAGE)) return "Error: 'message' parameter is required";

        // Save all documents before committing to ensure disk matches editor state
        ApplicationManager.getApplication().invokeAndWait(() ->
            ApplicationManager.getApplication().runWriteAction(() ->
                FileDocumentManager.getInstance().saveAllDocuments()));

        List<String> gitArgs = new ArrayList<>();
        gitArgs.add(PARAM_COMMIT);

        if (args.has("amend") && args.get("amend").getAsBoolean()) {
            gitArgs.add("--amend");
        }
        if (args.has("all") && args.get("all").getAsBoolean()) {
            gitArgs.add(GIT_FLAG_ALL);
        }

        gitArgs.add("-m");
        gitArgs.add(args.get(PARAM_MESSAGE).getAsString());

        return runGit(gitArgs.toArray(new String[0]));
    }

    private String gitStage(JsonObject args) throws Exception {
        List<String> gitArgs = new ArrayList<>();
        gitArgs.add("add");

        if (args.has("all") && args.get("all").getAsBoolean()) {
            gitArgs.add(GIT_FLAG_ALL);
        } else if (args.has(JSON_PATHS)) {
            for (var elem : args.getAsJsonArray(JSON_PATHS)) {
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

        if (args.has(JSON_PATHS)) {
            for (var elem : args.getAsJsonArray(JSON_PATHS)) {
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
        String action = args.has(JSON_ACTION) ? args.get(JSON_ACTION).getAsString() : "list";

        return switch (action) {
            case "list" -> {
                boolean all = args.has("all") && args.get("all").getAsBoolean();
                yield runGit(PARAM_BRANCH, all ? GIT_FLAG_ALL : "--list", "-v");
            }
            case "create" -> {
                if (!args.has("name")) yield "Error: 'name' required for create";
                String base = args.has("base") ? args.get("base").getAsString() : "HEAD";
                yield runGit(PARAM_BRANCH, args.get("name").getAsString(), base);
            }
            case "switch", "checkout" -> {
                if (!args.has("name")) yield "Error: 'name' required for switch";
                yield runGit("switch", args.get("name").getAsString());
            }
            case "delete" -> {
                if (!args.has("name")) yield "Error: 'name' required for delete";
                boolean force = args.has("force") && args.get("force").getAsBoolean();
                yield runGit(PARAM_BRANCH, force ? "-D" : "-d", args.get("name").getAsString());
            }
            default -> "Error: unknown action '" + action + "'. Use: list, create, switch, delete";
        };
    }

    private String gitStash(JsonObject args) throws Exception {
        String action = args.has(JSON_ACTION) ? args.get(JSON_ACTION).getAsString() : "list";

        return switch (action) {
            case "list" -> runGit(JSON_STASH, "list");
            case "push", "save" -> {
                List<String> gitArgs = new ArrayList<>(List.of(JSON_STASH, "push"));
                if (args.has(PARAM_MESSAGE)) {
                    gitArgs.add("-m");
                    gitArgs.add(args.get(PARAM_MESSAGE).getAsString());
                }
                if (args.has("include_untracked") && args.get("include_untracked").getAsBoolean()) {
                    gitArgs.add("--include-untracked");
                }
                yield runGit(gitArgs.toArray(new String[0]));
            }
            case "pop" -> {
                String index = args.has(JSON_INDEX) ? args.get(JSON_INDEX).getAsString() : "";
                yield index.isEmpty() ? runGit(JSON_STASH, "pop") : runGit(JSON_STASH, "pop", JSON_STASH_PREFIX + index + "}");
            }
            case JSON_APPLY -> {
                String index = args.has(JSON_INDEX) ? args.get(JSON_INDEX).getAsString() : "";
                yield index.isEmpty() ? runGit(JSON_STASH, JSON_APPLY) : runGit(JSON_STASH, JSON_APPLY, JSON_STASH_PREFIX + index + "}");
            }
            case "drop" -> {
                String index = args.has(JSON_INDEX) ? args.get(JSON_INDEX).getAsString() : "";
                yield index.isEmpty() ? runGit(JSON_STASH, "drop") : runGit(JSON_STASH, "drop", JSON_STASH_PREFIX + index + "}");
            }
            default -> "Error: unknown stash action '" + action + "'. Use: list, push, pop, apply, drop";
        };
    }

    private String gitShow(JsonObject args) throws Exception {
        List<String> gitArgs = new ArrayList<>();
        gitArgs.add("show");

        String ref = args.has("ref") ? args.get("ref").getAsString() : "HEAD";
        gitArgs.add(ref);

        if (args.has(PARAM_STAT_ONLY) && args.get(PARAM_STAT_ONLY).getAsBoolean()) {
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
        String method = args.has(PARAM_METHOD) ? args.get(PARAM_METHOD).getAsString().toUpperCase() : "GET";
        String body = args.has("body") ? args.get("body").getAsString() : null;

        URL url = URI.create(urlStr).toURL();
        java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
        conn.setRequestMethod(method);
        conn.setConnectTimeout(10_000);
        conn.setReadTimeout(30_000);

        // Set headers
        if (args.has(JSON_HEADERS)) {
            JsonObject headers = args.getAsJsonObject(JSON_HEADERS);
            for (String key : headers.keySet()) {
                conn.setRequestProperty(key, headers.get(key).getAsString());
            }
        }

        // Write body
        if (body != null && ("POST".equals(method) || "PUT".equals(method) || "PATCH".equals(method))) {
            if (!args.has(JSON_HEADERS) || !args.getAsJsonObject(JSON_HEADERS).has(CONTENT_TYPE_HEADER)) {
                conn.setRequestProperty(CONTENT_TYPE_HEADER, APPLICATION_JSON);
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
        String title = args.has(JSON_TITLE) ? args.get(JSON_TITLE).getAsString() : null;
        String basePath = project.getBasePath();
        if (basePath == null) return ERROR_NO_PROJECT_PATH;
        int timeoutSec = args.has(PARAM_TIMEOUT) ? args.get(PARAM_TIMEOUT).getAsInt() : 60;
        String tabTitle = title != null ? title : "Command: " + truncateForTitle(command);

        GeneralCommandLine cmd;
        if (System.getProperty(OS_NAME_PROPERTY).contains("Win")) {
            cmd = new GeneralCommandLine("cmd", "/c", command);
        } else {
            cmd = new GeneralCommandLine("sh", "-c", command);
        }
        cmd.setWorkDirectory(basePath);

        // Set JAVA_HOME from project SDK if available
        String javaHome = getProjectJavaHome();
        if (javaHome != null) {
            cmd.withEnvironment(JAVA_HOME_ENV, javaHome);
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
                    .withTitle(tabTitle)
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
        String level = args.has(PARAM_LEVEL) ? args.get(PARAM_LEVEL).getAsString().toUpperCase() : null;

        Path logFile = Path.of(System.getProperty("idea.log.path", ""), IDEA_LOG_FILENAME);
        if (!Files.exists(logFile)) {
            // Try standard location
            String logDir = System.getProperty("idea.system.path");
            if (logDir != null) {
                logFile = Path.of(logDir, "..", "log", IDEA_LOG_FILENAME);
            }
        }
        if (!Files.exists(logFile)) {
            // Try via PathManager
            try {
                Class<?> pm = Class.forName("com.intellij.openapi.application.PathManager");
                String logPath = (String) pm.getMethod("getLogPath").invoke(null);
                logFile = Path.of(logPath, IDEA_LOG_FILENAME);
            } catch (Exception ignored) {
                // PathManager not available or reflection failed
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
                if (!notification.getTitle().isEmpty()) {
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
        String tabName = args.has(JSON_TAB_NAME) ? args.get(JSON_TAB_NAME).getAsString() : null;
        boolean newTab = args.has("new_tab") && args.get("new_tab").getAsBoolean();
        String shell = args.has("shell") ? args.get("shell").getAsString() : null;

        CompletableFuture<String> resultFuture = new CompletableFuture<>();

        ApplicationManager.getApplication().invokeLater(() -> {
            try {
                var managerClass = Class.forName("org.jetbrains.plugins.terminal.TerminalToolWindowManager");
                var manager = managerClass.getMethod(GET_INSTANCE_METHOD, Project.class).invoke(null, project);

                var result = getOrCreateTerminalWidget(managerClass, manager, tabName, newTab, shell, command);
                sendTerminalCommand(result.widget, command);

                resultFuture.complete("Command sent to terminal '" + result.tabName + "': " + command +
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
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "Terminal opened (response timed out, but command was likely sent).";
        } catch (Exception e) {
            return "Terminal opened (response timed out, but command was likely sent).";
        }
    }

    private record TerminalWidgetResult(Object widget, String tabName) {
    }

    private TerminalWidgetResult getOrCreateTerminalWidget(Class<?> managerClass, Object manager,
                                                           String tabName, boolean newTab,
                                                           String shell, String command) throws Exception {
        // Try to reuse existing terminal tab
        if (tabName != null && !newTab) {
            Object widget = findTerminalWidgetByTabName(managerClass, tabName);
            if (widget != null) {
                return new TerminalWidgetResult(widget, tabName);
            }
        }

        // Create new tab
        String title = tabName != null ? tabName : "Agent: " + truncateForTitle(command);
        List<String> shellCommand = shell != null ? List.of(shell) : null;
        var createSession = managerClass.getMethod("createNewSession",
            String.class, String.class, List.class, boolean.class, boolean.class);
        Object widget = createSession.invoke(manager, project.getBasePath(), title, shellCommand, true, true);
        return new TerminalWidgetResult(widget, title + " (new)");
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
            var toolWindow = com.intellij.openapi.wm.ToolWindowManager.getInstance(project).getToolWindow(TERMINAL_TOOL_WINDOW_ID);
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
        String tabName = args.has(JSON_TAB_NAME) ? args.get(JSON_TAB_NAME).getAsString() : null;

        CompletableFuture<String> resultFuture = new CompletableFuture<>();

        ApplicationManager.getApplication().invokeLater(() -> {
            try {
                var managerClass = Class.forName("org.jetbrains.plugins.terminal.TerminalToolWindowManager");
                var toolWindow = com.intellij.openapi.wm.ToolWindowManager.getInstance(project).getToolWindow(TERMINAL_TOOL_WINDOW_ID);
                if (toolWindow == null) {
                    resultFuture.complete("Terminal tool window not available.");
                    return;
                }

                com.intellij.ui.content.Content targetContent = findTerminalContent(toolWindow, tabName);
                if (targetContent == null) {
                    resultFuture.complete("No terminal tab found" +
                        (tabName != null ? " matching '" + tabName + "'" : "") + ".");
                    return;
                }

                readTerminalText(managerClass, targetContent, resultFuture);

            } catch (Exception e) {
                LOG.warn("Failed to read terminal output", e);
                resultFuture.complete("Failed to read terminal output: " + e.getMessage());
            }
        });

        try {
            return resultFuture.get(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "Timed out reading terminal output.";
        } catch (Exception e) {
            return "Timed out reading terminal output.";
        }
    }

    private com.intellij.ui.content.Content findTerminalContent(
        com.intellij.openapi.wm.ToolWindow toolWindow, String tabName) {
        var contentManager = toolWindow.getContentManager();

        // Find by name if specified
        if (tabName != null) {
            for (var content : contentManager.getContents()) {
                String displayName = content.getDisplayName();
                if (displayName != null && displayName.contains(tabName)) {
                    return content;
                }
            }
        }

        // Fall back to selected content
        return contentManager.getSelectedContent();
    }

    private void readTerminalText(Class<?> managerClass, com.intellij.ui.content.Content targetContent,
                                  CompletableFuture<String> resultFuture) throws Exception {
        // Find widget via findWidgetByContent
        var findWidgetByContent = managerClass.getMethod("findWidgetByContent",
            com.intellij.ui.content.Content.class);
        Object widget = findWidgetByContent.invoke(null, targetContent);
        if (widget == null) {
            resultFuture.complete("No terminal widget found for tab '" + targetContent.getDisplayName() +
                "'. The auto-created default tab may not be readable — use agent-created tabs instead.");
            return;
        }

        // Call getText() via the TerminalWidget interface
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
    }

    private String listTerminals() {
        StringBuilder result = new StringBuilder();

        appendOpenTerminalTabs(result);
        appendAvailableShells(result);
        appendDefaultShell(result);

        result.append("\n\nTip: Use run_in_terminal with tab_name to reuse an existing tab, or new_tab=true to force a new one.");
        return result.toString();
    }

    private void appendOpenTerminalTabs(StringBuilder result) {
        result.append("Open terminal tabs:\n");
        try {
            var toolWindowManager = com.intellij.openapi.wm.ToolWindowManager.getInstance(project);
            var toolWindow = toolWindowManager.getToolWindow(TERMINAL_TOOL_WINDOW_ID);
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
    }

    private void appendAvailableShells(StringBuilder result) {
        result.append("\nAvailable shells:\n");
        String os = System.getProperty(OS_NAME_PROPERTY, "").toLowerCase();
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
    }

    private void appendDefaultShell(StringBuilder result) {
        try {
            var settingsClass = Class.forName("org.jetbrains.plugins.terminal.TerminalProjectOptionsProvider");
            var getInstance = settingsClass.getMethod(GET_INSTANCE_METHOD, Project.class);
            var settings = getInstance.invoke(null, project);
            var getShellPath = settings.getClass().getMethod("getShellPath");
            String defaultShell = (String) getShellPath.invoke(settings);
            result.append("\nIntelliJ default shell: ").append(defaultShell);
        } catch (Exception e) {
            result.append("\nCould not determine IntelliJ default shell.");
        }
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
        String tabName = args.has(JSON_TAB_NAME) ? args.get(JSON_TAB_NAME).getAsString() : null;

        //noinspection RedundantCast
        return ApplicationManager.getApplication().runReadAction((com.intellij.openapi.util.Computable<String>) () -> {
            try {
                List<com.intellij.execution.ui.RunContentDescriptor> descriptors = collectRunDescriptors();
                if (descriptors.isEmpty()) {
                    return "No Run or Debug panel tabs available.";
                }

                var result = findTargetRunDescriptor(descriptors, tabName);
                if (result instanceof String errorMsg) {
                    return errorMsg;
                }

                var target = (com.intellij.execution.ui.RunContentDescriptor) result;
                var console = target.getExecutionConsole();
                if (console == null) {
                    return "Tab '" + target.getDisplayName() + "' has no console.";
                }

                String text = extractConsoleText(console);
                if (text == null || text.isEmpty()) {
                    return "Tab '" + target.getDisplayName() + "' has no text content (console may still be loading or is an unsupported type).";
                }

                return formatRunOutput(target.getDisplayName(), text, maxChars);
            } catch (Exception e) {
                return "Error reading Run output: " + e.getMessage();
            }
        });
    }

    private List<com.intellij.execution.ui.RunContentDescriptor> collectRunDescriptors() {
        var manager = com.intellij.execution.ui.RunContentManager.getInstance(project);
        var descriptors = new ArrayList<>(manager.getAllDescriptors());

        // Also include debug session descriptors
        try {
            var debugManager = com.intellij.xdebugger.XDebuggerManager.getInstance(project);
            for (var session : debugManager.getDebugSessions()) {
                var rd = session.getRunContentDescriptor();
                if (!descriptors.contains(rd)) {
                    descriptors.add(rd);
                }
            }
        } catch (Exception ignored) {
            // XDebugger may not be available
        }
        return descriptors;
    }

    private Object findTargetRunDescriptor(List<com.intellij.execution.ui.RunContentDescriptor> descriptors,
                                           String tabName) {
        if (tabName == null) {
            return descriptors.getLast();
        }

        // Find by name
        for (var d : descriptors) {
            if (d.getDisplayName() != null && d.getDisplayName().contains(tabName)) {
                return d;
            }
        }

        // Not found - return error message
        StringBuilder available = new StringBuilder("No tab matching '").append(tabName).append("'. Available tabs:\n");
        for (var d : descriptors) {
            available.append("  - ").append(d.getDisplayName()).append("\n");
        }
        return available.toString();
    }

    private String formatRunOutput(String displayName, String text, int maxChars) {
        StringBuilder result = new StringBuilder();
        result.append("Tab: ").append(displayName).append("\n");
        result.append("Total length: ").append(text.length()).append(" chars\n\n");

        if (text.length() > maxChars) {
            result.append("...(truncated, showing last ").append(maxChars).append(" of ").append(text.length())
                .append(" chars. Use max_chars parameter to read more.)\n");
            result.append(text.substring(text.length() - maxChars));
        } else {
            result.append(text);
        }

        return result.toString();
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
                        String status;
                        if (passed) {
                            status = "? PASSED";
                        } else if (defect) {
                            status = "? FAILED";
                        } else {
                            status = "? UNKNOWN";
                        }
                        testOutput.append("  ").append(status).append(" ").append(name).append("\n");

                        // For failed tests, try to get the error message
                        if (defect) {
                            appendTestErrorDetails(test, testOutput);
                        }
                    }
                }

                // Also get the console text portion of the test runner
                appendTestConsoleOutput(console, testOutput);

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

    private void appendTestErrorDetails(Object test, StringBuilder testOutput) {
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
            // Method not available on this test result type
        } catch (Exception e) {
            LOG.debug("Failed to get test error details", e);
        }
    }

    private void appendTestConsoleOutput(Object console, StringBuilder testOutput) {
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
            // Method not available in this version
        } catch (Exception e) {
            LOG.debug("Failed to get test console output", e);
        }
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
            // Method not available in this version
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
            // XML parsing or file access errors are non-fatal
        }

        return null;
    }

    public record ClassInfo(String fqn, Module module) {
    }

    public interface ClassResolver {
        ClassInfo resolveClass(String className);
    }

    private ClassInfo resolveClass(String className) {
        return ReadAction.compute(() -> {
            String searchName = className.contains(".") ? className.substring(className.lastIndexOf('.') + 1) : className;
            List<ClassInfo> matches = new ArrayList<>();
            PsiSearchHelper.getInstance(project).processElementsWithWord(
                (element, offset) -> {
                    String type = classifyElement(element);
                    if (TEST_TYPE_CLASS.equals(type) && element instanceof PsiNamedElement named
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
                            // Reflection method not available or failed
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
        String filePattern = args.has(PARAM_FILE_PATTERN) ? args.get(PARAM_FILE_PATTERN).getAsString() : "";

        return ReadAction.compute(() -> {
            List<String> tests = new ArrayList<>();
            String basePath = project.getBasePath();
            ProjectFileIndex fileIndex = ProjectFileIndex.getInstance(project);

            fileIndex.iterateContent(vf -> {
                if (vf.isDirectory()) return true;
                String name = vf.getName();
                if (!name.endsWith(JAVA_EXTENSION) && !name.endsWith(".kt")) return true;
                if (!filePattern.isEmpty() && doesNotMatchGlob(name, filePattern)) return true;
                if (!fileIndex.isInTestSourceContent(vf)) return true;

                collectTestMethodsFromFile(vf, basePath, tests);
                return tests.size() < 500;
            });

            if (tests.isEmpty()) return "No tests found";
            return tests.size() + " tests:\n" + String.join("\n", tests);
        });
    }

    private void collectTestMethodsFromFile(VirtualFile vf, String basePath, List<String> tests) {
        PsiFile psiFile = PsiManager.getInstance(project).findFile(vf);
        if (psiFile == null) return;
        Document doc = FileDocumentManager.getInstance().getDocument(vf);

        psiFile.accept(new PsiRecursiveElementWalkingVisitor() {
            @Override
            public void visitElement(@NotNull PsiElement element) {
                if (!(element instanceof PsiNamedElement named)) {
                    super.visitElement(element);
                    return;
                }
                String type = classifyElement(element);
                if ((ELEMENT_TYPE_METHOD.equals(type) || ELEMENT_TYPE_FUNCTION.equals(type)) && hasTestAnnotation(element)) {
                    String methodName = named.getName();
                    String className = getContainingClassName(element);
                    String relPath = basePath != null ? relativize(basePath, vf.getPath()) : vf.getPath();
                    int line = doc != null ? doc.getLineNumber(element.getTextOffset()) + 1 : 0;
                    tests.add(String.format("%s.%s (%s:%d)", className, methodName, relPath, line));
                }
                super.visitElement(element);
            }
        });
    }

    private String runTests(JsonObject args) throws Exception {
        String target = args.get("target").getAsString();
        String module = args.has(JSON_MODULE) ? args.get(JSON_MODULE).getAsString() : "";
        String basePath = project.getBasePath();
        if (basePath == null) return ERROR_NO_PROJECT_PATH;

        String configResult = tryRunTestConfig(target);
        if (configResult != null) return configResult;

        String junitResult = tryRunJUnitNatively(target);
        if (junitResult != null) return junitResult;

        return runTestsViaGradle(target, module, basePath);
    }

    private String runTestsViaGradle(String target, String module, String basePath) throws Exception {
        String gradlew = basePath + (System.getProperty(OS_NAME_PROPERTY).contains("Win")
            ? "\\gradlew.bat" : "/gradlew");
        String taskPrefix = module.isEmpty() ? "" : ":" + module + ":";
        String javaHome = getProjectJavaHome();

        GeneralCommandLine cmd = new GeneralCommandLine();
        cmd.setExePath(gradlew);
        cmd.addParameters(taskPrefix + "test", "--tests", target);
        cmd.setWorkDirectory(basePath);
        if (javaHome != null) {
            cmd.withEnvironment(JAVA_HOME_ENV, javaHome);
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

        int exitCode;
        try {
            exitCode = exitFuture.get(120, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            processHandler.destroyProcess();
            return "Tests timed out after 120 seconds. Partial output:\n"
                + truncateOutput(output.toString());
        }

        if (exitCode == 0) {
            String xmlResults = parseJunitXmlResults(basePath, module);
            if (!xmlResults.isEmpty()) {
                return xmlResults;
            }
        }

        return (exitCode == 0 ? "? Tests PASSED" : "? Tests FAILED (exit code " + exitCode + ")")
            + "\n\n" + truncateOutput(output.toString());
    }

    private String getTestResults(JsonObject args) {
        String module = args.has(JSON_MODULE) ? args.get(JSON_MODULE).getAsString() : "";
        String basePath = project.getBasePath();
        if (basePath == null) return ERROR_NO_PROJECT_PATH;

        String results = parseJunitXmlResults(basePath, module);
        return results.isEmpty() ? "No test results found. Run tests first." : results;
    }

    private String getCoverage(JsonObject args) {
        String file = args.has("file") ? args.get("file").getAsString() : "";
        String basePath = project.getBasePath();
        if (basePath == null) return ERROR_NO_PROJECT_PATH;

        // Try JaCoCo XML report
        for (String module : List.of("", "plugin-core", "mcp-server")) {
            Path jacocoXml = module.isEmpty()
                ? Path.of(basePath, BUILD_DIR, "reports", "jacoco", "test", "jacocoTestReport.xml")
                : Path.of(basePath, module, BUILD_DIR, "reports", "jacoco", "test", "jacocoTestReport.xml");
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
            // XML parsing or file access errors are non-fatal
        }

        return """
            No coverage data found. Run tests with coverage first:
              - IntelliJ: Right-click test → Run with Coverage
              - Gradle: Add jacoco plugin and run `gradlew jacocoTestReport`""";
    }

// ---- Test & Run Helper Methods ----

    private String tryRunTestConfig(String target) {
        try {
            // Look for a matching test run configuration
            var configs = RunManager.getInstance(project).getAllSettings();
            for (var config : configs) {
                String typeName = config.getType().getDisplayName().toLowerCase();
                if ((typeName.contains(JUNIT_TYPE_ID) || typeName.contains("test"))
                    && config.getName().contains(target)) {
                    return runConfiguration(createJsonWithName(config.getName()));
                }
            }
        } catch (Exception ignored) {
            // XML parsing or file access errors are non-fatal
        }
        return null;
    }

    /**
     * Create a temporary JUnit run configuration and execute it via IntelliJ's native test runner.
     * This gives proper test tree UI, pass/fail counts, and rerun-failed support.
     */
    private String tryRunJUnitNatively(String target) {
        try {
            var junitType = findJUnitConfigurationType();
            if (junitType == null) return null;

            // Parse target: "com.example.MyTest" or "MyTest.testFoo" or "*Test"
            String testClass = target;
            String testMethod = null;
            if (target.contains("*")) {
                // Pattern-based — can't map to a single JUnit class, fall back to Gradle
                return null;
            }

            // Check for "ClassName.methodName" pattern (not a package separator)
            int lastDot = target.lastIndexOf('.');
            if (lastDot > 0) {
                String possibleMethod = target.substring(lastDot + 1);
                String possibleClass = target.substring(0, lastDot);
                // If the part after the last dot starts with lowercase, it's likely a method name
                if (!possibleMethod.isEmpty() && Character.isLowerCase(possibleMethod.charAt(0))) {
                    testClass = possibleClass;
                    testMethod = possibleMethod;
                }
            }

            // Resolve the class via PSI to get FQN and module
            ClassInfo classInfo = resolveClass(testClass);
            if (classInfo.fqn() == null) return null; // class not found, fall back to Gradle

            CompletableFuture<String> resultFuture = new CompletableFuture<>();
            final String resolvedClass = classInfo.fqn();
            final String resolvedMethod = testMethod;
            final Module resolvedModule = classInfo.module();

            ApplicationManager.getApplication().invokeLater(() -> {
                try {
                    RunManager runManager = RunManager.getInstance(project);
                    var factory = junitType.getConfigurationFactories()[0];
                    String configName = "Test: " + (resolvedMethod != null
                        ? resolvedClass.substring(resolvedClass.lastIndexOf('.') + 1) + "." + resolvedMethod
                        : resolvedClass.substring(resolvedClass.lastIndexOf('.') + 1));
                    var settings = runManager.createConfiguration(configName, factory);
                    RunConfiguration config = settings.getConfiguration();

                    // Set test class/method via getPersistentData()
                    var getData = config.getClass().getMethod("getPersistentData");
                    Object data = getData.invoke(config);
                    data.getClass().getField("MAIN_CLASS_NAME").set(data, resolvedClass);
                    if (resolvedMethod != null) {
                        data.getClass().getField("METHOD_NAME").set(data, resolvedMethod);
                        data.getClass().getField("TEST_OBJECT").set(data, TEST_TYPE_METHOD);
                    } else {
                        data.getClass().getField("TEST_OBJECT").set(data, TEST_TYPE_CLASS);
                    }

                    // Set module
                    if (resolvedModule != null) {
                        try {
                            var setModule = config.getClass().getMethod("setModule", Module.class);
                            setModule.invoke(config, resolvedModule);
                        } catch (NoSuchMethodException ignored) {
                            // Method not available in this version
                        }
                    }

                    settings.setTemporary(true);
                    runManager.addConfiguration(settings);
                    runManager.setSelectedConfiguration(settings);

                    // Execute via IntelliJ's test runner
                    var executor = DefaultRunExecutor.getRunExecutorInstance();
                    var envBuilder = ExecutionEnvironmentBuilder.createOrNull(executor, settings);
                    if (envBuilder == null) {
                        resultFuture.complete("Error: Cannot create execution environment for JUnit test");
                        return;
                    }

                    var env = envBuilder.build();
                    ExecutionManager.getInstance(project).restartRunProfile(env);
                    resultFuture.complete("Started tests via IntelliJ JUnit runner: " + configName
                        + "\nResults will appear in the IntelliJ Test Runner panel."
                        + "\nUse get_test_results to check results after completion.");
                } catch (Exception e) {
                    LOG.warn("Failed to run JUnit natively, will fall back to Gradle", e);
                    resultFuture.complete(null);
                }
            });

            return resultFuture.get(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOG.warn("tryRunJUnitNatively failed", e);
            return null;
        } catch (Exception e) {
            LOG.warn("tryRunJUnitNatively failed", e);
            return null;
        }
    }

    private String getProjectJavaHome() {
        try {
            Sdk sdk = ProjectRootManager.getInstance(project).getProjectSdk();
            if (sdk != null && sdk.getHomePath() != null) {
                return sdk.getHomePath();
            }
        } catch (Exception ignored) {
            // SDK access errors are non-fatal
        }
        // Don't fall back to IDE's JBR ? let the system JAVA_HOME take effect
        return System.getenv(JAVA_HOME_ENV);
    }

    private static JsonObject createJsonWithName(String name) {
        JsonObject obj = new JsonObject();
        obj.addProperty("name", name);
        return obj;
    }

    private boolean hasTestAnnotation(PsiElement element) {
        return hasTestAnnotationViaReflection(element) || hasTestAnnotationViaText(element);
    }

    private boolean hasTestAnnotationViaReflection(PsiElement element) {
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
            // Reflection may not work for all element types
        }
        return false;
    }

    private boolean hasTestAnnotationViaText(PsiElement element) {
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
                if (ELEMENT_TYPE_CLASS.equals(type)) return named.getName();
            }
            parent = parent.getParent();
        }
        return "UnknownClass";
    }

    private String parseJunitXmlResults(String basePath, String module) {
        List<Path> reportDirs = findTestReportDirs(basePath, module);
        if (reportDirs.isEmpty()) return "";

        int totalTests = 0;
        int totalFailed = 0;
        int totalErrors = 0;
        int totalSkipped = 0;
        double totalTime = 0;
        List<String> failures = new ArrayList<>();

        for (Path reportDir : reportDirs) {
            try (var xmlFiles = Files.list(reportDir)) {
                for (Path xmlFile : xmlFiles.filter(p -> p.toString().endsWith(".xml")).toList()) {
                    TestSuiteResult result = parseTestSuiteXml(xmlFile);
                    if (result == null) continue;
                    totalTests += result.tests;
                    totalFailed += result.failed;
                    totalErrors += result.errors;
                    totalSkipped += result.skipped;
                    totalTime += result.time;
                    failures.addAll(result.failures);
                }
            } catch (IOException ignored) {
                // IO errors during directory listing are non-fatal
            }
        }

        if (totalTests == 0) return "";
        return formatTestResults(totalTests, totalFailed, totalErrors, totalSkipped, totalTime, failures);
    }

    private List<Path> findTestReportDirs(String basePath, String module) {
        List<Path> reportDirs = new ArrayList<>();
        if (module.isEmpty()) {
            try (var dirs = Files.walk(Path.of(basePath), 4)) {
                dirs.filter(p -> p.endsWith("test-results/test") && Files.isDirectory(p))
                    .forEach(reportDirs::add);
            } catch (IOException ignored) {
                // Directory walk errors are non-fatal
            }
        } else {
            Path dir = Path.of(basePath, module, BUILD_DIR, "test-results", "test");
            if (Files.isDirectory(dir)) reportDirs.add(dir);
        }
        return reportDirs;
    }

    private record TestSuiteResult(int tests, int failed, int errors, int skipped,
                                   double time, List<String> failures) {
    }

    private TestSuiteResult parseTestSuiteXml(Path xmlFile) {
        try {
            var dbf = DocumentBuilderFactory.newInstance();
            //noinspection HttpUrlsUsage - XML feature URI, not an actual URL
            dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            var doc = dbf.newDocumentBuilder().parse(xmlFile.toFile());
            var suites = doc.getElementsByTagName("testsuite");

            int tests = 0;
            int failed = 0;
            int errors = 0;
            int skipped = 0;
            double time = 0;
            List<String> failures = new ArrayList<>();

            for (int i = 0; i < suites.getLength(); i++) {
                var suite = suites.item(i);
                tests += intAttr(suite, "tests");
                failed += intAttr(suite, "failures");
                errors += intAttr(suite, "errors");
                skipped += intAttr(suite, "skipped");
                time += doubleAttr(suite, "time");
                collectFailureDetails((org.w3c.dom.Element) suite, failures);
            }
            return new TestSuiteResult(tests, failed, errors, skipped, time, failures);
        } catch (Exception ignored) {
            // XML parsing errors are non-fatal
            return null;
        }
    }

    private static void collectFailureDetails(org.w3c.dom.Element suite, List<String> failures) {
        var testcases = suite.getElementsByTagName("testcase");
        for (int j = 0; j < testcases.getLength(); j++) {
            var tc = testcases.item(j);
            var failNodes = ((org.w3c.dom.Element) tc).getElementsByTagName("failure");
            if (failNodes.getLength() > 0) {
                String tcName = tc.getAttributes().getNamedItem("name").getNodeValue();
                String cls = tc.getAttributes().getNamedItem("classname").getNodeValue();
                String msg = failNodes.item(0).getAttributes().getNamedItem("message").getNodeValue();
                failures.add(String.format("  ? %s.%s: %s", cls, tcName, msg));
            }
        }
    }

    private static String formatTestResults(int totalTests, int totalFailed, int totalErrors,
                                            int totalSkipped, double totalTime, List<String> failures) {
        int passed = totalTests - totalFailed - totalErrors - totalSkipped;
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("Test Results: %d tests, %d passed, %d failed, %d errors, %d skipped (%.1fs)%n",
            totalTests, passed, totalFailed, totalErrors, totalSkipped, totalTime));

        if (!failures.isEmpty()) {
            sb.append("\nFailures:\n");
            failures.forEach(f -> sb.append(f).append("\n"));
        }
        return sb.toString().trim();
    }

    private String parseJacocoXml(Path xmlPath, String fileFilter) {
        try {
            var dbf = DocumentBuilderFactory.newInstance();
            //noinspection HttpUrlsUsage - XML feature URI, not an actual URL
            dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            var doc = dbf.newDocumentBuilder().parse(xmlPath.toFile());
            var packages = doc.getElementsByTagName("package");
            List<String> lines = new ArrayList<>();
            int totalLines = 0;
            int coveredLines = 0;

            for (int i = 0; i < packages.getLength(); i++) {
                var pkg = (org.w3c.dom.Element) packages.item(i);
                var classes = pkg.getElementsByTagName("class");
                for (int j = 0; j < classes.getLength(); j++) {
                    var cls = (org.w3c.dom.Element) classes.item(j);
                    String name = cls.getAttribute("name").replace('/', '.');
                    if (!fileFilter.isEmpty() && !name.contains(fileFilter)) continue;

                    var coverage = processClassCoverage(cls);
                    if (coverage != null) {
                        totalLines += coverage.total;
                        coveredLines += coverage.covered;
                        lines.add(String.format("  %s: %.1f%% (%d/%d lines)",
                            name, coverage.percentage, coverage.covered, coverage.total));
                    }
                }
            }

            if (lines.isEmpty()) return "No line coverage data in JaCoCo report";
            double totalPct = coveredLines * 100.0 / Math.max(1, totalLines);
            return String.format("Coverage: %.1f%% overall (%d/%d lines)%n%n%s",
                totalPct, coveredLines, totalLines, String.join("\n", lines));
        } catch (Exception e) {
            return "Error parsing JaCoCo report: " + e.getMessage();
        }
    }

    private CoverageData processClassCoverage(org.w3c.dom.Element cls) {
        var counters = cls.getElementsByTagName("counter");
        for (int k = 0; k < counters.getLength(); k++) {
            var counter = counters.item(k);
            if ("LINE".equals(counter.getAttributes().getNamedItem("type").getNodeValue())) {
                int missed = intAttr(counter, "missed");
                int covered = intAttr(counter, "covered");
                int total = missed + covered;
                double pct = covered * 100.0 / Math.max(1, total);
                return new CoverageData(covered, total, pct);
            }
        }
        return null;
    }

    private record CoverageData(int covered, int total, double percentage) {
    }

    private static int intAttr(org.w3c.dom.Node node, String attr) {
        var item = node.getAttributes().getNamedItem(attr);
        return item != null ? Integer.parseInt(item.getNodeValue()) : 0;
    }

    @SuppressWarnings("SameParameterValue") // Utility method mirrors intAttr, kept parameterized for consistency
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
                    if (type != null && !type.equals(ELEMENT_TYPE_FIELD)) {
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
            return classifyJavaClass(element);
        }
        if (cls.contains("PsiMethod")) return ELEMENT_TYPE_METHOD;
        if (cls.contains("PsiField")) return ELEMENT_TYPE_FIELD;
        if (cls.contains("PsiEnumConstant")) return ELEMENT_TYPE_FIELD;

        // Kotlin PSI
        String kotlinType = classifyKotlinElement(cls, element);
        if (kotlinType != null) return kotlinType;

        // Generic patterns
        if (cls.contains("Interface") && !cls.contains("Reference")) return ELEMENT_TYPE_INTERFACE;
        if (cls.contains("Enum") && cls.contains("Class")) return ELEMENT_TYPE_CLASS;

        return null;
    }

    private static String classifyJavaClass(PsiElement element) {
        try {
            if ((boolean) element.getClass().getMethod("isInterface").invoke(element))
                return ELEMENT_TYPE_INTERFACE;
            if ((boolean) element.getClass().getMethod("isEnum").invoke(element)) return ELEMENT_TYPE_ENUM;
        } catch (NoSuchMethodException | java.lang.reflect.InvocationTargetException
                 | IllegalAccessException ignored) {
            // Reflection unavailable for this PsiClass variant
        }
        return ELEMENT_TYPE_CLASS;
    }

    private static String classifyKotlinElement(String cls, PsiElement element) {
        return switch (cls) {
            case "KtClass", "KtObjectDeclaration" -> classifyKotlinClass(element);
            case "KtNamedFunction" -> ELEMENT_TYPE_FUNCTION;
            case "KtProperty" -> ELEMENT_TYPE_FIELD;
            case "KtTypeAlias" -> ELEMENT_TYPE_CLASS;
            default -> null;
        };
    }

    private static String classifyKotlinClass(PsiElement element) {
        try {
            var isInterface = element.getClass().getMethod("isInterface");
            if ((boolean) isInterface.invoke(element)) return ELEMENT_TYPE_INTERFACE;
            var isEnum = element.getClass().getMethod("isEnum");
            if ((boolean) isEnum.invoke(element)) return ELEMENT_TYPE_ENUM;
        } catch (NoSuchMethodException | java.lang.reflect.InvocationTargetException
                 | IllegalAccessException ignored) {
            // Reflection unavailable for this Kotlin class variant
        }
        return ELEMENT_TYPE_CLASS;
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

    private static boolean doesNotMatchGlob(String fileName, String pattern) {
        String regex = pattern.replace(".", "\\.").replace("*", ".*").replace("?", ".");
        return !fileName.matches(regex);
    }

    private static String fileType(String name) {
        String l = name.toLowerCase();
        if (l.endsWith(JAVA_EXTENSION)) return "Java";
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
        String symbol = args.has(PARAM_SYMBOL) ? args.get(PARAM_SYMBOL).getAsString() : "";
        if (symbol.isEmpty())
            return "Error: 'symbol' parameter required (e.g. java.util.List, com.google.gson.Gson.fromJson)";

        return ReadAction.compute(() -> {
            try {
                GlobalSearchScope scope = GlobalSearchScope.allScope(project);
                String[] parts = splitSymbolParts(symbol);
                String className = parts[0];
                String memberName = parts[1];

                PsiElement resolvedClass = resolveJavaClass(className, scope);
                if (resolvedClass == null && memberName == null) {
                    return "Symbol not found: " + symbol + ". Use a fully qualified name (e.g. java.util.List).";
                }
                if (resolvedClass == null) {
                    return "Symbol not found: " + symbol + ". Use a fully qualified name (e.g. java.util.List).";
                }

                PsiElement element = resolvedClass;
                if (memberName != null) {
                    PsiElement member = findMemberInClass(resolvedClass, memberName);
                    if (member != null) element = member;
                }

                return generateDocumentation(element, symbol);
            } catch (Exception e) {
                LOG.warn("get_documentation error", e);
                return "Error retrieving documentation: " + e.getMessage();
            }
        });
    }

    private String[] splitSymbolParts(String symbol) {
        try {
            Class<?> javaPsiFacadeClass = Class.forName("com.intellij.psi.JavaPsiFacade");
            Object facade = javaPsiFacadeClass.getMethod(GET_INSTANCE_METHOD, Project.class).invoke(null, project);
            GlobalSearchScope scope = GlobalSearchScope.allScope(project);

            PsiElement resolvedClass = (PsiElement) javaPsiFacadeClass
                .getMethod("findClass", String.class, GlobalSearchScope.class)
                .invoke(facade, symbol, scope);

            if (resolvedClass != null) {
                return new String[]{symbol, null};
            }
        } catch (Exception ignored) {
            // Reflection errors handled by caller
        }

        int lastDot = symbol.lastIndexOf('.');
        if (lastDot > 0) {
            return new String[]{symbol.substring(0, lastDot), symbol.substring(lastDot + 1)};
        }
        return new String[]{symbol, null};
    }

    private PsiElement resolveJavaClass(String className, GlobalSearchScope scope) {
        try {
            Class<?> javaPsiFacadeClass = Class.forName("com.intellij.psi.JavaPsiFacade");
            Object facade = javaPsiFacadeClass.getMethod(GET_INSTANCE_METHOD, Project.class).invoke(null, project);
            return (PsiElement) javaPsiFacadeClass
                .getMethod("findClass", String.class, GlobalSearchScope.class)
                .invoke(facade, className, scope);
        } catch (Exception e) {
            LOG.warn("resolveJavaClass error", e);
            return null;
        }
    }

    private PsiElement findMemberInClass(PsiElement resolvedClass, String memberName) {
        // Direct children first
        for (PsiElement child : resolvedClass.getChildren()) {
            if (child instanceof PsiNamedElement named && memberName.equals(named.getName())) {
                return child;
            }
        }
        // Try inner classes
        for (PsiElement child : resolvedClass.getChildren()) {
            if (child instanceof PsiNamedElement) {
                for (PsiElement grandchild : child.getChildren()) {
                    if (grandchild instanceof PsiNamedElement named && memberName.equals(named.getName())) {
                        return grandchild;
                    }
                }
            }
        }
        return null;
    }

    private String generateDocumentation(PsiElement element, String symbol) {
        try {
            Class<?> langDocClass = Class.forName("com.intellij.lang.LanguageDocumentation");
            Object langDocInstance = langDocClass.getField("INSTANCE").get(null);
            Object provider = langDocClass.getMethod("forLanguage", com.intellij.lang.Language.class)
                .invoke(langDocInstance, element.getLanguage());

            if (provider == null) {
                return extractDocComment(element, symbol);
            }

            String doc = (String) provider.getClass().getMethod("generateDoc", PsiElement.class, PsiElement.class)
                .invoke(provider, element, null);

            if (doc == null || doc.isEmpty()) {
                return extractDocComment(element, symbol);
            }

            String text = stripHtmlForDocumentation(doc);
            return truncateOutput("Documentation for " + symbol + ":\n\n" + text);
        } catch (Exception e) {
            LOG.warn("generateDocumentation error", e);
            return extractDocComment(element, symbol);
        }
    }

    private static String stripHtmlForDocumentation(String doc) {
        return doc.replaceAll("<[^>]+>", "")
            .replace("&nbsp;", " ")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&amp;", "&")
            .replaceAll("&#\\d+;", "")
            .replaceAll("\n{3,}", "\n\n")
            .trim();
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
                    text = text.replace("/**", "")
                        .replace("*/", "")
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

    private String downloadSources(JsonObject args) {
        String library = args.has("library") ? args.get("library").getAsString() : "";

        try {
            java.util.concurrent.CompletableFuture<String> future = new java.util.concurrent.CompletableFuture<>();

            ApplicationManager.getApplication().invokeLater(() -> {
                try {
                    StringBuilder sb = new StringBuilder();
                    boolean settingChanged = enableDownloadSources(sb);
                    scanLibrarySources(library, sb);
                    if (settingChanged) {
                        triggerProjectResync(sb);
                    }
                    future.complete(truncateOutput(sb.toString()));
                } catch (Exception e) {
                    future.complete(ERROR_PREFIX + e.getMessage());
                }
            });

            return future.get(30, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOG.warn("download_sources interrupted", e);
            return "Error: Operation interrupted";
        } catch (Exception e) {
            LOG.warn("download_sources error", e);
            return ERROR_PREFIX + e.getMessage();
        }
    }

    private void scanLibrarySources(String library, StringBuilder sb) {
        Module[] modules = ModuleManager.getInstance(project).getModules();
        List<String> withSources = new ArrayList<>();
        List<String> withoutSources = new ArrayList<>();

        for (Module module : modules) {
            ModuleRootManager rootManager = ModuleRootManager.getInstance(module);
            for (var entry : rootManager.getOrderEntries()) {
                if (!(entry instanceof com.intellij.openapi.roots.LibraryOrderEntry libEntry))
                    continue;

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
                sb.append("  ? ").append(lib).append("\n");
            }
        }
    }

    private boolean enableDownloadSources(StringBuilder sb) {
        try {
            Class<?> gradleSettingsClass = Class.forName(
                "org.jetbrains.plugins.gradle.settings.GradleSettings");
            Object gradleSettings = gradleSettingsClass.getMethod(GET_INSTANCE_METHOD, Project.class)
                .invoke(null, project);

            java.util.Collection<?> linkedSettings = (java.util.Collection<?>)
                gradleSettingsClass.getMethod("getLinkedProjectsSettings").invoke(gradleSettings);

            if (linkedSettings == null || linkedSettings.isEmpty()) {
                sb.append("No Gradle project settings found.\n");
                return false;
            }

            boolean anyChanged = false;
            Class<?> gradleProjectSettingsClass = Class.forName(
                "org.jetbrains.plugins.gradle.settings.GradleProjectSettings");

            for (Object projectSettings : linkedSettings) {
                anyChanged |= enableExternalAnnotations(gradleProjectSettingsClass, projectSettings, sb);
                anyChanged |= enableDownloadSourcesSetting(gradleProjectSettingsClass, projectSettings, sb);
            }
            return anyChanged;
        } catch (ClassNotFoundException e) {
            sb.append("Gradle plugin not available. ");
            return enableMavenDownloadSources(sb);
        } catch (Exception e) {
            LOG.warn("enableDownloadSources error", e);
            sb.append("Error enabling download sources: ").append(e.getMessage()).append("\n");
            return false;
        }
    }

    private static boolean enableExternalAnnotations(Class<?> settingsClass, Object projectSettings, StringBuilder sb) {
        try {
            Method getResolve = settingsClass.getMethod("isResolveExternalAnnotations");
            boolean currentValue = (boolean) getResolve.invoke(projectSettings);
            if (!currentValue) {
                Method setResolve = settingsClass.getMethod("setResolveExternalAnnotations", boolean.class);
                setResolve.invoke(projectSettings, true);
                sb.append("Enabled 'Resolve external annotations' for Gradle project.\n");
                return true;
            }
        } catch (NoSuchMethodException ignored) {
            // Method not available in this IDE version
        } catch (Exception e) {
            LOG.warn("enableExternalAnnotations error", e);
        }
        return false;
    }

    private boolean enableDownloadSourcesSetting(Class<?> gradleProjectSettingsClass,
                                                 Object projectSettings, StringBuilder sb) {
        try {
            boolean downloadOnSync = AdvancedSettings.getBoolean("gradle.download.sources.on.sync");
            if (!downloadOnSync) {
                AdvancedSettings.setBoolean("gradle.download.sources.on.sync", true);
                sb.append("Enabled 'Download sources on sync' in Advanced Settings.\n");
                return true;
            } else {
                sb.append("'Download sources on sync' is already enabled.\n");
            }
        } catch (Exception e) {
            LOG.info("AdvancedSettings download sources not available: " + e.getMessage());
            return enableDownloadSourcesLegacy(gradleProjectSettingsClass, projectSettings, sb);
        }
        return false;
    }

    private static boolean enableDownloadSourcesLegacy(Class<?> settingsClass,
                                                       Object projectSettings, StringBuilder sb) {
        try {
            Method getDownload = settingsClass.getMethod("isDownloadSources");
            Method setDownload = settingsClass.getMethod("setDownloadSources", boolean.class);
            boolean current = (boolean) getDownload.invoke(projectSettings);
            if (!current) {
                setDownload.invoke(projectSettings, true);
                sb.append("Enabled 'Download sources' for Gradle project.\n");
                return true;
            } else {
                sb.append("'Download sources' is already enabled.\n");
            }
        } catch (NoSuchMethodException ex) {
            sb.append("Download sources setting not found in this IntelliJ version.\n");
        } catch (Exception e) {
            LOG.warn("enableDownloadSourcesLegacy error", e);
        }
        return false;
    }

    private boolean enableMavenDownloadSources(StringBuilder sb) {
        try {
            Class<?> mavenSettingsClass = Class.forName(
                "org.jetbrains.idea.maven.project.MavenImportingSettings");
            // Maven has a different settings path
            Class<?> mavenProjectsManagerClass = Class.forName(
                "org.jetbrains.idea.maven.project.MavenProjectsManager");
            Object manager = mavenProjectsManagerClass.getMethod(GET_INSTANCE_METHOD, Project.class)
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
        String content = args.has(PARAM_CONTENT) ? args.get(PARAM_CONTENT).getAsString() : "";

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
                    // Cast needed: runWriteAction is overloaded (Computable vs. ThrowableComputable)
                    //noinspection RedundantCast
                    resultFile[0] = ApplicationManager.getApplication().runWriteAction(
                        (com.intellij.openapi.util.Computable<com.intellij.openapi.vfs.VirtualFile>) () -> {
                            try {
                                VirtualFile file = scratchService.findFile(
                                    scratchRoot,
                                    name,
                                    com.intellij.ide.scratch.ScratchFileService.Option.create_if_missing
                                );

                                if (file != null) {
                                    OutputStream out = file.getOutputStream(null);
                                    out.write(content.getBytes(StandardCharsets.UTF_8));
                                    out.close();
                                }
                                return file;
                            } catch (IOException e) {
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

            return "Created scratch file: " + resultFile[0].getPath() + " (" + content.length() + FORMAT_CHARS_SUFFIX;
        } catch (Exception e) {
            LOG.warn("Failed to create scratch file", e);
            return "Error creating scratch file: " + e.getMessage();
        }
    }

    /**
     * List all scratch files visible to the IDE.
     * Returns paths that can be used with intellij_read_file.
     */
    @SuppressWarnings("unused")
    private String listScratchFiles(JsonObject args) {
        try {
            StringBuilder result = new StringBuilder();
            final int[] count = {0};
            final Set<String> seenPaths = new HashSet<>();

            ApplicationManager.getApplication().invokeAndWait(() -> {
                try {
                    result.append("Scratch files:\n");

                    // First, check currently open files in editors (catches files open but not in VFS yet)
                    com.intellij.openapi.fileEditor.FileEditorManager editorManager =
                        com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project);
                    VirtualFile[] openFiles = editorManager.getOpenFiles();

                    for (VirtualFile file : openFiles) {
                        // Check if this is a scratch file (path contains "scratches")
                        String path = file.getPath();
                        if (path.contains("scratches") && !file.isDirectory()) {
                            seenPaths.add(path);
                            long sizeKB = file.getLength() / 1024;
                            result.append("- ").append(path)
                                .append(" (").append(sizeKB).append(" KB) [OPEN]\n");
                            count[0]++;
                        }
                    }

                    // Then, list files from scratch root directory (catches files on disk)
                    com.intellij.ide.scratch.ScratchRootType scratchRoot =
                        com.intellij.ide.scratch.ScratchRootType.getInstance();

                    // Get scratch root directory
                    VirtualFile scratchesDir = scratchRoot.findFile(null, "",
                        com.intellij.ide.scratch.ScratchFileService.Option.existing_only);

                    if (scratchesDir != null && scratchesDir.exists()) {
                        listScratchFilesRecursive(scratchesDir, result, count, 0, seenPaths);
                    }
                } catch (Exception e) {
                    LOG.warn("Failed to list scratch files", e);
                    result.append("Error listing scratch files: ").append(e.getMessage());
                }
            });

            if (count[0] == 0 && !result.toString().contains("Error")) {
                result.append("\nTotal: 0 scratch files\n");
                result.append("Use create_scratch_file to create one.");
            } else {
                result.append("\nTotal: ").append(count[0]).append(" scratch file(s)\n");
                result.append("Use intellij_read_file with these paths to read content.");
            }

            return result.toString();
        } catch (Exception e) {
            LOG.warn("Failed to list scratch files", e);
            return "Error listing scratch files: " + e.getMessage();
        }
    }

    private void listScratchFilesRecursive(VirtualFile dir, StringBuilder result, int[] count, int depth, Set<
        String> seenPaths) {
        if (depth > 3) return; // Prevent excessive recursion

        for (VirtualFile child : dir.getChildren()) {
            if (child.isDirectory()) {
                listScratchFilesRecursive(child, result, count, depth + 1, seenPaths);
            } else {
                String path = child.getPath();
                if (!seenPaths.contains(path)) {  // Skip if already listed from open files
                    seenPaths.add(path);
                    String indent = "  ".repeat(depth);
                    long sizeKB = child.getLength() / 1024;
                    result.append(indent).append("- ").append(path)
                        .append(" (").append(sizeKB).append(" KB)\n");
                    count[0]++;
                }
            }
        }
    }

// ==================== Refactoring & Code Modification Tools ====================

    private String applyQuickfix(JsonObject args) throws Exception {
        if (!args.has("file") || !args.has("line") || !args.has(PARAM_INSPECTION_ID)) {
            return "Error: 'file', 'line', and '" + PARAM_INSPECTION_ID + "' parameters are required";
        }
        String pathStr = args.get("file").getAsString();
        int targetLine = args.get("line").getAsInt();
        String inspectionId = args.get(PARAM_INSPECTION_ID).getAsString();
        int fixIndex = args.has("fix_index") ? args.get("fix_index").getAsInt() : 0;

        CompletableFuture<String> resultFuture = new CompletableFuture<>();

        ApplicationManager.getApplication().invokeLater(() -> {
            try {
                VirtualFile vf = resolveVirtualFile(pathStr);
                if (vf == null) {
                    resultFuture.complete(ERROR_PREFIX + ERROR_FILE_NOT_FOUND + pathStr);
                    return;
                }

                ApplicationManager.getApplication().runWriteAction(() -> {
                    try {
                        resultFuture.complete(executeQuickfix(vf, pathStr, targetLine, inspectionId, fixIndex));
                    } catch (Exception e) {
                        LOG.warn("Error applying quickfix", e);
                        resultFuture.complete("Error applying quickfix: " + e.getMessage());
                    }
                });
            } catch (Exception e) {
                LOG.warn("Error in applyQuickfix", e);
                resultFuture.complete(ERROR_PREFIX + e.getMessage());
            }
        });

        return resultFuture.get(30, TimeUnit.SECONDS);
    }

    private String executeQuickfix(VirtualFile vf, String pathStr, int targetLine,
                                   String inspectionId, int fixIndex) {
        PsiFile psiFile = PsiManager.getInstance(project).findFile(vf);
        if (psiFile == null) return ERROR_PREFIX + ERROR_CANNOT_PARSE + pathStr;

        Document document = FileDocumentManager.getInstance().getDocument(vf);
        if (document == null) return "Error: Cannot get document for: " + pathStr;

        if (targetLine < 1 || targetLine > document.getLineCount()) {
            return "Error: Line " + targetLine + " is out of bounds (file has " + document.getLineCount() + FORMAT_LINES_SUFFIX;
        }

        int lineStartOffset = document.getLineStartOffset(targetLine - 1);
        int lineEndOffset = document.getLineEndOffset(targetLine - 1);

        var profile = com.intellij.profile.codeInspection.InspectionProjectProfileManager
            .getInstance(project).getCurrentProfile();
        var toolWrapper = profile.getInspectionTool(inspectionId, project);

        if (toolWrapper == null) {
            return "Error: Inspection '" + inspectionId + "' not found. " +
                "Use the inspection ID from run_inspections output (e.g., 'RedundantCast', 'unused').";
        }

        List<com.intellij.codeInspection.ProblemDescriptor> lineProblems =
            findProblemsOnLine(toolWrapper.getTool(), psiFile, lineStartOffset, lineEndOffset);

        if (lineProblems.isEmpty()) {
            return "No problems found for inspection '" + inspectionId + "' at line " + targetLine +
                " in " + pathStr + ". The inspection may have been resolved, or it may be a global inspection " +
                "that doesn't support quickfixes. Try using intellij_write_file instead.";
        }

        return applyAndReportFix(lineProblems, fixIndex, pathStr, targetLine);
    }

    private List<com.intellij.codeInspection.ProblemDescriptor> findProblemsOnLine(
        com.intellij.codeInspection.InspectionProfileEntry tool, PsiFile psiFile,
        int lineStartOffset, int lineEndOffset) {
        List<com.intellij.codeInspection.ProblemDescriptor> problems = new ArrayList<>();
        if (tool instanceof com.intellij.codeInspection.LocalInspectionTool localTool) {
            var inspectionManager = com.intellij.codeInspection.InspectionManager.getInstance(project);
            var holder = new com.intellij.codeInspection.ProblemsHolder(inspectionManager, psiFile, false);
            var visitor = localTool.buildVisitor(holder, false);
            psiFile.accept(new PsiRecursiveElementWalkingVisitor() {
                @Override
                public void visitElement(@NotNull PsiElement element) {
                    element.accept(visitor);
                    super.visitElement(element);
                }
            });
            problems.addAll(holder.getResults());
        }

        List<com.intellij.codeInspection.ProblemDescriptor> lineProblems = new ArrayList<>();
        for (var problem : problems) {
            PsiElement elem = problem.getPsiElement();
            if (elem != null) {
                int offset = elem.getTextOffset();
                if (offset >= lineStartOffset && offset <= lineEndOffset) {
                    lineProblems.add(problem);
                }
            }
        }
        return lineProblems;
    }

    private String applyAndReportFix(List<com.intellij.codeInspection.ProblemDescriptor> lineProblems,
                                     int fixIndex, String pathStr, int targetLine) {
        com.intellij.codeInspection.ProblemDescriptor targetProblem =
            lineProblems.get(Math.min(fixIndex, lineProblems.size() - 1));

        var fixes = targetProblem.getFixes();
        if (fixes == null || fixes.length == 0) {
            return "No quickfixes available for this problem. Description: " +
                targetProblem.getDescriptionTemplate() + ". Use intellij_write_file to fix manually.";
        }

        var fix = fixes[Math.min(fixIndex, fixes.length - 1)];

        //noinspection unchecked
        com.intellij.openapi.command.CommandProcessor.getInstance().executeCommand(
            project,
            () -> fix.applyFix(project, targetProblem),
            "Apply Quick Fix: " + fix.getName(),
            null
        );

        com.intellij.psi.PsiDocumentManager.getInstance(project).commitAllDocuments();
        FileDocumentManager.getInstance().saveAllDocuments();

        StringBuilder sb = new StringBuilder();
        sb.append("? Applied fix: ").append(fix.getName()).append("\n");
        sb.append("  File: ").append(pathStr).append(" line ").append(targetLine).append("\n");
        if (fixes.length > 1) {
            sb.append("  (").append(fixes.length).append(" fixes were available, applied #")
                .append(Math.min(fixIndex, fixes.length - 1)).append(")\n");
            sb.append("  Other available fixes:\n");
            for (int i = 0; i < fixes.length; i++) {
                if (i != Math.min(fixIndex, fixes.length - 1)) {
                    sb.append("    ").append(i).append(": ").append(fixes[i].getName()).append("\n");
                }
            }
        }
        return sb.toString();
    }

    private String refactor(JsonObject args) throws Exception {
        if (!args.has("operation") || !args.has("file") || !args.has(PARAM_SYMBOL)) {
            return "Error: 'operation', 'file', and 'symbol' parameters are required";
        }
        String operation = args.get("operation").getAsString();
        String pathStr = args.get("file").getAsString();
        String symbolName = args.get(PARAM_SYMBOL).getAsString();
        int targetLine = args.has("line") ? args.get("line").getAsInt() : -1;
        String newName = args.has("new_name") ? args.get("new_name").getAsString() : null;

        if ("rename".equals(operation) && (newName == null || newName.isEmpty())) {
            return "Error: 'new_name' is required for rename operation";
        }

        CompletableFuture<String> resultFuture = new CompletableFuture<>();

        ApplicationManager.getApplication().invokeLater(() -> {
            try {
                VirtualFile vf = resolveVirtualFile(pathStr);
                if (vf == null) {
                    resultFuture.complete(ERROR_PREFIX + ERROR_FILE_NOT_FOUND + pathStr);
                    return;
                }

                PsiFile psiFile = PsiManager.getInstance(project).findFile(vf);
                if (psiFile == null) {
                    resultFuture.complete(ERROR_PREFIX + ERROR_CANNOT_PARSE + pathStr);
                    return;
                }

                Document document = FileDocumentManager.getInstance().getDocument(vf);
                PsiNamedElement targetElement = findNamedElement(psiFile, document, symbolName, targetLine);
                if (targetElement == null) {
                    resultFuture.complete("Error: Symbol '" + symbolName + "' not found in " + pathStr +
                        (targetLine > 0 ? " at line " + targetLine : "") +
                        ". Use search_symbols to find the correct name and location.");
                    return;
                }

                ApplicationManager.getApplication().runWriteAction(() -> {
                    try {
                        String result = executeRefactoring(operation, targetElement, symbolName, newName, pathStr);
                        resultFuture.complete(result);
                    } catch (Exception e) {
                        LOG.warn("Refactoring error", e);
                        resultFuture.complete("Error during refactoring: " + e.getMessage());
                    }
                });
            } catch (Exception e) {
                resultFuture.complete(ERROR_PREFIX + e.getMessage());
            }
        });

        return resultFuture.get(30, TimeUnit.SECONDS);
    }

    private String executeRefactoring(String operation, PsiNamedElement targetElement,
                                      String symbolName, String newName, String pathStr) {
        return switch (operation) {
            case "rename" -> performRename(targetElement, symbolName, newName, pathStr);
            case "safe_delete" -> performSafeDelete(targetElement, symbolName, pathStr);
            case "inline" -> "Error: 'inline' refactoring is not yet supported via this tool. " +
                "Use intellij_write_file to manually inline the code.";
            case "extract_method" -> "Error: 'extract_method' requires a code selection range " +
                "which is not well-suited for tool-based invocation. " +
                "Use intellij_write_file to manually extract the method.";
            default -> "Error: Unknown operation '" + operation + "'. Supported: rename, safe_delete";
        };
    }

    private String performRename(PsiNamedElement targetElement, String symbolName,
                                 String newName, String pathStr) {
        var refs = ReferencesSearch.search(targetElement, GlobalSearchScope.projectScope(project)).findAll();
        int refCount = refs.size();

        var factory = com.intellij.refactoring.RefactoringFactory.getInstance(project);
        var rename = factory.createRename(targetElement, newName);
        rename.setSearchInComments(true);
        rename.setSearchInNonJavaFiles(true);
        com.intellij.openapi.command.CommandProcessor.getInstance().executeCommand(
            project,
            () -> {
                var usages = rename.findUsages();
                rename.doRefactoring(usages);
            },
            "Rename " + symbolName + " to " + newName,
            null
        );

        com.intellij.psi.PsiDocumentManager.getInstance(project).commitAllDocuments();
        FileDocumentManager.getInstance().saveAllDocuments();

        return "? Renamed '" + symbolName + "' to '" + newName + "'\n" +
            "  Updated " + refCount + " references across the project.\n" +
            "  File: " + pathStr;
    }

    private String performSafeDelete(PsiNamedElement targetElement, String symbolName, String pathStr) {
        var refs = ReferencesSearch.search(targetElement, GlobalSearchScope.projectScope(project)).findAll();

        if (!refs.isEmpty()) {
            return formatUsageReport(symbolName, refs);
        }

        com.intellij.openapi.command.CommandProcessor.getInstance().executeCommand(
            project,
            targetElement::delete,
            "Safe Delete " + symbolName,
            null
        );
        com.intellij.psi.PsiDocumentManager.getInstance(project).commitAllDocuments();
        FileDocumentManager.getInstance().saveAllDocuments();

        return "? Safely deleted '" + symbolName + "' (no usages found).\n  File: " + pathStr;
    }

    private String formatUsageReport(String symbolName, Collection<PsiReference> refs) {
        StringBuilder sb = new StringBuilder();
        sb.append("Cannot safely delete '").append(symbolName)
            .append("' ? it has ").append(refs.size()).append(" usages:\n");
        int shown = 0;
        for (var ref : refs) {
            if (shown++ >= 10) {
                sb.append("  ... and ").append(refs.size() - 10).append(" more\n");
                break;
            }
            PsiFile refFile = ref.getElement().getContainingFile();
            int line = -1;
            if (refFile != null) {
                Document refDoc = FileDocumentManager.getInstance()
                    .getDocument(refFile.getVirtualFile());
                if (refDoc != null) {
                    line = refDoc.getLineNumber(ref.getElement().getTextOffset()) + 1;
                }
            }
            sb.append("  ").append(refFile != null ? refFile.getName() : "?")
                .append(":").append(line).append("\n");
        }
        return sb.toString();
    }

    /**
     * Find a PsiNamedElement by name, optionally constrained to a specific line.
     */
    private PsiNamedElement findNamedElement(PsiFile psiFile, Document document, String name, int targetLine) {
        PsiNamedElement[] found = {null};
        psiFile.accept(new PsiRecursiveElementWalkingVisitor() {
            @Override
            public void visitElement(@NotNull PsiElement element) {
                if (element instanceof PsiNamedElement named && name.equals(named.getName())) {
                    if (targetLine <= 0) {
                        // No line constraint — take first match
                        if (found[0] == null) found[0] = named;
                    } else if (document != null) {
                        int line = document.getLineNumber(element.getTextOffset()) + 1;
                        if (line == targetLine) {
                            found[0] = named;
                        }
                    }
                }
                if (found[0] == null) super.visitElement(element);
            }
        });
        return found[0];
    }

    private String goToDeclaration(JsonObject args) {
        if (!args.has("file") || !args.has(PARAM_SYMBOL) || !args.has("line")) {
            return "Error: 'file', 'symbol', and 'line' parameters are required";
        }
        String pathStr = args.get("file").getAsString();
        String symbolName = args.get(PARAM_SYMBOL).getAsString();
        int targetLine = args.get("line").getAsInt();

        return ReadAction.compute(() -> {
            VirtualFile vf = resolveVirtualFile(pathStr);
            if (vf == null) return ERROR_PREFIX + ERROR_FILE_NOT_FOUND + pathStr;

            PsiFile psiFile = PsiManager.getInstance(project).findFile(vf);
            if (psiFile == null) return ERROR_PREFIX + ERROR_CANNOT_PARSE + pathStr;

            Document document = FileDocumentManager.getInstance().getDocument(vf);
            if (document == null) return "Error: Cannot get document for: " + pathStr;

            if (targetLine < 1 || targetLine > document.getLineCount()) {
                return "Error: Line " + targetLine + " is out of bounds (file has " +
                    document.getLineCount() + FORMAT_LINES_SUFFIX;
            }
            int lineStartOffset = document.getLineStartOffset(targetLine - 1);
            int lineEndOffset = document.getLineEndOffset(targetLine - 1);

            List<PsiElement> declarations = findDeclarationsOnLine(
                psiFile, lineStartOffset, lineEndOffset, symbolName);

            if (declarations.isEmpty()) {
                declarations = findDeclarationByOffset(
                    psiFile, document, lineStartOffset, lineEndOffset, symbolName);
            }

            if (declarations.isEmpty()) {
                return "Could not resolve declaration for '" + symbolName + "' at line " + targetLine +
                    " in " + pathStr + ". The symbol may be unresolved or from an unindexed library.";
            }

            return formatDeclarationResults(declarations, symbolName);
        });
    }

    private List<PsiElement> findDeclarationsOnLine(
        PsiFile psiFile, int lineStartOffset, int lineEndOffset, String symbolName) {
        List<PsiElement> declarations = new ArrayList<>();
        psiFile.accept(new PsiRecursiveElementWalkingVisitor() {
            @Override
            public void visitElement(@NotNull PsiElement element) {
                int offset = element.getTextOffset();
                if (offset < lineStartOffset || offset > lineEndOffset) {
                    super.visitElement(element);
                    return;
                }
                if (!element.getText().equals(symbolName)
                    && !(element instanceof PsiNamedElement named && symbolName.equals(named.getName()))) {
                    super.visitElement(element);
                    return;
                }
                PsiReference ref = element.getReference();
                if (ref != null) {
                    PsiElement resolved = ref.resolve();
                    if (resolved != null) declarations.add(resolved);
                }
                if (element instanceof PsiNamedElement) {
                    for (PsiReference r : element.getReferences()) {
                        PsiElement res = r.resolve();
                        if (res != null && res != element) declarations.add(res);
                    }
                }
                super.visitElement(element);
            }
        });
        return declarations;
    }

    private List<PsiElement> findDeclarationByOffset(
        PsiFile psiFile, Document document, int lineStartOffset, int lineEndOffset, String symbolName) {
        List<PsiElement> declarations = new ArrayList<>();
        String lineText = document.getText(new com.intellij.openapi.util.TextRange(lineStartOffset, lineEndOffset));
        int symIdx = lineText.indexOf(symbolName);
        if (symIdx < 0) return declarations;

        int offset = lineStartOffset + symIdx;
        PsiElement elemAtOffset = psiFile.findElementAt(offset);
        if (elemAtOffset == null) return declarations;

        PsiElement current = elemAtOffset;
        for (int i = 0; i < 5 && current != null; i++) {
            PsiReference ref = current.getReference();
            if (ref != null) {
                PsiElement resolved = ref.resolve();
                if (resolved != null) {
                    declarations.add(resolved);
                    break;
                }
            }
            current = current.getParent();
        }
        return declarations;
    }

    private String formatDeclarationResults(List<PsiElement> declarations, String symbolName) {
        StringBuilder sb = new StringBuilder();
        sb.append("Declaration of '").append(symbolName).append("':\n\n");
        String basePath = project.getBasePath();

        for (PsiElement decl : declarations) {
            PsiFile declFile = decl.getContainingFile();
            if (declFile == null) continue;

            VirtualFile declVf = declFile.getVirtualFile();
            String declPath = resolveDeclPath(declVf, basePath);

            Document declDoc = declVf != null ? FileDocumentManager.getInstance().getDocument(declVf) : null;
            int declLine = declDoc != null ? declDoc.getLineNumber(decl.getTextOffset()) + 1 : -1;

            sb.append("  File: ").append(declPath).append("\n");
            sb.append("  Line: ").append(declLine).append("\n");
            appendDeclarationContext(sb, declDoc, declLine);
            sb.append("\n");
        }
        return sb.toString();
    }

    private String resolveDeclPath(VirtualFile declVf, String basePath) {
        if (declVf != null && basePath != null) return relativize(basePath, declVf.getPath());
        if (declVf != null) return declVf.getName();
        return "?";
    }

    private void appendDeclarationContext(StringBuilder sb, Document declDoc, int declLine) {
        if (declDoc == null || declLine <= 0) return;
        int startLine = Math.max(0, declLine - 3);
        int endLine = Math.min(declDoc.getLineCount() - 1, declLine + 2);
        sb.append("  Context:\n");
        for (int l = startLine; l <= endLine; l++) {
            int ls = declDoc.getLineStartOffset(l);
            int le = declDoc.getLineEndOffset(l);
            String lineContent = declDoc.getText(new com.intellij.openapi.util.TextRange(ls, le));
            sb.append(l == declLine - 1 ? "  → " : "    ")
                .append(l + 1).append(": ").append(lineContent).append("\n");
        }
    }

    private String getTypeHierarchy(JsonObject args) {
        if (!args.has(PARAM_SYMBOL)) return "Error: 'symbol' parameter is required";
        String symbolName = args.get(PARAM_SYMBOL).getAsString();
        String direction = args.has("direction") ? args.get("direction").getAsString() : "both";

        return ReadAction.compute(() -> {
            com.intellij.psi.PsiClass psiClass = resolveClassByName(symbolName);
            if (psiClass == null) {
                return "Error: Class/interface '" + symbolName + "' not found. " +
                    "Use search_symbols to find the correct name.";
            }

            StringBuilder sb = new StringBuilder();
            String basePath = project.getBasePath();

            String qualifiedName = psiClass.getQualifiedName();
            sb.append("Type hierarchy for: ").append(qualifiedName != null ? qualifiedName : symbolName);
            sb.append(psiClass.isInterface() ? " (interface)" : " (class)").append("\n\n");

            if ("supertypes".equals(direction) || "both".equals(direction)) {
                sb.append("Supertypes:\n");
                appendSupertypes(psiClass, sb, basePath, "  ", new HashSet<>(), 0);
                sb.append("\n");
            }

            if ("subtypes".equals(direction) || "both".equals(direction)) {
                appendSubtypes(psiClass, sb, basePath);
            }

            return sb.toString();
        });
    }

    private com.intellij.psi.PsiClass resolveClassByName(String symbolName) {
        var javaPsiFacade = com.intellij.psi.JavaPsiFacade.getInstance(project);
        var scope = GlobalSearchScope.allScope(project);

        com.intellij.psi.PsiClass psiClass = javaPsiFacade.findClass(symbolName, scope);
        if (psiClass != null) return psiClass;

        var classes = javaPsiFacade.findClasses(symbolName, scope);
        if (classes.length == 0) {
            var shortNameCache = com.intellij.psi.search.PsiShortNamesCache.getInstance(project);
            classes = shortNameCache.getClassesByName(symbolName, scope);
        }
        return classes.length > 0 ? classes[0] : null;
    }

    private void appendSubtypes(com.intellij.psi.PsiClass psiClass, StringBuilder sb, String basePath) {
        sb.append("Subtypes/Implementations:\n");
        var searcher = com.intellij.psi.search.searches.ClassInheritorsSearch.search(
            psiClass, GlobalSearchScope.projectScope(project), true);
        var inheritors = searcher.findAll();
        if (inheritors.isEmpty()) {
            sb.append("  (none found in project scope)\n");
            return;
        }
        for (var inheritor : inheritors) {
            String iName = inheritor.getQualifiedName();
            String iFile = getClassFile(inheritor, basePath);
            sb.append("  ").append(inheritor.isInterface() ? "interface " : "class ")
                .append(iName != null ? iName : inheritor.getName())
                .append(iFile).append("\n");
        }
    }

    private void appendSupertypes(com.intellij.psi.PsiClass psiClass, StringBuilder sb,
                                  String basePath, String indent, Set<String> visited, int depth) {
        if (depth > 10) return;
        String qn = psiClass.getQualifiedName();
        if (qn != null && !visited.add(qn)) return;

        // Superclass
        com.intellij.psi.PsiClass superClass = psiClass.getSuperClass();
        if (superClass != null && !"java.lang.Object".equals(superClass.getQualifiedName())) {
            String superName = superClass.getQualifiedName();
            String file = getClassFile(superClass, basePath);
            sb.append(indent).append("extends ").append(superName != null ? superName : superClass.getName())
                .append(file).append("\n");
            appendSupertypes(superClass, sb, basePath, indent + "  ", visited, depth + 1);
        }

        // Interfaces
        for (var iface : psiClass.getInterfaces()) {
            String ifaceName = iface.getQualifiedName();
            if ("java.lang.Object".equals(ifaceName)) continue;
            String file = getClassFile(iface, basePath);
            sb.append(indent).append("implements ").append(ifaceName != null ? ifaceName : iface.getName())
                .append(file).append("\n");
            appendSupertypes(iface, sb, basePath, indent + "  ", visited, depth + 1);
        }
    }

    private String getClassFile(com.intellij.psi.PsiClass cls, String basePath) {
        PsiFile file = cls.getContainingFile();
        if (file != null && file.getVirtualFile() != null && basePath != null) {
            String path = file.getVirtualFile().getPath();
            if (path.contains(".jar!")) return ""; // Library class — don't show path
            return " (" + relativize(basePath, path) + ")";
        }
        return "";
    }

    private String createFile(JsonObject args) throws Exception {
        if (!args.has("path") || !args.has(PARAM_CONTENT)) {
            return "Error: 'path' and 'content' parameters are required";
        }
        String pathStr = args.get("path").getAsString();
        String content = args.get(PARAM_CONTENT).getAsString();

        // Resolve path
        String basePath = project.getBasePath();
        Path pathObj = Path.of(pathStr);
        Path filePath;
        if (pathObj.isAbsolute()) {
            filePath = pathObj;
        } else if (basePath != null) {
            filePath = Path.of(basePath, pathStr);
        } else {
            return "Error: Cannot resolve relative path without project base path";
        }

        if (Files.exists(filePath)) {
            return "Error: File already exists: " + pathStr +
                ". Use intellij_write_file to modify existing files.";
        }

        // Create parent directories
        Path parentDir = filePath.getParent();
        if (parentDir != null) {
            Files.createDirectories(parentDir);
        }
        // Write content
        Files.writeString(filePath, content, StandardCharsets.UTF_8);

        // Refresh VFS so IntelliJ sees the file
        CompletableFuture<String> resultFuture = new CompletableFuture<>();
        ApplicationManager.getApplication().invokeLater(() -> {
            try {
                LocalFileSystem.getInstance().refreshAndFindFileByPath(filePath.toString());
                resultFuture.complete("✓ Created file: " + pathStr + " (" + content.length() + FORMAT_CHARS_SUFFIX);
            } catch (Exception e) {
                resultFuture.complete("File created but VFS refresh failed: " + e.getMessage());
            }
        });

        return resultFuture.get(10, TimeUnit.SECONDS);
    }

    private String deleteFile(JsonObject args) throws Exception {
        if (!args.has("path")) return ERROR_PATH_REQUIRED;
        String pathStr = args.get("path").getAsString();

        CompletableFuture<String> resultFuture = new CompletableFuture<>();

        // Resolve VirtualFile in read action on background thread to avoid EDT violations
        ReadAction.nonBlocking(() -> {
            try {
                VirtualFile vf = resolveVirtualFile(pathStr);
                if (vf == null) {
                    resultFuture.complete(ERROR_PREFIX + ERROR_FILE_NOT_FOUND + pathStr);
                    return null;
                }

                if (vf.isDirectory()) {
                    resultFuture.complete("Error: Cannot delete directories. Path is a directory: " + pathStr);
                    return null;
                }

                // Schedule delete on EDT after VFS resolution
                ApplicationManager.getApplication().invokeLater(() ->
                    ApplicationManager.getApplication().runWriteAction(() -> {
                        try {
                            com.intellij.openapi.command.CommandProcessor.getInstance().executeCommand(
                                project,
                                () -> {
                                    try {
                                        vf.delete(PsiBridgeService.this);
                                    } catch (IOException e) {
                                        throw new RuntimeException(e);
                                    }
                                },
                                "Delete File: " + vf.getName(),
                                null
                            );
                            resultFuture.complete("? Deleted file: " + pathStr);
                        } catch (Exception e) {
                            resultFuture.complete("Error deleting file: " + e.getMessage());
                        }
                    })
                );
                return null;
            } catch (Exception e) {
                resultFuture.complete(ERROR_PREFIX + e.getMessage());
                return null;
            }
        }).inSmartMode(project).submit(AppExecutorUtil.getAppExecutorService());

        return resultFuture.get(10, TimeUnit.SECONDS);
    }

    private String buildProject(JsonObject args) throws Exception {
        String moduleName = args.has(JSON_MODULE) ? args.get(JSON_MODULE).getAsString() : "";

        CompletableFuture<String> resultFuture = new CompletableFuture<>();
        long startTime = System.currentTimeMillis();

        ApplicationManager.getApplication().invokeLater(() -> {
            try {
                var compilerManager = com.intellij.openapi.compiler.CompilerManager.getInstance(project);

                com.intellij.openapi.compiler.CompileStatusNotification callback =
                    (aborted, errorCount, warningCount, context) ->
                        resultFuture.complete(formatBuildResult(aborted, errorCount, warningCount, context, startTime));

                if (!moduleName.isEmpty()) {
                    Module module = resolveModule(moduleName);
                    if (module == null) {
                        resultFuture.complete("Error: Module '" + moduleName + "' not found.\n" + listAvailableModules());
                        return;
                    }
                    compilerManager.compile(module, callback);
                } else {
                    compilerManager.make(callback);
                }
            } catch (Exception e) {
                LOG.warn("Build error", e);
                resultFuture.complete("Error starting build: " + e.getMessage());
            }
        });

        return resultFuture.get(300, TimeUnit.SECONDS);
    }

    private String formatBuildResult(boolean aborted, int errorCount, int warningCount,
                                     com.intellij.openapi.compiler.CompileContext context, long startTime) {
        long elapsed = System.currentTimeMillis() - startTime;
        StringBuilder sb = new StringBuilder();

        if (aborted) {
            sb.append("Build aborted.\n");
        } else if (errorCount == 0) {
            sb.append("? Build succeeded");
        } else {
            sb.append("? Build failed");
        }
        sb.append(String.format(" (%d errors, %d warnings, %.1fs)%n",
            errorCount, warningCount, elapsed / 1000.0));

        appendCompilerMessages(sb, context, com.intellij.openapi.compiler.CompilerMessageCategory.ERROR, "ERROR", Integer.MAX_VALUE);
        appendCompilerMessages(sb, context, com.intellij.openapi.compiler.CompilerMessageCategory.WARNING, "WARN", 20);

        return sb.toString();
    }

    private static void appendCompilerMessages(StringBuilder sb, com.intellij.openapi.compiler.CompileContext context,
                                               com.intellij.openapi.compiler.CompilerMessageCategory category,
                                               String label, int maxCount) {
        var messages = context.getMessages(category);
        int shown = 0;
        for (var msg : messages) {
            if (shown++ >= maxCount) {
                sb.append("  ... and ").append(messages.length - maxCount).append(" more ").append(label.toLowerCase()).append("s\n");
                break;
            }
            String file = msg.getVirtualFile() != null ? msg.getVirtualFile().getName() : "";
            sb.append("  ").append(label).append(" ").append(file);
            if (msg instanceof com.intellij.compiler.CompilerMessageImpl impl && impl.getLine() > 0) {
                sb.append(":").append(impl.getLine());
            }
            sb.append(" ").append(msg.getMessage()).append("\n");
        }
    }

    private Module resolveModule(String moduleName) {
        Module module = ModuleManager.getInstance(project).findModuleByName(moduleName);
        if (module == null) {
            String projectName = project.getName();
            module = ModuleManager.getInstance(project).findModuleByName(projectName + "." + moduleName);
        }
        return module;
    }

    private String listAvailableModules() {
        StringBuilder available = new StringBuilder("Available modules:\n");
        for (Module m : ModuleManager.getInstance(project).getModules()) {
            available.append("  ").append(m.getName()).append("\n");
        }
        return available.toString();
    }

    /**
     * Open a file in the editor, optionally navigating to a specific line.
     * This makes the file visible to the user and triggers DaemonCodeAnalyzer
     * (which enables get_highlights to return SonarLint and other external annotator results).
     */
    private String openInEditor(JsonObject args) throws Exception {
        if (!args.has("file")) {
            return "Error: 'file' parameter is required";
        }
        String pathStr = args.get("file").getAsString();
        int line = args.has("line") ? args.get("line").getAsInt() : -1;

        CompletableFuture<String> resultFuture = new CompletableFuture<>();

        ApplicationManager.getApplication().invokeLater(() -> {
            try {
                VirtualFile vf = resolveVirtualFile(pathStr);
                if (vf == null) {
                    resultFuture.complete(ERROR_PREFIX + ERROR_FILE_NOT_FOUND + pathStr);
                    return;
                }

                if (line > 0) {
                    new com.intellij.openapi.fileEditor.OpenFileDescriptor(project, vf, line - 1, 0)
                        .navigate(true);
                } else {
                    com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project)
                        .openFile(vf, true);
                }

                // Force DaemonCodeAnalyzer to run on this file
                PsiFile psiFile = ReadAction.compute(() -> PsiManager.getInstance(project).findFile(vf));
                if (psiFile != null) {
                    // Using deprecated restart() method - no alternative available
                    //noinspection deprecation
                    com.intellij.codeInsight.daemon.DaemonCodeAnalyzer.getInstance(project).restart(psiFile);
                }

                resultFuture.complete("Opened " + pathStr + (line > 0 ? " at line " + line : "") +
                    " (daemon analysis triggered - use get_highlights after a moment)");
            } catch (Exception e) {
                resultFuture.complete("Error opening file: " + e.getMessage());
            }
        });

        return resultFuture.get(10, TimeUnit.SECONDS);
    }

    /**
     * Show a diff between two files, or between the current file content and a provided string,
     * in IntelliJ's diff viewer.
     */
    private String showDiff(JsonObject args) throws Exception {
        if (!args.has("file")) {
            return "Error: 'file' parameter is required";
        }
        String pathStr = args.get("file").getAsString();

        CompletableFuture<String> resultFuture = new CompletableFuture<>();

        ApplicationManager.getApplication().invokeLater(() -> {
            try {
                VirtualFile vf = resolveVirtualFile(pathStr);
                if (vf == null) {
                    resultFuture.complete(ERROR_PREFIX + ERROR_FILE_NOT_FOUND + pathStr);
                    return;
                }

                String result = showDiffForFile(args, vf, pathStr);
                resultFuture.complete(result);
            } catch (Exception e) {
                resultFuture.complete("Error showing diff: " + e.getMessage());
            }
        });

        return resultFuture.get(10, TimeUnit.SECONDS);
    }

    private String showDiffForFile(JsonObject args, VirtualFile vf, String pathStr) {
        if (args.has("file2")) {
            return showTwoFileDiff(args, vf, pathStr);
        } else if (args.has(PARAM_CONTENT)) {
            return showContentDiff(args, vf, pathStr);
        } else {
            return showVcsDiff(vf, pathStr);
        }
    }

    private String showTwoFileDiff(JsonObject args, VirtualFile vf, String pathStr) {
        String pathStr2 = args.get("file2").getAsString();
        VirtualFile vf2 = resolveVirtualFile(pathStr2);
        if (vf2 == null) {
            return "Error: Second file not found: " + pathStr2;
        }
        var content1 = com.intellij.diff.DiffContentFactory.getInstance().create(project, vf);
        var content2 = com.intellij.diff.DiffContentFactory.getInstance().create(project, vf2);
        var request = new com.intellij.diff.requests.SimpleDiffRequest(
            "Diff: " + vf.getName() + " vs " + vf2.getName(),
            content1, content2, vf.getName(), vf2.getName());
        com.intellij.diff.DiffManager.getInstance().showDiff(project, request);
        return "Showing diff: " + pathStr + " vs " + pathStr2;
    }

    private String showContentDiff(JsonObject args, VirtualFile vf, String pathStr) {
        String newContent = args.get(PARAM_CONTENT).getAsString();
        String title = args.has(JSON_TITLE) ? args.get(JSON_TITLE).getAsString() : "Proposed Changes";
        var content1 = com.intellij.diff.DiffContentFactory.getInstance().create(project, vf);
        var content2 = com.intellij.diff.DiffContentFactory.getInstance()
            .create(project, newContent, vf.getFileType());
        var request = new com.intellij.diff.requests.SimpleDiffRequest(
            title, content1, content2, DIFF_LABEL_CURRENT, "Proposed");
        com.intellij.diff.DiffManager.getInstance().showDiff(project, request);
        return "Showing diff for " + pathStr + ": current vs proposed changes";
    }

    private String showVcsDiff(VirtualFile vf, String pathStr) {
        var content1 = com.intellij.diff.DiffContentFactory.getInstance().create(project, vf);
        com.intellij.diff.DiffManager.getInstance().showDiff(project,
            new com.intellij.diff.requests.SimpleDiffRequest(
                "File: " + vf.getName(), content1, content1, DIFF_LABEL_CURRENT, DIFF_LABEL_CURRENT));
        return "Opened " + pathStr + " in diff viewer. " +
            "Tip: pass 'file2' for two-file diff, or 'content' to diff against proposed changes.";
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
