package com.github.catatafishen.agentbridge.psi.review;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class PersistedStateCodecTest {

    @Nested
    class SerializeApprovals {

        @Test
        void normalCase() {
            Map<String, ApprovalState> input = new LinkedHashMap<>();
            input.put("file1.java", ApprovalState.APPROVED);
            input.put("file2.java", ApprovalState.PENDING);

            Map<String, String> result = PersistedStateCodec.serializeApprovals(input);

            assertEquals(2, result.size());
            assertEquals("APPROVED", result.get("file1.java"));
            assertEquals("PENDING", result.get("file2.java"));
        }

        @Test
        void emptyMap() {
            Map<String, String> result = PersistedStateCodec.serializeApprovals(Collections.emptyMap());
            assertTrue(result.isEmpty());
        }
    }

    @Nested
    class DeserializeApprovals {

        @Test
        void normalCase() {
            Map<String, String> input = new LinkedHashMap<>();
            input.put("file1.java", "APPROVED");
            input.put("file2.java", "PENDING");

            Map<String, ApprovalState> result = PersistedStateCodec.deserializeApprovals(input);

            assertEquals(2, result.size());
            assertEquals(ApprovalState.APPROVED, result.get("file1.java"));
            assertEquals(ApprovalState.PENDING, result.get("file2.java"));
        }

        @Test
        void nullInputReturnsEmpty() {
            Map<String, ApprovalState> result = PersistedStateCodec.deserializeApprovals(null);
            assertNotNull(result);
            assertTrue(result.isEmpty());
        }

        @Test
        void unknownEnumValueDefaultsToPending() {
            Map<String, String> input = new LinkedHashMap<>();
            input.put("file1.java", "UNKNOWN_VALUE");
            input.put("file2.java", "garbage");

            Map<String, ApprovalState> result = PersistedStateCodec.deserializeApprovals(input);

            assertEquals(2, result.size());
            assertEquals(ApprovalState.PENDING, result.get("file1.java"));
            assertEquals(ApprovalState.PENDING, result.get("file2.java"));
        }

        @Test
        void emptyMap() {
            Map<String, ApprovalState> result = PersistedStateCodec.deserializeApprovals(Collections.emptyMap());
            assertTrue(result.isEmpty());
        }
    }

    @Nested
    class SerializeLongs {

        @Test
        void normalValuesIncludingNegativeAndZero() {
            Map<String, Long> input = new LinkedHashMap<>();
            input.put("a", 123456789L);
            input.put("b", -99L);
            input.put("c", 0L);

            Map<String, String> result = PersistedStateCodec.serializeLongs(input);

            assertEquals(3, result.size());
            assertEquals("123456789", result.get("a"));
            assertEquals("-99", result.get("b"));
            assertEquals("0", result.get("c"));
        }
    }

    @Nested
    class DeserializeLongs {

        @Test
        void normalCase() {
            Map<String, String> input = new LinkedHashMap<>();
            input.put("a", "100");
            input.put("b", "-50");

            Map<String, Long> result = PersistedStateCodec.deserializeLongs(input);

            assertEquals(2, result.size());
            assertEquals(100L, result.get("a"));
            assertEquals(-50L, result.get("b"));
        }

        @Test
        void nullInputReturnsEmpty() {
            Map<String, Long> result = PersistedStateCodec.deserializeLongs(null);
            assertNotNull(result);
            assertTrue(result.isEmpty());
        }

        @Test
        void malformedEntriesSkipped() {
            Map<String, String> input = new LinkedHashMap<>();
            input.put("bad1", "abc");
            input.put("bad2", "");
            input.put("bad3", "12.5");

            Map<String, Long> result = PersistedStateCodec.deserializeLongs(input);

            assertTrue(result.isEmpty());
        }

        @Test
        void validEntriesPreservedAlongsideMalformed() {
            Map<String, String> input = new LinkedHashMap<>();
            input.put("good", "42");
            input.put("bad", "abc");
            input.put("alsoGood", "-7");

            Map<String, Long> result = PersistedStateCodec.deserializeLongs(input);

            assertEquals(2, result.size());
            assertEquals(42L, result.get("good"));
            assertEquals(-7L, result.get("alsoGood"));
            assertNull(result.get("bad"));
        }
    }

    @Nested
    class SerializeInts {

        @Test
        void normalValues() {
            Map<String, Integer> input = new LinkedHashMap<>();
            input.put("x", 1);
            input.put("y", -2);
            input.put("z", 0);

            Map<String, String> result = PersistedStateCodec.serializeInts(input);

            assertEquals(3, result.size());
            assertEquals("1", result.get("x"));
            assertEquals("-2", result.get("y"));
            assertEquals("0", result.get("z"));
        }
    }

    @Nested
    class DeserializeInts {

        @Test
        void normalCase() {
            Map<String, String> input = new LinkedHashMap<>();
            input.put("x", "10");
            input.put("y", "-3");

            Map<String, Integer> result = PersistedStateCodec.deserializeInts(input);

            assertEquals(2, result.size());
            assertEquals(10, result.get("x"));
            assertEquals(-3, result.get("y"));
        }

        @Test
        void nullInputReturnsEmpty() {
            Map<String, Integer> result = PersistedStateCodec.deserializeInts(null);
            assertNotNull(result);
            assertTrue(result.isEmpty());
        }

        @Test
        void malformedEntriesSkipped() {
            Map<String, String> input = new LinkedHashMap<>();
            input.put("bad1", "abc");
            input.put("bad2", "");
            input.put("bad3", "12.5");

            Map<String, Integer> result = PersistedStateCodec.deserializeInts(input);

            assertTrue(result.isEmpty());
        }

        @Test
        void overflowSkipped() {
            Map<String, String> input = new LinkedHashMap<>();
            input.put("overflow", Long.toString(Long.MAX_VALUE));
            input.put("valid", "5");

            Map<String, Integer> result = PersistedStateCodec.deserializeInts(input);

            assertEquals(1, result.size());
            assertEquals(5, result.get("valid"));
            assertNull(result.get("overflow"));
        }
    }

    @Nested
    class RoundTrip {

        @Test
        void approvalsRoundTrip() {
            Map<String, ApprovalState> original = new LinkedHashMap<>();
            original.put("src/Main.java", ApprovalState.APPROVED);
            original.put("src/Test.java", ApprovalState.PENDING);

            Map<String, String> serialized = PersistedStateCodec.serializeApprovals(original);
            Map<String, ApprovalState> deserialized = PersistedStateCodec.deserializeApprovals(serialized);

            assertEquals(original, deserialized);
        }

        @Test
        void longsRoundTrip() {
            Map<String, Long> original = new LinkedHashMap<>();
            original.put("ts1", 1700000000000L);
            original.put("ts2", -1L);
            original.put("ts3", 0L);

            Map<String, String> serialized = PersistedStateCodec.serializeLongs(original);
            Map<String, Long> deserialized = PersistedStateCodec.deserializeLongs(serialized);

            assertEquals(original, deserialized);
        }

        @Test
        void intsRoundTrip() {
            Map<String, Integer> original = new LinkedHashMap<>();
            original.put("count", 42);
            original.put("negative", -100);
            original.put("zero", 0);

            Map<String, String> serialized = PersistedStateCodec.serializeInts(original);
            Map<String, Integer> deserialized = PersistedStateCodec.deserializeInts(serialized);

            assertEquals(original, deserialized);
        }
    }
}
