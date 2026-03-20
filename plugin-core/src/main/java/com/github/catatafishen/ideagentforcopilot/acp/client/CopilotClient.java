package com.github.catatafishen.ideagentforcopilot.acp.client;

import com.github.catatafishen.ideagentforcopilot.acp.model.Model;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * GitHub Copilot ACP client.
 * <p>
 * Command: {@code copilot --acp --stdio [--config-dir ...] [--additional-mcp-config @file]}
 * Tool prefix: {@code agentbridge-read_file} → strip {@code agentbridge-}
 * Model display: multiplier from {@code _meta.copilotUsage}
 * References: requires inline (no ACP resource blocks)
 * MCP: HTTP via {@code --additional-mcp-config} flag + session/new mcpServers
 */
public final class CopilotClient extends AcpClient {

    private static final Logger LOG = Logger.getInstance(CopilotClient.class);

    private static final String AGENT_ID = "copilot";
    private static final String MCP_SERVER_NAME = "agentbridge";
    private static final String MCP_TYPE_HTTP = "http";

    public CopilotClient(Project project) {
        super(project);
    }

    @Override
    public String agentId() {
        return AGENT_ID;
    }

    @Override
    public String displayName() {
        return "GitHub Copilot";
    }

    @Override
    protected List<String> buildCommand(String cwd, int mcpPort) {
        List<String> cmd = new ArrayList<>(List.of(AGENT_ID, "--acp", "--stdio"));

        // Use per-project config dir to avoid cross-project contamination
        String configDir = cwd + File.separator + ".agent-work" + File.separator + AGENT_ID;
        cmd.add("--config-dir");
        cmd.add(configDir);

        // Register our MCP HTTP server via a temp config file
        if (mcpPort > 0) {
            String configFile = writeMcpConfigFile(mcpPort);
            if (configFile != null) {
                cmd.add("--additional-mcp-config");
                cmd.add("@" + configFile);
            }
        }

        return cmd;
    }

    @Override
    protected Map<String, String> buildEnvironment(int mcpPort) {
        String basePath = project.getBasePath();
        if (basePath == null) return Map.of();
        // COPILOT_HOME points to the project-specific Copilot config directory
        String copilotHome = basePath + File.separator + ".agent-work" + File.separator + AGENT_ID;
        return Map.of("COPILOT_HOME", copilotHome);
    }

    @Override
    protected void customizeNewSession(String cwd, int mcpPort, JsonObject params) {
        if (mcpPort <= 0) return;
        // Copilot requires mcpServers in session/new as an array with headers as array (not object)
        JsonObject server = new JsonObject();
        server.addProperty("name", MCP_SERVER_NAME);
        server.addProperty("type", MCP_TYPE_HTTP);
        server.addProperty("url", "http://localhost:" + mcpPort + "/mcp");
        server.add("headers", new JsonArray()); // Copilot requires headers as empty array

        JsonArray servers = new JsonArray();
        servers.add(server);
        params.add("mcpServers", servers);
    }

    @Override
    protected String resolveToolId(String protocolTitle) {
        return protocolTitle.replaceFirst("^agentbridge-", "");
    }

    @Override
    public boolean requiresInlineReferences() {
        return true;
    }

    @Override
    public ModelDisplayMode modelDisplayMode() {
        return ModelDisplayMode.MULTIPLIER;
    }

    @Override
    public @Nullable String getModelMultiplier(Model model) {
        JsonObject meta = model._meta();
        if (meta != null && meta.has("copilotUsage")) {
            return meta.get("copilotUsage").getAsString();
        }
        return null;
    }

    /**
     * Write the MCP server config as a temp JSON file and return its path.
     * Returns null if writing fails (MCP will still work via session/new injection).
     */
    @Nullable
    private static String writeMcpConfigFile(int mcpPort) {
        String json = "{\"mcpServers\":{\"" + MCP_SERVER_NAME + "\":{"
            + "\"type\":\"" + MCP_TYPE_HTTP + "\","
            + "\"url\":\"http://localhost:" + mcpPort + "/mcp\","
            + "\"headers\":{}}}}";
        try {
            File configFile = File.createTempFile("acp-mcp-", ".json");
            configFile.deleteOnExit();
            Files.writeString(configFile.toPath(), json);
            return configFile.getAbsolutePath();
        } catch (IOException e) {
            LOG.warn("Failed to write Copilot MCP config file", e);
            return null;
        }
    }
}
