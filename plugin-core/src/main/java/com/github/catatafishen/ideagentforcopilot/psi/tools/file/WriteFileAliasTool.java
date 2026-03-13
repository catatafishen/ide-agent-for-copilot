package com.github.catatafishen.ideagentforcopilot.psi.tools.file;

import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

/**
 * Backward-compatible alias for {@link WriteFileTool} using the {@code write_file} id.
 */
@SuppressWarnings("java:S112")
public final class WriteFileAliasTool extends WriteFileTool {

    public WriteFileAliasTool(Project project) {
        super(project);
    }

    @Override
    public @NotNull String id() {
        return "write_file";
    }
}
