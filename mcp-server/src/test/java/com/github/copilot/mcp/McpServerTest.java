package com.github.copilot.mcp;

import com.google.gson.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.io.*;
import java.nio.file.*;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.*;

class McpServerTest {

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() throws IOException {
        // Create a small project structure for testing
        Path srcDir = tempDir.resolve("src/main/java/com/example");
        Files.createDirectories(srcDir);

        Files.writeString(srcDir.resolve("UserService.java"),
            """
            package com.example;
            
            public class UserService {
                private final UserRepository userRepo;
            
                public UserService(UserRepository userRepo) {
                    this.userRepo = userRepo;
                }
            
                public User findById(long id) {
                    return userRepo.findById(id);
                }
            
                public void deleteUser(long id) {
                    userRepo.delete(id);
                }
            }
            """);

        Files.writeString(srcDir.resolve("UserRepository.java"),
            """
            package com.example;
            
            public interface UserRepository {
                User findById(long id);
                void delete(long id);
                void save(User user);
            }
            """);

        Files.writeString(srcDir.resolve("User.java"),
            """
            package com.example;
            
            public class User {
                private long id;
                private String name;
            
                public long getId() { return id; }
                public String getName() { return name; }
            }
            """);

        // Set project root for tests
        try {
            var field = McpServer.class.getDeclaredField("projectRoot");
            field.setAccessible(true);
            field.set(null, tempDir.toString());
        } catch (Exception e) {
            fail("Could not set projectRoot: " + e);
        }
    }

    @Test
    void testInitialize() {
        JsonObject request = buildRequest("initialize", new JsonObject());
        JsonObject response = McpServer.handleMessage(request);

        assertNotNull(response);
        assertEquals(1, response.get("id").getAsLong());
        JsonObject result = response.getAsJsonObject("result");
        assertEquals("intellij-code-tools", result.getAsJsonObject("serverInfo").get("name").getAsString());
        assertTrue(result.getAsJsonObject("capabilities").has("tools"));
    }

    @Test
    void testToolsList() {
        JsonObject request = buildRequest("tools/list", new JsonObject());
        JsonObject response = McpServer.handleMessage(request);

        assertNotNull(response);
        JsonArray tools = response.getAsJsonObject("result").getAsJsonArray("tools");
        assertEquals(33, tools.size(), "Should have 33 tools (code nav + file I/O + testing + quality + run configs + git + infra)");

        // Verify tool names
        var toolNames = new ArrayList<String>();
        tools.forEach(t -> toolNames.add(t.getAsJsonObject().get("name").getAsString()));
        assertTrue(toolNames.contains("search_symbols"));
        assertTrue(toolNames.contains("get_file_outline"));
        assertTrue(toolNames.contains("find_references"));
        assertTrue(toolNames.contains("list_project_files"));
        // Git tools
        assertTrue(toolNames.contains("git_status"));
        assertTrue(toolNames.contains("git_diff"));
        assertTrue(toolNames.contains("git_log"));
        assertTrue(toolNames.contains("git_blame"));
        assertTrue(toolNames.contains("git_commit"));
        assertTrue(toolNames.contains("git_stage"));
        assertTrue(toolNames.contains("git_unstage"));
        assertTrue(toolNames.contains("git_branch"));
        assertTrue(toolNames.contains("git_stash"));
        assertTrue(toolNames.contains("git_show"));
    }

    @Test
    void testSearchSymbolsFindsClass() throws IOException {
        JsonObject args = new JsonObject();
        args.addProperty("query", "UserService");
        String result = McpServer.searchSymbols(args);

        assertTrue(result.contains("UserService"), "Should find UserService class");
        assertTrue(result.contains("[class]"), "Should identify as class");
    }

    @Test
    void testSearchSymbolsFindsInterface() throws IOException {
        JsonObject args = new JsonObject();
        args.addProperty("query", "UserRepository");
        String result = McpServer.searchSymbols(args);

        assertTrue(result.contains("UserRepository"), "Should find UserRepository");
        assertTrue(result.contains("[interface]"), "Should identify as interface");
    }

