package com.github.copilot.intellij.psi;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.startup.StartupActivity;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Starts the PSI Bridge HTTP server when a project opens.
 * This ensures the bridge is available before any MCP tool calls arrive.
 */
public class PsiBridgeStartup implements StartupActivity.DumbAware {
    private static final Logger LOG = Logger.getInstance(PsiBridgeStartup.class);

    @Override
    public void runActivity(@NotNull Project project) {
        LOG.info("Starting PSI Bridge for project: " + project.getName());
        
        // Create agent workspace directory structure
        createAgentWorkspace(project);
        
        PsiBridgeService.getInstance(project).start();
    }

    /**
     * Creates .agent-work/ directory structure for agent session state.
     * This directory is typically gitignored and provides a safe workspace
     * for the agent to store plans, checkpoints, and analysis files.
     */
    private void createAgentWorkspace(@NotNull Project project) {
        if (project.getBasePath() == null) {
            return;
        }

        try {
            Path agentWork = Path.of(project.getBasePath(), ".agent-work");
            Path checkpoints = agentWork.resolve("checkpoints");
            Path files = agentWork.resolve("files");

            Files.createDirectories(checkpoints);
            Files.createDirectories(files);

            // Create plan.md if it doesn't exist
            Path planFile = agentWork.resolve("plan.md");
            if (!Files.exists(planFile)) {
                Files.writeString(planFile, "# Agent Work Plan\n\nThis file is used by the agent to track tasks and progress.\n");
            }

            LOG.info("Agent workspace initialized at: " + agentWork);
        } catch (IOException e) {
            LOG.warn("Failed to create agent workspace", e);
        }
    }
}
