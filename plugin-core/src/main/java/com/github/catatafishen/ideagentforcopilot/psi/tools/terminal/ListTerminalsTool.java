package com.github.catatafishen.ideagentforcopilot.psi.tools.terminal;

import com.github.catatafishen.ideagentforcopilot.psi.TerminalTools;
import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import com.github.catatafishen.ideagentforcopilot.ui.renderers.IdeInfoRenderer;
import org.jetbrains.annotations.Nullable;

/**
 * Lists active terminal tabs.
 */
@SuppressWarnings("java:S112")
public final class ListTerminalsTool extends TerminalTool {

    public ListTerminalsTool(Project project, TerminalTools terminalTools) {
        super(project, terminalTools);
    }

    @Override public @NotNull String id() { return "list_terminals"; }
    @Override public @NotNull String displayName() { return "List Terminals"; }
    @Override public @NotNull String description() { return "List active terminal tabs"; }
    @Override public boolean isReadOnly() { return true; }

    @Override
    public @Nullable String execute(@NotNull JsonObject args) throws Exception {
        return terminalTools.listTerminals();
    }

    @Override
    public @NotNull Object resultRenderer() {
        return IdeInfoRenderer.INSTANCE;
    }
}
