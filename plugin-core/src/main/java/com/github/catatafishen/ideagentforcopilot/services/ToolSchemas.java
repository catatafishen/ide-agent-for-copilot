package com.github.catatafishen.ideagentforcopilot.services;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Single source of truth for MCP tool input schemas (JSON Schema objects).
 * Ported from the duplicated schema definitions in the mcp-server module.
 * Used by {@link com.github.catatafishen.idemcpserver.McpProtocolHandler} to serve
 * complete tool schemas to any MCP client.
 */
public final class ToolSchemas {

    private static final Map<String, JsonObject> SCHEMAS;

    // JSON Schema type constants
    private static final String TYPE_STRING = "string";
    private static final String TYPE_BOOLEAN = "boolean";
    private static final String TYPE_INTEGER = "integer";
    private static final String TYPE_OBJECT = "object";
    private static final String TYPE_ARRAY = "array";

    // JSON Schema property key constants
    private static final String KEY_TYPE = "type";
    private static final String KEY_PROPERTIES = "properties";
    private static final String KEY_REQUIRED = "required";
    private static final String KEY_DESCRIPTION = "description";
    private static final String KEY_DEFAULT = "default";

    // Frequently used parameter name constants
    private static final String PARAM_PATH = "path";
    private static final String PARAM_FILE = "file";
    private static final String PARAM_SYMBOL = "symbol";
    private static final String PARAM_LINE = "line";
    private static final String PARAM_NAME = "name";
    private static final String PARAM_CONTENT = "content";
    private static final String PARAM_ACTION = "action";
    private static final String PARAM_BRANCH = "branch";
    private static final String PARAM_COMMIT = "commit";
    private static final String PARAM_REMOTE = "remote";
    private static final String PARAM_MESSAGE = "message";
    private static final String PARAM_QUERY = "query";
    private static final String PARAM_MODULE = "module";
    private static final String PARAM_SCOPE = "scope";
    private static final String PARAM_LIMIT = "limit";
    private static final String PARAM_OFFSET = "offset";
    private static final String PARAM_TAB_NAME = "tab_name";
    private static final String PARAM_MAX_CHARS = "max_chars";
    private static final String PARAM_COMMAND = "command";
    private static final String PARAM_PATHS = "paths";
    private static final String PARAM_NEW_NAME = "new_name";
    private static final String PARAM_ABORT = "abort";
    private static final String PARAM_FILE_PATTERN = "file_pattern";
    private static final String PARAM_INSPECTION_ID = "inspection_id";
    private static final String DESC_FILE_WITH_SYMBOL = "Absolute or project-relative path to the file containing the symbol";
    private static final String DESC_LINE_HINT = "Optional: line number hint to disambiguate if multiple symbols share the same name";
    private static final String DESC_REMOTE_ORIGIN = "Remote name (default: origin)";

    private ToolSchemas() {
    }

    /**
     * Get the input schema for a tool by name.
     * Returns an empty object schema if the tool has no defined schema.
     */
    public static JsonObject getInputSchema(String toolName) {
        JsonObject schema = SCHEMAS.get(toolName);
        return schema != null ? schema.deepCopy() : emptySchema();
    }

    private static JsonObject emptySchema() {
        JsonObject s = new JsonObject();
        s.addProperty(KEY_TYPE, TYPE_OBJECT);
        s.add(KEY_PROPERTIES, new JsonObject());
        s.add(KEY_REQUIRED, new JsonArray());
        return s;
    }

    private static JsonObject schema(Object[][] params, String... required) {
        JsonObject s = new JsonObject();
        s.addProperty(KEY_TYPE, TYPE_OBJECT);
        JsonObject props = new JsonObject();
        for (Object[] p : params) {
            JsonObject prop = new JsonObject();
            prop.addProperty(KEY_TYPE, (String) p[1]);
            prop.addProperty(KEY_DESCRIPTION, (String) p[2]);
            if (p.length > 3 && p[3] != null) {
                prop.addProperty(KEY_DEFAULT, (String) p[3]);
            }
            props.add((String) p[0], prop);
        }
        s.add(KEY_PROPERTIES, props);
        JsonArray req = new JsonArray();
        for (String r : required) req.add(r);
        s.add(KEY_REQUIRED, req);
        return s;
    }

    private static void addArrayItems(JsonObject schema, String propName) {
        JsonObject prop = schema.getAsJsonObject(KEY_PROPERTIES).getAsJsonObject(propName);
        JsonObject items = new JsonObject();
        items.addProperty(KEY_TYPE, TYPE_STRING);
        prop.add("items", items);
    }

    private static void addDictProperty(JsonObject schema, String name, String description) {
        JsonObject prop = new JsonObject();
        prop.addProperty(KEY_TYPE, "object");
        prop.addProperty(KEY_DESCRIPTION, description);
        prop.add("properties", new JsonObject());
        JsonObject additionalProps = new JsonObject();
        additionalProps.addProperty(KEY_TYPE, TYPE_STRING);
        prop.add("additionalProperties", additionalProps);
        schema.getAsJsonObject(KEY_PROPERTIES).add(name, prop);
    }

