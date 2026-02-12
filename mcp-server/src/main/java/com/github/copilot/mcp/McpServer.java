package com.github.copilot.mcp;

import com.google.gson.*;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import java.util.stream.*;

/**
 * Lightweight MCP (Model Context Protocol) stdio server providing code intelligence tools.
 * Launched as a subprocess by the Copilot agent via the ACP mcpServers parameter.
 *
 * Provides tools:
 * - search_symbols: Find class/method/function definitions across the project
 * - get_file_outline: Extract structure (classes, methods, fields) from a source file
 * - find_references: Find usages of a symbol name across the project
 * - list_project_files: List source files in the project with type info
 */
public class McpServer {

    private static final Gson GSON = new GsonBuilder().create();
    private static String projectRoot = ".";

    public static void main(String[] args) throws Exception {
        if (args.length > 0) {
            projectRoot = args[0];
        }

        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
        String line;
        while ((line = reader.readLine()) != null) {
            line = line.trim();
            if (line.isEmpty()) continue;
            try {
                JsonObject msg = JsonParser.parseString(line).getAsJsonObject();
                JsonObject response = handleMessage(msg);
                if (response != null) {
                    System.out.println(GSON.toJson(response));
                    System.out.flush();
                }
            } catch (Exception e) {
                System.err.println("MCP Server error: " + e.getMessage());
            }
        }
    }

    static JsonObject handleMessage(JsonObject msg) {
        String method = msg.has("method") ? msg.get("method").getAsString() : null;
        boolean hasId = msg.has("id") && !msg.get("id").isJsonNull();
        JsonObject params = msg.has("params") ? msg.getAsJsonObject("params") : new JsonObject();

        if (method == null) return null;

        return switch (method) {
            case "initialize" -> hasId ? respond(msg, handleInitialize()) : null;
            case "initialized" -> null; // notification
            case "tools/list" -> hasId ? respond(msg, handleToolsList()) : null;
            case "tools/call" -> hasId ? respond(msg, handleToolsCall(params)) : null;
            case "ping" -> hasId ? respond(msg, new JsonObject()) : null;
            default -> hasId ? respondError(msg, -32601, "Method not found: " + method) : null;
        };
    }

    private static JsonObject respond(JsonObject request, JsonObject result) {
        JsonObject response = new JsonObject();
        response.addProperty("jsonrpc", "2.0");
        response.add("id", request.get("id"));
        response.add("result", result);
        return response;
    }

    private static JsonObject respondError(JsonObject request, int code, String message) {
        JsonObject response = new JsonObject();
        response.addProperty("jsonrpc", "2.0");
        response.add("id", request.get("id"));
        JsonObject error = new JsonObject();
        error.addProperty("code", code);
        error.addProperty("message", message);
        response.add("error", error);
        return response;
    }

    private static JsonObject handleInitialize() {
        JsonObject result = new JsonObject();
        result.addProperty("protocolVersion", "2025-03-26");
        JsonObject serverInfo = new JsonObject();
        serverInfo.addProperty("name", "intellij-code-tools");
        serverInfo.addProperty("version", "0.1.0");
        result.add("serverInfo", serverInfo);
        JsonObject capabilities = new JsonObject();
        JsonObject tools = new JsonObject();
        tools.addProperty("listChanged", false);
        capabilities.add("tools", tools);
        result.add("capabilities", capabilities);
        return result;
    }

