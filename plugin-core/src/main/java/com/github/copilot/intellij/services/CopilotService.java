package com.github.copilot.intellij.services;

import com.github.copilot.intellij.bridge.CopilotAcpClient;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

/**
 * Service for managing the Copilot ACP client lifecycle.
 * Starts the copilot --acp process on first use and stops it on IDE shutdown.
 * Each project has its own instance with session state in .agent-work/
 */
@Service(Service.Level.PROJECT)
@SuppressWarnings("java:S112") // RuntimeException wraps startup failures for service initialization
public final class CopilotService implements Disposable {
    private static final Logger LOG = Logger.getInstance(CopilotService.class);

    private final Project project;
    private CopilotAcpClient acpClient;
    private volatile boolean started = false;

    public CopilotService(Project project) {
        this.project = project;
        LOG.info("Copilot ACP Service initialized for project: " + project.getName());
    }

    @NotNull
    public static CopilotService getInstance(@NotNull Project project) {
        return project.getService(CopilotService.class);
    }

    /**
     * Start the Copilot ACP process if not already running.
     */
    public synchronized void start() {
        if (started && acpClient != null && acpClient.isHealthy()) {
            LOG.debug("ACP client already running");
            return;
        }

        try {
            LOG.info("Starting Copilot ACP client for project: " + project.getName());
            if (acpClient != null) {
                acpClient.close();
            }

            String projectPath = project.getBasePath();
            acpClient = new CopilotAcpClient(projectPath);
            acpClient.start();
            started = true;
            LOG.info("Copilot ACP client started with config-dir: " + projectPath + "/.agent-work");

        } catch (Exception e) {
            LOG.error("Failed to start Copilot ACP client", e);
            throw new RuntimeException("Failed to start Copilot ACP client", e);
        }
    }

    /**
     * Get the ACP client for making calls.
     * Starts the client if not already running.
     */
    @NotNull
    public CopilotAcpClient getClient() {
        if (!started || acpClient == null || !acpClient.isHealthy()) {
            start();
        }
        return acpClient;
    }

    /**
     * Stop the ACP client.
     */
    public synchronized void stop() {
        if (!started) {
            return;
        }

        try {
            LOG.info("Stopping Copilot ACP client...");
            if (acpClient != null) {
                acpClient.close();
            }
            started = false;
            acpClient = null;
        } catch (Exception e) {
            LOG.error("Failed to stop ACP client", e);
        }
    }

    /**
     * Restart the CLI process so it picks up the new model from CopilotSettings.
     * The --model flag is read at process startup by buildAcpCommand().
     * <p>
     * TODO: Replace with session/set_config_option once Copilot CLI supports it.
     *  See <a href="https://github.com/github/copilot-cli/issues/1485">#1485</a>
     */
    public synchronized void restartWithModel(@NotNull String modelId) {
        LOG.info("Restarting Copilot ACP client with model: " + modelId);
        stop();
        start();
    }

    @Override
    public void dispose() {
        LOG.info("Copilot ACP Service disposed");
        stop();
    }
}
