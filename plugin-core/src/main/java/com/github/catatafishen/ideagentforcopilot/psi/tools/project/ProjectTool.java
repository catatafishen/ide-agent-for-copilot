package com.github.catatafishen.ideagentforcopilot.psi.tools.project;

import com.github.catatafishen.ideagentforcopilot.psi.tools.Tool;
import com.github.catatafishen.ideagentforcopilot.services.ToolRegistry;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

/**
 * Abstract base for project tools. Provides category and common constants.
 */
public abstract class ProjectTool extends Tool {

    protected ProjectTool(Project project) {
        super(project);
    }

    @Override
    public @NotNull ToolRegistry.Category category() {
        return ToolRegistry.Category.PROJECT;
    }
}
