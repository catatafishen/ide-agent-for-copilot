package com.github.catatafishen.ideagentforcopilot.bridge;

import com.github.catatafishen.ideagentforcopilot.services.AgentProfile;
import com.github.catatafishen.ideagentforcopilot.services.McpInjectionMethod;
import com.github.catatafishen.ideagentforcopilot.services.PermissionInjectionMethod;
import com.github.catatafishen.ideagentforcopilot.services.ToolExecutionCorrelator;
import com.github.catatafishen.ideagentforcopilot.services.ToolRegistry;
import com.google.gson.JsonObject;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Junie-specific ACP client.
 *
 * <h2>Junie's Tool Update Protocol</h2>
 * Junie sends tool_call_update events with:
 * <ul>
 *   <li><b>IN_PROGRESS:</b> Tool arguments as JSON (e.g., {"paths": [...]}) - not useful to display</li>
 *   <li><b>COMPLETED:</b> Natural language summary only ("Two files staged") - no raw output</li>
 * </ul>
 *
 * <p>Junie never forwards the raw MCP tool results (diffs, file contents, etc.).
 * It converts everything to natural language summaries for the user.
 *
 * <h2>Correlation Strategy</h2>
 * To show both raw results AND summaries:
 * <ol>
 *   <li>When MCP tool executes, {@link ToolExecutionCorrelator} records the raw result</li>
 *   <li>When Junie sends COMPLETED update, we look up the matching execution</li>
 *   <li>Combine: raw result (for technical accuracy) + summary (for context)</li>
 *   <li>Fallback: if no match found, just show summary as description</li>
 * </ol>
 *
 * <p>See {@link ToolExecutionCorrelator} for matching strategy details.
 */
public class JunieAcpClient extends AcpClient {
    private static final com.intellij.openapi.diagnostic.Logger LOG =
        com.intellij.openapi.diagnostic.Logger.getInstance(JunieAcpClient.class);

    public static final String PROFILE_ID = "junie";

    @NotNull
    public static AgentProfile createDefaultProfile() {
        AgentProfile p = new AgentProfile();
        p.setId(PROFILE_ID);
        p.setDisplayName("Junie");
        p.setBuiltIn(true);
        p.setExperimental(false);
        p.setTransportType(TransportType.ACP);
        p.setDescription("""
            Junie CLI by JetBrains. Connects via ACP (--acp true). \
            Authenticate with your JetBrains Account or a JUNIE_API_KEY token. \
            Install from junie.jetbrains.com and run 'junie' once to authenticate.""");
        p.setBinaryName(PROFILE_ID);
        p.setAlternateNames(List.of());
        p.setInstallHint("Install from junie.jetbrains.com and run 'junie' to authenticate.");
        p.setInstallUrl("https://junie.jetbrains.com/docs/junie-cli.html");
        p.setSupportsOAuthSignIn(false);
        p.setAcpArgs(List.of("--acp=true"));
        p.setMcpMethod(McpInjectionMethod.MCP_LOCATION_FLAG);
        p.setSupportsMcpConfigFlag(false);
        p.setMcpConfigTemplate(
            "{\"mcpServers\":{\"intellij-code-tools\":"
                + "{\"type\":\"stdio\","
                + "\"command\":\"{javaPath}\","
                + "\"args\":[\"-jar\",\"{mcpJarPath}\",\"--port\",\"{mcpPort}\"]}}}");
        p.setSupportsModelFlag(true);
        p.setSupportsConfigDir(false);
        p.setRequiresResourceDuplication(true);
        p.setExcludeAgentBuiltInTools(true);
        p.setUsePluginPermissions(true);
        p.setPermissionInjectionMethod(PermissionInjectionMethod.NONE);
        p.setPrependInstructionsTo("");
        return p;
    }

