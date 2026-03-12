package com.github.catatafishen.ideagentforcopilot.psi.tools.editor;

import com.github.catatafishen.ideagentforcopilot.psi.EditorTools;
import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;
import com.github.catatafishen.ideagentforcopilot.ui.renderers.IdeInfoRenderer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Lists all available IDE themes with their dark/light type.
 */
@SuppressWarnings("java:S112")
public final class ListThemesTool extends EditorTool {

    public ListThemesTool(Project project, EditorTools editorTools) {
        super(project, editorTools);
    }

    @Override public @NotNull String id() { return "list_themes"; }
    @Override public @NotNull String displayName() { return "List Themes"; }
    @Override public @NotNull String description() { return "List all available IDE themes with their dark/light type"; }
    @Override public boolean isReadOnly() { return true; }

    @Override
    public @NotNull Object resultRenderer() {
        return IdeInfoRenderer.INSTANCE;
    }

    @Override
    public @Nullable String execute(@NotNull JsonObject args) throws Exception {
        return editorTools.listThemes(args);
    }
}
