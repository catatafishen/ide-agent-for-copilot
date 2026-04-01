package com.github.catatafishen.ideagentforcopilot.settings;

import com.github.catatafishen.ideagentforcopilot.acp.client.AcpClient;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * {@link AgentBinaryResolver} for ACP-protocol clients (Copilot, Kiro, Junie, OpenCode, Codex).
 *
 * <p>Reads the user's custom binary path from {@link AcpClient#loadCustomBinaryPath(String)},
 * which is stored in the application-level {@code PropertiesComponent} under the key
 * {@code agentbridge.<agentId>.customBinary}.
 */
public class AcpClientBinaryResolver extends AgentBinaryResolver {

    private final String agentId;
    private final String binaryName;
    private final String[] alternates;

    /**
     * @param agentId    the agent identifier (e.g. {@code "copilot"}) — used to look up the
     *                   custom binary path in settings
     * @param binaryName the primary binary name for auto-detection (e.g. {@code "copilot"})
     * @param alternates additional names to try when the primary is not found
     */
    public AcpClientBinaryResolver(@NotNull String agentId,
                                    @NotNull String binaryName,
                                    @NotNull String... alternates) {
        this.agentId = agentId;
        this.binaryName = binaryName;
        this.alternates = alternates;
    }

    @Override
    @Nullable
    protected String customBinaryPath() {
        return AcpClient.loadCustomBinaryPath(agentId);
    }

    @Override
    @NotNull
    protected String primaryBinaryName() {
        return binaryName;
    }

    @Override
    @NotNull
    protected String[] alternateNames() {
        return alternates;
    }
}
