package com.github.catatafishen.agentbridge.bridge;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the pure-logic static methods in {@link OpenCodeAgentConfig}.
 */
class OpenCodeAgentConfigTest {

    // ── convertMcpServersToObject ──────────────────────────

    @Test
    void convertMcpServersToObject_convertsNamedServers() throws Exception {
        String input = """
            {"mcpServers": [
                {"name": "agentbridge", "transport": "http", "url": "http://localhost:9876"},
                {"name": "custom", "transport": "stdio", "command": "/bin/custom"}
            ]}""";

        String result = invokeConvert(input);
        JsonObject root = JsonParser.parseString(result).getAsJsonObject();

        assertFalse(root.has("mcpServers"), "mcpServers array should be removed");
        assertTrue(root.has("mcp"), "mcp object should be added");

        JsonObject mcp = root.getAsJsonObject("mcp");
        assertTrue(mcp.has("agentbridge"));
        assertTrue(mcp.has("custom"));

        // Name field should be removed from the entry
        assertFalse(mcp.getAsJsonObject("agentbridge").has("name"));
        assertEquals("http", mcp.getAsJsonObject("agentbridge").get("transport").getAsString());
    }

    @Test
    void convertMcpServersToObject_defaultsNameToAgentbridge() throws Exception {
        String input = """
            {"mcpServers": [
                {"transport": "http", "url": "http://localhost:9876"}
            ]}""";

        String result = invokeConvert(input);
        JsonObject mcp = JsonParser.parseString(result).getAsJsonObject().getAsJsonObject("mcp");

        assertTrue(mcp.has("agentbridge"),
            "Server without 'name' should default to 'agentbridge'");
    }

    @Test
    void convertMcpServersToObject_returnsUnchangedWithoutMcpServers() throws Exception {
        String input = """
            {"other": "data"}""";
        assertEquals(input.trim(), invokeConvert(input.trim()));
    }

    @Test
    void convertMcpServersToObject_returnsUnchangedWhenMcpServersNotArray() throws Exception {
        String input = """
            {"mcpServers": "not an array"}""";
        assertEquals(input.trim(), invokeConvert(input.trim()));
    }

    @Test
    void convertMcpServersToObject_handlesEmptyArray() throws Exception {
        String input = """
            {"mcpServers": []}""";

        String result = invokeConvert(input);
        JsonObject root = JsonParser.parseString(result).getAsJsonObject();

        assertTrue(root.has("mcp"));
        assertEquals(0, root.getAsJsonObject("mcp").size());
    }

    @Test
    void convertMcpServersToObject_skipsNonObjectElements() throws Exception {
        String input = """
            {"mcpServers": [
                "not an object",
                {"name": "real", "transport": "http"}
            ]}""";

        String result = invokeConvert(input);
        JsonObject mcp = JsonParser.parseString(result).getAsJsonObject().getAsJsonObject("mcp");

        assertEquals(1, mcp.size(), "Should skip non-object elements");
        assertTrue(mcp.has("real"));
    }

    @Test
    void convertMcpServersToObject_preservesOtherRootFields() throws Exception {
        String input = """
            {"mcpServers": [{"name": "srv", "transport": "http"}], "permissions": {"read": true}}""";

        String result = invokeConvert(input);
        JsonObject root = JsonParser.parseString(result).getAsJsonObject();

        assertTrue(root.has("permissions"), "Other root fields should be preserved");
        assertTrue(root.has("mcp"));
    }

    // ── Reflection helper ──────────────────────────────────

    private static String invokeConvert(String configJson) throws Exception {
        Method m = OpenCodeAgentConfig.class.getDeclaredMethod("convertMcpServersToObject", String.class);
        m.setAccessible(true);
        return (String) m.invoke(null, configJson);
    }
}