    static {
        Map<String, JsonObject> m = new LinkedHashMap<>();

        // ── Search & navigation ──────────────────────────────────────────────

        m.put("search_symbols", schema(new Object[][]{
            {PARAM_QUERY, TYPE_STRING, "Symbol name to search for, or '*' to list all symbols in the project"},
            {"type", TYPE_STRING, "Optional: filter by type (class, method, field, property). Default: all types", ""}
        }, PARAM_QUERY));

        m.put("get_file_outline", schema(new Object[][]{
            {PARAM_PATH, TYPE_STRING, "Absolute or project-relative path to the file to outline"}
        }, PARAM_PATH));

        m.put("get_class_outline", schema(new Object[][]{
            {"class_name", TYPE_STRING, "Fully qualified class name (e.g. 'java.util.ArrayList', 'com.intellij.openapi.project.Project')"},
            {"include_inherited", TYPE_BOOLEAN, "If true, include inherited methods and fields from superclasses. Default: false (own members only)"}
        }, "class_name"));

        m.put("find_references", schema(new Object[][]{
            {PARAM_SYMBOL, TYPE_STRING, "The exact symbol name to search for"},
            {PARAM_FILE_PATTERN, TYPE_STRING, "Optional glob pattern to filter files (e.g., '*.java')", ""}
        }, PARAM_SYMBOL));

        m.put("list_project_files", schema(new Object[][]{
            {"directory", TYPE_STRING, "Optional subdirectory to list (relative to project root)", ""},
            {"pattern", TYPE_STRING, "Optional glob pattern (e.g., '*.java')", ""}
        }));

        m.put("search_text", schema(new Object[][]{
            {PARAM_QUERY, TYPE_STRING, "Text or regex pattern to search for"},
            {PARAM_FILE_PATTERN, TYPE_STRING, "Optional glob pattern to filter files (e.g., '*.kt', '*.java')", ""},
            {"regex", TYPE_BOOLEAN, "If true, treat query as regex. Default: false (literal match)"},
            {"case_sensitive", TYPE_BOOLEAN, "Case-sensitive search. Default: true"},
            {"max_results", TYPE_INTEGER, "Maximum results to return (default: 100)"}
        }, PARAM_QUERY));

        m.put("list_tests", schema(new Object[][]{
            {PARAM_FILE_PATTERN, TYPE_STRING, "Optional glob pattern to filter test files (e.g., '*IntegrationTest*')", ""}
        }));

        m.put("run_tests", schema(new Object[][]{
            {"target", TYPE_STRING, "Test target: fully qualified class class.method (e.g., 'MyTest.testFoo'), or pattern with wildcards (e.g., '*Test')"},
            {PARAM_MODULE, TYPE_STRING, "Optional Gradle module name (e.g., 'plugin-core')", ""}
        }, "target"));

        m.put("get_coverage", schema(new Object[][]{
            {PARAM_FILE, TYPE_STRING, "Optional file or class name to filter coverage results", ""}
        }));

        m.put("get_project_info", schema(new Object[][]{}));

        m.put("list_run_configurations", schema(new Object[][]{}));

        m.put("run_configuration", schema(new Object[][]{
            {PARAM_NAME, TYPE_STRING, "Exact name of the run configuration"}
        }, PARAM_NAME));

        JsonObject createRunConfig = schema(new Object[][]{
            {PARAM_NAME, TYPE_STRING, "Name for the new run configuration"},
            {"type", TYPE_STRING, "Configuration type: 'application', 'junit', or 'gradle'"},
            {"jvm_args", TYPE_STRING, "Optional: JVM arguments (e.g., '-Xmx512m')"},
            {"program_args", TYPE_STRING, "Optional: program arguments"},
            {"working_dir", TYPE_STRING, "Optional: working directory path"},
            {"main_class", TYPE_STRING, "Optional: main class (for Application configs)"},
            {"test_class", TYPE_STRING, "Optional: test class (for JUnit configs)"},
            {"module_name", TYPE_STRING, "Optional: IntelliJ module name (from project structure)"},
            {"tasks", TYPE_STRING, "Optional: Gradle task names, space-separated (e.g., ':plugin-core:buildPlugin')"},
            {"script_parameters", TYPE_STRING, "Optional: Gradle script parameters (e.g., '--info')"},
            {"shared", TYPE_BOOLEAN, "Store as shared project file (default: true). If false, stored in workspace only"}
        }, PARAM_NAME, "type");
        addDictProperty(createRunConfig, "env", "Environment variables as key-value pairs");
        m.put("create_run_configuration", createRunConfig);

        JsonObject editRunConfig = schema(new Object[][]{
            {PARAM_NAME, TYPE_STRING, "Name of the run configuration to edit"},
            {"jvm_args", TYPE_STRING, "Optional: new JVM arguments"},
            {"program_args", TYPE_STRING, "Optional: new program arguments"},
            {"working_dir", TYPE_STRING, "Optional: new working directory"},
            {"tasks", TYPE_STRING, "Optional: Gradle task names, space-separated (e.g., ':plugin-core:buildPlugin')"},
            {"script_parameters", TYPE_STRING, "Optional: Gradle script parameters (e.g., '--info')"},
            {"shared", TYPE_BOOLEAN, "Optional: toggle shared (project file) vs workspace-local storage"}
        }, PARAM_NAME);
        addDictProperty(editRunConfig, "env", "Environment variables as key-value pairs");
        m.put("edit_run_configuration", editRunConfig);

        m.put("delete_run_configuration", schema(new Object[][]{
            {PARAM_NAME, TYPE_STRING, "Exact name of the run configuration to delete"}
        }, PARAM_NAME));

        // ── Code quality ─────────────────────────────────────────────────────

        m.put("get_problems", schema(new Object[][]{
            {PARAM_PATH, TYPE_STRING, "Optional: file path to check. If omitted, checks all open files", ""}
        }));

        m.put("optimize_imports", schema(new Object[][]{
            {PARAM_PATH, TYPE_STRING, "Absolute or project-relative path to the file to optimize imports"}
        }, PARAM_PATH));

        m.put("format_code", schema(new Object[][]{
            {PARAM_PATH, TYPE_STRING, "Absolute or project-relative path to the file to format"}
        }, PARAM_PATH));

        m.put("get_highlights", schema(new Object[][]{
            {PARAM_PATH, TYPE_STRING, "Optional: file path to check. If omitted, checks all open files", ""},
            {PARAM_LIMIT, TYPE_INTEGER, "Maximum number of highlights to return (default: 100)"}
        }));

        m.put("get_compilation_errors", schema(new Object[][]{
            {PARAM_PATH, TYPE_STRING, "Optional: specific file to check. If omitted, checks all open source files", ""}
        }));

        m.put("run_inspections", schema(new Object[][]{
            {PARAM_SCOPE, TYPE_STRING, "Optional: file or directory path to inspect. Examples: 'src/main/java/com/example/MyClass.java' or 'src/main/java/com/example'"},
            {PARAM_LIMIT, TYPE_INTEGER, "Page size (default: 100). Maximum problems per response"},
            {PARAM_OFFSET, TYPE_INTEGER, "Number of problems to skip (default: 0). Use for pagination"},
            {"min_severity", TYPE_STRING, "Minimum severity filter. Options: ERROR, WARNING, WEAK_WARNING, INFO. Default: all severities included. Only set this if the user explicitly asks to filter by severity."}
        }));

        m.put("add_to_dictionary", schema(new Object[][]{
            {"word", TYPE_STRING, "The word to add to the project dictionary"}
        }, "word"));

        m.put("suppress_inspection", schema(new Object[][]{
            {PARAM_PATH, TYPE_STRING, "Path to the file containing the code to suppress"},
            {PARAM_LINE, TYPE_INTEGER, "Line number where the inspection finding is located"},
            {PARAM_INSPECTION_ID, TYPE_STRING, "The inspection ID to suppress (e.g., 'SpellCheckingInspection')"}
        }, PARAM_PATH, PARAM_LINE, PARAM_INSPECTION_ID));

        m.put("run_qodana", schema(new Object[][]{
            {PARAM_LIMIT, TYPE_INTEGER, "Maximum number of problems to return (default: 100)"}
        }));

        m.put("run_sonarqube_analysis", schema(new Object[][]{
            {PARAM_SCOPE, TYPE_STRING, "Analysis scope: 'all' (full project) or 'changed' (VCS changed files only). Default: 'all'"},
            {PARAM_LIMIT, TYPE_INTEGER, "Maximum number of findings to return. Default: 100"},
            {PARAM_OFFSET, TYPE_INTEGER, "Pagination offset. Default: 0"}
        }));

        // ── File operations ──────────────────────────────────────────────────

        m.put("intellij_read_file", schema(new Object[][]{
            {PARAM_PATH, TYPE_STRING, "Absolute or project-relative path to the file to read"},
            {"start_line", TYPE_INTEGER, "Optional: first line to read (1-based, inclusive)"},
            {"end_line", TYPE_INTEGER, "Optional: last line to read (1-based, inclusive). Use with start_line to read a range"}
        }, PARAM_PATH));

        m.put("intellij_write_file", schema(new Object[][]{
            {PARAM_PATH, TYPE_STRING, "Absolute or project-relative path to the file to write or create"},
            {PARAM_CONTENT, TYPE_STRING, "Full file content to write (replaces entire file). Creates the file if it doesn't exist"},
            {"auto_format_and_optimize_imports", TYPE_BOOLEAN, "Auto-format code AND optimize imports after writing (default: true). \u26a0\ufe0f Import optimization REMOVES imports it considers unused \u2014 if you add imports in one edit and reference them in a later edit, set this to false or combine both changes in one edit"}
        }, PARAM_PATH, PARAM_CONTENT));

        m.put("edit_text", schema(new Object[][]{
            {PARAM_PATH, TYPE_STRING, "Absolute or project-relative path to the file to edit"},
            {"old_str", TYPE_STRING, "Exact string to find and replace. Must match exactly one location in the file"},
            {"new_str", TYPE_STRING, "Replacement string"},
            {"auto_format_and_optimize_imports", TYPE_BOOLEAN, "Auto-format code AND optimize imports after editing (default: true). \u26a0\ufe0f Import optimization REMOVES imports it considers unused \u2014 if you add imports in one edit and reference them in a later edit, set this to false or combine both changes in one edit"}
        }, PARAM_PATH, "old_str", "new_str"));

        m.put("create_file", schema(new Object[][]{
            {PARAM_PATH, TYPE_STRING, "Path for the new file (absolute or project-relative). File must not already exist"},
            {PARAM_CONTENT, TYPE_STRING, "Content to write to the file"}
        }, PARAM_PATH, PARAM_CONTENT));

        m.put("delete_file", schema(new Object[][]{
            {PARAM_PATH, TYPE_STRING, "Path to the file to delete (absolute or project-relative)"}
        }, PARAM_PATH));

        m.put("rename_file", schema(new Object[][]{
            {PARAM_PATH, TYPE_STRING, "Path to the file to rename (absolute or project-relative)"},
            {PARAM_NEW_NAME, TYPE_STRING, "New file name (just the filename, not a full path)"}
        }, PARAM_PATH, PARAM_NEW_NAME));

        m.put("move_file", schema(new Object[][]{
            {PARAM_PATH, TYPE_STRING, "Path to the file to move (absolute or project-relative)"},
            {"destination", TYPE_STRING, "Destination directory path (absolute or project-relative)"}
        }, PARAM_PATH, "destination"));

        m.put("reload_from_disk", schema(new Object[][]{
            {PARAM_PATH, TYPE_STRING, "File or directory path to reload (absolute or project-relative). Omit to reload the entire project root."}
        }));

        m.put("open_in_editor", schema(new Object[][]{
            {PARAM_FILE, TYPE_STRING, "Path to the file to open"},
            {PARAM_LINE, TYPE_INTEGER, "Optional: line number to navigate to after opening"},
            {"focus", TYPE_BOOLEAN, "Optional: if true (default), the editor gets focus. Set to false to open without stealing focus"}
        }, PARAM_FILE));

        m.put("show_diff", schema(new Object[][]{
            {PARAM_FILE, TYPE_STRING, "Path to the first file"},
            {"file2", TYPE_STRING, "Optional: path to second file for two-file comparison"},
            {PARAM_CONTENT, TYPE_STRING, "Optional: proposed new content to diff against the current file"},
            {"title", TYPE_STRING, "Optional: title for the diff viewer tab"}
        }, PARAM_FILE));

        // ── Git ──────────────────────────────────────────────────────────────

        m.put("git_status", schema(new Object[][]{
            {"verbose", TYPE_BOOLEAN, "If true, show full 'git status' output including untracked files"}
        }));

        m.put("git_diff", schema(new Object[][]{
            {"staged", TYPE_BOOLEAN, "If true, show staged (cached) changes only"},
            {PARAM_COMMIT, TYPE_STRING, "Compare against this commit (e.g., 'HEAD~1', branch name)"},
            {PARAM_PATH, TYPE_STRING, "Limit diff to this file path"},
            {"stat_only", TYPE_BOOLEAN, "If true, show only file stats (insertions/deletions), not full diff"}
        }));

        m.put("git_log", schema(new Object[][]{
            {"max_count", TYPE_INTEGER, "Maximum number of commits to show (default: 10)"},
            {"format", TYPE_STRING, "Output format: 'oneline', 'short', 'medium', 'full'"},
            {"author", TYPE_STRING, "Filter commits by author name or email"},
            {"since", TYPE_STRING, "Show commits after this date (e.g., '2024-01-01')"},
            {PARAM_PATH, TYPE_STRING, "Show only commits touching this file"},
            {PARAM_BRANCH, TYPE_STRING, "Show commits from this branch (default: current)"}
        }));

        m.put("git_blame", schema(new Object[][]{
            {PARAM_PATH, TYPE_STRING, "File path to blame"},
            {"line_start", TYPE_INTEGER, "Start line number for partial blame"},
            {"line_end", TYPE_INTEGER, "End line number for partial blame"}
        }, PARAM_PATH));

        m.put("git_commit", schema(new Object[][]{
            {PARAM_MESSAGE, TYPE_STRING, "Commit message (use conventional commit format)"},
            {"amend", TYPE_BOOLEAN, "If true, amend the previous commit instead of creating a new one"},
            {"all", TYPE_BOOLEAN, "If true, automatically stage all modified and deleted files"}
        }, PARAM_MESSAGE));

        JsonObject gitStage = schema(new Object[][]{
            {PARAM_PATH, TYPE_STRING, "Single file path to stage"},
            {PARAM_PATHS, TYPE_ARRAY, "Multiple file paths to stage"},
            {"all", TYPE_BOOLEAN, "If true, stage all changes (including untracked files)"}
        });
        addArrayItems(gitStage, PARAM_PATHS);
        m.put("git_stage", gitStage);

        JsonObject gitUnstage = schema(new Object[][]{
            {PARAM_PATH, TYPE_STRING, "Single file path to unstage"},
            {PARAM_PATHS, TYPE_ARRAY, "Multiple file paths to unstage"}
        });
        addArrayItems(gitUnstage, PARAM_PATHS);
        m.put("git_unstage", gitUnstage);

        m.put("git_branch", schema(new Object[][]{
            {PARAM_ACTION, TYPE_STRING, "Action: 'list' (default), 'create', 'switch', 'delete'"},
            {PARAM_NAME, TYPE_STRING, "Branch name (required for create/switch/delete)"},
            {"base", TYPE_STRING, "Base ref for create (default: HEAD)"},
            {"all", TYPE_BOOLEAN, "For list: include remote branches"},
            {"force", TYPE_BOOLEAN, "For delete: force delete unmerged branches"}
        }));

        m.put("git_stash", schema(new Object[][]{
            {PARAM_ACTION, TYPE_STRING, "Action: 'list' (default), 'push', 'pop', 'apply', 'drop'"},
            {PARAM_MESSAGE, TYPE_STRING, "Stash message (for push action)"},
            {"index", TYPE_STRING, "Stash index (for pop/apply/drop, e.g., 'stash@{0}')"},
            {"include_untracked", TYPE_BOOLEAN, "For push: include untracked files"}
        }));

        m.put("git_revert", schema(new Object[][]{
            {PARAM_COMMIT, TYPE_STRING, "Commit SHA to revert"},
            {"no_commit", TYPE_BOOLEAN, "If true, revert changes to working tree without creating a commit"},
            {"no_edit", TYPE_BOOLEAN, "If true, use the default commit message without editing"}
        }, PARAM_COMMIT));

        m.put("git_show", schema(new Object[][]{
            {"ref", TYPE_STRING, "Commit SHA, branch, tag, or ref (default: HEAD)"},
            {"stat_only", TYPE_BOOLEAN, "If true, show only file stats, not full diff content"},
            {PARAM_PATH, TYPE_STRING, "Limit output to this file path"}
        }));

        m.put("git_push", schema(new Object[][]{
            {PARAM_REMOTE, TYPE_STRING, DESC_REMOTE_ORIGIN},
            {PARAM_BRANCH, TYPE_STRING, "Branch to push (default: current)"},
            {"force", TYPE_BOOLEAN, "Force push"},
            {"set_upstream", TYPE_BOOLEAN, "Set upstream tracking reference"},
            {"tags", TYPE_BOOLEAN, "Push all tags"}
        }));

        m.put("git_remote", schema(new Object[][]{
            {PARAM_ACTION, TYPE_STRING, "Action: 'list' (default), 'add', 'remove', 'set_url', 'get_url'"},
            {PARAM_NAME, TYPE_STRING, "Remote name (required for add/remove/set_url/get_url)"},
            {"url", TYPE_STRING, "Remote URL (required for add/set_url)"}
        }));

        m.put("git_fetch", schema(new Object[][]{
            {PARAM_REMOTE, TYPE_STRING, DESC_REMOTE_ORIGIN},
            {PARAM_BRANCH, TYPE_STRING, "Specific branch to fetch"},
            {"prune", TYPE_BOOLEAN, "Remove remote-tracking refs that no longer exist on the remote"},
            {"tags", TYPE_BOOLEAN, "Fetch all tags from the remote"}
        }));

        m.put("git_pull", schema(new Object[][]{
            {PARAM_REMOTE, TYPE_STRING, DESC_REMOTE_ORIGIN},
            {PARAM_BRANCH, TYPE_STRING, "Branch to pull (default: current tracking branch)"},
            {"rebase", TYPE_BOOLEAN, "If true, rebase instead of merge when pulling"},
            {"ff_only", TYPE_BOOLEAN, "If true, only fast-forward (abort if not possible)"}
        }));

        m.put("git_merge", schema(new Object[][]{
            {PARAM_BRANCH, TYPE_STRING, "Branch to merge into current branch"},
            {PARAM_MESSAGE, TYPE_STRING, "Custom merge commit message"},
            {"no_ff", TYPE_BOOLEAN, "Create a merge commit even for fast-forward merges"},
            {"ff_only", TYPE_BOOLEAN, "Only merge if fast-forward is possible"},
            {"squash", TYPE_BOOLEAN, "Squash all commits into a single commit (requires manual commit after)"},
            {PARAM_ABORT, TYPE_BOOLEAN, "Abort an in-progress merge"}
        }));

        m.put("git_rebase", schema(new Object[][]{
            {PARAM_BRANCH, TYPE_STRING, "Branch to rebase onto"},
            {"onto", TYPE_STRING, "Rebase onto a specific commit (used with --onto)"},
            {"interactive", TYPE_BOOLEAN, "Start an interactive rebase"},
            {"autosquash", TYPE_BOOLEAN, "Automatically squash fixup! and squash! commits (requires interactive)"},
            {PARAM_ABORT, TYPE_BOOLEAN, "Abort an in-progress rebase"},
            {"continue_rebase", TYPE_BOOLEAN, "Continue a paused rebase after resolving conflicts"},
            {"skip", TYPE_BOOLEAN, "Skip the current patch and continue rebase"}
        }));

        JsonObject gitCherryPick = schema(new Object[][]{
            {"commits", TYPE_ARRAY, "One or more commit SHAs to cherry-pick"},
            {"no_commit", TYPE_BOOLEAN, "Apply changes without creating commits"},
            {PARAM_ABORT, TYPE_BOOLEAN, "Abort an in-progress cherry-pick"},
            {"continue_pick", TYPE_BOOLEAN, "Continue cherry-pick after resolving conflicts"}
        });
        addArrayItems(gitCherryPick, "commits");
        m.put("git_cherry_pick", gitCherryPick);

        m.put("git_tag", schema(new Object[][]{
            {PARAM_ACTION, TYPE_STRING, "Action: 'list' (default), 'create', 'delete'"},
            {PARAM_NAME, TYPE_STRING, "Tag name (required for create/delete)"},
            {PARAM_COMMIT, TYPE_STRING, "Commit to tag (default: HEAD, for create)"},
            {PARAM_MESSAGE, TYPE_STRING, "Tag message (for annotated tags)"},
            {"annotate", TYPE_BOOLEAN, "Create an annotated tag (requires message)"},
            {"pattern", TYPE_STRING, "Glob pattern to filter tags (for list)"},
            {"sort", TYPE_STRING, "Sort field for list (e.g., '-creatordate' for newest first)"}
        }));

        m.put("git_reset", schema(new Object[][]{
            {PARAM_COMMIT, TYPE_STRING, "Target commit (default: HEAD)"},
            {"mode", TYPE_STRING, "Reset mode: 'soft' (keep staged), 'mixed' (default, unstage), 'hard' (discard all changes)"},
            {PARAM_PATH, TYPE_STRING, "Reset a specific file path (unstages it)"}
        }));

        m.put("get_file_history", schema(new Object[][]{
            {PARAM_PATH, TYPE_STRING, "Path to the file to get history for (absolute or project-relative)"},
            {"max_count", TYPE_INTEGER, "Maximum number of commits to show (default: 20)"}
        }, PARAM_PATH));

        // ── Infrastructure ───────────────────────────────────────────────────

        JsonObject httpRequest = schema(new Object[][]{
            {"url", TYPE_STRING, "Full URL to request (e.g., http://localhost:8080/api)"},
            {"method", TYPE_STRING, "HTTP method: GET (default), POST, PUT, PATCH, DELETE"},
            {"body", TYPE_STRING, "Request body (for POST/PUT/PATCH)"}
        }, "url");
        addDictProperty(httpRequest, "headers", "Request headers as key-value pairs");
        m.put("http_request", httpRequest);

        m.put("run_command", schema(new Object[][]{
            {PARAM_COMMAND, TYPE_STRING, "Shell command to execute (e.g., 'gradle build', 'cat file.txt')"},
            {"timeout", TYPE_INTEGER, "Timeout in seconds (default: 60)"},
            {"title", TYPE_STRING, "Human-readable title for the Run panel tab. ALWAYS set this to a short descriptive name"},
            {PARAM_OFFSET, TYPE_INTEGER, "Character offset to start output from (default: 0). Use for pagination when output is truncated"},
            {PARAM_MAX_CHARS, TYPE_INTEGER, "Maximum characters to return per page (default: 8000)"}
        }, PARAM_COMMAND));

        m.put("read_ide_log", schema(new Object[][]{
            {"lines", TYPE_INTEGER, "Number of recent lines to return (default: 50)"},
            {"filter", TYPE_STRING, "Only return lines containing this text"},
            {"level", TYPE_STRING, "Filter by log level: INFO, WARN, ERROR"}
        }));

        m.put("get_notifications", schema(new Object[][]{}));

        m.put("read_run_output", schema(new Object[][]{
            {PARAM_TAB_NAME, TYPE_STRING, "Name of the Run tab to read (default: most recent)"},
            {PARAM_MAX_CHARS, TYPE_INTEGER, "Maximum characters to return (default: 8000)"}
        }));

        m.put("read_build_output", schema(new Object[][]{
            {PARAM_TAB_NAME, TYPE_STRING, "Name of the Build tab to read (default: currently selected or most recent). Use tab names shown in IntelliJ's Build tool window."},
            {PARAM_MAX_CHARS, TYPE_INTEGER, "Maximum characters to return (default: 8000)"}
        }));

        // ── Terminal ─────────────────────────────────────────────────────────

        m.put("run_in_terminal", schema(new Object[][]{
            {PARAM_COMMAND, TYPE_STRING, "The command to run in the terminal"},
            {PARAM_TAB_NAME, TYPE_STRING, "Name for the terminal tab. If omitted, reuses the most recent agent-created tab or creates a new one"},
            {"new_tab", TYPE_BOOLEAN, "If true, always create a new terminal tab instead of reusing an existing one"},
            {"shell", TYPE_STRING, "Shell to use (e.g., 'bash', 'zsh'). If omitted, uses the default shell"}
        }, PARAM_COMMAND));

        m.put("write_terminal_input", schema(new Object[][]{
            {"input", TYPE_STRING, "Text or keystrokes to send. Supports escape sequences: {enter}, {tab}, {ctrl-c}, {ctrl-d}, {ctrl-z}, {escape}, {up}, {down}, {left}, {right}, {backspace}, \\n, \\t"},
            {PARAM_TAB_NAME, TYPE_STRING, "Name of the terminal tab to write to. If omitted, writes to the currently selected tab"}
        }, "input"));

        m.put("read_terminal_output", schema(new Object[][]{
            {PARAM_TAB_NAME, TYPE_STRING, "Name of the terminal tab to read from"},
            {"max_lines", TYPE_INTEGER, "Maximum number of lines to return from the end of the terminal buffer (default: 50). Use 0 for the full buffer."}
        }));

        m.put("list_terminals", schema(new Object[][]{}));

        // ── Documentation ────────────────────────────────────────────────────

        m.put("get_documentation", schema(new Object[][]{
            {PARAM_SYMBOL, TYPE_STRING, "Fully qualified symbol name (e.g. java.util.List)"}
        }, PARAM_SYMBOL));

        m.put("download_sources", schema(new Object[][]{
            {"library", TYPE_STRING, "Optional library name filter (e.g. 'junit')"}
        }));

        // ── Scratch files ────────────────────────────────────────────────────

        m.put("create_scratch_file", schema(new Object[][]{
            {PARAM_NAME, TYPE_STRING, "Scratch file name with extension (e.g., 'test.py', 'notes.md')"},
            {PARAM_CONTENT, TYPE_STRING, "The content to write to the scratch file"}
        }, PARAM_NAME, PARAM_CONTENT));

        m.put("list_scratch_files", schema(new Object[][]{}));

        m.put("run_scratch_file", schema(new Object[][]{
            {PARAM_NAME, TYPE_STRING, "Scratch file name with extension (e.g., 'test.kts', 'MyApp.java', 'hello.js')"},
            {PARAM_MODULE, TYPE_STRING, "Optional: module name for classpath (e.g., 'plugin-core')"},
            {"interactive", TYPE_BOOLEAN, "Optional: enable interactive/REPL mode (Kotlin scripts)"}
        }, PARAM_NAME));

        // ── IDE & project ────────────────────────────────────────────────────

        m.put("get_indexing_status", schema(new Object[][]{
            {"wait", TYPE_BOOLEAN, "If true, blocks until indexing finishes"},
            {"timeout", TYPE_INTEGER, "Max seconds to wait when wait=true (default: 30)"}
        }));

        m.put("get_active_file", schema(new Object[][]{}));

        m.put("get_open_editors", schema(new Object[][]{}));

        m.put("list_themes", schema(new Object[][]{}));

        m.put("set_theme", schema(new Object[][]{
            {"theme", TYPE_STRING, "Theme name or partial name (e.g., 'Darcula', 'Light')"}
        }, "theme"));

        m.put("build_project", schema(new Object[][]{
            {PARAM_MODULE, TYPE_STRING, "Optional: build only a specific module (e.g., 'plugin-core')"}
        }));

        m.put("mark_directory", schema(new Object[][]{
            {PARAM_PATH, TYPE_STRING, "Directory path (absolute or project-relative)"},
            {"type", TYPE_STRING, "Directory type: 'sources', 'test_sources', 'resources', 'test_resources', 'generated_sources', 'excluded', or 'unmark' to remove marking"}
        }, PARAM_PATH, "type"));

        m.put("edit_project_structure", schema(new Object[][]{
            {PARAM_ACTION, TYPE_STRING, "Action: 'list_modules', 'list_dependencies', 'add_dependency', 'remove_dependency', 'list_sdks', 'add_sdk', 'remove_sdk'"},
            {PARAM_MODULE, TYPE_STRING, "Module name (required for list_dependencies, add_dependency, remove_dependency)"},
            {"dependency_name", TYPE_STRING, "Name of the dependency to add or remove"},
            {"dependency_type", TYPE_STRING, "Type of dependency to add: 'library' (default) or 'module'"},
            {PARAM_SCOPE, TYPE_STRING, "Dependency scope: 'COMPILE' (default), 'TEST', 'RUNTIME', 'PROVIDED'"},
            {"jar_path", TYPE_STRING, "Path to JAR file (absolute or project-relative). Required when adding a library dependency"},
            {"sdk_type", TYPE_STRING, "SDK type name for add_sdk (e.g., 'Python SDK', 'JavaSDK'). Use list_sdks to see available types"},
            {"sdk_name", TYPE_STRING, "SDK name for remove_sdk. Use list_sdks to see configured SDK names"},
            {"home_path", TYPE_STRING, "Home path for add_sdk. Use list_sdks to see suggested paths for each SDK type"}
        }, PARAM_ACTION));

        // ── Refactoring ──────────────────────────────────────────────────────

        m.put("apply_quickfix", schema(new Object[][]{
            {PARAM_FILE, TYPE_STRING, "Path to the file containing the problem"},
            {PARAM_LINE, TYPE_INTEGER, "Line number where the problem is located"},
            {PARAM_INSPECTION_ID, TYPE_STRING, "The inspection ID from run_inspections output (e.g., 'unused')"},
            {"fix_index", TYPE_INTEGER, "Which fix to apply if multiple are available (default: 0)"}
        }, PARAM_FILE, PARAM_LINE, PARAM_INSPECTION_ID));

        m.put("refactor", schema(new Object[][]{
            {"operation", TYPE_STRING, "Refactoring type: 'rename', 'extract_method', 'inline', or 'safe_delete'"},
            {PARAM_FILE, TYPE_STRING, DESC_FILE_WITH_SYMBOL},
            {PARAM_SYMBOL, TYPE_STRING, "Name of the symbol to refactor (class, method, field, or variable)"},
            {PARAM_LINE, TYPE_INTEGER, "Line number to disambiguate if multiple symbols share the same name"},
            {PARAM_NEW_NAME, TYPE_STRING, "New name for 'rename' operation. Required when operation is 'rename'"}
        }, "operation", PARAM_FILE, PARAM_SYMBOL));

        m.put("replace_symbol_body", schema(new Object[][]{
            {PARAM_FILE, TYPE_STRING, DESC_FILE_WITH_SYMBOL},
            {PARAM_SYMBOL, TYPE_STRING, "Name of the symbol to replace (method, class, function, or field)"},
            {"new_body", TYPE_STRING, "The complete new definition to replace the symbol with"},
            {PARAM_LINE, TYPE_INTEGER, DESC_LINE_HINT}
        }, PARAM_FILE, PARAM_SYMBOL, "new_body"));

        m.put("insert_before_symbol", schema(new Object[][]{
            {PARAM_FILE, TYPE_STRING, DESC_FILE_WITH_SYMBOL},
            {PARAM_SYMBOL, TYPE_STRING, "Name of the symbol to insert before"},
            {PARAM_CONTENT, TYPE_STRING, "The content to insert before the symbol"},
            {PARAM_LINE, TYPE_INTEGER, DESC_LINE_HINT}
        }, PARAM_FILE, PARAM_SYMBOL, PARAM_CONTENT));

        m.put("insert_after_symbol", schema(new Object[][]{
            {PARAM_FILE, TYPE_STRING, DESC_FILE_WITH_SYMBOL},
            {PARAM_SYMBOL, TYPE_STRING, "Name of the symbol to insert after"},
            {PARAM_CONTENT, TYPE_STRING, "The content to insert after the symbol"},
            {PARAM_LINE, TYPE_INTEGER, DESC_LINE_HINT}
        }, PARAM_FILE, PARAM_SYMBOL, PARAM_CONTENT));

        m.put("go_to_declaration", schema(new Object[][]{
            {PARAM_FILE, TYPE_STRING, "Path to the file containing the symbol usage"},
            {PARAM_SYMBOL, TYPE_STRING, "Name of the symbol to look up"},
            {PARAM_LINE, TYPE_INTEGER, "Line number where the symbol appears"}
        }, PARAM_FILE, PARAM_SYMBOL, PARAM_LINE));

        m.put("get_type_hierarchy", schema(new Object[][]{
            {PARAM_SYMBOL, TYPE_STRING, "Fully qualified or simple class/interface name"},
            {"direction", TYPE_STRING, "Direction: 'supertypes' (ancestors) or 'subtypes' (descendants). Default: both"}
        }, PARAM_SYMBOL));

        m.put("find_implementations", schema(new Object[][]{
            {PARAM_SYMBOL, TYPE_STRING, "Class, interface, or method name to find implementations for"},
            {PARAM_FILE, TYPE_STRING, "Optional: file path for method context (required when searching for method overrides)"},
            {PARAM_LINE, TYPE_INTEGER, "Optional: line number to disambiguate the method (required when searching for method overrides)"}
        }, PARAM_SYMBOL));

        m.put("get_call_hierarchy", schema(new Object[][]{
            {PARAM_SYMBOL, TYPE_STRING, "Method name to find callers for"},
            {PARAM_FILE, TYPE_STRING, "Path to the file containing the method definition"},
            {PARAM_LINE, TYPE_INTEGER, "Line number where the method is defined"}
        }, PARAM_SYMBOL, PARAM_FILE, PARAM_LINE));

        m.put("undo", schema(new Object[][]{
            {PARAM_PATH, TYPE_STRING, "Path to the file to undo changes on"},
            {"count", TYPE_INTEGER, "Number of undo steps (default: 1). Each write + auto-format counts as 2 steps"}
        }, PARAM_PATH));

        m.put("redo", schema(new Object[][]{
            {PARAM_PATH, TYPE_STRING, "Path to the file to redo changes on"},
            {"count", TYPE_INTEGER, "Number of redo steps (default: 1)"}
        }, PARAM_PATH));

        // ── Other ────────────────────────────────────────────────────────────

        m.put("get_chat_html", schema(new Object[][]{}));

        m.put("search_conversation_history", schema(new Object[][]{
            {PARAM_QUERY, TYPE_STRING, "Text to search for across conversations (case-insensitive)"},
            {PARAM_FILE, TYPE_STRING, "Conversation to read: 'current' for the active session, or an archive timestamp (e.g., '2026-03-04T15-30-00')"},
            {PARAM_MAX_CHARS, TYPE_INTEGER, "Maximum characters to return (default: 8000)"}
        }));

        SCHEMAS = Collections.unmodifiableMap(m);
    }
}
