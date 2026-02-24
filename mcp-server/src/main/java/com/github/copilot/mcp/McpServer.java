package com.github.copilot.mcp;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Lightweight MCP (Model Context Protocol) stdio server providing code intelligence and git tools.
 * Launched as a subprocess by the Copilot agent via the ACP mcpServers parameter.
 * Provides 54 tools: code navigation, file I/O, testing, code quality, run configs, git, infrastructure, and terminal.
 */
@SuppressWarnings({"SpellCheckingInspection", "java:S1192", "java:S5843", "java:S5998", "java:S5855"})
// tool schema definitions use repeated JSON property names by design; regex patterns are intentionally complex for symbol parsing
public class McpServer {

    private static final Logger LOG = Logger.getLogger(McpServer.class.getName());
    private static final Gson GSON = new GsonBuilder().create();
    private static String projectRoot = ".";

    private static final Map<String, Pattern> SYMBOL_PATTERNS = new LinkedHashMap<>();

    static {
        SYMBOL_PATTERNS.put("class", Pattern.compile(
            "^\\s*(?:public|private|protected|abstract|final|open|data|sealed|internal)?\\s*(?:class|object|enum)\\s+(\\w+)"));
        SYMBOL_PATTERNS.put("interface", Pattern.compile(
            "^\\s*(?:public|private|protected)?\\s*interface\\s+(\\w+)"));
        SYMBOL_PATTERNS.put("method", Pattern.compile(
            "^\\s*(?:public|private|protected|internal|override|abstract|static|final|suspend)?\\s*(?:fun|def)\\s+(\\w+)"));
        SYMBOL_PATTERNS.put("function", Pattern.compile(
            "^\\s*(?:public|private|protected|static|final|synchronized)?\\s*(?:\\w+(?:<[^>]+>)?\\s+)+(\\w+)\\s*\\("));
        SYMBOL_PATTERNS.put("field", Pattern.compile(
            "^\\s*(?:public|private|protected|internal)?\\s*(?:val|var|const|static|final)?\\s*(?:val|var|let|const)?\\s+(\\w+)\\s*[:=]"));
    }

    private static final Pattern OUTLINE_CLASS_PATTERN = Pattern.compile(
        "^\\s*(?:public|private|protected|abstract|final|open|data|sealed|internal)?\\s*(?:class|object|enum|interface)\\s+(\\w+)");
    private static final Pattern OUTLINE_METHOD_PATTERN = Pattern.compile(
        "^\\s*(?:public|private|protected|internal|override|abstract|static|final|suspend)?\\s*(?:fun|def)\\s+(\\w+)");
    private static final Pattern OUTLINE_JAVA_METHOD_PATTERN = Pattern.compile(
        "^\\s*(?:public|private|protected|static|final|synchronized|abstract)?\\s*(?:void|int|long|boolean|String|\\w+(?:<[^>]+>)?)\\s+(\\w+)\\s*\\(");
    private static final Pattern OUTLINE_FIELD_PATTERN = Pattern.compile(
        "^\\s*(?:public|private|protected|internal)?\\s*(?:val|var|const|static|final)?\\s*(?:val|var)?\\s+(\\w+)\\s*[:=]");

    private static final Map<String, String> FILE_TYPE_MAP = Map.ofEntries(
        Map.entry(".java", "Java"), Map.entry(".kt", "Kotlin"), Map.entry(".kts", "Kotlin"),
        Map.entry(".py", "Python"), Map.entry(".js", "JavaScript"), Map.entry(".jsx", "JavaScript"),
        Map.entry(".ts", "TypeScript"), Map.entry(".tsx", "TypeScript"), Map.entry(".go", "Go"),
        Map.entry(".rs", "Rust"), Map.entry(".xml", "XML"), Map.entry(".json", "JSON"),
        Map.entry(".md", "Markdown"), Map.entry(".gradle", "Gradle"), Map.entry(".gradle.kts", "Gradle"),
        Map.entry(".yaml", "YAML"), Map.entry(".yml", "YAML")
    );