    private static JsonObject handleToolsList() {
        JsonObject result = new JsonObject();
        JsonArray tools = new JsonArray();

        tools.add(buildTool("search_symbols",
                "Search for class, method, function, or interface definitions across the project using " +
                "IntelliJ's code analysis engine (AST). Returns file path, line number, and the definition line. " +
                "More accurate than grep — understands code structure. PREFER THIS over grep for finding definitions.",
                Map.of(
                    "query", Map.of("type", "string", "description", "Symbol name or pattern to search for (supports regex)"),
                    "type", Map.of("type", "string", "description", "Optional: filter by type (class, method, function, interface, field)", "default", "")
                ),
                List.of("query")));

        tools.add(buildTool("get_file_outline",
                "Get the structural outline of a source file using AST analysis: classes, methods, fields, " +
                "functions with line numbers. PREFER THIS over grep for understanding file structure.",
                Map.of("path", Map.of("type", "string", "description", "Absolute or project-relative path to the source file")),
                List.of("path")));

        tools.add(buildTool("find_references",
                "Find all usages of a symbol using IntelliJ's reference resolution engine. " +
                "Understands imports, type hierarchy, and overrides. PREFER THIS over grep for finding usages.",
                Map.of(
                    "symbol", Map.of("type", "string", "description", "The exact symbol name to search for"),
                    "file_pattern", Map.of("type", "string", "description", "Optional glob pattern to filter files (e.g., '*.java', '*.kt')", "default", "")
                ),
                List.of("symbol")));

        tools.add(buildTool("list_project_files",
                "List source files in the project with their types. Useful for getting an overview of the project structure.",
                Map.of(
                    "directory", Map.of("type", "string", "description", "Optional subdirectory to list (relative to project root)", "default", ""),
                    "pattern", Map.of("type", "string", "description", "Optional glob pattern (e.g., '*.java')", "default", "")
                ),
                List.of()));

        tools.add(buildTool("list_tests",
                "Discover test classes and test methods in the project using AST analysis. " +
                "Finds methods annotated with @Test, @ParameterizedTest, @RepeatedTest. " +
                "PREFER THIS over grep for finding tests.",
                Map.of(
                    "file_pattern", Map.of("type", "string", "description", "Optional glob pattern to filter test files (e.g., '*.java', '*Test.kt')", "default", "")
                ),
                List.of()));

        tools.add(buildTool("run_tests",
                "Run tests through IntelliJ's test runner. Tests appear in the IDE's Run panel. " +
                "Returns structured results with pass/fail counts and failure details. " +
                "PREFER THIS over running gradle/maven commands directly.",
                Map.of(
                    "target", Map.of("type", "string", "description", "Test target: fully qualified class name (e.g., 'com.example.MyTest'), " +
                            "class.method (e.g., 'MyTest.testFoo'), or pattern with wildcards (e.g., '*Test')"),
                    "module", Map.of("type", "string", "description", "Optional Gradle module name (e.g., 'mcp-server', 'plugin-core')", "default", "")
                ),
                List.of("target")));

        tools.add(buildTool("get_test_results",
                "Get the results from the last test run without re-running tests. " +
                "Returns pass/fail counts, failure details, and timing from JUnit XML reports.",
                Map.of(
                    "module", Map.of("type", "string", "description", "Optional Gradle module name to get results for", "default", "")
                ),
                List.of()));

        tools.add(buildTool("get_coverage",
                "Get code coverage data for the project or a specific file. " +
                "Reads JaCoCo reports or IntelliJ coverage data. Run tests with coverage first.",
                Map.of(
                    "file", Map.of("type", "string", "description", "Optional file or class name to filter coverage for", "default", "")
                ),
                List.of()));

        tools.add(buildTool("get_project_info",
                "Get project environment info: SDK/JDK path and version, modules, build system, " +
                "and run configurations. ALWAYS call this first before running any build/test commands " +
                "to get the correct JAVA_HOME and build tool paths. Do NOT search for java or gradle yourself.",
                Map.of(),
                List.of()));

        tools.add(buildTool("list_run_configurations",
                "List all IntelliJ run configurations in the project. " +
                "Shows configuration name, type (JUnit, Gradle, Application, etc.), and whether it's temporary.",
                Map.of(),
                List.of()));

        tools.add(buildTool("run_configuration",
                "Execute an existing IntelliJ run configuration by name. " +
                "The run will appear in IntelliJ's Run panel with full output. " +
                "PREFER THIS over running commands manually. Use list_run_configurations to find available configs.",
                Map.of(
                    "name", Map.of("type", "string", "description", "Exact name of the run configuration to execute")
                ),
                List.of("name")));

        tools.add(buildTool("create_run_configuration",
                "Create a new IntelliJ run configuration. Supports Application, JUnit, Gradle, and other types. " +
                "Can set environment variables, JVM args, working directory, main class, test class, etc. " +
                "The configuration is saved and selected in IntelliJ. Use run_configuration to execute it.",
                Map.of(
                    "name", Map.of("type", "string", "description", "Name for the new run configuration"),
                    "type", Map.of("type", "string", "description", "Configuration type: 'application', 'junit', 'gradle', etc."),
                    "env", Map.of("type", "string", "description", "Optional: JSON object of environment variables, e.g. {\"KEY\":\"value\"}"),
                    "jvm_args", Map.of("type", "string", "description", "Optional: JVM arguments (e.g., '-Xmx2g -Dkey=value')"),
                    "program_args", Map.of("type", "string", "description", "Optional: program arguments"),
                    "working_dir", Map.of("type", "string", "description", "Optional: working directory path"),
                    "main_class", Map.of("type", "string", "description", "Optional: main class (for Application configs)"),
                    "test_class", Map.of("type", "string", "description", "Optional: test class (for JUnit configs)"),
                    "module_name", Map.of("type", "string", "description", "Optional: IntelliJ module name (from get_project_info)")
                ),
                List.of("name", "type")));

        tools.add(buildTool("edit_run_configuration",
                "Edit an existing IntelliJ run configuration. Can modify environment variables, JVM args, " +
                "working directory, program args, and type-specific properties. " +
                "For env vars, pass a JSON object — set value to null to remove a variable.",
                Map.of(
                    "name", Map.of("type", "string", "description", "Name of the run configuration to edit"),
                    "env", Map.of("type", "string", "description", "Optional: JSON object of env vars to set/update. Use null value to remove."),
                    "jvm_args", Map.of("type", "string", "description", "Optional: new JVM arguments"),
                    "program_args", Map.of("type", "string", "description", "Optional: new program arguments"),
                    "working_dir", Map.of("type", "string", "description", "Optional: new working directory")
                ),
                List.of("name")));

        result.add("tools", tools);
        return result;
    }

