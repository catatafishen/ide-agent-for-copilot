package com.github.catatafishen.ideagentforcopilot.bridge;

import com.github.catatafishen.ideagentforcopilot.services.ToolRegistry;
import org.jetbrains.annotations.Nullable;

/**
 * ACP client for OpenCode.
 *
 * <p>Extends the generic {@link AcpClient} for OpenCode-specific behaviour.
 * Currently all OpenCode-specific concerns (built-in tool exclusion, config-JSON permission
 * injection, env-var MCP injection) are handled by the {@link AgentConfig} strategy, so
 * no {@link AgentClient} method overrides are needed yet.</p>
 *
 * <p>This class exists as an explicit extension point: future OpenCode-specific
 * {@link AgentClient} overrides belong here rather than in the generic base.</p>
 */
public class OpenCodeAcpClient extends AcpClient {

    public OpenCodeAcpClient(@org.jetbrains.annotations.NotNull AgentConfig config,
                              @org.jetbrains.annotations.NotNull AgentSettings settings,
                              @Nullable ToolRegistry registry,
                              @Nullable String projectBasePath,
                              int mcpPort) {
        super(config, settings, registry, projectBasePath, mcpPort);
    }
}
