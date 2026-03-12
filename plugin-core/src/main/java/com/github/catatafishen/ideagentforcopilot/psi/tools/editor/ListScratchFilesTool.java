package com.github.catatafishen.ideagentforcopilot.psi.tools.editor;

import com.github.catatafishen.ideagentforcopilot.psi.EditorTools;
import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;
import com.github.catatafishen.ideagentforcopilot.ui.renderers.IdeInfoRenderer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Lists existing scratch files in the IDE scratch directory.
 */
@SuppressWarnings("java:S112")
public final class ListScratchFilesTool extends EditorTool {

    public ListScratchFilesTool(Project project, EditorTools editorTools) {
        super(project, editorTools);
    }

    @Override public @NotNull String id() { return "list_scratch_files"; }
    @Override public @NotNull String displayName() { return "List Scratch Files"; }
    @Override public @NotNull String description() { return "List existing scratch files in the IDE scratch directory"; }
    @Override public boolean isReadOnly() { return true; }

    @Override
    public @NotNull Object resultRenderer() {
        return IdeInfoRenderer.INSTANCE;
    }

    @Override
    public @Nullable String execute(@NotNull JsonObject args) throws Exception {
        return editorTools.listScratchFiles(args);
    }
}
