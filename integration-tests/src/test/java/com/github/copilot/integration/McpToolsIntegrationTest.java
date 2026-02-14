package com.github.copilot.integration;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.*;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Integration tests for MCP tools via PSI Bridge.
 * <p>
 * These tests require:
 * 1. IntelliJ sandbox IDE running with the plugin
 * 2. PSI Bridge server active (check ~/.copilot/psi-bridge.json)
 * 3. Project loaded in the sandbox
 * <p>
 * Run manually with: ./gradlew :integration-tests:test --tests McpToolsIntegrationTest
 * <p>
 * Note: These tests depend on external services (Copilot CLI, IDE) and should NOT
 * be run in CI pipelines. They validate end-to-end tool functionality.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class McpToolsIntegrationTest {

    private static HttpClient httpClient;
    private static String psiBridgeUrl;

    @BeforeAll
    static void setup() throws IOException {
        httpClient = HttpClient.newHttpClient();

        // Read PSI bridge config
        Path bridgeConfig = Path.of(System.getProperty("user.home"), ".copilot", "psi-bridge.json");

        if (!Files.exists(bridgeConfig)) {
            throw new IllegalStateException(
                    "PSI Bridge not running. Start sandbox IDE first: ./restart-sandbox.sh");
        }

        String json = Files.readString(bridgeConfig);
        JsonObject config = JsonParser.parseString(json).getAsJsonObject();
        int port = config.get("port").getAsInt();
        psiBridgeUrl = "http://localhost:" + port;

        System.out.println("PSI Bridge URL: " + psiBridgeUrl);
    }

    /**
     * Test that PSI Bridge is responding
     */
    @Test
    @Order(1)
    void testPsiBridgeAlive() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(psiBridgeUrl + "/health"))
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request,
                HttpResponse.BodyHandlers.ofString());

        Assertions.assertEquals(200, response.statusCode(),
                "PSI Bridge should respond to health check");
    }

    /**
     * Test list_project_files tool
     */
    @Test
    @Order(2)
    void testListProjectFiles() throws Exception {
        String result = callTool("list_project_files", "{}");

        // Result might be JSON or plain text list
        try {
            JsonObject response = JsonParser.parseString(result).getAsJsonObject();
            Assertions.assertTrue(response.has("files"), "Response should contain 'files' array");
            int fileCount = response.getAsJsonArray("files").size();
            Assertions.assertTrue(fileCount > 0, "Project should have at least one file");
            System.out.println("✓ Found " + fileCount + " files in project");
        } catch (Exception e) {
            // Plain text format
            Assertions.assertTrue(result.contains(".java") || result.contains(".kt"),
                    "Should list some source files");
            System.out.println("✓ Listed project files (text format)");
        }
    }

    /**
     * Test intellij_read_file tool
     */
    @Test
    @Order(3)
    void testReadFile() throws Exception {
        String args = "{\"path\":\"README.md\"}";
        String result = callTool("intellij_read_file", args);

        Assertions.assertTrue(result.contains("IntelliJ"),
                "README.md should contain 'IntelliJ'");

        System.out.println("✓ Successfully read README.md (" + result.length() + " chars)");
    }

    /**
     * Test create_scratch_file tool
     */
    @Test
    @Order(4)
    void testCreateScratchFile() throws Exception {
        String args = "{"
                + "\"name\":\"integration-test.md\","
                + "\"content\":\"# Integration Test\\n\\nThis file was created by automated tests.\""
                + "}";

        String result = callTool("create_scratch_file", args);

        Assertions.assertTrue(result.contains("Created scratch file"),
                "Should return success message");
        Assertions.assertTrue(result.contains("integration-test.md"),
                "Should mention file name");

        System.out.println("✓ " + result);
    }

    /**
     * Test search_symbols tool
     */
    @Test
    @Order(5)
    void testSearchSymbols() throws Exception {
        String args = "{\"query\":\"McpServer\",\"limit\":5}";
        String result = callTool("search_symbols", args);

        Assertions.assertTrue(result.toLowerCase().contains("mcp") || result.contains("symbol"),
                "Should find symbols or indicate search completed");

        System.out.println("✓ Symbol search completed for 'McpServer'");
    }

    /**
     * Test get_project_info tool
     */
    @Test
    @Order(6)
    void testGetProjectInfo() throws Exception {
        String result = callTool("get_project_info", "{}");

        Assertions.assertTrue(result.contains("intellij-copilot-plugin") || result.contains("Project"),
                "Should return project information");

        System.out.println("✓ Project info retrieved");
    }

    /**
     * Helper method to call MCP tool via PSI Bridge
     */
    private String callTool(String toolName, String argsJson) throws Exception {
        String requestBody = "{"
                + "\"name\":\"" + toolName + "\","
                + "\"arguments\":" + argsJson
                + "}";

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(psiBridgeUrl + "/tools/call"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();

        HttpResponse<String> response = httpClient.send(request,
                HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new RuntimeException("Tool call failed: HTTP " + response.statusCode()
                    + " - " + response.body());
        }

        // Parse response and extract "result" field
        JsonObject responseObj = JsonParser.parseString(response.body()).getAsJsonObject();
        return responseObj.get("result").getAsString();
    }
}
