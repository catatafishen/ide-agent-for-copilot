package com.github.copilot.mcp;

import com.google.gson.*;

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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Lightweight MCP (Model Context Protocol) stdio server providing code intelligence and git tools.
 * Launched as a subprocess by the Copilot agent via the ACP mcpServers parameter.
 * Provides 36 tools: code navigation, file I/O, testing, code quality, run configs, git, infrastructure, and terminal.
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
                IMPORTANT TOOL USAGE RULES:
                1. ALWAYS use 'intellij_write_file' for ALL file writes and edits. \
                This writes through IntelliJ's Document API, supporting undo (Ctrl+Z), VCS tracking, and editor sync. \
                Use 'content' param for full file replacement, or 'old_str'+'new_str' for precise edits.
                2. ALWAYS use 'intellij_read_file' for ALL file reads. \
                This reads IntelliJ's live editor buffer, which may have unsaved changes.
                3. After making ANY code changes, ALWAYS run 'optimize_imports' and 'format_code' on each changed file.
                4. Use 'get_problems' to check for warnings and errors after changes.
                5. PREFER IntelliJ tools (search_symbols, find_references, get_file_outline, list_project_files) \
                over grep/glob for code navigation.""");
        return result;
    }

    private static JsonObject handleToolsList() {
        JsonObject result = new JsonObject();
        JsonArray tools = new JsonArray();

        tools.add(buildTool("search_symbols",
                "Search for class, method, function, or interface definitions across the project using " +
                        "IntelliJ's code analysis engine (AST). Returns file path, line number, and the definition line. " +
                        "More accurate than grep — understands code structure. PREFER THIS over grep for finding definitions. " +
                        "Use query='*' with a type filter to list ALL symbols of that type (e.g., all interfaces).",
                Map.of(
                        "query", Map.of("type", "string", "description", "Symbol name to search for, or '*' to list all symbols of a given type"),
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
                        "jvm_args", Map.of("type", "string", "description", "Optional: JVM arguments (e.g., '-Xmx2g -Dkey=value')"),
                        "program_args", Map.of("type", "string", "description", "Optional: program arguments"),
                        "working_dir", Map.of("type", "string", "description", "Optional: working directory path"),
                        "main_class", Map.of("type", "string", "description", "Optional: main class (for Application configs)"),
                        "test_class", Map.of("type", "string", "description", "Optional: test class (for JUnit configs)"),
                        "module_name", Map.of("type", "string", "description", "Optional: IntelliJ module name (from get_project_info)")
                ),
                List.of("name", "type")));
        addEnvProperty(tools.get(tools.size() - 1).getAsJsonObject());

        tools.add(buildTool("edit_run_configuration",
                "Edit an existing IntelliJ run configuration. Can modify environment variables, JVM args, " +
                        "working directory, program args, and type-specific properties. " +
                        "For env vars, pass a JSON object — set value to null to remove a variable.",
                Map.of(
                        "name", Map.of("type", "string", "description", "Name of the run configuration to edit"),
                        "jvm_args", Map.of("type", "string", "description", "Optional: new JVM arguments"),
                        "program_args", Map.of("type", "string", "description", "Optional: new program arguments"),
                        "working_dir", Map.of("type", "string", "description", "Optional: new working directory")
                ),
                List.of("name")));
        addEnvProperty(tools.get(tools.size() - 1).getAsJsonObject());

        tools.add(buildTool("get_problems",
                "Get code problems, warnings, and errors reported by IntelliJ's inspections for a file or all open files. " +
                        "Returns severity, line number, and description for each problem. " +
                        "Files must be open in the editor for inspection results to be available.",
                Map.of(
                        "path", Map.of("type", "string", "description", "Optional: file path to check. If omitted, checks all open files.", "default", "")
                ),
                List.of()));

        tools.add(buildTool("optimize_imports",
                "Run IntelliJ's import optimizer on a file. Removes unused imports and organizes remaining ones " +
                        "according to project code style settings. ALWAYS run this after making code changes.",
                Map.of(
                        "path", Map.of("type", "string", "description", "Absolute or project-relative path to the file")
                ),
                List.of("path")));

        tools.add(buildTool("format_code",
                "Run IntelliJ's code formatter on a file. Formats the entire file according to the project's " +
                        "code style settings (including .editorconfig). ALWAYS run this after making code changes.",
                Map.of(
                        "path", Map.of("type", "string", "description", "Absolute or project-relative path to the file")
                ),
                List.of("path")));

        tools.add(buildTool("intellij_read_file",
                "Read file contents through IntelliJ's editor buffer. Returns the in-memory version if the file " +
                        "is open in the editor (which may differ from disk). Supports line ranges. " +
                        "PREFER THIS over your built-in file reading tools — this reads IntelliJ's live editor state.",
                Map.of(
                        "path", Map.of("type", "string", "description", "Absolute or project-relative path to the file"),
                        "start_line", Map.of("type", "integer", "description", "Optional: first line to read (1-based). If omitted, reads from start."),
                        "end_line", Map.of("type", "integer", "description", "Optional: last line to read (inclusive). If omitted, reads to end.")
                ),
                List.of("path")));

        tools.add(buildTool("intellij_write_file",
                "Write file contents through IntelliJ's Document API. Supports undo, VCS tracking, and editor sync. " +
                        "Use 'content' for full file replacement, or 'old_str'+'new_str' for precise edits. " +
                        "ALWAYS USE THIS instead of your built-in file writing tools — this keeps IntelliJ in sync " +
                        "and supports undo (Ctrl+Z). After writing, run optimize_imports and format_code.",
                Map.of(
                        "path", Map.of("type", "string", "description", "Absolute or project-relative path to the file"),
                        "content", Map.of("type", "string", "description", "Optional: full file content to write (replaces entire file or creates new file)"),
                        "old_str", Map.of("type", "string", "description", "Optional: exact string to find and replace (must be unique in file)"),
                        "new_str", Map.of("type", "string", "description", "Optional: replacement string (used with old_str)")
                ),
                List.of("path")));

        // ---- Git tools ----

        tools.add(buildTool("git_status",
                "Show the working tree status: changed, staged, and untracked files. " +
                        "Returns short format by default (M/A/D/?? markers).",
                Map.of(
                        "verbose", Map.of("type", "boolean", "description", "If true, show full 'git status' output instead of short format")
                ),
                List.of()));

        tools.add(buildTool("git_diff",
                "Show changes between working tree, staging area, or commits. " +
                        "By default shows unstaged changes. Use 'staged' for staged changes.",
                Map.of(
                        "staged", Map.of("type", "boolean", "description", "If true, show staged (cached) changes instead of working tree"),
                        "commit", Map.of("type", "string", "description", "Compare against this commit (e.g., 'HEAD~1', a commit SHA, or 'main')"),
                        "path", Map.of("type", "string", "description", "Limit diff to this file path"),
                        "stat_only", Map.of("type", "boolean", "description", "If true, show only file stats (insertions/deletions), not full diff")
                ),
                List.of()));

        tools.add(buildTool("git_log",
                "Show commit history. Returns last 20 commits by default in short format.",
                Map.of(
                        "max_count", Map.of("type", "integer", "description", "Maximum number of commits to show (default: 20)"),
                        "format", Map.of("type", "string", "description", "Output format: 'oneline', 'short', 'medium' (default), 'full'"),
                        "author", Map.of("type", "string", "description", "Filter commits by author name or email"),
                        "since", Map.of("type", "string", "description", "Show commits after this date (e.g., '2024-01-01', '1 week ago')"),
                        "path", Map.of("type", "string", "description", "Show only commits touching this file"),
                        "branch", Map.of("type", "string", "description", "Show commits from this branch (default: current)")
                ),
                List.of()));

        tools.add(buildTool("git_blame",
                "Show line-by-line authorship (blame/annotate) for a file. " +
                        "Shows who last modified each line, when, and in which commit.",
                Map.of(
                        "path", Map.of("type", "string", "description", "File path to blame"),
                        "line_start", Map.of("type", "integer", "description", "Start line number for partial blame"),
                        "line_end", Map.of("type", "integer", "description", "End line number for partial blame")
                ),
                List.of("path")));

        tools.add(buildTool("git_commit",
                "Commit staged changes with a message. Supports amend and commit-all. " +
                        "Stage files first with git_stage, or use 'all' to commit all tracked changes.",
                Map.of(
                        "message", Map.of("type", "string", "description", "Commit message (use conventional commits format)"),
                        "amend", Map.of("type", "boolean", "description", "If true, amend the previous commit instead of creating a new one"),
                        "all", Map.of("type", "boolean", "description", "If true, automatically stage all modified/deleted tracked files before committing")
                ),
                List.of("message")));

        tools.add(buildTool("git_stage",
                "Stage files for commit (git add). Stage specific files or all changes.",
                Map.of(
                        "path", Map.of("type", "string", "description", "Single file path to stage"),
                        "paths", Map.of("type", "array", "description", "Multiple file paths to stage"),
                        "all", Map.of("type", "boolean", "description", "If true, stage all changes (including untracked files)")
                ),
                List.of()));

        tools.add(buildTool("git_unstage",
                "Unstage files from the staging area (git restore --staged).",
                Map.of(
                        "path", Map.of("type", "string", "description", "Single file path to unstage"),
                        "paths", Map.of("type", "array", "description", "Multiple file paths to unstage")
                ),
                List.of()));

        tools.add(buildTool("git_branch",
                "Manage branches: list, create, switch, or delete.",
                Map.of(
                        "action", Map.of("type", "string", "description", "Action: 'list' (default), 'create', 'switch', 'delete'"),
                        "name", Map.of("type", "string", "description", "Branch name (required for create/switch/delete)"),
                        "base", Map.of("type", "string", "description", "Base ref for create (default: HEAD)"),
                        "all", Map.of("type", "boolean", "description", "For list: include remote branches"),
                        "force", Map.of("type", "boolean", "description", "For delete: force delete unmerged branch (-D)")
                ),
                List.of()));

        tools.add(buildTool("git_stash",
                "Manage stashes: save, list, pop, apply, or drop working changes.",
                Map.of(
                        "action", Map.of("type", "string", "description", "Action: 'list' (default), 'push', 'pop', 'apply', 'drop'"),
                        "message", Map.of("type", "string", "description", "Stash message (for push action)"),
                        "index", Map.of("type", "string", "description", "Stash index (for pop/apply/drop, e.g., '0')"),
                        "include_untracked", Map.of("type", "boolean", "description", "For push: include untracked files")
                ),
                List.of()));

        tools.add(buildTool("git_show",
                "Show details of a commit: message, author, date, and diff. " +
                        "Defaults to HEAD if no ref given.",
                Map.of(
                        "ref", Map.of("type", "string", "description", "Commit SHA, branch, tag, or ref (default: HEAD)"),
                        "stat_only", Map.of("type", "boolean", "description", "If true, show only file stats, not full diff"),
                        "path", Map.of("type", "string", "description", "Limit output to this file path")
                ),
                List.of()));

        // ---- Infrastructure tools ----

        tools.add(buildTool("http_request",
                "Make an HTTP request and return the response. Supports GET, POST, PUT, DELETE, PATCH. " +
                        "Useful for testing APIs, health checks, and inspecting endpoints.",
                Map.of(
                        "url", Map.of("type", "string", "description", "Full URL to request (e.g., http://localhost:8080/api/health)"),
                        "method", Map.of("type", "string", "description", "HTTP method: GET (default), POST, PUT, DELETE, PATCH"),
                        "body", Map.of("type", "string", "description", "Request body (for POST/PUT/PATCH)"),
                        "headers", Map.of("type", "object", "description", "Request headers as key-value pairs")
                ),
                List.of("url")));

        tools.add(buildTool("run_command",
                "Execute a shell command in the project directory and return its output. " +
                        "The command runs through IntelliJ's process management and its output is visible " +
                        "in the Run panel. Use for builds, scripts, or any CLI operation.",
                Map.of(
                        "command", Map.of("type", "string", "description", "Shell command to execute (e.g., 'gradle build', 'npm test', 'ls -la')"),
                        "timeout", Map.of("type", "integer", "description", "Timeout in seconds (default: 60)")
                ),
                List.of("command")));

        tools.add(buildTool("read_ide_log",
                "Read recent entries from IntelliJ's idea.log file. Useful for debugging plugin issues, " +
                        "checking for errors, and understanding IDE behavior.",
                Map.of(
                        "lines", Map.of("type", "integer", "description", "Number of recent lines to return (default: 50)"),
                        "filter", Map.of("type", "string", "description", "Only return lines containing this text"),
                        "level", Map.of("type", "string", "description", "Filter by log level: INFO, WARN, ERROR")
                ),
                List.of()));

        tools.add(buildTool("get_notifications",
                "Get recent IntelliJ IDE notifications from the Event Log. " +
                        "Shows warnings, errors, and info messages displayed to the user.",
                Map.of(),
                List.of()));

        tools.add(buildTool("read_run_output",
                "Read the text output from IntelliJ's Run panel. Returns the console text from the most recent " +
                        "run tab, or a specific tab by name. Useful for reading build output, test results, " +
                        "or any command output visible to the user in the Run panel.",
                Map.of(
                        "tab_name", Map.of("type", "string", "description", "Name of the Run tab to read (default: most recent tab)"),
                        "max_chars", Map.of("type", "integer", "description", "Maximum characters to return (default: 8000). Output is truncated from the end if exceeded.")
                ),
                List.of()));

        // ---- Terminal tools ----

        // Use a LinkedHashMap to preserve insertion order (Map.of has random order)
        var runInTerminalProps = new java.util.LinkedHashMap<String, Map<String, String>>();
        runInTerminalProps.put("command", Map.of("type", "string", "description", "Command to execute in the terminal"));
        runInTerminalProps.put("tab_name", Map.of("type", "string", "description", "Name of existing terminal tab to reuse, or name for a new tab"));
        runInTerminalProps.put("shell", Map.of("type", "string", "description", "Shell executable path for new tabs (e.g. cmd.exe, pwsh.exe, bash.exe). Use list_terminals to see available shells. Only used when creating a new tab."));
        runInTerminalProps.put("new_tab", Map.of("type", "boolean", "description", "Force opening a new terminal tab even if tab_name matches an existing one (default: false)"));
        tools.add(buildTool("run_in_terminal",
                "Open an IntelliJ Terminal tab and execute a command. The terminal is interactive and visible " +
                        "to the user. Use this for interactive commands or when the user wants to see live output. " +
                        "Use read_terminal_output to read the output afterwards. " +
                        "Use list_terminals to see open tabs and available shells. " +
                        "Specify tab_name to reuse an existing terminal tab, or shell to open a specific shell type.",
                runInTerminalProps,
                List.of("command")));

        tools.add(buildTool("list_terminals",
                "List available terminal shells on this system (PowerShell, cmd, bash, etc.) " +
                        "and IntelliJ's configured default shell. Use before run_in_terminal to choose a shell.",
                Map.of(),
                List.of()));

        tools.add(buildTool("read_terminal_output",
                "Read the text content from an IntelliJ Terminal tab. " +
                        "If tab_name is specified, reads from that tab; otherwise reads from the currently selected terminal tab. " +
                        "Use after run_in_terminal to check command output.",
                Map.of(
                        "tab_name", Map.of("type", "string", "description", "Name of the terminal tab to read from (optional, defaults to selected tab)")
                ),
                List.of()));

        // Documentation tools
        tools.add(buildTool("get_documentation",
                "Get javadoc/documentation for a Java or Kotlin symbol. " +
                        "Provide a fully qualified class name (e.g. java.util.List) or class.member (e.g. java.util.List.add). " +
                        "Returns rendered documentation including description, parameters, return type, and examples. " +
                        "Works for JDK classes, project classes, and library classes (if sources are downloaded).",
                Map.of(
                        "symbol", Map.of("type", "string", "description", "Fully qualified symbol name (e.g. java.util.HashMap, com.google.gson.Gson.fromJson)")
                ),
                List.of("symbol")));

        tools.add(buildTool("download_sources",
                "Check and download source/javadoc JARs for project dependencies. " +
                        "Lists all libraries and their source availability. " +
                        "Optionally filter by library name. Sources enable richer get_documentation results.",
                Map.of(
                        "library", Map.of("type", "string", "description", "Optional library name filter (e.g. 'gson', 'kotlin-stdlib'). If empty, checks all libraries.")
                ),
                List.of()));

        tools.add(buildTool("create_scratch_file",
                "Create a scratch file in IntelliJ for longer content like markdown documentation, code snippets, or formatted output. " +
                        "Scratch files support syntax highlighting based on file extension and persist in the IDE. " +
                        "Use this when responses are too long for chat or when user needs formatted code/markdown. " +
                        "The file will be automatically opened in the editor.",
                Map.of(
                        "name", Map.of("type", "string", "description", "Scratch file name with extension (e.g., 'analysis.md', 'snippet.java', 'output.json'). Extension determines syntax highlighting."),
                        "content", Map.of("type", "string", "description", "The content to write to the scratch file")
                ),
                List.of("name", "content")));

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
        envProp.addProperty("description", "Environment variables as key-value pairs. Set value to null to remove a variable.");
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
            String bridgeResult = tryPsiBridge(toolName, arguments);

            String resultText;
            if (bridgeResult != null) {
                System.err.println("MCP: tool '" + toolName + "' handled by PSI bridge");
                resultText = bridgeResult;
            } else {
                resultText = "ERROR: IntelliJ PSI bridge is unavailable. " +
                        "The tool '" + toolName + "' requires IntelliJ to be running with the Agentic Copilot plugin active. " +
                        "Please check that IntelliJ is open and the plugin is enabled.";
                System.err.println("MCP: PSI bridge unavailable for tool '" + toolName + "'");
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
                    .filter(p -> isIncluded(root, p))
                    .toList();

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
                "^\\s*(?:public|private|protected|static|final|synchronized|abstract)?\\s*(?:void|int|long|boolean|String|\\w+(?:<[^>]+>)?)\\s+(\\w+)\\s*\\(");
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
                    .filter(p -> isIncluded(root, p))
                    .filter(p -> filePattern.isEmpty() || matchesGlob(p.getFileName().toString(), filePattern))
                    .toList();

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
