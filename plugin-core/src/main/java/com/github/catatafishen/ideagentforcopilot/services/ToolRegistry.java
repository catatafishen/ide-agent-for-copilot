package com.github.catatafishen.ideagentforcopilot.services;

import java.util.List;

/**
 * Registry of all tools the agent can use, both built-in agent tools
 * and MCP tools we provide via IntelliJ.
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
        TESTING("Testing"),
        PROJECT("Project"),
        INFRASTRUCTURE("Infrastructure"),
        TERMINAL("Terminal"),
        EDITOR("Editor"),
        SHELL("Shell (built-in)"),
        OTHER("Other"),
        MACRO("Recorded Macros");

        public final String displayName;

        Category(String displayName) {
            this.displayName = displayName;
        }
    }

    record ToolEntry(
        @org.jetbrains.annotations.NotNull String id,
        @org.jetbrains.annotations.NotNull String displayName,
        @org.jetbrains.annotations.NotNull String description,
        @org.jetbrains.annotations.NotNull Category category,
        boolean isBuiltIn,
        boolean hasDenyControl,
        boolean supportsPathSubPermissions
    ) implements ToolDefinition {
    }

    /**
     * Built-in agent tools (bash, edit, etc.) that are handled by the Copilot CLI
     * rather than our MCP server. These are NOT in DEFINITIONS.
     */
    private static final List<ToolEntry> BUILT_IN_TOOLS = List.of(
        // Copilot CLI: view/read/grep/glob run silently -- no permission hook (hasDenyControl=false)
        // OpenCode:    read/grep/glob/list run silently -- no permission hook (hasDenyControl=false)
        new ToolEntry("view", "Read File (built-in)", "Read file contents from disk (Copilot CLI built-in)", Category.FILE, true, false, true),
        new ToolEntry("read", "Read File (built-in)", "Read file contents from disk (built-in)", Category.FILE, true, false, true),
        new ToolEntry("grep", "Grep Search (built-in)", "Search file contents with regular expressions (built-in)", Category.SEARCH, true, false, false),
        new ToolEntry("glob", "Glob Find (built-in)", "Find files by name pattern (built-in)", Category.SEARCH, true, false, false),
        new ToolEntry("list", "List Files (built-in)", "List files and directories (OpenCode built-in)", Category.SEARCH, true, false, true),
        new ToolEntry("bash", "Bash Shell (built-in)", "Run arbitrary shell commands -- use run_command instead for safer, paginated execution", Category.SHELL, true, true, false),
        // edit/write/create/execute/runInTerminal fire permission requests (hasDenyControl=true)
        new ToolEntry("edit", "Edit File (built-in)", "Edit a file in place (built-in) -- use edit_text or replace_symbol_body for IDE-aware editing", Category.FILE, true, true, true),
        new ToolEntry("write", "Write File (built-in)", "Create or overwrite a file (OpenCode built-in) -- use intellij_write_file for IDE-aware writing", Category.FILE, true, true, true),
        new ToolEntry("create", "Create File (built-in)", "Create a new file (Copilot CLI built-in) -- use create_file for IDE-aware creation", Category.FILE, true, true, true),
        new ToolEntry("execute", "Execute (built-in)", "Execute a shell command (built-in)", Category.SHELL, true, true, false),
        new ToolEntry("runInTerminal", "Run in Terminal (built-in)", "Run a command in the integrated terminal (Copilot CLI built-in)", Category.SHELL, true, true, false)
    );

    private ToolRegistry() {
    }

    /**
     * Permission question templates for built-in agent tools (bash, edit, etc.).
     * MCP tools define their templates via {@link ToolDefinition#permissionTemplate()}.
     * Placeholders like {@code {param}} are replaced with actual argument values at runtime.
     */
    private static final java.util.Map<String, String> BUILT_IN_PERMISSION_QUESTIONS =
        java.util.Map.of(
            "bash", "Run: {cmd}",
            "edit", "Edit {path}",
            "write", "Write {path}",
            "create", "Create {path}",
            "execute", "Execute: {command}",
            "runInTerminal", "Run in terminal: {command}"
        );

    /**
     * Resolves a human-readable permission question for the given tool and arguments.
     * Substitutes {@code {paramName}} placeholders with the corresponding argument values.
     * Returns {@code null} if no custom template is registered for this tool.
     *
     * @param toolId the tool identifier (e.g. {@code "git_push"})
     * @param args   the tool call arguments as a JSON object (may be null)
     */
    @org.jetbrains.annotations.Nullable
    public static String resolvePermissionQuestion(
        @org.jetbrains.annotations.NotNull String toolId,
        @org.jetbrains.annotations.Nullable com.google.gson.JsonObject args) {
        String template = resolveTemplate(toolId);
        if (template == null) return null;
        if (args == null) return stripPlaceholders(template);
        String q = substituteArgs(template, args);
        return stripPlaceholders(q);
    }

    @org.jetbrains.annotations.Nullable
    private static String resolveTemplate(@org.jetbrains.annotations.NotNull String toolId) {
        ToolDefinition def = DEFINITIONS.get(toolId);
        if (def != null && def.permissionTemplate() != null) {
            return def.permissionTemplate();
        }
        return BUILT_IN_PERMISSION_QUESTIONS.get(toolId);
    }

    private static String substituteArgs(
        @org.jetbrains.annotations.NotNull String template,
        @org.jetbrains.annotations.NotNull com.google.gson.JsonObject args) {
        String q = template;
        for (java.util.Map.Entry<String, com.google.gson.JsonElement> e : args.entrySet()) {
            q = q.replace("{" + e.getKey() + "}", formatArgValue(e.getValue()));
        }
        return q;
    }

    private static String formatArgValue(@org.jetbrains.annotations.NotNull com.google.gson.JsonElement value) {
        if (value.isJsonNull()) return "";
        if (value.isJsonPrimitive()) {
            String s = value.getAsString();
            return s.length() > 60 ? s.substring(0, 57) + "…" : s;
        }
        if (value.isJsonArray()) {
            java.util.StringJoiner joiner = new java.util.StringJoiner(", ");
            for (com.google.gson.JsonElement el : value.getAsJsonArray()) {
                joiner.add(el.isJsonPrimitive() ? el.getAsString() : el.toString());
            }
            return joiner.toString();
        }
        return value.toString();
    }

    @org.jetbrains.annotations.Nullable
    private static String stripPlaceholders(@org.jetbrains.annotations.NotNull String text) {
        String q = text.replaceAll("\\{[^}]+}", "").replaceAll("\\(\\s*\\)", "")
            .replaceAll("\\s+", " ").trim();
        return q.isEmpty() ? null : q;
    }

    // ── ToolDefinition-based registry ────────────────────────────────────────

    /**
     * Registry of tools defined via the new {@link ToolDefinition} interface.
     * All MCP tools the plugin provides are registered here at startup.
     */
    private static final java.util.Map<String, ToolDefinition> DEFINITIONS =
        new java.util.LinkedHashMap<>();

    /**
     * Register a single tool definition. Overwrites any prior definition with the same ID.
     */
    public static void register(@org.jetbrains.annotations.NotNull ToolDefinition def) {
        DEFINITIONS.put(def.id(), def);
    }

    /**
     * Unregister a tool definition by ID. Used when removing dynamically registered tools.
     */
    public static void unregister(@org.jetbrains.annotations.NotNull String id) {
        DEFINITIONS.remove(id);
    }

    /**
     * Register multiple tool definitions.
     */
    public static void registerAll(@org.jetbrains.annotations.NotNull java.util.Collection<? extends ToolDefinition> defs) {
        for (ToolDefinition def : defs) {
            DEFINITIONS.put(def.id(), def);
        }
    }

    /**
     * Look up a {@link ToolDefinition} by tool ID. Returns null if not found.
     */
    @org.jetbrains.annotations.Nullable
    public static ToolDefinition findDefinition(@org.jetbrains.annotations.NotNull String id) {
        return DEFINITIONS.get(id);
    }

    // ── Unified lookups ────────────────────────────────────────────────────

    /**
     * Returns all tools: built-in agent tools plus all registered MCP tool definitions.
     */
    public static List<ToolDefinition> getAllTools() {
        var all = new java.util.ArrayList<ToolDefinition>(BUILT_IN_TOOLS);
        all.addAll(DEFINITIONS.values());
        return List.copyOf(all);
    }

    /**
     * Look up a tool by id (exact match). Checks {@link ToolDefinition} registry first,
     * falls back to built-in tools list, returns null if not found.
     */
    public static ToolDefinition findById(String id) {
        if (id == null) return null;
        ToolDefinition def = DEFINITIONS.get(id);
        if (def != null) return def;
        for (ToolEntry e : BUILT_IN_TOOLS) {
            if (e.id.equals(id)) return e;
        }
        return null;
    }

    /**
     * Returns the IDs of all built-in agent tools (e.g., view, edit, bash).
     * Used to populate {@code excludedTools} in {@code session/new} for agents
     * that support filtering out their native tools.
     */
    @org.jetbrains.annotations.NotNull
    public static List<String> getBuiltInToolIds() {
        List<String> ids = new java.util.ArrayList<>();
        for (ToolEntry e : BUILT_IN_TOOLS) {
            ids.add(e.id());
        }
        return ids;
    }

    /**
     * Returns MCP tool annotations for a given tool ID.
     * Reads flags from {@link ToolDefinition}; returns empty annotations for unknown tools.
     */
    public static com.google.gson.JsonObject getMcpAnnotations(@org.jetbrains.annotations.NotNull String toolId) {
        ToolDefinition def = DEFINITIONS.get(toolId);
        com.google.gson.JsonObject ann = new com.google.gson.JsonObject();

        if (def != null) {
            ann.addProperty("title", def.displayName());
            ann.addProperty("readOnlyHint", def.isReadOnly());
            ann.addProperty("destructiveHint", def.isDestructive());
            ann.addProperty("openWorldHint", def.isOpenWorld());
        } else {
            ToolDefinition entry = findById(toolId);
            if (entry != null) {
                ann.addProperty("title", entry.displayName());
            }
        }
        return ann;
    }
}
