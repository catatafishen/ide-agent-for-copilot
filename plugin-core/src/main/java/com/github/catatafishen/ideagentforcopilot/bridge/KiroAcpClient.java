package com.github.catatafishen.ideagentforcopilot.bridge;

import com.github.catatafishen.ideagentforcopilot.services.AgentProfile;
import com.github.catatafishen.ideagentforcopilot.services.McpInjectionMethod;
import com.github.catatafishen.ideagentforcopilot.services.PermissionInjectionMethod;
import com.github.catatafishen.ideagentforcopilot.services.ToolRegistry;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

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
        p.setMcpConfigTemplate(
            "{\"mcpServers\":["
                + "{\"command\":\"{javaPath}\","
                + "\"args\":[\"-jar\",\"{mcpJarPath}\",\"--port\",\"{mcpPort}\"],"
                + "\"env\":{}}"
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
}
