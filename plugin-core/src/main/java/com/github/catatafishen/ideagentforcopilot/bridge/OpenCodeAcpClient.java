package com.github.catatafishen.ideagentforcopilot.bridge;

import com.github.catatafishen.ideagentforcopilot.services.AgentProfile;
import com.github.catatafishen.ideagentforcopilot.services.McpInjectionMethod;
import com.github.catatafishen.ideagentforcopilot.services.PermissionInjectionMethod;
import com.github.catatafishen.ideagentforcopilot.services.ToolRegistry;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

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

    public static final String PROFILE_ID = "opencode";

    @NotNull
    public static AgentProfile createDefaultProfile() {
        AgentProfile p = new AgentProfile();
        p.setId(PROFILE_ID);
        p.setDisplayName("OpenCode");
        p.setBuiltIn(true);
        p.setExperimental(true);
        p.setDescription("Experimental profile — OpenCode ACP support is community-maintained. "
            + "Install: npm i -g opencode-ai");
        p.setBinaryName(PROFILE_ID);
        p.setInstallHint("Install with: npm i -g opencode-ai");
        p.setAcpArgs(List.of("acp"));
        p.setMcpMethod(McpInjectionMethod.ENV_VAR);
        p.setMcpEnvVarName("OPENCODE_CONFIG_CONTENT");
        p.setMcpConfigTemplate(
            "{\"mcp\":{\"intellij-code-tools\":"
                + "{\"type\":\"local\","
                + "\"command\":[\"{javaPath}\",\"-jar\",\"{mcpJarPath}\","
                + "\"--port\",\"{mcpPort}\"]}}}");
        p.setSupportsMcpConfigFlag(false);
        p.setSupportsModelFlag(false);
        p.setSupportsConfigDir(false);
        p.setRequiresResourceDuplication(false);
        p.setExcludeAgentBuiltInTools(true);
        p.setUsePluginPermissions(false);
        p.setPermissionInjectionMethod(PermissionInjectionMethod.CONFIG_JSON);
        return p;
    }

    public OpenCodeAcpClient(@NotNull AgentConfig config,
                              @org.jetbrains.annotations.NotNull AgentSettings settings,
                              @Nullable ToolRegistry registry,
                              @Nullable String projectBasePath,
                              int mcpPort) {
        super(config, settings, registry, projectBasePath, mcpPort);
    }
}