    private static JsonObject buildTool(String name, String description, Map<String, Map<String, String>> properties, List<String> required) {
        JsonObject tool = new JsonObject();
        tool.addProperty("name", name);
        tool.addProperty("description", description);
        JsonObject inputSchema = new JsonObject();
        inputSchema.addProperty("type", "object");
        JsonObject props = new JsonObject();
        for (var entry : properties.entrySet()) {
            JsonObject prop = new JsonObject();
            entry.getValue().forEach(prop::addProperty);
            props.add(entry.getKey(), prop);
        }
        inputSchema.add("properties", props);
        JsonArray req = new JsonArray();
        required.forEach(req::add);
        inputSchema.add("required", req);
        tool.add("inputSchema", inputSchema);
        return tool;
    }

    private static JsonObject handleToolsCall(JsonObject params) {
        String toolName = params.has("name") ? params.get("name").getAsString() : "";
        JsonObject arguments = params.has("arguments") ? params.getAsJsonObject("arguments") : new JsonObject();

        try {
            // Try PSI bridge first for accurate AST-based analysis
            String bridgeResult = tryPsiBridge(toolName, arguments);

            String resultText;
            if (bridgeResult != null) {
                System.err.println("MCP: tool '" + toolName + "' handled by PSI bridge");
                resultText = bridgeResult;
            } else {
                // Fall back to regex-based analysis
                resultText = switch (toolName) {
                    case "search_symbols" -> searchSymbols(arguments);
                    case "get_file_outline" -> getFileOutline(arguments);
                    case "find_references" -> findReferences(arguments);
                    case "list_project_files" -> listProjectFiles(arguments);
                    case "list_tests" -> listTestsFallback(arguments);
                    case "run_tests", "get_test_results", "get_coverage",
                         "get_project_info", "list_run_configurations", "run_configuration",
                         "create_run_configuration", "edit_run_configuration" ->
                            "PSI bridge unavailable. These tools require IntelliJ to be running.";
                    default -> "Unknown tool: " + toolName;
                };
            }

            JsonObject result = new JsonObject();
            JsonArray content = new JsonArray();
            JsonObject textContent = new JsonObject();
            textContent.addProperty("type", "text");
            textContent.addProperty("text", resultText);
            content.add(textContent);
            result.add("content", content);
            return result;

        } catch (Exception e) {
            JsonObject result = new JsonObject();
            result.addProperty("isError", true);
            JsonArray content = new JsonArray();
            JsonObject textContent = new JsonObject();
            textContent.addProperty("type", "text");
            textContent.addProperty("text", "Error: " + e.getMessage());
            content.add(textContent);
            result.add("content", content);
            return result;
        }
    }

