package com.github.catatafishen.ideagentforcopilot.psi.tools.terminal;

import com.github.catatafishen.ideagentforcopilot.psi.tools.Tool;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * Factory that creates all terminal tool instances.
 */
public final class TerminalToolFactory {

    private TerminalToolFactory() {
    }

    public static @NotNull List<Tool> create(@NotNull Project project) {
        return List.of(
            new RunInTerminalTool(project),
            new WriteTerminalInputTool(project),
            new ReadTerminalOutputTool(project),
            new ListTerminalsTool(project)
        );
    }
}
