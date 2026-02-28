package com.github.copilot.intellij.services;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;

/**
 * Application-level service for managing the IDE Agent for Copilot plugin.
 * Singleton that lives for the entire IDE session.
 */
@Service(Service.Level.APP)
public final class AgenticCopilotService implements Disposable {
    private static final Logger LOG = Logger.getInstance(AgenticCopilotService.class);

    public AgenticCopilotService() {
        LOG.info("IDE Agent for Copilot Service initialized");
    }

    @Override
    public void dispose() {
        LOG.info("IDE Agent for Copilot Service disposed");
    }
}
