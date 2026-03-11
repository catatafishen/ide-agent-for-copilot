package com.github.catatafishen.ideagentforcopilot.services;

import com.github.catatafishen.ideagentforcopilot.psi.ToolHandler;
import com.google.gson.JsonObject;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Fluent builder for creating {@link ToolDefinition} instances without
 * requiring a dedicated class for each tool.
 * <p>
 * Example:
 * <pre>{@code
 * ToolDefinition tool = ToolBuilder.create("git_log", "Git Log",
 *         "Show commit history", Category.GIT)
 *     .readOnly()
 *     .permissionTemplate("View log of {branch}")
 *     .schema(mySchema)
 *     .handler(commands::gitLog)
 *     .build();
 * }</pre>
 */
public final class ToolBuilder {

    private final String id;
    private final String displayName;
    private final String description;
    private final ToolRegistry.Category category;

    // Behavior flags — all default to false
    private boolean builtIn;
    private boolean denyControl;
    private boolean pathSubPermissions;
    private boolean readOnly;
    private boolean destructive;
    private boolean openWorld;

    // Optional fields
    private JsonObject schema;
    private String permissionTemplate;
    private ToolHandler handler;

    private ToolBuilder(String id, String displayName, String description,
                        ToolRegistry.Category category) {
        this.id = id;
        this.displayName = displayName;
        this.description = description;
        this.category = category;
    }

    /**
     * Start building a new tool definition.
     */
    public static ToolBuilder create(@NotNull String id, @NotNull String displayName,
                                     @NotNull String description,
                                     @NotNull ToolRegistry.Category category) {
        return new ToolBuilder(id, displayName, description, category);
    }

    // ── Flag setters ─────────────────────────────────────────

    public ToolBuilder builtIn() {
        this.builtIn = true;
        return this;
    }

    public ToolBuilder builtIn(boolean hasDenyControl) {
        this.builtIn = true;
        this.denyControl = hasDenyControl;
        return this;
    }

    public ToolBuilder pathSubPermissions() {
        this.pathSubPermissions = true;
        return this;
    }

    public ToolBuilder readOnly() {
        this.readOnly = true;
        return this;
    }

    public ToolBuilder destructive() {
        this.destructive = true;
        return this;
    }

    public ToolBuilder openWorld() {
        this.openWorld = true;
        return this;
    }

    // ── Optional fields ──────────────────────────────────────

    public ToolBuilder schema(@Nullable JsonObject schema) {
        this.schema = schema;
        return this;
    }

    public ToolBuilder permissionTemplate(@Nullable String template) {
        this.permissionTemplate = template;
        return this;
    }

    public ToolBuilder handler(@NotNull ToolHandler handler) {
        this.handler = handler;
        return this;
    }

    // ── Build ────────────────────────────────────────────────

    public ToolDefinition build() {
        return new BuiltToolDefinition(
            id, displayName, description, category,
            builtIn, denyControl, pathSubPermissions,
            readOnly, destructive, openWorld,
            schema, permissionTemplate, handler
        );
    }

    /**
     * Immutable implementation produced by the builder.
     */
    private record BuiltToolDefinition(
        @NotNull String id,
        @NotNull String displayName,
        @NotNull String description,
        @NotNull ToolRegistry.Category category,
        boolean isBuiltIn,
        boolean hasDenyControl,
        boolean supportsPathSubPermissions,
        boolean isReadOnly,
        boolean isDestructive,
        boolean isOpenWorld,
        @Nullable JsonObject inputSchema,
        @Nullable String permissionTemplate,
        @Nullable ToolHandler handler
    ) implements ToolDefinition {

        @Override
        public @Nullable String execute(@NotNull JsonObject args) throws Exception {
            return handler != null ? handler.handle(args) : null;
        }

        @Override
        public boolean hasExecutionHandler() {
            return handler != null;
        }
    }
}