    public JunieAcpClient(@NotNull AgentConfig config,
                          @NotNull AgentSettings settings,
                          @Nullable ToolRegistry registry,
                          @Nullable String projectBasePath,
                          int mcpPort) {
        super(config, settings, registry, projectBasePath, mcpPort);
    }

    @Override
    @NotNull
    public String normalizeToolName(@NotNull String name) {
        return name.trim()
            .replaceFirst("Tool: intellij-code-tools/", "")
            .replaceFirst("Tool: ", "");
    }

    @NotNull
    @Override
    protected SessionUpdate.ToolCallUpdate buildToolCallUpdateEvent(@NotNull JsonObject update) {
        SessionUpdate.ToolCallUpdate base = super.buildToolCallUpdateEvent(update);
        String toolCallId = base.toolCallId();
        String naturalLanguageSummary = base.result();

        // IN_PROGRESS: Just tool arguments, not useful - return with null result to avoid showing JSON args in UI
        if (base.status() == SessionUpdate.ToolCallStatus.IN_PROGRESS) {
            return new SessionUpdate.ToolCallUpdate(toolCallId, base.status(), null, null, null);
        }

        // FAILED: Return as-is
        if (base.status() == SessionUpdate.ToolCallStatus.FAILED) {
            return base;
        }

        // COMPLETED: Try to correlate with actual MCP execution to get raw result
        if (naturalLanguageSummary != null && !naturalLanguageSummary.isEmpty()) {
            // Extract and normalize tool name from the update event
            String rawToolName = update.has("title") ? update.get("title").getAsString() : null;
            String normalizedToolName = rawToolName != null ? normalizeToolName(rawToolName) : null;

            // Extract arguments from the update event (if available)
            // Junie sometimes includes the arguments in the content during IN_PROGRESS,
            // but by COMPLETED they're not in the update. We'll try to match without them.
            JsonObject args = null; // TODO: extract if available in future Junie versions

            // Look up the matching tool execution
            if (normalizedToolName != null && projectBasePath != null) {
                try {
                    com.intellij.openapi.project.Project project = findProject(projectBasePath);
                    if (project != null) {
                        ToolExecutionCorrelator correlator = ToolExecutionCorrelator.getInstance(project);
                        String rawResult = correlator.consumeResult(normalizedToolName, args);

                        if (rawResult != null) {
                            // Success: we have both raw result and natural language summary
                            LOG.debug("Junie completed with correlation: tool=" + normalizedToolName
                                + ", rawLen=" + rawResult.length()
                                + ", descLen=" + naturalLanguageSummary.length());
                            return new SessionUpdate.ToolCallUpdate(
                                toolCallId,
                                base.status(),
                                rawResult,           // Raw tool output (diffs, file contents, etc.)
                                base.error(),
                                naturalLanguageSummary // Natural language explanation
                            );
                        } else {
                            // No match - log for debugging
                            LOG.debug("Junie completed without correlation: tool=" + normalizedToolName
                                + " (no matching execution found, using summary only)");
                        }
                    }
                } catch (Exception e) {
                    LOG.warn("Failed to correlate Junie tool result for " + normalizedToolName, e);
                }
            }

            // Fallback: Just use Junie's natural language summary as description
            LOG.debug("Junie completed (fallback): using summary only, len=" + naturalLanguageSummary.length());
            return new SessionUpdate.ToolCallUpdate(toolCallId, base.status(), null, base.error(), naturalLanguageSummary);
        }

        // Empty or null content - return as-is
        return base;
    }

    /**
     * Finds the Project instance for the given project base path.
     * Required to get the ToolExecutionCorrelator service instance.
     */
    @Nullable
    private com.intellij.openapi.project.Project findProject(@NotNull String projectBasePath) {
        for (com.intellij.openapi.project.Project project : com.intellij.openapi.project.ProjectManager.getInstance().getOpenProjects()) {
            if (project.getBasePath() != null && project.getBasePath().equals(projectBasePath)) {
                return project;
            }
        }
        return null;
    }
}
