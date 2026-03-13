package com.github.catatafishen.ideagentforcopilot.psi.tools.file;

import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Backward-compatible alias for {@link ReadFileTool} using the {@code read_file} id.
 */
@SuppressWarnings("java:S112")
public final class ReadFileAliasTool extends ReadFileTool {

    public ReadFileAliasTool(Project project) {
        super(project);
    }

    @Override
    public @NotNull String id() {
        return "read_file";
    }

    @Override
    public @NotNull JsonObject inputSchema() {
        return schema(new Object[][]{
            {"path", TYPE_STRING, "Absolute or project-relative path to the file to read"},
            {"start_line", TYPE_INTEGER, "Optional: first line to read (1-based, inclusive)"},
            {"end_line", TYPE_INTEGER, "Optional: last line to read (1-based, inclusive). Use with start_line to read a range"}
        }, "path");
    }
}
