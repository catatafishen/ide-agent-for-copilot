package com.github.copilot.intellij.services;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Registry of all tools the agent can use, both built-in Copilot CLI tools
 * (which cannot be disabled due to ACP bug #556) and MCP tools we provide.
 */
public final class ToolRegistry {

    public enum Category {
        FILE("File Operations"),
        SEARCH("Search & Navigation"),
        CODE_QUALITY("Code Quality"),
        BUILD("Build / Run / Test"),
        RUN("Terminal & Commands"),
        GIT("Git"),
        REFACTOR("Refactoring"),
        IDE("IDE & Project"),
        SHELL("Shell (built-in)"),
        OTHER("Other");

        public final String displayName;

        Category(String displayName) {
            this.displayName = displayName;
        }
    }

    public static final class ToolEntry {
        public final String id;
        public final String displayName;
        public final Category category;
        /**
         * True = Copilot CLI injects this tool; we cannot disable it (ACP bug #556).
         */
        public final boolean isBuiltIn;
        /**
         * True = this built-in tool fires a permission request that we can intercept.
         * False (and isBuiltIn=true) = runs silently with no hook.
         */
        public final boolean hasDenyControl;
        /**
         * True = tool accepts a file path; supports inside-project / outside-project
         * sub-permissions.
         */
        public final boolean supportsPathSubPermissions;

        public ToolEntry(String id, String displayName, Category category,
                         boolean isBuiltIn, boolean hasDenyControl, boolean supportsPathSubPermissions) {
            this.id = id;
            this.displayName = displayName;
            this.category = category;
            this.isBuiltIn = isBuiltIn;
            this.hasDenyControl = hasDenyControl;
            this.supportsPathSubPermissions = supportsPathSubPermissions;
        }
    }

    private static final List<ToolEntry> ALL_TOOLS = Collections.unmodifiableList(Arrays.asList(
        // ── Built-in CLI tools (cannot disable — ACP bug #556) ──────────────────
        // view/read/grep/glob/bash run silently — no permission hook fires (hasDenyControl=false)
        new ToolEntry("view", "Read File (built-in)", Category.FILE, true, false, true),
        new ToolEntry("read", "Read File alt (built-in)", Category.FILE, true, false, true),
        new ToolEntry("grep", "Grep Search (built-in)", Category.SEARCH, true, false, false),
        new ToolEntry("glob", "Glob Find (built-in)", Category.SEARCH, true, false, false),
        new ToolEntry("bash", "Bash Shell (built-in)", Category.SHELL, true, true, false),
        // edit/create/execute/runInTerminal fire permission requests (hasDenyControl=true)
        new ToolEntry("edit", "Edit File (built-in)", Category.FILE, true, true, true),
        new ToolEntry("create", "Create File (built-in)", Category.FILE, true, true, true),
        new ToolEntry("execute", "Execute (built-in)", Category.SHELL, true, true, false),
        new ToolEntry("runInTerminal", "Run in Terminal (built-in)", Category.SHELL, true, true, false),

        // ── File operations ──────────────────────────────────────────────────────
        new ToolEntry("intellij_read_file", "Read File", Category.FILE, false, false, true),
        new ToolEntry("intellij_write_file", "Write File", Category.FILE, false, false, true),
        new ToolEntry("create_file", "Create File", Category.FILE, false, false, true),
        new ToolEntry("delete_file", "Delete File", Category.FILE, false, false, true),
        new ToolEntry("reload_from_disk", "Reload from Disk", Category.FILE, false, false, true),
        new ToolEntry("open_in_editor", "Open in Editor", Category.FILE, false, false, true),
        new ToolEntry("show_diff", "Show Diff", Category.FILE, false, false, false),

        // ── Search & navigation ──────────────────────────────────────────────────
        new ToolEntry("search_symbols", "Search Symbols", Category.SEARCH, false, false, false),
        new ToolEntry("search_text", "Search Text", Category.SEARCH, false, false, false),
        new ToolEntry("find_references", "Find References", Category.SEARCH, false, false, false),
        new ToolEntry("go_to_declaration", "Go to Declaration", Category.SEARCH, false, false, false),
        new ToolEntry("get_file_outline", "Get File Outline", Category.SEARCH, false, false, false),
        new ToolEntry("get_class_outline", "Get Class Outline", Category.SEARCH, false, false, false),
        new ToolEntry("get_type_hierarchy", "Get Type Hierarchy", Category.SEARCH, false, false, false),

        // ── Code quality ─────────────────────────────────────────────────────────
        new ToolEntry("run_inspections", "Run Inspections", Category.CODE_QUALITY, false, false, false),
        new ToolEntry("run_qodana", "Run Qodana", Category.CODE_QUALITY, false, false, false),
        new ToolEntry("run_sonarqube_analysis", "Run SonarQube", Category.CODE_QUALITY, false, false, false),
        new ToolEntry("get_problems", "Get Problems", Category.CODE_QUALITY, false, false, false),
        new ToolEntry("get_highlights", "Get Highlights", Category.CODE_QUALITY, false, false, false),
        new ToolEntry("get_compilation_errors", "Get Compilation Errors", Category.CODE_QUALITY, false, false, false),

        // ── Build / Run / Test ────────────────────────────────────────────────────
        new ToolEntry("build_project", "Build Project", Category.BUILD, false, false, false),
        new ToolEntry("run_tests", "Run Tests", Category.BUILD, false, false, false),
        new ToolEntry("get_test_results", "Get Test Results", Category.BUILD, false, false, false),
        new ToolEntry("get_coverage", "Get Coverage", Category.BUILD, false, false, false),
        new ToolEntry("run_configuration", "Run Configuration", Category.BUILD, false, false, false),
        new ToolEntry("create_run_configuration", "Create Run Config", Category.BUILD, false, false, false),
        new ToolEntry("edit_run_configuration", "Edit Run Config", Category.BUILD, false, false, false),
        new ToolEntry("list_run_configurations", "List Run Configs", Category.BUILD, false, false, false),

        // ── Terminal & commands ───────────────────────────────────────────────────
        new ToolEntry("run_command", "Run Command", Category.RUN, false, false, false),
        new ToolEntry("run_in_terminal", "Run in Terminal", Category.RUN, false, false, false),
        new ToolEntry("read_run_output", "Read Run Output", Category.RUN, false, false, false),
        new ToolEntry("read_terminal_output", "Read Terminal", Category.RUN, false, false, false),

        // ── Git ───────────────────────────────────────────────────────────────────
        new ToolEntry("git_status", "Git Status", Category.GIT, false, false, false),
        new ToolEntry("git_diff", "Git Diff", Category.GIT, false, false, false),
        new ToolEntry("git_log", "Git Log", Category.GIT, false, false, false),
        new ToolEntry("git_commit", "Git Commit", Category.GIT, false, false, false),
        new ToolEntry("git_stage", "Git Stage", Category.GIT, false, false, false),
        new ToolEntry("git_unstage", "Git Unstage", Category.GIT, false, false, false),
        new ToolEntry("git_branch", "Git Branch", Category.GIT, false, false, false),
        new ToolEntry("git_stash", "Git Stash", Category.GIT, false, false, false),
        new ToolEntry("git_show", "Git Show", Category.GIT, false, false, false),
        new ToolEntry("git_blame", "Git Blame", Category.GIT, false, false, false),

        // ── Refactoring ───────────────────────────────────────────────────────────
        new ToolEntry("refactor", "Refactor", Category.REFACTOR, false, false, false),
        new ToolEntry("optimize_imports", "Optimize Imports", Category.REFACTOR, false, false, false),
        new ToolEntry("format_code", "Format Code", Category.REFACTOR, false, false, false),
        new ToolEntry("suppress_inspection", "Suppress Inspection", Category.REFACTOR, false, false, false),
        new ToolEntry("apply_quickfix", "Apply Quickfix", Category.REFACTOR, false, false, false),
        new ToolEntry("add_to_dictionary", "Add to Dictionary", Category.REFACTOR, false, false, false),

        // ── IDE & project ─────────────────────────────────────────────────────────
        new ToolEntry("get_project_info", "Get Project Info", Category.IDE, false, false, false),
        new ToolEntry("list_project_files", "List Project Files", Category.IDE, false, false, false),
        new ToolEntry("mark_directory", "Mark Directory", Category.IDE, false, false, false),
        new ToolEntry("get_indexing_status", "Get Indexing Status", Category.IDE, false, false, false),
        new ToolEntry("get_documentation", "Get Documentation", Category.IDE, false, false, false),
        new ToolEntry("download_sources", "Download Sources", Category.IDE, false, false, false),
        new ToolEntry("get_active_file", "Get Active File", Category.IDE, false, false, false),
        new ToolEntry("get_open_editors", "Get Open Editors", Category.IDE, false, false, false),

        // ── Other ─────────────────────────────────────────────────────────────────
        new ToolEntry("get_chat_html", "Get Chat HTML", Category.OTHER, false, false, false),
        new ToolEntry("get_notifications", "Get Notifications", Category.OTHER, false, false, false),
        new ToolEntry("read_ide_log", "Read IDE Log", Category.OTHER, false, false, false),
        new ToolEntry("create_scratch_file", "Create Scratch File", Category.OTHER, false, false, false),
        new ToolEntry("list_scratch_files", "List Scratch Files", Category.OTHER, false, false, false),
        new ToolEntry("http_request", "HTTP Request", Category.OTHER, false, false, false)
    ));

    private ToolRegistry() {
    }

    public static List<ToolEntry> getAllTools() {
        return ALL_TOOLS;
    }

    /**
     * Look up a tool by id (exact match). Returns null if not found.
     */
    public static ToolEntry findById(String id) {
        if (id == null) return null;
        for (ToolEntry e : ALL_TOOLS) {
            if (e.id.equals(id)) return e;
        }
        return null;
    }
}
