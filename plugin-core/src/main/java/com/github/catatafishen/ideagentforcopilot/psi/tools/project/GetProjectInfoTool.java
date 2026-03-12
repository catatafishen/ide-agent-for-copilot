package com.github.catatafishen.ideagentforcopilot.psi.tools.project;

import com.github.catatafishen.ideagentforcopilot.psi.ProjectTools;
import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;
import com.github.catatafishen.ideagentforcopilot.ui.renderers.ProjectInfoRenderer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Gets project name, SDK, modules, and overall structure.
 */
@SuppressWarnings("java:S112")
public final class GetProjectInfoTool extends ProjectTool {

    public GetProjectInfoTool(Project project, ProjectTools projectTools) {
        super(project, projectTools);
    }

    @Override public @NotNull String id() { return "get_project_info"; }
    @Override public @NotNull String displayName() { return "Get Project Info"; }
    @Override public @NotNull String description() { return "Get project name, SDK, modules, and overall structure"; }
    @Override public boolean isReadOnly() { return true; }

    @Override
    public @NotNull Object resultRenderer() {
        return ProjectInfoRenderer.INSTANCE;
    }

    @Override
    public @Nullable String execute(@NotNull JsonObject args) throws Exception {
        return projectTools.getProjectInfo(args);
    }
}
