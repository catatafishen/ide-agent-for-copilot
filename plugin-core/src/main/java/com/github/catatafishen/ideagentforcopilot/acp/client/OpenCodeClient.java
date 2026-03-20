package com.github.catatafishen.ideagentforcopilot.acp.client;

import com.github.catatafishen.ideagentforcopilot.acp.model.SessionUpdate;
import com.github.catatafishen.ideagentforcopilot.permissions.PermissionDefaults;
import com.intellij.openapi.project.Project;

import java.util.List;
import java.util.Map;

/**
 * OpenCode ACP client.
 * <p>
 * Tool prefix: {@code agentbridge_read_file} → strip {@code agentbridge_}
 * MCP: configured via {@code OPENCODE_CONFIG_CONTENT} env var
 * References: requires inline (no ACP resource blocks)
 * Special: deferred tool call pattern (tool_call without args, then tool_call_update with args → merge)
 */
public final class OpenCodeClient extends AcpClient {

    public OpenCodeClient(Project project) {
        super(project);
    }

    @Override
    public String agentId() {
        return "opencode";
    }

    @Override
    public String displayName() {
        return "OpenCode";
    }

    @Override
    protected List<String> buildCommand(String cwd, int mcpPort) {
        return List.of("opencode", "acp");
    }

    @Override
    protected Map<String, String> buildEnvironment(int mcpPort) {
        return Map.of("OPENCODE_CONFIG_CONTENT", buildConfigJson(mcpPort));
    }

    @Override
    protected String resolveToolId(String protocolTitle) {
        return protocolTitle.replaceFirst("^agentbridge_", "");
    }

    @Override
    public boolean requiresInlineReferences() {
        return true;
    }

    @Override
    protected SessionUpdate processUpdate(SessionUpdate update) {
        // TODO: Wire up deferred tool call merging
        return update;
    }

    @Override
    protected PermissionDefaults permissionDefaults() {
        return PermissionDefaults.PERMISSIVE;
    }

    private String buildConfigJson(int mcpPort) {
        return "{\"mcp\":{\"agentbridge\":{\"url\":\"http://127.0.0.1:" + mcpPort + "\"}}}";
    }
}
