package com.github.catatafishen.ideagentforcopilot.psi.tools.editor;

import com.github.catatafishen.ideagentforcopilot.psi.tools.Tool;
import com.github.catatafishen.ideagentforcopilot.services.ToolRegistry;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

/**
 * Abstract base for editor tools.
 */
public abstract class EditorTool extends Tool {

    protected EditorTool(Project project) {
        super(project);
    }

    @Override
    public @NotNull ToolRegistry.Category category() {
        return ToolRegistry.Category.EDITOR;
    }
}
