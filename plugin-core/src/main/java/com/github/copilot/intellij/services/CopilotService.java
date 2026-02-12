package com.github.copilot.intellij.services;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.github.copilot.intellij.bridge.CopilotAcpClient;
import org.jetbrains.annotations.NotNull;

/**
 * Service for managing the Copilot ACP client lifecycle.
 * Starts the copilot --acp process on first use and stops it on IDE shutdown.
 */
@Service(Service.Level.APP)
public final class CopilotService implements Disposable {
    private static final Logger LOG = Logger.getInstance(CopilotService.class);

    private CopilotAcpClient acpClient;
    private volatile boolean started = false;

    public CopilotService() {
        LOG.info("Copilot ACP Service initialized");
    }

    @NotNull
    public static CopilotService getInstance() {
        return ApplicationManager.getApplication().getService(CopilotService.class);
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
            LOG.info("Starting Copilot ACP client...");
            if (acpClient != null) {
                acpClient.close();
            }
            acpClient = new CopilotAcpClient();
            acpClient.start();
            started = true;
            LOG.info("Copilot ACP client started");

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
     * Check if the ACP client is running and healthy.
     */
    public boolean isHealthy() {
        return started && acpClient != null && acpClient.isHealthy();
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

    @Override
    public void dispose() {
        LOG.info("Copilot ACP Service disposed");
        stop();
    }
}
