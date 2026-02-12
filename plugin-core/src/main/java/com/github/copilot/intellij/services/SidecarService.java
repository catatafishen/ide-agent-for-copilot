package com.github.copilot.intellij.services;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.github.copilot.intellij.bridge.SidecarProcess;
import com.github.copilot.intellij.bridge.SidecarClient;
import org.jetbrains.annotations.NotNull;

/**
 * Service for managing the lifecycle of the Go sidecar process.
 * Ensures the sidecar starts when needed and stops cleanly on IDE shutdown.
 */
@Service(Service.Level.APP)
public final class SidecarService implements Disposable {
    private static final Logger LOG = Logger.getInstance(SidecarService.class);

    private SidecarProcess sidecarProcess;
    private SidecarClient sidecarClient;
    private volatile boolean started = false;

    public SidecarService() {
        LOG.info("Sidecar Service initialized");
    }

    @NotNull
    public static SidecarService getInstance() {
        return ApplicationManager.getApplication().getService(SidecarService.class);
    }

    /**
     * Start the sidecar process if not already running.
     * This is called lazily on first use.
     */
    public synchronized void start() {
        if (started) {
            LOG.debug("Sidecar already started");
            return;
        }

        try {
            LOG.info("Starting sidecar process...");
            sidecarProcess = new SidecarProcess();
            sidecarProcess.start();

            int port = sidecarProcess.getPort();
            LOG.info("Sidecar started on port " + port);

            sidecarClient = new SidecarClient("http://localhost:" + port);
            started = true;

        } catch (Exception e) {
            LOG.error("Failed to start sidecar", e);
            throw new RuntimeException("Failed to start Copilot sidecar", e);
        }
    }

    /**
     * Get the sidecar client for making RPC calls.
     * Starts the sidecar if not already running.
     */
    @NotNull
    public SidecarClient getClient() {
        if (!started) {
            start();
        }
        return sidecarClient;
    }

    /**
     * Check if the sidecar is running and healthy.
     */
    public boolean isHealthy() {
        if (!started || sidecarClient == null) {
            return false;
        }
        try {
            return sidecarClient.healthCheck();
        } catch (Exception e) {
            LOG.warn("Health check failed", e);
            return false;
        }
    }

    /**
     * Stop the sidecar process.
     */
    public synchronized void stop() {
        if (!started) {
            return;
        }

        try {
            LOG.info("Stopping sidecar process...");
            if (sidecarProcess != null) {
                sidecarProcess.stop();
            }
            started = false;
            sidecarClient = null;
        } catch (Exception e) {
            LOG.error("Failed to stop sidecar", e);
        }
    }

    @Override
    public void dispose() {
        LOG.info("Sidecar Service disposed");
        stop();
    }
}
