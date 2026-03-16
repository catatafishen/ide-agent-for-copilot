package com.github.catatafishen.ideagentforcopilot.bridge;

import com.github.catatafishen.ideagentforcopilot.services.ToolRegistry;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * A concrete implementation of {@link AcpClient} used for generic agent profiles
 * that don't require specialized tool name normalization or process customization.
 */
public class DefaultAcpClient extends AcpClient {

    public DefaultAcpClient(@NotNull AgentConfig config,
                            @NotNull AgentSettings settings,
                            @Nullable ToolRegistry registry,
                            @Nullable String projectBasePath,
                            int mcpPort) {
        super(config, settings, registry, projectBasePath, mcpPort);
    }

    @Override
    @NotNull
    public String normalizeToolName(@NotNull String name) {
        // Fallback to simple slash-based stripping for generic ACP clients
        return name.replaceFirst("^[^/]+/", "");
    }
}