    /**
     * Try to delegate a tool call to the IntelliJ PSI bridge for accurate AST analysis.
     * Falls back to null if bridge is unavailable.
     */
    private static String tryPsiBridge(String toolName, JsonObject arguments) {
        try {
            Path bridgeFile = Path.of(System.getProperty("user.home"), ".copilot", "psi-bridge.json");
            if (!Files.exists(bridgeFile)) return null;

            String content = Files.readString(bridgeFile);
            JsonObject bridge = JsonParser.parseString(content).getAsJsonObject();
            int port = bridge.get("port").getAsInt();

            // Verify project path matches
            if (bridge.has("projectPath")) {
                String bridgeProject = bridge.get("projectPath").getAsString().replace('\\', '/');
                String ourProject = projectRoot.replace('\\', '/');
                if (!ourProject.startsWith(bridgeProject) && !bridgeProject.startsWith(ourProject)) {
                    return null;
                }
            }

            URL url = URI.create("http://127.0.0.1:" + port + "/tools/call").toURL();
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(2000);
            conn.setReadTimeout(30000);
            conn.setRequestProperty("Content-Type", "application/json");

            JsonObject request = new JsonObject();
            request.addProperty("name", toolName);
            request.add("arguments", arguments);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(GSON.toJson(request).getBytes(StandardCharsets.UTF_8));
            }

            if (conn.getResponseCode() == 200) {
                try (InputStream is = conn.getInputStream()) {
                    String response = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                    JsonObject result = JsonParser.parseString(response).getAsJsonObject();
                    return result.get("result").getAsString();
                }
            }
        } catch (Exception e) {
            System.err.println("PSI Bridge unavailable (" + e.getMessage() + "), using regex fallback");
        }
        return null;
    }

    // --- Tool implementations (regex fallback) ---

    static String listTestsFallback(JsonObject args) throws IOException {
        String filePattern = args.has("file_pattern") ? args.get("file_pattern").getAsString() : "";
        Pattern testAnnotation = Pattern.compile("^\\s*@(Test|ParameterizedTest|RepeatedTest)");
        Pattern methodPattern = Pattern.compile("^\\s*(?:public|private|protected)?\\s*(?:void|fun)\\s+(\\w+)\\s*\\(");

        List<String> results = new ArrayList<>();
        Path root = Path.of(projectRoot);

        try (Stream<Path> files = Files.walk(root)) {
            List<Path> testFiles = files
                    .filter(Files::isRegularFile)
                    .filter(p -> {
                        String name = p.getFileName().toString();
                        return (name.endsWith(".java") || name.endsWith(".kt"))
                                && (filePattern.isEmpty() || matchesGlob(name, filePattern));
                    })
                    .filter(p -> !isExcluded(root, p))
                    .collect(Collectors.toList());

            for (Path file : testFiles) {
                try {
                    List<String> lines = Files.readAllLines(file);
                    for (int i = 0; i < lines.size() - 1; i++) {
                        if (testAnnotation.matcher(lines.get(i)).find()) {
                            for (int j = i + 1; j < Math.min(i + 5, lines.size()); j++) {
                                Matcher m = methodPattern.matcher(lines.get(j));
                                if (m.find()) {
                                    String relPath = root.relativize(file).toString();
                                    results.add(String.format("%s (%s:%d)", m.group(1), relPath, j + 1));
                                    break;
                                }
                            }
                        }
                    }
                } catch (Exception ignored) {
                }
                if (results.size() >= 500) break;
            }
        } catch (java.io.UncheckedIOException ignored) {
        }

        if (results.isEmpty()) return "No tests found";
        return results.size() + " tests:\n" + String.join("\n", results);
    }

    static String searchSymbols(JsonObject args) throws IOException {
        String query = args.get("query").getAsString();
        String typeFilter = args.has("type") ? args.get("type").getAsString() : "";
        Pattern queryPattern = Pattern.compile(query, Pattern.CASE_INSENSITIVE);

        List<String> results = new ArrayList<>();
        Path root = Path.of(projectRoot);

        // Patterns for different symbol types across languages
        Map<String, Pattern> symbolPatterns = new LinkedHashMap<>();
        symbolPatterns.put("class", Pattern.compile(
            "^\\s*(?:public|private|protected|abstract|final|open|data|sealed|internal)?\\s*(?:class|object|enum)\\s+(\\w+)"));
        symbolPatterns.put("interface", Pattern.compile(
            "^\\s*(?:public|private|protected)?\\s*interface\\s+(\\w+)"));
        symbolPatterns.put("method", Pattern.compile(
            "^\\s*(?:public|private|protected|internal|override|abstract|static|final|suspend)?\\s*(?:fun|def)\\s+(\\w+)"));
        symbolPatterns.put("function", Pattern.compile(
            "^\\s*(?:public|private|protected|static|final|synchronized)?\\s*(?:\\w+(?:<[^>]+>)?\\s+)+(\\w+)\\s*\\("));
        symbolPatterns.put("field", Pattern.compile(
            "^\\s*(?:public|private|protected|internal)?\\s*(?:val|var|const|static|final)?\\s*(?:val|var|let|const)?\\s+(\\w+)\\s*[:=]"));

        try (Stream<Path> files = Files.walk(root)) {
            List<Path> sourceFiles = files
                .filter(Files::isRegularFile)
                .filter(p -> isSourceFile(p.toString()))
                .filter(p -> !isExcluded(root, p))
                .collect(Collectors.toList());

            for (Path file : sourceFiles) {
                try {
                    List<String> lines = Files.readAllLines(file);
                    for (int i = 0; i < lines.size(); i++) {
                        String line = lines.get(i);
                        for (var entry : symbolPatterns.entrySet()) {
                            if (!typeFilter.isEmpty() && !entry.getKey().equals(typeFilter)) continue;
                            Matcher m = entry.getValue().matcher(line);
                            if (m.find()) {
                                String symbolName = m.group(1);
                                if (queryPattern.matcher(symbolName).find()) {
                                    String relPath = root.relativize(file).toString();
                                    results.add(String.format("%s:%d [%s] %s", relPath, i + 1, entry.getKey(), line.trim()));
                                }
                            }
                        }
                        if (results.size() >= 50) break;
                    }
                } catch (Exception e) {
                    // Skip unreadable files
                }
                if (results.size() >= 50) break;
            }
        } catch (java.io.UncheckedIOException e) {
            // AccessDeniedException from Files.walk on Windows junction points
        }

        if (results.isEmpty()) return "No symbols found matching '" + query + "'";
        return String.join("\n", results);
    }

    static String getFileOutline(JsonObject args) throws IOException {
        String pathStr = args.get("path").getAsString();
        Path file = resolvePath(pathStr);
        if (!Files.exists(file)) return "File not found: " + pathStr;

        List<String> lines = Files.readAllLines(file);
        List<String> outline = new ArrayList<>();

        Pattern classPattern = Pattern.compile(
            "^\\s*(?:public|private|protected|abstract|final|open|data|sealed|internal)?\\s*(?:class|object|enum|interface)\\s+(\\w+)");
        Pattern methodPattern = Pattern.compile(
            "^\\s*(?:public|private|protected|internal|override|abstract|static|final|suspend)?\\s*(?:fun|def)\\s+(\\w+)");
        Pattern javaMethodPattern = Pattern.compile(
            "^\\s*(?:public|private|protected|static|final|synchronized|abstract)?\\s*(?:(?:void|int|long|boolean|String|\\w+(?:<[^>]+>)?)\\s+)(\\w+)\\s*\\(");
        Pattern fieldPattern = Pattern.compile(
            "^\\s*(?:public|private|protected|internal)?\\s*(?:val|var|const|static|final)?\\s*(?:val|var)?\\s+(\\w+)\\s*[:=]");

        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            Matcher cm = classPattern.matcher(line);
            if (cm.find()) {
                outline.add(String.format("  %d: class %s", i + 1, cm.group(1)));
                continue;
            }
            Matcher mm = methodPattern.matcher(line);
            if (mm.find()) {
                outline.add(String.format("  %d:   fun %s()", i + 1, mm.group(1)));
                continue;
            }
            Matcher jm = javaMethodPattern.matcher(line);
            if (jm.find() && !line.contains("new ") && !line.trim().startsWith("return") && !line.trim().startsWith("if")) {
                outline.add(String.format("  %d:   method %s()", i + 1, jm.group(1)));
                continue;
            }
            Matcher fm = fieldPattern.matcher(line);
            if (fm.find() && !line.trim().startsWith("//") && !line.trim().startsWith("*")) {
                outline.add(String.format("  %d:   field %s", i + 1, fm.group(1)));
            }
        }

        if (outline.isEmpty()) return "No structural elements found in " + pathStr;
        String relPath = Path.of(projectRoot).relativize(file).toString();
        return "Outline of " + relPath + ":\n" + String.join("\n", outline);
    }

    static String findReferences(JsonObject args) throws IOException {
        String symbol = args.get("symbol").getAsString();
        String filePattern = args.has("file_pattern") ? args.get("file_pattern").getAsString() : "";
        Pattern pattern = Pattern.compile("\\b" + Pattern.quote(symbol) + "\\b");

        List<String> results = new ArrayList<>();
        Path root = Path.of(projectRoot);

        try (Stream<Path> files = Files.walk(root, FileVisitOption.FOLLOW_LINKS)) {
            List<Path> sourceFiles = files
                .filter(Files::isRegularFile)
                .filter(p -> isSourceFile(p.toString()))
                .filter(p -> !isExcluded(root, p))
                .filter(p -> filePattern.isEmpty() || matchesGlob(p.getFileName().toString(), filePattern))
                .collect(Collectors.toList());

            for (Path file : sourceFiles) {
                try {
                    List<String> lines = Files.readAllLines(file);
                    for (int i = 0; i < lines.size(); i++) {
                        if (pattern.matcher(lines.get(i)).find()) {
                            String relPath = root.relativize(file).toString();
                            results.add(String.format("%s:%d: %s", relPath, i + 1, lines.get(i).trim()));
                        }
                    }
                } catch (Exception e) {
                    // Skip unreadable files
                }
                if (results.size() >= 100) break;
            }
        } catch (java.io.UncheckedIOException e) {
            // AccessDeniedException from Files.walk on Windows junction points
        }

        if (results.isEmpty()) return "No references found for '" + symbol + "'";
        return results.size() + " references found:\n" + String.join("\n", results);
    }

    static String listProjectFiles(JsonObject args) throws IOException {
        String dir = args.has("directory") ? args.get("directory").getAsString() : "";
        String pattern = args.has("pattern") ? args.get("pattern").getAsString() : "";

        Path root = Path.of(projectRoot);
        Path searchDir = dir.isEmpty() ? root : root.resolve(dir);
        if (!Files.exists(searchDir)) return "Directory not found: " + dir;

        List<String> results = new ArrayList<>();
        try (Stream<Path> files = Files.walk(searchDir, FileVisitOption.FOLLOW_LINKS)) {
            files.filter(Files::isRegularFile)
                .filter(p -> !isExcluded(root, p))
                .filter(p -> pattern.isEmpty() || matchesGlob(p.getFileName().toString(), pattern))
                .sorted()
                .limit(200)
                .forEach(p -> {
                    String relPath = root.relativize(p).toString();
                    String type = getFileType(p.toString());
                    results.add(String.format("%s [%s]", relPath, type));
                });
        } catch (java.io.UncheckedIOException e) {
            // AccessDeniedException from Files.walk on Windows junction points
        }

        if (results.isEmpty()) return "No files found";
        return results.size() + " files:\n" + String.join("\n", results);
    }

    // --- Utility methods ---

    private static Path resolvePath(String pathStr) throws IOException {
        Path path = Path.of(pathStr);
        Path resolved = path.isAbsolute() ? path : Path.of(projectRoot).resolve(path);
        Path normalized = resolved.normalize();
        Path rootPath = Path.of(projectRoot).normalize();
        if (!normalized.startsWith(rootPath)) {
            throw new IOException("Access denied: path outside project root");
        }
        return normalized;
    }

    private static boolean isSourceFile(String path) {
        String lower = path.toLowerCase();
        return lower.endsWith(".java") || lower.endsWith(".kt") || lower.endsWith(".kts") ||
               lower.endsWith(".py") || lower.endsWith(".js") || lower.endsWith(".ts") ||
               lower.endsWith(".tsx") || lower.endsWith(".jsx") || lower.endsWith(".go") ||
               lower.endsWith(".rs") || lower.endsWith(".c") || lower.endsWith(".cpp") ||
               lower.endsWith(".h") || lower.endsWith(".cs") || lower.endsWith(".rb") ||
               lower.endsWith(".scala") || lower.endsWith(".groovy") || lower.endsWith(".xml") ||
               lower.endsWith(".yaml") || lower.endsWith(".yml") || lower.endsWith(".json") ||
               lower.endsWith(".md") || lower.endsWith(".gradle") || lower.endsWith(".gradle.kts");
    }

    private static boolean isExcluded(Path root, Path file) {
        String rel = root.relativize(file).toString().replace('\\', '/');
        return rel.startsWith("build/") || rel.startsWith(".gradle/") || rel.startsWith(".git/") ||
               rel.startsWith("node_modules/") || rel.startsWith("target/") || rel.startsWith(".idea/") ||
               rel.startsWith("AppData/") || rel.startsWith(".copilot/") || rel.startsWith(".jdks/") ||
               rel.startsWith(".nuget/") || rel.startsWith(".m2/") || rel.startsWith(".npm/") ||
               rel.contains("/build/") || rel.contains("/.gradle/") || rel.contains("/node_modules/");
    }

    private static boolean matchesGlob(String fileName, String pattern) {
        String regex = pattern.replace(".", "\\.").replace("*", ".*").replace("?", ".");
        return fileName.matches(regex);
    }

    private static String getFileType(String path) {
        String lower = path.toLowerCase();
        if (lower.endsWith(".java")) return "Java";
        if (lower.endsWith(".kt") || lower.endsWith(".kts")) return "Kotlin";
        if (lower.endsWith(".py")) return "Python";
        if (lower.endsWith(".js") || lower.endsWith(".jsx")) return "JavaScript";
        if (lower.endsWith(".ts") || lower.endsWith(".tsx")) return "TypeScript";
        if (lower.endsWith(".go")) return "Go";
        if (lower.endsWith(".rs")) return "Rust";
        if (lower.endsWith(".xml")) return "XML";
        if (lower.endsWith(".json")) return "JSON";
        if (lower.endsWith(".md")) return "Markdown";
        if (lower.endsWith(".gradle") || lower.endsWith(".gradle.kts")) return "Gradle";
        if (lower.endsWith(".yaml") || lower.endsWith(".yml")) return "YAML";
        return "Other";
    }
}
