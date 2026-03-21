package com.github.catatafishen.ideagentforcopilot.acp.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;

import java.util.List;

/**
 * AWS Kiro ACP client.
 * <p>
 * Tool prefix: {@code Running: @agentbridge/read_file} → strip {@code Running: @agentbridge/}
 * MCP: reads from config file
 */
public final class KiroClient extends AcpClient {

    public KiroClient(Project project) {
        super(project);
    }

    @Override
    public String agentId() {
        return "kiro";
    }

    @Override
    public String displayName() {
        return "Kiro";
    }

    @Override
    protected List<String> buildCommand(String cwd, int mcpPort) {
        return List.of("kiro-cli", "acp");
    }

    @Override
    protected void customizeNewSession(String cwd, int mcpPort, JsonObject params) {
        // Kiro requires mcpServers in session/new params (field is mandatory)
        JsonObject server = buildMcpStdioServer("agentbridge", mcpPort);
        if (server == null) {
            throw new IllegalStateException("Cannot configure Kiro MCP server — Java binary or mcp-server.jar not found");
        }
        JsonArray servers = new JsonArray();
        servers.add(server);
        params.add("mcpServers", servers);
    }

    @Override
    protected String resolveToolId(String protocolTitle) {
        return protocolTitle.replaceFirst("^Running: @agentbridge/", "");
    }
}
