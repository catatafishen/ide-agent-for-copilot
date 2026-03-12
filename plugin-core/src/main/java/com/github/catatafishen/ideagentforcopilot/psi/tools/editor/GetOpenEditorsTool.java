package com.github.catatafishen.ideagentforcopilot.psi.tools.editor;

import com.github.catatafishen.ideagentforcopilot.psi.EditorTools;
import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;
import com.github.catatafishen.ideagentforcopilot.ui.renderers.IdeInfoRenderer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Lists all currently open editor tabs.
 */
@SuppressWarnings("java:S112")
public final class GetOpenEditorsTool extends EditorTool {

    public GetOpenEditorsTool(Project project, EditorTools editorTools) {
        super(project, editorTools);
    }

    @Override public @NotNull String id() { return "get_open_editors"; }
    @Override public @NotNull String displayName() { return "Get Open Editors"; }
    @Override public @NotNull String description() { return "List all currently open editor tabs"; }
    @Override public boolean isReadOnly() { return true; }

    @Override
    public @NotNull Object resultRenderer() {
        return IdeInfoRenderer.INSTANCE;
    }

    @Override
    public @Nullable String execute(@NotNull JsonObject args) throws Exception {
        return editorTools.getOpenEditors(args);
    }
}
