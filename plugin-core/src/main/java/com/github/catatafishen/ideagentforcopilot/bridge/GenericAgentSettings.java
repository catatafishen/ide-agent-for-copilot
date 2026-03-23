package com.github.catatafishen.ideagentforcopilot.bridge;

import com.github.catatafishen.ideagentforcopilot.services.ActiveAgentManager;
import com.github.catatafishen.ideagentforcopilot.services.GenericSettings;
import com.github.catatafishen.ideagentforcopilot.services.ToolPermission;
import com.github.catatafishen.ideagentforcopilot.services.ToolRegistry;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public final class GenericAgentSettings implements AgentSettings {

    private final GenericSettings settings;
    private final Project project;

    public GenericAgentSettings(@NotNull GenericSettings settings, @Nullable Project project) {
        this.settings = settings;
        this.project = project;
    }

    @Override
    public boolean isAutoApprovePermissions() {
        if (project == null) return false;
        var profile = ActiveAgentManager.getInstance(project).getActiveProfile();
        return !profile.isUsePluginPermissions();
    }

    @Override
    public int getPromptTimeout() {
        return settings.getPromptTimeout();
    }

    @Override
    public int getMaxToolCallsPerTurn() {
        return settings.getMaxToolCallsPerTurn();
    }

    @Override
    public @NotNull ToolPermission resolveEffectivePermission(@NotNull String toolId, boolean insideProject) {
        if (project == null) return settings.getToolPermission(toolId);
        return settings.resolveEffectivePermission(toolId, insideProject, ToolRegistry.getInstance(project));
    }

    @Override
    public @NotNull ToolPermission getToolPermission(@NotNull String toolId) {
        return settings.getToolPermission(toolId);
    }

    @Override
    public void setActiveAgentLabel(@Nullable String label) {
        settings.setActiveAgentLabel(label);
    }
}
