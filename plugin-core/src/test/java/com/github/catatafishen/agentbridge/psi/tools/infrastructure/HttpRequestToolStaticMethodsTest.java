package com.github.catatafishen.agentbridge.psi.tools.infrastructure;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for static utility methods in {@link HttpRequestTool}.
 * Uses reflection since the methods are private.
 */
class HttpRequestToolStaticMethodsTest {

    // ── encodeFormData ──────────────────────────────────────

    @Test
    void encodeFormData_encodesKeyValuePairs() throws Exception {
        JsonObject form = new JsonObject();
        form.addProperty("username", "john");
        form.addProperty("password", "s3cret");

        String result = invokeEncodeFormData(form);
        assertTrue(result.contains("username=john"));
        assertTrue(result.contains("password=s3cret"));
        assertTrue(result.contains("&"), "Should join with &");
    }

    @Test
    void encodeFormData_urlEncodesSpecialChars() throws Exception {
        JsonObject form = new JsonObject();
        form.addProperty("q", "hello world");
        form.addProperty("url", "https://example.com/path?a=1&b=2");

        String result = invokeEncodeFormData(form);
        assertTrue(result.contains("q=hello+world") || result.contains("q=hello%20world"));
        assertFalse(result.contains("https://example.com"), "URL should be encoded");
    }

    @Test
    void encodeFormData_handlesEmptyObject() throws Exception {
        assertEquals("", invokeEncodeFormData(new JsonObject()));
    }

    @Test
    void encodeFormData_handlesSinglePair() throws Exception {
        JsonObject form = new JsonObject();
        form.addProperty("key", "value");
        assertEquals("key=value", invokeEncodeFormData(form));
    }

    // ── resolveBody ─────────────────────────────────────────

    @Test
    void resolveBody_returnsBodyWhenPresent() throws Exception {
        JsonObject args = new JsonObject();
        args.addProperty("body", "{\"data\":1}");
        assertEquals("{\"data\":1}", invokeResolveBody(args));
    }

    @Test
    void resolveBody_encodesFormDataWhenPresent() throws Exception {
        JsonObject args = new JsonObject();
        JsonObject form = new JsonObject();
        form.addProperty("k", "v");
        args.add("form_data", form);
        assertEquals("k=v", invokeResolveBody(args));
    }

    @Test
    void resolveBody_prefersBodyOverFormData() throws Exception {
        JsonObject args = new JsonObject();
        args.addProperty("body", "raw body");
        JsonObject form = new JsonObject();
        form.addProperty("k", "v");
        args.add("form_data", form);

        assertEquals("raw body", invokeResolveBody(args), "body should take priority over form_data");
    }

    @Test
    void resolveBody_returnsNullWhenNeitherPresent() throws Exception {
        assertNull(invokeResolveBody(new JsonObject()));
    }

    // ── resolveBodyContentType ──────────────────────────────

    @Test
    void resolveBodyContentType_returnsFormEncodedForFormData() throws Exception {
        JsonObject args = new JsonObject();
        args.add("form_data", new JsonObject());
        assertEquals("application/x-www-form-urlencoded", invokeResolveBodyContentType(args));
    }

    @Test
    void resolveBodyContentType_returnsJsonForBody() throws Exception {
        JsonObject args = new JsonObject();
        args.addProperty("body", "{}");
        assertEquals("application/json", invokeResolveBodyContentType(args));
    }

    @Test
    void resolveBodyContentType_returnsNullWhenNoBody() throws Exception {
        assertNull(invokeResolveBodyContentType(new JsonObject()));
    }

    // ── extractHost ─────────────────────────────────────────

    @Test
    void extractHost_extractsFromValidUrl() throws Exception {
        assertEquals("api.example.com", invokeExtractHost("https://api.example.com/v1/data"));
    }

    @Test
    void extractHost_truncatesInvalidUrl() throws Exception {
        String result = invokeExtractHost("not-a-url-at-all");
        assertNotNull(result);
        assertFalse(result.isEmpty());
    }

    @Test
    void extractHost_handlesUrlWithPort() throws Exception {
        assertEquals("localhost", invokeExtractHost("http://localhost:8080/path"));
    }

    // ── truncateUrl ─────────────────────────────────────────

    @Test
    void truncateUrl_shortUrlUnchanged() throws Exception {
        assertEquals("http://example.com", invokeTruncateUrl("http://example.com"));
    }

    @Test
    void truncateUrl_longUrlTruncated() throws Exception {
        String longUrl = "https://api.example.com/very/long/path/that/exceeds/limit";
        String result = invokeTruncateUrl(longUrl);
        assertTrue(result.endsWith("..."));
        assertEquals(30, result.length());
    }

    @Test
    void truncateUrl_exactlyThirtyCharsNotTruncated() throws Exception {
        String url = "123456789012345678901234567890"; // 30 chars
        assertEquals(url, invokeTruncateUrl(url));
    }

    // ── Reflection helpers ──────────────────────────────────

    private static String invokeEncodeFormData(JsonObject form) throws Exception {
        Method m = HttpRequestTool.class.getDeclaredMethod("encodeFormData", JsonObject.class);
        m.setAccessible(true);
        return (String) m.invoke(null, form);
    }

    private static String invokeResolveBody(JsonObject args) throws Exception {
        Method m = HttpRequestTool.class.getDeclaredMethod("resolveBody", JsonObject.class);
        m.setAccessible(true);
        return (String) m.invoke(null, args);
    }

    private static String invokeResolveBodyContentType(JsonObject args) throws Exception {
        Method m = HttpRequestTool.class.getDeclaredMethod("resolveBodyContentType", JsonObject.class);
        m.setAccessible(true);
        return (String) m.invoke(null, args);
    }

    private static String invokeExtractHost(String url) throws Exception {
        Method m = HttpRequestTool.class.getDeclaredMethod("extractHost", String.class);
        m.setAccessible(true);
        return (String) m.invoke(null, url);
    }

    private static String invokeTruncateUrl(String url) throws Exception {
        Method m = HttpRequestTool.class.getDeclaredMethod("truncateUrl", String.class);
        m.setAccessible(true);
        return (String) m.invoke(null, url);
    }
}
