package com.github.catatafishen.agentbridge.acp.client;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

class JunieClientTest {

    // ── isJuniePermissionBug (private static) ───────────────────────────

    @Test
    void isJuniePermissionBug_matchesDirectMessage() throws Exception {
        assertTrue(invokeIsJuniePermissionBug(
            new RuntimeException("RequestPermissionOutcome was not received")));
    }

    @Test
    void isJuniePermissionBug_matchesInCauseChain() throws Exception {
        Exception root = new RuntimeException("RequestPermissionOutcome timeout");
        Exception wrapper = new RuntimeException("prompt failed", root);
        assertTrue(invokeIsJuniePermissionBug(wrapper));
    }

    @Test
    void isJuniePermissionBug_noMatch() throws Exception {
        assertFalse(invokeIsJuniePermissionBug(new RuntimeException("connection refused")));
    }

    @Test
    void isJuniePermissionBug_nullMessage() throws Exception {
        assertFalse(invokeIsJuniePermissionBug(new RuntimeException((String) null)));
    }

    // ── tryParseArgsFromContentItem (private static) ────────────────────

    @Test
    void tryParseArgs_directTextBlock() throws Exception {
        JsonObject block = new JsonObject();
        block.addProperty("type", "text");
        block.addProperty("text", "{\"path\": \"test.txt\"}");
        JsonObject result = invokeTryParseArgs(block);
        assertNotNull(result);
        assertEquals("test.txt", result.get("path").getAsString());
    }

    @Test
    void tryParseArgs_nestedContentBlock() throws Exception {
        JsonObject inner = new JsonObject();
        inner.addProperty("type", "text");
        inner.addProperty("text", "{\"query\": \"hello\"}");

        JsonObject outer = new JsonObject();
        outer.addProperty("type", "content");
        outer.add("content", inner);

        JsonObject result = invokeTryParseArgs(outer);
        assertNotNull(result);
        assertEquals("hello", result.get("query").getAsString());
    }

    @Test
    void tryParseArgs_nonJsonText() throws Exception {
        JsonObject block = new JsonObject();
        block.addProperty("text", "not json at all");
        assertNull(invokeTryParseArgs(block));
    }

    @Test
    void tryParseArgs_textNotStartingWithBrace() throws Exception {
        JsonObject block = new JsonObject();
        block.addProperty("text", "[1, 2, 3]");
        assertNull(invokeTryParseArgs(block));
    }

    @Test
    void tryParseArgs_notJsonObject() throws Exception {
        assertNull(invokeTryParseArgs(new JsonPrimitive("hello")));
    }

    @Test
    void tryParseArgs_noTextKey() throws Exception {
        JsonObject block = new JsonObject();
        block.addProperty("type", "image");
        assertNull(invokeTryParseArgs(block));
    }

    @Test
    void tryParseArgs_emptyText() throws Exception {
        JsonObject block = new JsonObject();
        block.addProperty("text", "   ");
        assertNull(invokeTryParseArgs(block));
    }

    // ── Reflection helpers ──────────────────────────────────────────────

    private static boolean invokeIsJuniePermissionBug(Throwable t) throws Exception {
        Method m = JunieClient.class.getDeclaredMethod("isJuniePermissionBug", Throwable.class);
        m.setAccessible(true);
        return (boolean) m.invoke(null, t);
    }

    private static JsonObject invokeTryParseArgs(JsonElement item) throws Exception {
        Method m = JunieClient.class.getDeclaredMethod("tryParseArgsFromContentItem", JsonElement.class);
        m.setAccessible(true);
        return (JsonObject) m.invoke(null, item);
    }
}