    @Test
    void testSearchSymbolsWithTypeFilter() throws IOException {
        JsonObject args = new JsonObject();
        args.addProperty("query", "User");
        args.addProperty("type", "class");
        String result = McpServer.searchSymbols(args);

        assertTrue(result.contains("[class]"), "Should only show classes");
        assertFalse(result.contains("[interface]"), "Should not show interfaces");
    }

    @Test
    void testGetFileOutline() throws IOException {
        JsonObject args = new JsonObject();
        args.addProperty("path", tempDir.resolve("src/main/java/com/example/UserService.java").toString());
        String result = McpServer.getFileOutline(args);

        assertTrue(result.contains("class UserService"), "Should show class");
        assertTrue(result.contains("Outline of"), "Should have outline header");
    }

    @Test
    void testGetFileOutlineNotFound() throws IOException {
        JsonObject args = new JsonObject();
        args.addProperty("path", "nonexistent.java");
        String result = McpServer.getFileOutline(args);

        assertTrue(result.contains("File not found"), "Should report file not found");
    }

    @Test
    void testFindReferences() throws IOException {
        JsonObject args = new JsonObject();
        args.addProperty("symbol", "userRepo");
        String result = McpServer.findReferences(args);

        assertTrue(result.contains("references found"), "Should find references");
        assertTrue(result.contains("UserService.java"), "Should find in UserService");
    }

    @Test
    void testFindReferencesWithFilePattern() throws IOException {
        JsonObject args = new JsonObject();
        args.addProperty("symbol", "findById");
        args.addProperty("file_pattern", "*.java");
        String result = McpServer.findReferences(args);

        assertTrue(result.contains("references found"), "Should find references");
    }

    @Test
    void testListProjectFiles() throws IOException {
        JsonObject args = new JsonObject();
        String result = McpServer.listProjectFiles(args);

        assertTrue(result.contains("files:"), "Should list files");
        assertTrue(result.contains("UserService.java"), "Should include UserService");
        assertTrue(result.contains("[Java]"), "Should identify Java files");
    }

    @Test
    void testListProjectFilesWithPattern() throws IOException {
        JsonObject args = new JsonObject();
        args.addProperty("pattern", "*.java");
        String result = McpServer.listProjectFiles(args);

        assertTrue(result.contains("[Java]"), "Should only show Java files");
        assertEquals(3, result.lines().filter(l -> l.contains("[Java]")).count(), "Should find 3 Java files");
    }

    @Test
    void testUnknownMethodReturnsError() {
        JsonObject request = buildRequest("unknown/method", new JsonObject());
        JsonObject response = McpServer.handleMessage(request);

        assertNotNull(response);
        assertTrue(response.has("error"), "Should return error for unknown method");
        assertEquals(-32601, response.getAsJsonObject("error").get("code").getAsInt());
    }

    @Test
    void testNotificationReturnsNull() {
        JsonObject msg = new JsonObject();
        msg.addProperty("jsonrpc", "2.0");
        msg.addProperty("method", "initialized");
        JsonObject response = McpServer.handleMessage(msg);

        assertNull(response, "Notification should not produce a response");
    }

    @Test
    void testPingReturnsEmptyResult() {
        JsonObject request = buildRequest("ping", new JsonObject());
        JsonObject response = McpServer.handleMessage(request);

        assertNotNull(response);
        assertNotNull(response.getAsJsonObject("result"));
    }

    @Test
    void testPathTraversalBlocked() {
        JsonObject args = new JsonObject();
        args.addProperty("path", "../../../etc/passwd");
        assertThrows(IOException.class, () -> McpServer.getFileOutline(args),
                "Should throw IOException for path traversal");
    }

    @Test
    void testAbsolutePathOutsideProjectBlocked() {
        JsonObject args = new JsonObject();
        args.addProperty("path", "/etc/passwd");
        assertThrows(IOException.class, () -> McpServer.getFileOutline(args),
                "Should throw IOException for absolute paths outside project");
    }

    private static JsonObject buildRequest(String method, JsonObject params) {
        JsonObject request = new JsonObject();
        request.addProperty("jsonrpc", "2.0");
        request.addProperty("id", 1);
        request.addProperty("method", method);
        request.add("params", params);
        return request;
    }
}