    /**
     * Sends a JSON-RPC response to the client via stdout.
     * The MCP protocol requires communication over stdin/stdout. System.out is intentional and necessary.
     */
    @SuppressWarnings("java:S106") // System.out is intentional — MCP protocol requires stdout
    private static void sendMcpResponse(JsonObject response) {
        String json = GSON.toJson(response);
        System.out.println(json);
        System.out.flush();
    }

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
                    sendMcpResponse(response);
                }
            } catch (Exception e) {
                LOG.log(Level.SEVERE, "MCP Server error", e);
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
            default -> hasId ? respondError(msg, "Method not found: " + method) : null;
        };
    }

    private static JsonObject respond(JsonObject request, JsonObject result) {
        JsonObject response = new JsonObject();
        response.addProperty("jsonrpc", "2.0");
        response.add("id", request.get("id"));
        response.add("result", result);
        return response;
    }

    private static JsonObject respondError(JsonObject request, String message) {
        JsonObject response = new JsonObject();
        response.addProperty("jsonrpc", "2.0");
        response.add("id", request.get("id"));
        JsonObject error = new JsonObject();
        error.addProperty("code", -32601);
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
        result.addProperty("instructions", """
            You are running inside an IntelliJ IDEA plugin with access to 56 IDE tools.

            FILE OPERATIONS:
            - intellij_read_file: Read any file. Supports 'start_line'/'end_line' for partial reads. \
              ⚠️ ALWAYS use start_line/end_line when you only need a section — full file reads waste tokens. \
              Example: intellij_read_file(path, start_line=50, end_line=100) reads only lines 50-100.
            - intellij_write_file: Write/edit files. \
              ⚠️ ALWAYS use 'old_str'+'new_str' for targeted edits. NEVER send full file content unless creating a new file. \
              Full file writes waste tokens and risk overwriting concurrent changes. \
              Example: intellij_write_file(path, old_str="old code", new_str="new code") \
              ✅ WRITE RESPONSES INCLUDE HIGHLIGHTS: Every successful edit automatically returns file highlights \
              (errors/warnings). Check the "--- Highlights (auto) ---" section in the response. \
              If errors are reported, fix them immediately before editing other files.

            CODE SEARCH (AST-based, searches live editor buffers):
            - search_symbols: Find class/method/function definitions by name
            - find_references: Find all usages of a symbol at specific location
            - get_file_outline: Get structure/symbols in a file
            - get_class_outline: Get constructors/methods/fields of any class by FQN (works on library JARs and JDK)

            COMMANDS:
            - run_command: One-shot commands (gradle build, git status). Output in Run panel.
            - run_in_terminal: Interactive shells or long-running processes. Opens visible terminal tab.

            BEST PRACTICES:

            1. TRUST TOOL OUTPUTS - they return data directly. Don't try to read temp files or invent processing tools.

            2. READ FILES EFFICIENTLY - Use start_line/end_line to read only the section you need. \
            Only read full files when you need the complete picture (e.g., first time reading a file).

            3. WORKSPACE: ALL temp files, plans, notes MUST go in '.agent-work/' inside the project root. \
            NEVER write to /tmp/, home directory, or any location outside the project. \
            '.agent-work/' is git-ignored and persists across sessions.

            4. AFTER EDITING: Check the auto-highlights in the write response. \
            If the "--- Highlights (auto) ---" section shows errors, fix them IMMEDIATELY before editing other files. \
            You do NOT need to call get_highlights separately — it's included in every write response. \
            Only call get_highlights explicitly for files you haven't edited (e.g., to check existing code quality).

            5. For MULTIPLE SEQUENTIAL EDITS: \
            When making 3+ edits to the same or different files, set auto_format=false to prevent reformatting between edits. \
            Check the auto-highlights in each write response \u2192 fix errors before continuing. \
            After all edits complete, call format_code and optimize_imports ONCE.

            AUTO_FORMAT IMPORTANT NOTES: \
            a) auto_format runs SYNCHRONOUSLY \u2192 the write response reflects the formatted state. \
            b) auto_format includes optimize_imports which REMOVES imports it considers unused. \
               If you add imports in one edit and the code using them in a later edit, \
               set auto_format=false on the import edit or add imports and code in the SAME edit. \
            c) auto_format may reindent code. The write response includes context lines showing the \
               post-format state so you can verify structure is correct. \
            d) If auto_format damages the file (e.g., shifts braces, removes needed imports), \
               use the 'undo' tool to revert. Each write+format creates 2 undo steps.

            6. BEFORE EDITING UNFAMILIAR FILES: If a file has inconsistent formatting or you get old_str match failures, \
            call format_code on the file first, then re-read it. This normalizes line endings, whitespace, and indentation. \
            Formatting changes can be committed separately before starting the actual fix. \
            The write tool auto-formats as a fallback when matching fails, but calling format_code explicitly is more reliable.

            7. CHECKING FILE HIGHLIGHTS (errors/warnings): \
            a) Just call get_highlights(file) directly - NO waiting or daemon commands needed. \
            b) IntelliJ analyzes files automatically in background. get_highlights returns current state. \
            c) DO NOT use shell commands like "sleep" or "wait for daemon" - just call get_highlights. \
            d) IMPORTANT: get_highlights only works for files ALREADY OPEN in the editor (cached daemon results). \
            For project-wide analysis, ALWAYS use run_inspections instead — it runs the full inspection engine. \
            Example: get_highlights("PsiBridgeService.java") returns all problems in that file.

            8. NEVER use grep/glob for IntelliJ project files or scratch files. \
            ALWAYS use IntelliJ tools: search_symbols, find_references, search_text, get_file_outline, get_class_outline, list_project_files, intellij_read_file. \
            Use search_text for text/regex pattern matching across files (replaces grep). \
            grep/glob will FAIL on scratch files (they're stored outside the project). \
            After creating a scratch file, save its path and use intellij_read_file to access it.

            9. TESTING RULES: \
            ALWAYS use 'run_tests' to run tests. DO NOT use './gradlew test' or 'mvn test'. \
            run_tests integrates with IntelliJ's test runner and provides structured results in the IDE's Run panel. \
            Use 'list_tests' to discover tests. DO NOT use grep for finding test methods. \
            Only use run_command for tests as a last resort if run_tests fails.

            10. GrazieInspection (grammar) does NOT support apply_quickfix \u2192 use intellij_write_file instead.

            11. GIT OPERATIONS: \
            ALWAYS use the built-in git tools (git_status, git_diff, git_log, git_blame, git_commit, \
            git_stage, git_unstage, git_branch, git_stash, git_show). \
            NEVER use 'run_command' for git operations (e.g., 'git checkout', 'git reset', 'git pull'). \
            Shell git commands bypass IntelliJ's VCS layer and cause editor buffer desync \u2192 \
            the editor will show stale content that doesn't match the files on disk. \
            IntelliJ git tools properly sync editor buffers, undo history, and VFS state. \
            If you need a git operation not covered by the built-in tools, ask the user to perform it manually.

            12. UNDO: \
            Use the 'undo' tool to revert bad edits. Each write registers as an undo step, \
            and auto_format registers a separate step. So a write with auto_format=true creates 2 undo steps. \
            Undo is the fastest way to recover from a bad edit \u2192 faster than re-reading and re-editing.

            13. VERIFICATION HIERARCHY (use the lightest tool that suffices): \
            a) Check auto-highlights in write response \u2192 after EACH edit. Instant. Catches most errors. \
            b) get_compilation_errors() \u2192 after editing multiple files. Fast scan of open files for ERROR-level only. \
            c) build_project \u2192 ONLY before committing. Full incremental compilation. Only one build runs at a time. \
            NEVER use build_project as your first error check after an edit \u2192 it's 100x slower than highlights. \
            If build_project says "Build already in progress", wait and retry \u2192 do NOT spam it.

            WORKFLOW FOR "FIX ALL ISSUES" / "FIX WHOLE PROJECT" TASKS:
            \u26A0\uFE0F CRITICAL: You MUST ask the user between EACH problem category. Do NOT fix everything in one go.

            Step 1: run_inspections() to get a COMPLETE overview of all issues. \
            TRUST the run_inspections output \u2192 it IS the authoritative source of all warnings/errors. \
            Do NOT use get_highlights to re-scan files \u2192 run_inspections already found everything.
            Step 2: Group issues by PROBLEM TYPE (not by file). Examples: \
            "Unused parameters: 5 across 3 files", "Redundant casts: 3 in PsiBridge", "Grammar: 50+ issues".
            Step 3: Pick the FIRST problem category and fix ALL instances of that problem \
            (this may span multiple files if they share the same issue \u2192 that's fine).
            Step 4: format_code + optimize_imports on changed files, then build_project to verify.
            Step 5: Commit the logical unit with a descriptive message like "fix: resolve unused parameters in test mocks".
            Step 6: \u26A0\uFE0F STOP HERE AND ASK THE USER \u26A0\uFE0F
               Say: "\u2705 Fixed [problem type] ([N] issues across [M] files). Should I continue with [next category]?"
               WAIT for user response. DO NOT proceed to the next category automatically.
            Step 7: If user says yes, repeat from Step 3 with the next problem category.

            \u26A0\uFE0F RULE: After fixing EACH problem TYPE, you MUST stop and ask before continuing. \
            Even if you found 10 different problem types, fix ONE type at a time and ask after EACH one.

            Example correct workflow:
            - Find issues: 5 unused params, 3 StringBuilder, 2 XXE vulnerabilities
            - Fix unused params \u2192 commit \u2192 ASK "Continue with StringBuilder?"
            - (user says yes)
            - Fix StringBuilder \u2192 commit \u2192 ASK "Continue with XXE vulnerabilities?"
            - (user says yes)
            - Fix XXE \u2192 commit \u2192 DONE

            KEY PRINCIPLES:
            - Related changes belong in ONE commit (e.g. refactoring that touches 4 files).
            - Unrelated changes need SEPARATE commits (don't mix grammar fixes with null checks).
            - Skip grammar issues (GrazieInspection) unless user specifically requests them.
            - Skip generated files (gradlew.bat, log files).
            - If you see 200+ issues, prioritize: compilation errors > warnings > style > grammar.

            SONARQUBE FOR IDE:
            If available, use run_sonarqube_analysis to find additional issues from SonarQube/SonarLint. \
            SonarQube findings are SEPARATE from IntelliJ inspections — run both for complete coverage. \
            SonarQube rules use keys like 'java:S1135' (TODO comments), 'java:S1172' (unused params).""");
        return result;
    }

    private static JsonObject handleToolsList() {
        JsonObject result = new JsonObject();
        JsonArray tools = new JsonArray();

        tools.add(buildTool("search_symbols", "Search Symbols",
            Map.of(
                "query", Map.of("type", "string", "description", "Symbol name to search for, or '*' to list all symbols in the project"),
                "type", Map.of("type", "string", "description", "Optional: filter by type (class, method, field, property). Default: all types", "default", "")
            ),
            List.of("query")));

        tools.add(buildTool("get_file_outline", "Get File Outline",
            Map.of("path", Map.of("type", "string", "description", "Absolute or project-relative path to the file to outline")),
            List.of("path")));

        tools.add(buildTool("get_class_outline", "Get Class Outline: shows constructors, methods, fields, and inner classes of any class by fully qualified name. Works on project classes, library classes (JARs), and JDK classes. Use this instead of go_to_declaration when you need to discover a class's API.",
            Map.of(
                "class_name", Map.of("type", "string", "description", "Fully qualified class name (e.g. 'java.util.ArrayList', 'com.intellij.openapi.project.Project')"),
                "include_inherited", Map.of("type", "boolean", "description", "If true, include inherited methods and fields from superclasses. Default: false (own members only)")
            ),
            List.of("class_name")));

        tools.add(buildTool("find_references", "Find References",
            Map.of(
                "symbol", Map.of("type", "string", "description", "The exact symbol name to search for"),
                "file_pattern", Map.of("type", "string", "description", "Optional glob pattern to filter files (e.g., '*.java')", "default", "")
            ),
            List.of("symbol")));

        tools.add(buildTool("list_project_files", "List Project Files",
            Map.of(
                "directory", Map.of("type", "string", "description", "Optional subdirectory to list (relative to project root)", "default", ""),
                "pattern", Map.of("type", "string", "description", "Optional glob pattern (e.g., '*.java')", "default", "")
            ),
            List.of()));

        tools.add(buildTool("search_text", "Search text or regex patterns across project files. Reads from IntelliJ editor buffers (always up-to-date, even for unsaved changes). Use instead of grep/ripgrep.",
            Map.of(
                "query", Map.of("type", "string", "description", "Text or regex pattern to search for"),
                "file_pattern", Map.of("type", "string", "description", "Optional glob pattern to filter files (e.g., '*.kt', '*.java')", "default", ""),
                "regex", Map.of("type", "boolean", "description", "If true, treat query as regex. Default: false (literal match)"),
                "case_sensitive", Map.of("type", "boolean", "description", "Case-sensitive search. Default: true"),
                "max_results", Map.of("type", "integer", "description", "Maximum results to return (default: 100)")
            ),
            List.of("query")));

        tools.add(buildTool("list_tests", "List Tests",
            Map.of(
                "file_pattern", Map.of("type", "string", "description", "Optional glob pattern to filter test files (e.g., '*IntegrationTest*')", "default", "")
            ),
            List.of()));

        tools.add(buildTool("run_tests", "Run Tests",
            Map.of(
                "target", Map.of("type", "string", "description", "Test target: fully qualified class " +
                    "class.method (e.g., 'MyTest.testFoo'), or pattern with wildcards (e.g., '*Test')"),
                "module", Map.of("type", "string", "description", "Optional Gradle module name (e.g., 'plugin-core')", "default", "")
            ),
            List.of("target")));

        tools.add(buildTool("get_test_results", "Get Test Results",
            Map.of(
                "module", Map.of("type", "string", "description", "Optional Gradle module name to get results from", "default", "")
            ),
            List.of()));

        tools.add(buildTool("get_coverage", "Get Coverage",
            Map.of(
                "file", Map.of("type", "string", "description", "Optional file or class name to filter coverage results", "default", "")
            ),
            List.of()));

        tools.add(buildTool("get_project_info", "Get Project Info",
            Map.of(),
            List.of()));

        tools.add(buildTool("list_run_configurations", "List Run Configurations",
            Map.of(),
            List.of()));

        tools.add(buildTool("run_configuration", "Run Configuration",
            Map.of(
                "name", Map.of("type", "string", "description", "Exact name of the run configuration")
            ),
            List.of("name")));

        tools.add(buildTool("create_run_configuration", "Create Run Configuration",
            Map.of(
                "name", Map.of("type", "string", "description", "Name for the new run configuration"),
                "type", Map.of("type", "string", "description", "Configuration type: 'application', 'junit', or 'gradle'"),
                "jvm_args", Map.of("type", "string", "description", "Optional: JVM arguments (e.g., '-Xmx512m')"),
                "program_args", Map.of("type", "string", "description", "Optional: program arguments"),
                "working_dir", Map.of("type", "string", "description", "Optional: working directory path"),
                "main_class", Map.of("type", "string", "description", "Optional: main class (for Application configs)"),
                "test_class", Map.of("type", "string", "description", "Optional: test class (for JUnit configs)"),
                "module_name", Map.of("type", "string", "description", "Optional: IntelliJ module name (from project structure)")
            ),
            List.of("name", "type")));
        addEnvProperty(tools.get(tools.size() - 1).getAsJsonObject());

        tools.add(buildTool("edit_run_configuration", "Edit Run Configuration",
            Map.of(
                "name", Map.of("type", "string", "description", "Name of the run configuration to edit"),
                "jvm_args", Map.of("type", "string", "description", "Optional: new JVM arguments"),
                "program_args", Map.of("type", "string", "description", "Optional: new program arguments"),
                "working_dir", Map.of("type", "string", "description", "Optional: new working directory")
            ),
            List.of("name")));
        addEnvProperty(tools.get(tools.size() - 1).getAsJsonObject());

        tools.add(buildTool("get_problems", "Get Problems",
            Map.of(
                "path", Map.of("type", "string", "description", "Optional: file path to check. If omitted, checks all open files", "default", "")
            ),
            List.of()));

        tools.add(buildTool("optimize_imports", "Optimize Imports",
            Map.of(
                "path", Map.of("type", "string", "description", "Absolute or project-relative path to the file to optimize imports")
            ),
            List.of("path")));

        tools.add(buildTool("format_code", "Format Code",
            Map.of(
                "path", Map.of("type", "string", "description", "Absolute or project-relative path to the file to format")
            ),
            List.of("path")));

        tools.add(buildTool("get_highlights", "Get cached editor highlights for open files. Use run_inspections for comprehensive project-wide analysis",
            Map.of(
                "path", Map.of("type", "string", "description", "Optional: file path to check. If omitted, checks all open files", "default", ""),
                "limit", Map.of("type", "integer", "description", "Maximum number of highlights to return (default: 100)")
            ),
            List.of()));

        tools.add(buildTool("get_compilation_errors", "Fast compilation error check using cached daemon results. Much faster than build_project. Use after editing multiple files to quickly verify no compile errors were introduced",
            Map.of(
                "path", Map.of("type", "string", "description", "Optional: specific file to check. If omitted, checks all open source files", "default", "")
            ),
            List.of()));

        tools.add(buildTool("run_inspections", "Run full IntelliJ inspection engine on project or scope. This is the PRIMARY tool for finding all warnings, errors, and code issues",
            Map.of(
                "scope", Map.of("type", "string", "description", "Optional: file or directory path to" +
                    "Examples: 'src/main/java/com/example/MyClass.java' or 'src/main/java/com/example'"),
                "limit", Map.of("type", "integer", "description", "Page size (default: 100). Maximum problems per response"),
                "offset", Map.of("type", "integer", "description", "Number of problems to skip (default: 0). Use for pagination"),
                "min_severity", Map.of("type", "string", "description", "Minimum severity filter. Options: E" +
                    "Default: all severities included. Only set this if the user explicitly asks to filter by severity.")
            ),
            List.of()));

        tools.add(buildTool("add_to_dictionary", "Add To Dictionary",
            Map.of(
                "word", Map.of("type", "string", "description", "The word to add to the project dictionary")
            ),
            List.of("word")));

        tools.add(buildTool("suppress_inspection", "Suppress Inspection",
            Map.of(
                "path", Map.of("type", "string", "description", "Path to the file containing the code to suppress"),
                "line", Map.of("type", "integer", "description", "Line number where the inspection finding is located"),
                "inspection_id", Map.of("type", "string", "description", "The inspection ID to suppress (e.g., 'SpellCheckingInspection')")
            ),
            List.of("path", "line", "inspection_id")));

        tools.add(buildTool("run_qodana", "Run Qodana",
            Map.of(
                "limit", Map.of("type", "integer", "description", "Maximum number of problems to return (default: 100)")
            ),
            List.of()));

        tools.add(buildTool("run_sonarqube_analysis", "Run SonarQube for IDE analysis. Requires SonarQube for IDE (SonarLint) plugin to be installed. Triggers full project or changed-files analysis and returns findings",
            Map.of(
                "scope", Map.of("type", "string", "description", "Analysis scope: 'all' (full project) or 'changed' (VCS changed files only). Default: 'all'"),
                "limit", Map.of("type", "integer", "description", "Maximum number of findings to return. Default: 100"),
                "offset", Map.of("type", "integer", "description", "Pagination offset. Default: 0")
            ),
            List.of()));

        tools.add(buildTool("intellij_read_file", "Intellij Read File",
            Map.of(
                "path", Map.of("type", "string", "description", "Absolute or project-relative path to the file to read"),
                "start_line", Map.of("type", "integer", "description", "Optional: first line to read (1-based, inclusive)"),
                "end_line", Map.of("type", "integer", "description", "Optional: last line to read (1-based, inclusive). Use with start_line to read a range")
            ),
            List.of("path")));

        tools.add(buildTool("intellij_write_file", "Write or edit a file. Supports three modes: (1) full write with 'content', (2) partial edit with 'old_str'+'new_str' (must match exactly one location), (3) line-range replace with 'start_line'+'new_str' (optionally 'end_line'). Operates on the IntelliJ editor buffer. Unicode and surrogate pairs in old_str are handled via normalized matching.",
            Map.of(
                "path", Map.of("type", "string", "description", "Absolute or project-relative path to the file to write or edit"),
                "content", Map.of("type", "string", "description", "Optional: full file content to write (replaces entire file). Creates the file if it doesn't exist"),
                "old_str", Map.of("type", "string", "description", "Optional: exact string to find and replace. Must match exactly one location in the file. Used with new_str for partial edits"),
                "new_str", Map.of("type", "string", "description", "Optional: replacement string. Used with old_str for partial edit, or with start_line for line-range replace"),
                "start_line", Map.of("type", "integer", "description", "Optional: first line number (1-based) for line-range replace mode. Used with new_str (and optionally end_line)"),
                "end_line", Map.of("type", "integer", "description", "Optional: last line number (1-based, inclusive) for line-range replace. Defaults to start_line if omitted"),
                "auto_format", Map.of("type", "boolean", "description", "Auto-format and optimize imports after writing (default: true)")
            ),
            List.of("path")));

        // ---- Git tools ----

        tools.add(buildTool("git_status", "Git Status",
            Map.of(
                "verbose", Map.of("type", "boolean", "description", "If true, show full 'git status' output including untracked files")
            ),
            List.of()));

        tools.add(buildTool("git_diff", "Git Diff",
            Map.of(
                "staged", Map.of("type", "boolean", "description", "If true, show staged (cached) changes only"),
                "commit", Map.of("type", "string", "description", "Compare against this commit (e.g., 'HEAD~1', branch name)"),
                "path", Map.of("type", "string", "description", "Limit diff to this file path"),
                "stat_only", Map.of("type", "boolean", "description", "If true, show only file stats (insertions/deletions), not full diff")
            ),
            List.of()));

        tools.add(buildTool("git_log", "Git Log",
            Map.of(
                "max_count", Map.of("type", "integer", "description", "Maximum number of commits to show (default: 10)"),
                "format", Map.of("type", "string", "description", "Output format: 'oneline', 'short', 'medium', 'full'"),
                "author", Map.of("type", "string", "description", "Filter commits by author name or email"),
                "since", Map.of("type", "string", "description", "Show commits after this date (e.g., '2024-01-01')"),
                "path", Map.of("type", "string", "description", "Show only commits touching this file"),
                "branch", Map.of("type", "string", "description", "Show commits from this branch (default: current)")
            ),
            List.of()));

        tools.add(buildTool("git_blame", "Git Blame",
            Map.of(
                "path", Map.of("type", "string", "description", "File path to blame"),
                "line_start", Map.of("type", "integer", "description", "Start line number for partial blame"),
                "line_end", Map.of("type", "integer", "description", "End line number for partial blame")
            ),
            List.of("path")));

        tools.add(buildTool("git_commit", "Git Commit",
            Map.of(
                "message", Map.of("type", "string", "description", "Commit message (use conventional commit format)"),
                "amend", Map.of("type", "boolean", "description", "If true, amend the previous commit instead of creating a new one"),
                "all", Map.of("type", "boolean", "description", "If true, automatically stage all modified and deleted files")
            ),
            List.of("message")));

        tools.add(buildTool("git_stage", "Git Stage",
            Map.of(
                "path", Map.of("type", "string", "description", "Single file path to stage"),
                "paths", Map.of("type", "array", "description", "Multiple file paths to stage"),
                "all", Map.of("type", "boolean", "description", "If true, stage all changes (including untracked files)")
            ),
            List.of()));

        tools.add(buildTool("git_unstage", "Git Unstage",
            Map.of(
                "path", Map.of("type", "string", "description", "Single file path to unstage"),
                "paths", Map.of("type", "array", "description", "Multiple file paths to unstage")
            ),
            List.of()));

        tools.add(buildTool("git_branch", "Git Branch",
            Map.of(
                "action", Map.of("type", "string", "description", "Action: 'list' (default), 'create', 'switch', 'delete'"),
                "name", Map.of("type", "string", "description", "Branch name (required for create/switch/delete)"),
                "base", Map.of("type", "string", "description", "Base ref for create (default: HEAD)"),
                "all", Map.of("type", "boolean", "description", "For list: include remote branches"),
                "force", Map.of("type", "boolean", "description", "For delete: force delete unmerged branches")
            ),
            List.of()));

        tools.add(buildTool("git_stash", "Git Stash",
            Map.of(
                "action", Map.of("type", "string", "description", "Action: 'list' (default), 'push', 'pop', 'apply', 'drop'"),
                "message", Map.of("type", "string", "description", "Stash message (for push action)"),
                "index", Map.of("type", "string", "description", "Stash index (for pop/apply/drop, e.g., 'stash@{0}')"),
                "include_untracked", Map.of("type", "boolean", "description", "For push: include untracked files")
            ),
            List.of()));

        tools.add(buildTool("git_show", "Git Show",
            Map.of(
                "ref", Map.of("type", "string", "description", "Commit SHA, branch, tag, or ref (default: HEAD)"),
                "stat_only", Map.of("type", "boolean", "description", "If true, show only file stats, not full diff content"),
                "path", Map.of("type", "string", "description", "Limit output to this file path")
            ),
            List.of()));

        // ---- Infrastructure tools ----

        tools.add(buildTool("http_request", "Http Request",
            Map.of(
                "url", Map.of("type", "string", "description", "Full URL to request (e.g., http://localhost:8080/api)"),
                "method", Map.of("type", "string", "description", "HTTP method: GET (default), POST, PUT, PATCH, DELETE"),
                "body", Map.of("type", "string", "description", "Request body (for POST/PUT/PATCH)"),
                "headers", Map.of("type", "object", "description", "Request headers as key-value pairs")
            ),
            List.of("url")));

        tools.add(buildTool("run_command", "Run a shell command in the project directory. Output is paginated (default 8000 chars). For running tests use run_tests; for code search use search_symbols instead. NEVER use for git commands (use git_status, git_diff, git_commit etc. instead \u2192 shell git causes buffer desync).",
            Map.of(
                "command", Map.of("type", "string", "description", "Shell command to execute (e.g., 'gradle build', 'cat file.txt')"),
                "timeout", Map.of("type", "integer", "description", "Timeout in seconds (default: 60)"),
                "title", Map.of("type", "string", "description", "Human-readable title for the Run panel tab. ALWAYS set this to a short descriptive name"),
                "offset", Map.of("type", "integer", "description", "Character offset to start output from (default: 0). Use for pagination when output is truncated"),
                "max_chars", Map.of("type", "integer", "description", "Maximum characters to return per page (default: 8000)")
            ),
            List.of("command")));

        tools.add(buildTool("read_ide_log", "Read Ide Log",
            Map.of(
                "lines", Map.of("type", "integer", "description", "Number of recent lines to return (default: 50)"),
                "filter", Map.of("type", "string", "description", "Only return lines containing this text"),
                "level", Map.of("type", "string", "description", "Filter by log level: INFO, WARN, ERROR")
            ),
            List.of()));

        tools.add(buildTool("get_notifications", "Get Notifications",
            Map.of(),
            List.of()));

        tools.add(buildTool("read_run_output", "Read Run Output",
            Map.of(
                "tab_name", Map.of("type", "string", "description", "Name of the Run tab to read (default: most recent)"),
                "max_chars", Map.of("type", "integer", "description", "Maximum characters to return (default: 8000)")
            ),
            List.of()));

        // ---- Terminal tools ----

        tools.add(buildTool("run_in_terminal", "Run In Terminal",
            Map.of(),
            List.of()));

        tools.add(buildTool("read_terminal_output", "Read Terminal Output",
            Map.of(
                "tab_name", Map.of("type", "string", "description", "Name of the terminal tab to read from")
            ),
            List.of()));

        // Documentation tools
        tools.add(buildTool("get_documentation", "Get Documentation",
            Map.of(
                "symbol", Map.of("type", "string", "description", "Fully qualified symbol name (e.g. java.util.List)")
            ),
            List.of("symbol")));

        tools.add(buildTool("download_sources", "Download Sources",
            Map.of(
                "library", Map.of("type", "string", "description", "Optional library name filter (e.g. 'junit')")
            ),
            List.of()));

        tools.add(buildTool("create_scratch_file", "Create Scratch File",
            Map.of(
                "name", Map.of("type", "string", "description", "Scratch file name with extension (e.g., 'test.py', 'notes.md')"),
                "content", Map.of("type", "string", "description", "The content to write to the scratch file")
            ),
            List.of("name", "content")));

        tools.add(buildTool("list_scratch_files", "List Scratch Files",
            Map.of(),
            List.of()));

        tools.add(buildTool("get_indexing_status", "Get Indexing Status",
            Map.of(
                "wait", Map.of("type", "boolean", "description", "If true, blocks until indexing finishes"),
                "timeout", Map.of("type", "integer", "description", "Max seconds to wait when wait=true (default: 30)")
            ),
            List.of()));

        // Editor & navigation tools
        tools.add(buildTool("open_in_editor", "Open In Editor",
            Map.of(
                "file", Map.of("type", "string", "description", "Path to the file to open"),
                "line", Map.of("type", "integer", "description", "Optional: line number to navigate to after opening")
            ),
            List.of("file")));

        tools.add(buildTool("show_diff", "Show Diff",
            Map.of(
                "file", Map.of("type", "string", "description", "Path to the first file"),
                "file2", Map.of("type", "string", "description", "Optional: path to second file for two-file comparison"),
                "content", Map.of("type", "string", "description", "Optional: proposed new content to diff against the current file"),
                "title", Map.of("type", "string", "description", "Optional: title for the diff viewer tab")
            ),
            List.of("file")));

        // Refactoring & code modification tools
        tools.add(buildTool("apply_quickfix", "Apply Quickfix",
            Map.of(
                "file", Map.of("type", "string", "description", "Path to the file containing the problem"),
                "line", Map.of("type", "integer", "description", "Line number where the problem is located"),
                "inspection_id", Map.of("type", "string", "description", "The inspection ID from run_inspections output (e.g., 'unused')"),
                "fix_index", Map.of("type", "integer", "description", "Which fix to apply if multiple are available (default: 0)")
            ),
            List.of("file", "line", "inspection_id")));

        tools.add(buildTool("refactor", "Refactor code: supports rename, extract_method, inline, and safe_delete operations",
            Map.of(
                "operation", Map.of("type", "string", "description", "Refactoring type: 'rename', 'extract_method', 'inline', or 'safe_delete'"),
                "file", Map.of("type", "string", "description", "Path to the file containing the symbol"),
                "symbol", Map.of("type", "string", "description", "Name of the symbol to refactor (class, method, field, or variable)"),
                "line", Map.of("type", "integer", "description", "Line number to disambiguate if multiple symbols share the same name"),
                "new_name", Map.of("type", "string", "description", "New name for 'rename' operation. Required when operation is 'rename'")
            ),
            List.of("operation", "file", "symbol")));

        tools.add(buildTool("go_to_declaration", "Go To Declaration",
            Map.of(
                "file", Map.of("type", "string", "description", "Path to the file containing the symbol usage"),
                "symbol", Map.of("type", "string", "description", "Name of the symbol to look up"),
                "line", Map.of("type", "integer", "description", "Line number where the symbol appears")
            ),
            List.of("file", "symbol", "line")));

        tools.add(buildTool("get_type_hierarchy", "Get Type Hierarchy: shows supertypes (superclasses/interfaces) and subtypes (subclasses/implementations)",
            Map.of(
                "symbol", Map.of("type", "string", "description", "Fully qualified or simple class/interface name"),
                "direction", Map.of("type", "string", "description", "Direction: 'supertypes' (ancestors) or 'subtypes' (descendants). Default: both")
            ),
            List.of("symbol")));

        tools.add(buildTool("create_file", "Create File",
            Map.of(
                "path", Map.of("type", "string", "description", "Path for the new file (absolute or project-relative). File must not already exist"),
                "content", Map.of("type", "string", "description", "Content to write to the file")
            ),
            List.of("path", "content")));

        tools.add(buildTool("delete_file", "Delete File",
            Map.of(
                "path", Map.of("type", "string", "description", "Path to the file to delete (absolute or project-relative)")
            ),
            List.of("path")));

        tools.add(buildTool("undo", "Undo last edit action(s) on a file. Reverts writes, edits, and auto-format operations using IntelliJ's undo stack",
            Map.of(
                "path", Map.of("type", "string", "description", "Path to the file to undo changes on"),
                "count", Map.of("type", "integer", "description", "Number of undo steps (default: 1). Each write + auto-format counts as 2 steps")
            ),
            List.of("path")));

        tools.add(buildTool("build_project", "Build Project: triggers incremental compilation of the project or a specific module",
            Map.of(
                "module", Map.of("type", "string", "description", "Optional: build only a specific module (e.g., 'plugin-core')")
            ),
            List.of()));

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

    /**
     * Add an 'env' property with correct object schema to a tool's inputSchema.
     */
    private static void addEnvProperty(JsonObject tool) {
        JsonObject schema = tool.getAsJsonObject("inputSchema");
        JsonObject props = schema.getAsJsonObject("properties");
        JsonObject envProp = new JsonObject();
        envProp.addProperty("type", "object");
        envProp.addProperty("description", "Environment variables as key-value ");
        JsonObject additionalProps = new JsonObject();
        additionalProps.addProperty("type", "string");
        envProp.add("additionalProperties", additionalProps);
        props.add("env", envProp);
    }

    private static JsonObject handleToolsCall(JsonObject params) {
        String toolName = params.has("name") ? params.get("name").getAsString() : "";
        JsonObject arguments = params.has("arguments") ? params.getAsJsonObject("arguments") : new JsonObject();

        try {
            // All tools go through the PSI bridge — no silent fallbacks
            String bridgeResult = delegateToPsiBridge(toolName, arguments);

            String resultText;
            if (bridgeResult != null) {
                LOG.fine(() -> "MCP: tool '" + toolName + "' handled by PSI bridge");
                resultText = bridgeResult;
            } else {
                resultText = "ERROR: IntelliJ PSI bridge is unavailable. " +
                    "The tool '" + toolName + "' requires IntelliJ to be running with the Copilot Bridge plugin active. " +
                    "Please check that IntelliJ is open and the plugin is enabled.";
                LOG.warning(() -> String.format("MCP: PSI bridge unavailable for tool '%s'", toolName));
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
    private static boolean isLongRunningTool(String toolName) {
        return "run_sonarqube_analysis".equals(toolName) || "run_qodana".equals(toolName);
    }

    private static String delegateToPsiBridge(String toolName, JsonObject arguments) {
        // Long-running tools need extended timeout
        int readTimeoutMs = isLongRunningTool(toolName) ? 180_000 : 30_000;
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
            conn.setReadTimeout(readTimeoutMs);
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
            LOG.log(Level.WARNING, "PSI Bridge unavailable, using regex fallback", e);
        }
        return null;
    }

    // --- Tool implementations (regex fallback) ---

    static String searchSymbols(JsonObject args) throws IOException {
        String query = args.get("query").getAsString();
        String typeFilter = args.has("type") ? args.get("type").getAsString() : "";
        Pattern queryPattern = Pattern.compile(query, Pattern.CASE_INSENSITIVE);

        List<String> results = new ArrayList<>();
        Path root = Path.of(projectRoot);

        try (Stream<Path> files = Files.walk(root)) {
            List<Path> sourceFiles = files
                .filter(Files::isRegularFile)
                .filter(p -> isSourceFile(p.toString()))
                .filter(p -> isIncluded(root, p))
                .toList();

            for (Path file : sourceFiles) {
                searchFileForSymbols(file, root, SYMBOL_PATTERNS, typeFilter, queryPattern, results);
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

        for (int i = 0; i < lines.size(); i++) {
            classifyOutlineLine(lines.get(i), i + 1, outline);
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
                .filter(p -> isIncluded(root, p))
                .filter(p -> filePattern.isEmpty() || matchesGlob(p.getFileName().toString(), filePattern))
                .toList();

            for (Path file : sourceFiles) {
                searchFileForReferences(file, root, pattern, results);
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
                .filter(p -> isIncluded(root, p))
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

    private static void classifyOutlineLine(String line, int lineNum, List<String> outline) {
        Matcher cm = OUTLINE_CLASS_PATTERN.matcher(line);
        if (cm.find()) {
            outline.add(String.format("  %d: class %s", lineNum, cm.group(1)));
            return;
        }
        Matcher mm = OUTLINE_METHOD_PATTERN.matcher(line);
        if (mm.find()) {
            outline.add(String.format("  %d:   fun %s()", lineNum, mm.group(1)));
            return;
        }
        Matcher jm = OUTLINE_JAVA_METHOD_PATTERN.matcher(line);
        if (jm.find() && !line.contains("new ") && !line.trim().startsWith("return") && !line.trim().startsWith("if")) {
            outline.add(String.format("  %d:   method %s()", lineNum, jm.group(1)));
            return;
        }
        Matcher fm = OUTLINE_FIELD_PATTERN.matcher(line);
        if (fm.find() && !line.trim().startsWith("//") && !line.trim().startsWith("*")) {
            outline.add(String.format("  %d:   field %s", lineNum, fm.group(1)));
        }
    }

    private static void searchFileForSymbols(Path file, Path root, Map<String, Pattern> symbolPatterns,
                                             String typeFilter, Pattern queryPattern, List<String> results) {
        try {
            List<String> lines = Files.readAllLines(file);
            for (int i = 0; i < lines.size() && results.size() < 50; i++) {
                String line = lines.get(i);
                for (var entry : symbolPatterns.entrySet()) {
                    if (!typeFilter.isEmpty() && !entry.getKey().equals(typeFilter)) continue;
                    Matcher m = entry.getValue().matcher(line);
                    if (m.find() && queryPattern.matcher(m.group(1)).find()) {
                        String relPath = root.relativize(file).toString();
                        results.add(String.format("%s:%d [%s] %s", relPath, i + 1, entry.getKey(), line.trim()));
                    }
                }
            }
        } catch (Exception e) {
            // Skip unreadable files
        }
    }

    private static void searchFileForReferences(Path file, Path root, Pattern pattern, List<String> results) {
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
    }

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

    private static boolean isIncluded(Path root, Path file) {
        String rel = root.relativize(file).toString().replace('\\', '/');
        return !(rel.startsWith("build/") || rel.startsWith(".gradle/") || rel.startsWith(".git/") ||
            rel.startsWith("node_modules/") || rel.startsWith("target/") || rel.startsWith(".idea/") ||
            rel.startsWith("AppData/") || rel.startsWith(".copilot/") || rel.startsWith(".jdks/") ||
            rel.startsWith(".nuget/") || rel.startsWith(".m2/") || rel.startsWith(".npm/") ||
            rel.contains("/build/") || rel.contains("/.gradle/") || rel.contains("/node_modules/"));
    }

    private static boolean matchesGlob(String fileName, String pattern) {
        String regex = pattern.replace(".", "\\.").replace("*", ".*").replace("?", ".");
        return fileName.matches(regex);
    }

    private static String getFileType(String path) {
        String lower = path.toLowerCase();
        for (var entry : FILE_TYPE_MAP.entrySet()) {
            if (lower.endsWith(entry.getKey())) return entry.getValue();
        }
        return "Other";
    }
}
