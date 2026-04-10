package com.github.catatafishen.agentbridge.services;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("PermissionTemplateUtil")
class PermissionTemplateUtilTest {

    // ── substituteArgs ────────────────────────────────────────────────────────

    @Test
    @DisplayName("substitutes a single named placeholder")
    void substituteSingle() {
        JsonObject args = new JsonObject();
        args.addProperty("path", "/foo/bar.kt");
        String result = PermissionTemplateUtil.substituteArgs("Read {path}?", args);
        assertEquals("Read /foo/bar.kt?", result);
    }

    @Test
    @DisplayName("substitutes multiple placeholders")
    void substituteMultiple() {
        JsonObject args = new JsonObject();
        args.addProperty("file", "Main.java");
        args.addProperty("line", "42");
        String result = PermissionTemplateUtil.substituteArgs("Edit {file} at {line}", args);
        assertEquals("Edit Main.java at 42", result);
    }

    @Test
    @DisplayName("leaves unknown placeholders untouched")
    void unknownPlaceholdersUntouched() {
        JsonObject args = new JsonObject();
        args.addProperty("x", "hello");
        String result = PermissionTemplateUtil.substituteArgs("{x} and {y}", args);
        assertEquals("hello and {y}", result);
    }

    @Test
    @DisplayName("null JSON value substitutes as empty string")
    void nullValueSubstitutesEmpty() {
        JsonObject args = new JsonObject();
        args.add("key", com.google.gson.JsonNull.INSTANCE);
        String result = PermissionTemplateUtil.substituteArgs("val={key}", args);
        assertEquals("val=", result);
    }

    @Test
    @DisplayName("long string values are truncated to 60 chars with ellipsis")
    void longValueTruncated() {
        JsonObject args = new JsonObject();
        String longVal = "a".repeat(100);
        args.addProperty("v", longVal);
        String result = PermissionTemplateUtil.substituteArgs("{v}", args);
        assertEquals(58, result.length(), "should be 57 chars + ellipsis character");
        assertTrue(result.endsWith("…"), "should end with ellipsis");
    }

    @Test
    @DisplayName("60-char value is NOT truncated")
    void exactBoundaryNotTruncated() {
        JsonObject args = new JsonObject();
        String exact = "b".repeat(60);
        args.addProperty("v", exact);
        String result = PermissionTemplateUtil.substituteArgs("{v}", args);
        assertEquals(exact, result);
    }

    @Test
    @DisplayName("array values are joined with comma-space")
    void arrayValueJoined() {
        JsonObject args = new JsonObject();
        com.google.gson.JsonArray arr = new com.google.gson.JsonArray();
        arr.add("alpha");
        arr.add("beta");
        args.add("list", arr);
        String result = PermissionTemplateUtil.substituteArgs("{list}", args);
        assertEquals("alpha, beta", result);
    }

    @Test
    @DisplayName("empty template returns empty string")
    void emptyTemplate() {
        String result = PermissionTemplateUtil.substituteArgs("", new JsonObject());
        assertEquals("", result);
    }

    // ── stripPlaceholders ────────────────────────────────────────────────────

    @Test
    @DisplayName("removes placeholder tokens")
    void stripsPlaceholders() {
        String result = PermissionTemplateUtil.stripPlaceholders("Delete {path}?");
        assertEquals("Delete ?", result);
    }

    @Test
    @DisplayName("removes empty parentheses left after strip")
    void stripsEmptyParens() {
        String result = PermissionTemplateUtil.stripPlaceholders("Run ({cmd})?");
        assertEquals("Run ?", result);
    }

    @Test
    @DisplayName("collapses multiple spaces after stripping")
    void collapsesSpaces() {
        String result = PermissionTemplateUtil.stripPlaceholders("a {b}  c");
        assertEquals("a c", result);
    }

    @Test
    @DisplayName("returns null when result is empty after stripping")
    void returnsNullWhenEmpty() {
        assertNull(PermissionTemplateUtil.stripPlaceholders("{placeholder}"));
    }

    @Test
    @DisplayName("returns null for all-whitespace input")
    void returnsNullForWhitespace() {
        assertNull(PermissionTemplateUtil.stripPlaceholders("   "));
    }

    @Test
    @DisplayName("preserves plain text with no placeholders")
    void preservesPlainText() {
        assertEquals("Just text", PermissionTemplateUtil.stripPlaceholders("Just text"));
    }
}
