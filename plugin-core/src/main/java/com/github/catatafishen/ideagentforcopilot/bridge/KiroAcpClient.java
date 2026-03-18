package com.github.catatafishen.ideagentforcopilot.bridge;

import com.github.catatafishen.ideagentforcopilot.services.AgentProfile;
import com.github.catatafishen.ideagentforcopilot.services.McpInjectionMethod;
import com.github.catatafishen.ideagentforcopilot.services.PermissionInjectionMethod;
import com.github.catatafishen.ideagentforcopilot.services.ToolRegistry;
import com.google.gson.JsonObject;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ACP client for Kiro.
 */
public class KiroAcpClient extends AcpClient {

    public static final String PROFILE_ID = "kiro";

    @NotNull
    public static AgentProfile createDefaultProfile() {
        AgentProfile p = new AgentProfile();
        p.setId(PROFILE_ID);
        p.setDisplayName("Kiro");
        p.setBuiltIn(true);
        p.setExperimental(true);
        p.setTransportType(TransportType.ACP);
        p.setDescription("Kiro CLI — experimental support. Ensure 'kiro-cli' is in your PATH.");
        p.setBinaryName("kiro-cli");
        p.setAlternateNames(List.of("kiro"));
        p.setInstallHint("Install Kiro CLI and ensure it's available on your PATH.");
        p.setInstallUrl("https://kiro.dev/docs/cli/acp/");
        p.setSupportsOAuthSignIn(false);
        p.setAcpArgs(List.of("acp"));

        // Kiro doesn't support MCP config flags, only SESSION_NEW injection
        // mcpCapabilities shows {"http": false, "sse": false} - command-based only
        p.setMcpMethod(McpInjectionMethod.SESSION_NEW);
        // ACP spec requires: name (required), command, args, env (as array, not object!)
        p.setMcpConfigTemplate(
            "{\"mcpServers\":["
                + "{\"name\":\"intellij-code-tools\","
                + "\"command\":\"{javaPath}\","
                + "\"args\":[\"-jar\",\"{mcpJarPath}\",\"--port\",\"{mcpPort}\"],"
                + "\"env\":[]}"
                + "]}");

        p.setSupportsMcpConfigFlag(false);
        p.setSupportsModelFlag(true);
        p.setSupportsConfigDir(false);
        p.setRequiresResourceDuplication(false);
        p.setExcludeAgentBuiltInTools(false);
        p.setUsePluginPermissions(true);
        p.setPermissionInjectionMethod(PermissionInjectionMethod.NONE);
        p.setSupportsSessionMessage(true);
        return p;
    }

    public KiroAcpClient(@NotNull AgentConfig config,
                         @NotNull AgentSettings settings,
                         @Nullable ToolRegistry registry,
                         @Nullable String projectBasePath,
                         int mcpPort) {
        super(config, settings, registry, projectBasePath, mcpPort);
    }

    @Override
    @NotNull
    public String normalizeToolName(@NotNull String name) {
        String slashPrefix = effectiveMcpPrefix.endsWith("-")
            ? effectiveMcpPrefix.substring(0, effectiveMcpPrefix.length() - 1) + "/"
            : effectiveMcpPrefix + "/";
        if (name.startsWith(slashPrefix)) {
            return name.substring(slashPrefix.length());
        }
        return name;
    }

    @Override
    @NotNull
    public List<com.github.catatafishen.ideagentforcopilot.settings.ProjectFilesSettings.FileEntry>
    getDefaultProjectFiles() {
        List<com.github.catatafishen.ideagentforcopilot.settings.ProjectFilesSettings.FileEntry> entries = new ArrayList<>();
        entries.add(new com.github.catatafishen.ideagentforcopilot.settings.ProjectFilesSettings.FileEntry(
            "Settings", ".agent-work/kiro/settings/cli.json", false, "Kiro"));
        entries.add(new com.github.catatafishen.ideagentforcopilot.settings.ProjectFilesSettings.FileEntry(
            "Steering", ".agent-work/kiro/steering/*.md", true, "Kiro"));
        entries.add(new com.github.catatafishen.ideagentforcopilot.settings.ProjectFilesSettings.FileEntry(
            "Agents", ".agent-work/kiro/agents/*.md", true, "Kiro"));
        return entries;
    }

    /**
     * Kiro-specific error response handling.
     * Kiro error data format: "Encountered an error in the response stream: request_id: [uuid], error: [error message]"
     * This extracts the request_id and error type for better diagnostics.
     */
    @Override
    protected void handleErrorResponse(JsonObject msg, long id, CompletableFuture<JsonObject> future) {
        // Let the parent do the initial processing
        super.handleErrorResponse(msg, id, future);

        // Additionally parse Kiro-specific error format from the errorData
        if (msg.has("error")) {
            JsonObject error = msg.getAsJsonObject("error");
            if (error.has("data") && error.get("data").isJsonPrimitive()) {
                String errorData = error.get("data").getAsString();
                KiroErrorContext kiroError = parseKiroErrorData(errorData);
                if (kiroError != null) {
                    // Log the parsed Kiro error details for better debugging
                    LOG.info("Kiro error details - Request ID: " + kiroError.requestId +
                        ", Error type: " + kiroError.errorType);
                }
            }
        }
    }

    /**
     * Parses Kiro-specific error format.
     * Expected format: "Encountered an error in the response stream: request_id: [uuid], error: [error message]"
     */
    @Nullable
    private KiroErrorContext parseKiroErrorData(@NotNull String errorData) {
        try {
            // Extract request_id
            Pattern requestIdPattern = Pattern.compile("request_id:\\s*([a-f0-9\\-]+)");
            Matcher requestIdMatcher = requestIdPattern.matcher(errorData);
            String requestId = requestIdMatcher.find() ? requestIdMatcher.group(1) : null;

            // Extract error type (e.g., "AccessDeniedException")
            Pattern errorTypePattern = Pattern.compile("error:\\s*[^:]*:\\s*(\\w+(?:Exception|Error)?)");
            Matcher errorTypeMatcher = errorTypePattern.matcher(errorData);
            String errorType = errorTypeMatcher.find() ? errorTypeMatcher.group(1) : null;

            if (requestId != null || errorType != null) {
                return new KiroErrorContext(requestId, errorType);
            }
        } catch (Exception e) {
            LOG.debug("Failed to parse Kiro error data", e);
        }
        return null;
    }

    /**
     * Container for parsed Kiro error information.
     */
    private static class KiroErrorContext {
        final String requestId;
        final String errorType;

        KiroErrorContext(String requestId, String errorType) {
            this.requestId = requestId;
            this.errorType = errorType;
        }
    }
}
