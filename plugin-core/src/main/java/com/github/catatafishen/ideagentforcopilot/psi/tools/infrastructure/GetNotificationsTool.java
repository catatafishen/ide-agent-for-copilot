package com.github.catatafishen.ideagentforcopilot.psi.tools.infrastructure;

import com.github.catatafishen.ideagentforcopilot.psi.InfrastructureTools;
import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import com.github.catatafishen.ideagentforcopilot.ui.renderers.IdeInfoRenderer;
import org.jetbrains.annotations.Nullable;

/**
 * Gets recent IntelliJ balloon notifications.
 */
@SuppressWarnings("java:S112")
public final class GetNotificationsTool extends InfrastructureTool {

    public GetNotificationsTool(Project project, InfrastructureTools infraTools) {
        super(project, infraTools);
    }

    @Override public @NotNull String id() { return "get_notifications"; }
    @Override public @NotNull String displayName() { return "Get Notifications"; }
    @Override public @NotNull String description() { return "Get recent IntelliJ balloon notifications"; }
    @Override public boolean isReadOnly() { return true; }

    @Override
    public @Nullable String execute(@NotNull JsonObject args) throws Exception {
        return infraTools.getNotifications(args);
    }

    @Override
    public @NotNull Object resultRenderer() {
        return IdeInfoRenderer.INSTANCE;
    }
}
