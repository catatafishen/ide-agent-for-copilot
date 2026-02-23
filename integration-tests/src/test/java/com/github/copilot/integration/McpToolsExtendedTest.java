package com.github.copilot.integration;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Extended integration tests for MCP tools via PSI Bridge.
 * Tests tools not covered by McpToolsIntegrationTest, focusing on
 * code analysis, inspection, and navigation capabilities.
 * <p>
 * Requirements:
 * 1. IntelliJ sandbox IDE running with the plugin
 * 2. PSI Bridge server active (check ~/.copilot/psi-bridge.json)
 * 3. Project loaded in the sandbox
 * <p>
 * No API costs — all tools are local IntelliJ operations.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class McpToolsExtendedTest {

    private static HttpClient httpClient;
    private static String psiBridgeUrl;

    @BeforeAll
    static void setup() throws IOException {
        httpClient = HttpClient.newHttpClient();
        Path bridgeConfig = Path.of(System.getProperty("user.home"), ".copilot", "psi-bridge.json");

        if (!Files.exists(bridgeConfig)) {
            throw new IllegalStateException(
                "PSI Bridge not running. Start sandbox IDE first: ./restart-sandbox.sh");
        }

        String json = Files.readString(bridgeConfig);
        JsonObject config = JsonParser.parseString(json).getAsJsonObject();
        int port = config.get("port").getAsInt();
        psiBridgeUrl = "http://localhost:" + port;
    }

    // ========================
    // Code Navigation Tools
    // ========================

    @Test
    @Order(1)
    void testGetOutline() throws Exception {
        String result = callTool("get_file_outline",
            "{\"path\":\"mcp-server/src/main/java/com/github/copilot/mcp/McpServer.java\"}");

        assertTrue(result.contains("McpServer") || result.contains("class"),
            "Outline should contain class name");
        assertTrue(result.contains("method") || result.contains("function") || result.contains("("),
            "Outline should contain methods");
    }

    @Test
    @Order(2)
    void testFindReferences() throws Exception {
        String result = callTool("find_references",
            "{\"query\":\"McpServer\",\"limit\":10}");

        // Should find references to McpServer across the project
        assertNotNull(result);
        assertFalse(result.isEmpty());
    }

    @Test
    @Order(3)
    void testSearchSymbolsWithTypeFilter() throws Exception {
        String result = callTool("search_symbols",
            "{\"query\":\"McpServer\",\"type\":\"class\",\"limit\":5}");

        assertTrue(result.toLowerCase().contains("mcpserver") || result.contains("class"),
            "Should find McpServer class");
    }

    @Test
    @Order(4)
    void testSearchSymbolsForMethods() throws Exception {
        String result = callTool("search_symbols",
            "{\"query\":\"callTool\",\"type\":\"method\",\"limit\":10}");

        assertNotNull(result);
        // callTool should exist in McpServer or test files
    }

    // ========================
    // File Operations
    // ========================

    @Test
    @Order(10)
    void testReadFileWithLineRange() throws Exception {
        String result = callTool("intellij_read_file",
            "{\"path\":\"build.gradle.kts\",\"start_line\":1,\"end_line\":10}");

        assertNotNull(result);
        assertTrue(result.contains("plugins") || result.contains("gradle") || result.contains("kotlin"),
            "First 10 lines of build.gradle.kts should contain build config");
    }

    @Test
    @Order(11)
    void testReadNonExistentFile() throws Exception {
        try {
            String result = callTool("intellij_read_file",
                "{\"path\":\"nonexistent/file/that/does/not/exist.java\"}");
            // Should return error message, not throw
            assertTrue(result.toLowerCase().contains("not found") || result.toLowerCase().contains("error")
                    || result.toLowerCase().contains("does not exist"),
                "Should indicate file not found");
        } catch (RuntimeException e) {
            // HTTP error is also acceptable
            assertTrue(e.getMessage().contains("404") || e.getMessage().contains("not found")
                || e.getMessage().contains("500"));
        }
    }

    @Test
    @Order(12)
    void testListProjectFilesWithPattern() throws Exception {
        String result = callTool("list_project_files",
            "{\"pattern\":\"*.java\"}");

        assertNotNull(result);
        assertTrue(result.contains(".java"), "Should list Java files");
    }

    @Test
    @Order(13)
    void testListProjectFilesWithDirectory() throws Exception {
        String result = callTool("list_project_files",
            "{\"directory\":\"mcp-server/src\"}");

        assertNotNull(result);
        assertTrue(result.contains("McpServer") || result.contains(".java"),
            "Should list files in mcp-server/src");
    }

    // ========================
    // Code Analysis Tools
    // ========================

    @Test
    @Order(20)
    void testGetProblems() throws Exception {
        String result = callTool("get_problems", "{}");

        assertNotNull(result);
        // May or may not find problems depending on which files are open
    }

    @Test
    @Order(21)
    void testGetHighlights() throws Exception {
        String result = callTool("get_highlights",
            "{\"scope\":\"project\",\"limit\":50}");

        assertNotNull(result);
        // Result format should be either "No highlights" or problem listings
        assertTrue(result.contains("highlight") || result.contains("problem")
                || result.contains("No") || result.contains("analyzed"),
            "Should return analysis result");
    }

    @Test
    @Order(22)
    void testRunInspections() throws Exception {
        String result = callTool("run_inspections", "{\"limit\":20}");

        assertNotNull(result);
        // Should return either problems or "no problems found"
        assertTrue(result.contains("problem") || result.contains("inspection")
                || result.contains("No") || result.contains("passed")
                || result.contains("Found"),
            "Should return inspection results");
    }

    // ========================
    // Project Info Tools
    // ========================

    @Test
    @Order(30)
    void testGetProjectInfoContainsModules() throws Exception {
        String result = callTool("get_project_info", "{}");

        assertTrue(result.contains("plugin-core") || result.contains("mcp-server"),
            "Project info should list modules");
    }

    @Test
    @Order(31)
    void testListTests() throws Exception {
        String result = callTool("list_tests", "{}");

        assertNotNull(result);
        assertTrue(result.contains("test") || result.contains("Test"),
            "Should list test classes or methods");
    }

    // ========================
    // Write Operations (using scratch files for safety)
    // ========================

    @Test
    @Order(40)
    void testCreateAndReadScratchFile() throws Exception {
        String uniqueName = "e2e-test-" + System.currentTimeMillis() + ".txt";
        String content = "Integration test content: " + uniqueName;

        String createResult = callTool("create_scratch_file",
            "{\"name\":\"" + uniqueName + "\",\"content\":\"" + content + "\"}");

        assertTrue(createResult.contains("Created") || createResult.contains("scratch"),
            "Should confirm scratch file creation");
    }

    @Test
    @Order(41)
    void testCreateScratchFileWithExtension() throws Exception {
        String createResult = callTool("create_scratch_file",
            "{\"name\":\"test-snippet.java\",\"content\":\"public class TestSnippet { }\"}");

        assertTrue(createResult.contains("Created") || createResult.contains("scratch"),
            "Should create .java scratch file");
    }

    // ========================
    // Error Handling
    // ========================

    @Test
    @Order(50)
    void testUnknownToolReturnsError() throws Exception {
        try {
            String result = callTool("nonexistent_tool_name", "{}");
            assertTrue(result.toLowerCase().contains("unknown") || result.toLowerCase().contains("error"),
                "Unknown tool should return error");
        } catch (RuntimeException e) {
            // HTTP error response is acceptable
            assertTrue(e.getMessage().contains("400") || e.getMessage().contains("404")
                || e.getMessage().contains("500") || e.getMessage().contains("unknown"));
        }
    }

    @Test
    @Order(51)
    void testMalformedArguments() throws Exception {
        try {
            // Send request with missing required field
            String result = callTool("intellij_read_file", "{}");
            // Should return error about missing path
            assertNotNull(result);
        } catch (RuntimeException e) {
            // Error response is expected
            assertNotNull(e.getMessage());
        }
    }

    @Test
    @Order(52)
    void testHealthEndpoint() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(psiBridgeUrl + "/health"))
            .GET()
            .build();

        HttpResponse<String> response = httpClient.send(request,
            HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode());
    }

    @Test
    @Order(53)
    void testToolsListEndpoint() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(psiBridgeUrl + "/tools/list"))
            .GET()
            .build();

        HttpResponse<String> response = httpClient.send(request,
            HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode());
        String body = response.body();
        JsonObject result = JsonParser.parseString(body).getAsJsonObject();
        assertTrue(result.has("tools"), "Should have tools array");
        JsonArray tools = result.getAsJsonArray("tools");
        assertTrue(tools.size() >= 30, "Should have 30+ tools, got " + tools.size());
    }

    // ========================
    // Helper
    // ========================

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

        JsonObject responseObj = JsonParser.parseString(response.body()).getAsJsonObject();
        return responseObj.get("result").getAsString();
    }

    // ========================
    // New Tool Integration Tests
    // ========================

    @Test
    @Order(20)
    void testGoToDeclaration() throws Exception {
        // PsiBridgeService references Project — navigate to its declaration
        String result = callTool("go_to_declaration",
            "{\"file\":\"plugin-core/src/main/java/com/github/copilot/intellij/psi/PsiBridgeService.java\"," +
                "\"symbol\":\"Project\",\"line\":28}");

        assertTrue(result.contains("Declaration") || result.contains("Project"),
            "Should return declaration info for Project: " + result);
        // Project is an IntelliJ platform class
        assertTrue(result.contains("File:") || result.contains("Line:") || result.contains("Context:"),
            "Should include file/line context: " + result);
    }

    @Test
    @Order(21)
    void testGoToDeclarationNotFound() throws Exception {
        String result = callTool("go_to_declaration",
            "{\"file\":\"plugin-core/src/main/java/com/github/copilot/intellij/psi/PsiBridgeService.java\"," +
                "\"symbol\":\"NonExistentSymbol12345\",\"line\":1}");

        assertTrue(result.contains("Could not resolve") || result.contains("Error"),
            "Should report symbol not found: " + result);
    }

    @Test
    @Order(22)
    void testGetTypeHierarchy() throws Exception {
        // PsiBridgeService implements Disposable
        String result = callTool("get_type_hierarchy",
            "{\"symbol\":\"PsiBridgeService\"}");

        assertTrue(result.contains("hierarchy") || result.contains("Disposable") || result.contains("PsiBridgeService"),
            "Should show type hierarchy: " + result);
        assertTrue(result.contains("implements") || result.contains("extends") || result.contains("Supertypes"),
            "Should show supertype relationships: " + result);
    }

    @Test
    @Order(23)
    void testGetTypeHierarchyWithDirection() throws Exception {
        String result = callTool("get_type_hierarchy",
            "{\"symbol\":\"PsiBridgeService\",\"direction\":\"supertypes\"}");

        assertTrue(result.contains("Supertypes") || result.contains("implements"),
            "Should show supertypes only: " + result);
        assertFalse(result.contains("Subtypes"),
            "Should NOT show subtypes when direction=supertypes: " + result);
    }

    @Test
    @Order(24)
    void testGetTypeHierarchyNotFound() throws Exception {
        String result = callTool("get_type_hierarchy",
            "{\"symbol\":\"TotallyFakeClassName\"}");

        assertTrue(result.contains("not found") || result.contains("Error"),
            "Should report class not found: " + result);
    }

    @Test
    @Order(30)
    void testCreateAndDeleteFile() throws Exception {
        String testPath = "integration-test-temp-file.txt";
        String content = "This file was created by integration tests. Safe to delete.";

        // Create
        String createResult = callTool("create_file",
            "{\"path\":\"" + testPath + "\",\"content\":\"" + content + "\"}");

        assertTrue(createResult.contains("Created file") || createResult.contains("✓"),
            "Should confirm file creation: " + createResult);

        // Verify it exists by reading it
        String readResult = callTool("intellij_read_file",
            "{\"path\":\"" + testPath + "\"}");

        assertTrue(readResult.contains("integration tests"),
            "Should be able to read created file: " + readResult);

        // Delete
        String deleteResult = callTool("delete_file",
            "{\"path\":\"" + testPath + "\"}");

        assertTrue(deleteResult.contains("Deleted file") || deleteResult.contains("✓"),
            "Should confirm file deletion: " + deleteResult);
    }

    @Test
    @Order(31)
    void testCreateFileAlreadyExists() throws Exception {
        // README.md already exists
        String result = callTool("create_file",
            "{\"path\":\"README.md\",\"content\":\"test\"}");

        assertTrue(result.contains("already exists") || result.contains("Error"),
            "Should report file already exists: " + result);
    }

    @Test
    @Order(32)
    void testDeleteFileNotFound() throws Exception {
        String result = callTool("delete_file",
            "{\"path\":\"this-file-does-not-exist-12345.txt\"}");

        assertTrue(result.contains("not found") || result.contains("Error"),
            "Should report file not found: " + result);
    }

    @Test
    @Order(40)
    void testBuildProject() throws Exception {
        // Build should succeed since the project compiles
        String result = callTool("build_project", "{}");

        assertTrue(result.contains("Build") || result.contains("succeeded") || result.contains("errors"),
            "Should return build result: " + result);
    }

    @Test
    @Order(41)
    void testBuildProjectModule() throws Exception {
        // Build a specific module
        String result = callTool("build_project",
            "{\"module\":\"mcp-server\"}");

        assertTrue(result.contains("Build") || result.contains("succeeded")
                || result.contains("errors") || result.contains("not found"),
            "Should return build result or module not found: " + result);
    }

    @Test
    @Order(50)
    void testRefactorMissingNewName() throws Exception {
        // Rename without new_name should fail
        String result = callTool("refactor",
            "{\"operation\":\"rename\",\"file\":\"README.md\",\"symbol\":\"test\"}");

        assertTrue(result.contains("new_name") || result.contains("required") || result.contains("Error"),
            "Should report new_name is required: " + result);
    }

    @Test
    @Order(51)
    void testRefactorUnknownOperation() throws Exception {
        String result = callTool("refactor",
            "{\"operation\":\"unknown_op\",\"file\":\"README.md\",\"symbol\":\"test\"}");

        assertTrue(result.contains("Unknown operation") || result.contains("Error"),
            "Should report unknown operation: " + result);
    }

    @Test
    @Order(52)
    void testApplyQuickfixMissingParams() throws Exception {
        // Missing required params
        String result = callTool("apply_quickfix",
            "{\"file\":\"README.md\"}");

        assertTrue(result.contains("required") || result.contains("Error"),
            "Should report missing parameters: " + result);
    }

    @Test
    @Order(60)
    void testToolsListIncludesNewTools() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(psiBridgeUrl + "/tools/list"))
            .GET()
            .build();

        HttpResponse<String> response = httpClient.send(request,
            HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode());
        String body = response.body();
        // The /tools/list endpoint returns tool names that the bridge handles
        assertTrue(body.contains("apply_quickfix"), "Should list apply_quickfix tool");
        assertTrue(body.contains("refactor"), "Should list refactor tool");
        assertTrue(body.contains("go_to_declaration"), "Should list go_to_declaration tool");
        assertTrue(body.contains("get_type_hierarchy"), "Should list get_type_hierarchy tool");
        assertTrue(body.contains("create_file"), "Should list create_file tool");
        assertTrue(body.contains("delete_file"), "Should list delete_file tool");
        assertTrue(body.contains("build_project"), "Should list build_project tool");
    }
}
