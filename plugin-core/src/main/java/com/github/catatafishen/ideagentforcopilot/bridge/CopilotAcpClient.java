package com.github.catatafishen.ideagentforcopilot.bridge;

import com.github.catatafishen.ideagentforcopilot.services.ToolRegistry;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * ACP client for GitHub Copilot.
 *
 * <p>Extends the generic {@link AcpClient} with Copilot-specific behaviour:
 * <ul>
 *   <li>Premium-request multipliers — Copilot charges by model tier (e.g. "1x", "2x", "10x")
 *       rather than by token count. {@link #supportsMultiplier()} returns {@code true} so
 *       the UI shows the multiplier chip instead of token/cost figures.</li>
 *   <li>{@link #getModelMultiplier(String)} resolves the multiplier label from the model list
 *       returned during session creation.</li>
 * </ul>
 *
 * <p>Everything else (binary discovery, MCP injection, permission handling, auth) is driven
 * by the {@link AgentConfig} / {@link AgentSettings} passed at construction time — the same
 * as the generic {@link AcpClient}.</p>
 */
public class CopilotAcpClient extends AcpClient {

    public CopilotAcpClient(@NotNull AgentConfig config,
                             @NotNull AgentSettings settings,
                             @Nullable ToolRegistry registry,
                             @Nullable String projectBasePath,
                             int mcpPort) {
        super(config, settings, registry, projectBasePath, mcpPort);
    }

    /**
     * Copilot bills by premium-request multiplier, not by token count.
     */
    @Override
    public boolean supportsMultiplier() {
        return true;
    }

    /**
     * Returns the multiplier label for the given model (e.g. {@code "1x"}, {@code "2x"}).
     * Looks up the model in the list returned at session creation and reads its {@code usage}
     * field, which is populated from the {@code copilotUsage} metadata field in the ACP
     * {@code session/new} response. Falls back to {@code "1x"} when the model is not found
     * or has no usage metadata.
     */
    @Override
    @NotNull
    public String getModelMultiplier(@NotNull String modelId) {
        if (availableModels == null) return "1x";
        for (Model model : availableModels) {
            if (modelId.equals(model.getId())) {
                String usage = model.getUsage();
                return (usage != null && !usage.isEmpty()) ? usage : "1x";
            }
        }
        return "1x";
    }
}
