package com.github.catatafishen.agentbridge.services;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link ToolCallHasher} — deterministic JSON hashing and hash matching.
 */
class ToolCallHasherTest {

    // ── computeBaseHash ─────────────────────────────────────────────────

    @Test
    void computeBaseHash_simpleObject_returnsDeterministicEightCharHash() {
        JsonObject obj = JsonParser.parseString("{\"a\":\"1\"}").getAsJsonObject();
        String hash = ToolCallHasher.computeBaseHash(obj);

        assertNotNull(hash);
        assertEquals(8, hash.length(), "Hash should be 8 hex characters");
        assertTrue(hash.matches("[0-9a-f]{8}"), "Hash should contain only hex digits");
    }

    @Test
    void computeBaseHash_calledTwice_returnsSameHash() {
        JsonObject obj = JsonParser.parseString("{\"a\":\"1\"}").getAsJsonObject();
        assertEquals(
                ToolCallHasher.computeBaseHash(obj),
                ToolCallHasher.computeBaseHash(obj));
    }

    @Test
    void computeBaseHash_keysSorted_sameHashRegardlessOfInsertionOrder() {
        JsonObject ab = JsonParser.parseString("{\"a\":\"1\",\"b\":\"2\"}").getAsJsonObject();
        JsonObject ba = JsonParser.parseString("{\"b\":\"2\",\"a\":\"1\"}").getAsJsonObject();

        assertEquals(ToolCallHasher.computeBaseHash(ab),
                ToolCallHasher.computeBaseHash(ba));
    }

    @Test
    void computeBaseHash_excludesToolUsePurposeKey() {
        JsonObject without = JsonParser.parseString("{\"a\":\"1\"}").getAsJsonObject();
        JsonObject with = JsonParser.parseString(
                "{\"a\":\"1\",\"__tool_use_purpose\":\"test\"}").getAsJsonObject();

        assertEquals(ToolCallHasher.computeBaseHash(without),
                ToolCallHasher.computeBaseHash(with));
    }

    @Test
    void computeBaseHash_onlyToolUsePurpose_sameAsEmpty() {
        JsonObject empty = JsonParser.parseString("{}").getAsJsonObject();
        JsonObject purposeOnly = JsonParser.parseString(
                "{\"__tool_use_purpose\":\"ignored\"}").getAsJsonObject();

        assertEquals(ToolCallHasher.computeBaseHash(empty),
                ToolCallHasher.computeBaseHash(purposeOnly));
    }

    @Test
    void computeBaseHash_emptyObject_returnsValidHash() {
        JsonObject empty = JsonParser.parseString("{}").getAsJsonObject();
        String hash = ToolCallHasher.computeBaseHash(empty);

        assertNotNull(hash);
        assertEquals(8, hash.length());
    }

    @Test
    void computeBaseHash_nestedObjects_stableAcrossKeyOrder() {
        JsonObject obj1 = JsonParser.parseString(
                "{\"outer\":{\"b\":\"2\",\"a\":\"1\"}}").getAsJsonObject();
        JsonObject obj2 = JsonParser.parseString(
                "{\"outer\":{\"a\":\"1\",\"b\":\"2\"}}").getAsJsonObject();

        assertEquals(ToolCallHasher.computeBaseHash(obj1),
                ToolCallHasher.computeBaseHash(obj2));
    }

    @Test
    void computeBaseHash_differentValues_produceDifferentHashes() {
        JsonObject obj1 = JsonParser.parseString("{\"a\":\"1\"}").getAsJsonObject();
        JsonObject obj2 = JsonParser.parseString("{\"a\":\"2\"}").getAsJsonObject();

        assertNotEquals(ToolCallHasher.computeBaseHash(obj1),
                ToolCallHasher.computeBaseHash(obj2));
    }

