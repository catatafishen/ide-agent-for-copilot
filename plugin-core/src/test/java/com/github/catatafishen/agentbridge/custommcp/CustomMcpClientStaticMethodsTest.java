package com.github.catatafishen.agentbridge.custommcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for static utility methods in {@link CustomMcpClient}.
 * Uses reflection since the methods are private.
 */
class CustomMcpClientStaticMethodsTest {

    // ── extractTextContent ──────────────────────────────────

    @Test
    void extractTextContent_concatenatesTextItems() throws Exception {
        JsonObject result = new JsonObject();
        JsonArray content = new JsonArray();

        JsonObject item1 = new JsonObject();
        item1.addProperty("type", "text");
        item1.addProperty("text", "line one");
        content.add(item1);

        JsonObject item2 = new JsonObject();
        item2.addProperty("type", "text");
        item2.addProperty("text", "line two");
        content.add(item2);

        result.add("content", content);

        assertEquals("line one\nline two", invokeExtractTextContent(result));
    }

    @Test
    void extractTextContent_skipsNonTextItems() throws Exception {
        JsonObject result = new JsonObject();
        JsonArray content = new JsonArray();

        JsonObject imageItem = new JsonObject();
        imageItem.addProperty("type", "image");
        imageItem.addProperty("data", "base64...");
        content.add(imageItem);

        JsonObject textItem = new JsonObject();
        textItem.addProperty("type", "text");
        textItem.addProperty("text", "only text");
        content.add(textItem);

        result.add("content", content);

        assertEquals("only text", invokeExtractTextContent(result));
    }

    @Test
    void extractTextContent_returnsEmptyWhenNoContent() throws Exception {
        assertEquals("", invokeExtractTextContent(new JsonObject()));
    }

    @Test
    void extractTextContent_returnsEmptyForEmptyContent() throws Exception {
        JsonObject result = new JsonObject();
        result.add("content", new JsonArray());
        assertEquals("", invokeExtractTextContent(result));
    }

    @Test
    void extractTextContent_handlesTextItemWithoutTextField() throws Exception {
        JsonObject result = new JsonObject();
        JsonArray content = new JsonArray();
        JsonObject item = new JsonObject();
        item.addProperty("type", "text");
        // no "text" field
        content.add(item);
        result.add("content", content);

        assertEquals("", invokeExtractTextContent(result));
    }

    // ── errorMessage ────────────────────────────────────────

    @Test
    void errorMessage_extractsMessageFromError() throws Exception {
        JsonObject response = new JsonObject();
        JsonObject error = new JsonObject();
        error.addProperty("message", "Tool not found");
        response.add("error", error);

        assertEquals("Tool not found", invokeErrorMessage(response));
    }

    @Test
    void errorMessage_returnsToStringWhenNoMessage() throws Exception {
        JsonObject response = new JsonObject();
        JsonObject error = new JsonObject();
        error.addProperty("code", -32601);
        response.add("error", error);

        String result = invokeErrorMessage(response);
        assertTrue(result.contains("-32601"), "Should fall back to error.toString()");
    }

    @Test
    void errorMessage_returnsUnknownWhenNoErrorField() throws Exception {
        assertEquals("unknown error", invokeErrorMessage(new JsonObject()));
    }

    @Test
    void errorMessage_returnsUnknownWhenErrorNotObject() throws Exception {
        JsonObject response = new JsonObject();
        response.addProperty("error", "string error");
        assertEquals("unknown error", invokeErrorMessage(response));
    }

    // ── stripSseEnvelope ────────────────────────────────────

    @Test
    void stripSseEnvelope_stripsDataPrefix() throws Exception {
        assertEquals("{\"result\":\"ok\"}", invokeStripSseEnvelope("data: {\"result\":\"ok\"}"));
    }

    @Test
    void stripSseEnvelope_handlesNoSpaceAfterData() throws Exception {
        assertEquals("{\"result\":\"ok\"}", invokeStripSseEnvelope("data:{\"result\":\"ok\"}"));
    }

    @Test
    void stripSseEnvelope_returnsOriginalWhenNotSse() throws Exception {
        String json = "{\"result\":\"ok\"}";
        assertEquals(json, invokeStripSseEnvelope(json));
    }

    @Test
    void stripSseEnvelope_handlesMultiLineData() throws Exception {
        // Returns first non-empty data line's content
        String input = "data: {\"first\":true}\ndata: {\"second\":true}\n\n";
        assertEquals("{\"first\":true}", invokeStripSseEnvelope(input));
    }

    @Test
    void stripSseEnvelope_trimsWhitespace() throws Exception {
        assertEquals("{\"r\":1}", invokeStripSseEnvelope("  data: {\"r\":1}  \n\n"));
    }

    @Test
    void stripSseEnvelope_returnsOriginalForEmptyDataLine() throws Exception {
        // data: followed by nothing — empty json, returns original
        String input = "data:   \n\n";
        assertEquals(input, invokeStripSseEnvelope(input));
    }

    // ── Reflection helpers ──────────────────────────────────

    private static String invokeExtractTextContent(JsonObject result) throws Exception {
        Method m = CustomMcpClient.class.getDeclaredMethod("extractTextContent", JsonObject.class);
        m.setAccessible(true);
        return (String) m.invoke(null, result);
    }

    private static String invokeErrorMessage(JsonObject response) throws Exception {
        Method m = CustomMcpClient.class.getDeclaredMethod("errorMessage", JsonObject.class);
        m.setAccessible(true);
        return (String) m.invoke(null, response);
    }

    private static String invokeStripSseEnvelope(String body) throws Exception {
        Method m = CustomMcpClient.class.getDeclaredMethod("stripSseEnvelope", String.class);
        m.setAccessible(true);
        return (String) m.invoke(null, body);
    }
}
