package com.github.copilot.intellij.services;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.annotations.NotNull;

/**
 * Application-level service for managing the Agentic Copilot plugin.
 * Singleton that lives for the entire IDE session.
 */
@Service(Service.Level.APP)
public final class AgenticCopilotService implements Disposable {
    private static final Logger LOG = Logger.getInstance(AgenticCopilotService.class);

    public AgenticCopilotService() {
        LOG.info("Agentic Copilot Service initialized");
    }

    @NotNull
    public static AgenticCopilotService getInstance() {
        return ApplicationManager.getApplication().getService(AgenticCopilotService.class);
    }

    /**
     * Get the sidecar service for managing the Go sidecar process.
     */
    @NotNull
    public SidecarService getSidecarService() {
        return SidecarService.getInstance();
    }

    @Override
    public void dispose() {
        LOG.info("Agentic Copilot Service disposed");
    }
}