    @Test
    void computeBaseHash_differentKeys_produceDifferentHashes() {
        JsonObject obj1 = JsonParser.parseString("{\"a\":\"1\"}").getAsJsonObject();
        JsonObject obj2 = JsonParser.parseString("{\"b\":\"1\"}").getAsJsonObject();

        assertNotEquals(ToolCallHasher.computeBaseHash(obj1),
                ToolCallHasher.computeBaseHash(obj2));
    }

    // ── computeStableValue ──────────────────────────────────────────────

    @Test
    void computeStableValue_string_returnedQuoted() {
        JsonElement elem = JsonParser.parseString("\"hello\"");
        assertEquals("\"hello\"", ToolCallHasher.computeStableValue(elem));
    }

    @Test
    void computeStableValue_integer_returnsLongString() {
        JsonElement elem = JsonParser.parseString("42");
        assertEquals("42", ToolCallHasher.computeStableValue(elem));
    }

    @Test
    void computeStableValue_longPrimitive_returnsLongString() {
        JsonElement elem = new JsonPrimitive(100L);
        assertEquals("100", ToolCallHasher.computeStableValue(elem));
    }

    @Test
    void computeStableValue_wholeDouble_convertedToLong() {
        JsonElement elem = new JsonPrimitive(3.0);
        assertEquals("3", ToolCallHasher.computeStableValue(elem));
    }

    @Test
    void computeStableValue_negativeWholeDouble_convertedToLong() {
        JsonElement elem = new JsonPrimitive(-5.0);
        assertEquals("-5", ToolCallHasher.computeStableValue(elem));
    }

    @Test
    void computeStableValue_zeroDouble_convertedToLong() {
        JsonElement elem = new JsonPrimitive(0.0);
        assertEquals("0", ToolCallHasher.computeStableValue(elem));
    }

    @Test
    void computeStableValue_fractionalDouble_keptAsDouble() {
        JsonElement elem = new JsonPrimitive(3.14);
        String result = ToolCallHasher.computeStableValue(elem);
        assertTrue(result.contains("3.14"), "Fractional double should be preserved: " + result);
    }

    @Test
    void computeStableValue_booleanTrue_returnsTrue() {
        assertEquals("true", ToolCallHasher.computeStableValue(JsonParser.parseString("true")));
    }

    @Test
    void computeStableValue_booleanFalse_returnsFalse() {
        assertEquals("false", ToolCallHasher.computeStableValue(JsonParser.parseString("false")));
    }

    @Test
    void computeStableValue_javaNull_returnsNullString() {
        assertEquals("null", ToolCallHasher.computeStableValue(null));
    }

    @Test
    void computeStableValue_jsonNull_returnsNullString() {
        assertEquals("null", ToolCallHasher.computeStableValue(JsonNull.INSTANCE));
    }

    @Test
    void computeStableValue_jsonObject_keysSorted() {
        JsonElement obj = JsonParser.parseString("{\"z\":\"last\",\"a\":\"first\"}");
        String result = ToolCallHasher.computeStableValue(obj);

        int aPos = result.indexOf("a=");
        int zPos = result.indexOf("z=");
        assertTrue(aPos >= 0 && zPos >= 0, "Both keys should be present: " + result);
        assertTrue(aPos < zPos, "Keys should be sorted alphabetically: " + result);
    }

    @Test
    void computeStableValue_jsonArray_elementsNormalized() {
        // 3.0 should be normalized to "3"; string and boolean kept as-is
        JsonElement arr = JsonParser.parseString("[3.0, \"text\", true]");
        String result = ToolCallHasher.computeStableValue(arr);

        // ArrayList.toString() wraps with [ ] and separates with ", "
        assertNotNull(result);
        assertTrue(result.startsWith("["), "Array should start with [");
        assertTrue(result.contains("\"text\""), "String should be preserved");
        assertTrue(result.contains("true"), "Boolean should be preserved");
    }

    @Test
    void computeStableValue_emptyArray_returnsEmptyList() {
        JsonElement arr = JsonParser.parseString("[]");
        assertEquals("[]", ToolCallHasher.computeStableValue(arr));
    }

