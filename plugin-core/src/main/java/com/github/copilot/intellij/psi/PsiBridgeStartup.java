package com.github.copilot.intellij.psi;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.startup.StartupActivity;
import org.jetbrains.annotations.NotNull;

/**
 * Starts the PSI Bridge HTTP server when a project opens.
 * This ensures the bridge is available before any MCP tool calls arrive.
 */
public class PsiBridgeStartup implements StartupActivity.DumbAware {
    private static final Logger LOG = Logger.getInstance(PsiBridgeStartup.class);

    @Override
    public void runActivity(@NotNull Project project) {
        LOG.info("Starting PSI Bridge for project: " + project.getName());
        PsiBridgeService.getInstance(project).start();
    }
}
