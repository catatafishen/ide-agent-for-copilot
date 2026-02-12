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
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.execution.RunContentExecutor;
import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.execution.process.OSProcessHandler;
import com.intellij.execution.process.ProcessAdapter;
import com.intellij.execution.process.ProcessEvent;
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
            ProjectFileIndex.getInstance(project).iterateContent(vf -> {
                if (!vf.isDirectory()) {
                    String relPath = relativize(basePath, vf.getPath());
                    if (relPath == null) return true;
                    if (!dir.isEmpty() && !relPath.startsWith(dir)) return true;
                    if (!pattern.isEmpty() && !matchGlob(vf.getName(), pattern)) return true;
                    files.add(String.format("%s [%s]", relPath, fileType(vf.getName())));
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
        String query = args.get("query").getAsString();
        String typeFilter = args.has("type") ? args.get("type").getAsString() : "";

        return ReadAction.compute(() -> {
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

    // ---- Test Tools ----

    private String listTests(JsonObject args) {
        String filePattern = args.has("file_pattern") ? args.get("file_pattern").getAsString() : "";

        return ReadAction.compute(() -> {
            List<String> tests = new ArrayList<>();
            String basePath = project.getBasePath();

            ProjectFileIndex.getInstance(project).iterateContent(vf -> {
                if (vf.isDirectory()) return true;
                String name = vf.getName();
                if (!name.endsWith(".java") && !name.endsWith(".kt")) return true;
                if (!filePattern.isEmpty() && !matchGlob(name, filePattern)) return true;

                // Only scan test source directories
                String relPath = relativize(basePath, vf.getPath());
                if (relPath == null || !relPath.contains("/test/")) return true;

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

        // Build Gradle command
        String gradlew = basePath + (System.getProperty("os.name").contains("Win")
                ? "\\gradlew.bat" : "/gradlew");
        String taskPrefix = module.isEmpty() ? "" : ":" + module + ":";

        GeneralCommandLine cmd = new GeneralCommandLine();
        cmd.setExePath(gradlew);
        cmd.addParameters(taskPrefix + "test", "--tests", target);
        cmd.setWorkDirectory(basePath);
        cmd.withEnvironment("JAVA_HOME",
                System.getProperty("java.home", System.getenv("JAVA_HOME")));

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

    // ---- Test Helper Methods ----

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
        if (cls.contains("PsiClass") && !cls.contains("Initializer")) return "class";
        if (cls.contains("PsiMethod")) return "method";
        if (cls.contains("PsiField")) return "field";
        if (cls.contains("PsiEnumConstant")) return "field";

        // Kotlin PSI
        if (cls.equals("KtClass") || cls.equals("KtObjectDeclaration")) return "class";
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