    @Test
    void computeStableValue_emptyObject_returnsEmptyMap() {
        JsonElement obj = JsonParser.parseString("{}");
        assertEquals("{}", ToolCallHasher.computeStableValue(obj));
    }

    @Test
    void computeStableValue_nestedStructure_recursiveNormalization() {
        JsonElement nested = JsonParser.parseString(
                "{\"arr\":[{\"b\":2,\"a\":1}],\"obj\":{\"y\":1.0,\"x\":\"val\"}}");
        String result = ToolCallHasher.computeStableValue(nested);

        assertNotNull(result);
        // Outer keys sorted: "arr" before "obj"
        assertTrue(result.indexOf("arr=") < result.indexOf("obj="),
                "Outer keys should be sorted: " + result);
        // Inner object in array: "a" before "b"
        assertTrue(result.indexOf("a=") < result.indexOf("b="),
                "Inner keys should be sorted: " + result);
        // 1.0 normalized to 1 (long)
        assertFalse(result.contains("1.0"), "Whole doubles should be normalized: " + result);
    }

    @Test
    void computeStableValue_arrayWithNulls_handledGracefully() {
        JsonArray arr = new JsonArray();
        arr.add(JsonNull.INSTANCE);
        arr.add(new JsonPrimitive("ok"));
        String result = ToolCallHasher.computeStableValue(arr);

        assertTrue(result.contains("null"), "Null elements should be rendered: " + result);
        assertTrue(result.contains("\"ok\""), "Non-null elements should be preserved: " + result);
    }

    // ── isMatchingHash ──────────────────────────────────────────────────

    @Test
    void isMatchingHash_exactMatch_returnsTrue() {
        assertTrue(ToolCallHasher.isMatchingHash("abc12345", "abc12345"));
    }

    @Test
    void isMatchingHash_prefixWithDashSuffix_returnsTrue() {
        assertTrue(ToolCallHasher.isMatchingHash("abc12345-1", "abc12345"));
    }

    @Test
    void isMatchingHash_prefixWithLongerSuffix_returnsTrue() {
        assertTrue(ToolCallHasher.isMatchingHash("abc12345-extra-data", "abc12345"));
    }

    @Test
    void isMatchingHash_noMatch_returnsFalse() {
        assertFalse(ToolCallHasher.isMatchingHash("xyz00000", "abc12345"));
    }

    @Test
    void isMatchingHash_prefixWithoutDash_returnsFalse() {
        assertFalse(ToolCallHasher.isMatchingHash("abc12345extra", "abc12345"));
    }

    @Test
    void isMatchingHash_hashIsSubstringButNotPrefix_returnsFalse() {
        assertFalse(ToolCallHasher.isMatchingHash("xxabc12345", "abc12345"));
    }

    @Test
    void isMatchingHash_emptyChipId_returnsFalse() {
        assertFalse(ToolCallHasher.isMatchingHash("", "abc12345"));
    }

    @Test
    void isMatchingHash_emptyBaseHash_matchesAnythingWithDash() {
        // "" equals "" → true
        assertTrue(ToolCallHasher.isMatchingHash("", ""));
        // "-foo" starts with "" + "-" = "-" → true
        assertTrue(ToolCallHasher.isMatchingHash("-foo", ""));
    }

    @Test
    void isMatchingHash_nullChipId_throwsException() {
        //noinspection DataFlowIssue — testing @NotNull contract
        assertThrows(Exception.class,
                () -> ToolCallHasher.isMatchingHash(null, "abc12345"));
    }

    @Test
    void isMatchingHash_nullBaseHash_returnsFalse() {
        //noinspection DataFlowIssue — testing @NotNull contract edge case
        // null + "-" becomes "null-", so equals and startsWith both return false
        assertFalse(ToolCallHasher.isMatchingHash("abc12345", null));
    }

    // ── EXCLUDED_KEY constant ───────────────────────────────────────────

    @Test
    void excludedKeyConstant_hasExpectedValue() {
        assertEquals("__tool_use_purpose", ToolCallHasher.EXCLUDED_KEY);
    }
}
