package com.github.catatafishen.ideagentforcopilot.psi.tools.editor;

import com.github.catatafishen.ideagentforcopilot.psi.EditorTools;
import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;
import com.github.catatafishen.ideagentforcopilot.ui.renderers.IdeInfoRenderer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Gets the path and content of the currently active editor file.
 */
@SuppressWarnings("java:S112")
public final class GetActiveFileTool extends EditorTool {

    public GetActiveFileTool(Project project, EditorTools editorTools) {
        super(project, editorTools);
    }

    @Override public @NotNull String id() { return "get_active_file"; }
    @Override public @NotNull String displayName() { return "Get Active File"; }
    @Override public @NotNull String description() { return "Get the path and content of the currently active editor file"; }
    @Override public boolean isReadOnly() { return true; }

    @Override
    public @NotNull Object resultRenderer() {
        return IdeInfoRenderer.INSTANCE;
    }

    @Override
    public @Nullable String execute(@NotNull JsonObject args) throws Exception {
        return editorTools.getActiveFile(args);
    }
}
