package com.github.catatafishen.agentbridge.psi.review;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Pure serialization/deserialization for AgentEditSession persisted state.
 * Converts typed maps (ApprovalState, Long, Integer) to/from String maps
 * suitable for IntelliJ's XmlSerializer.
 */
public final class PersistedStateCodec {

    private PersistedStateCodec() {}

    /**
     * Serializes an approval state map to string values (enum name).
     */
    public static @NotNull Map<String, String> serializeApprovals(@NotNull Map<String, ApprovalState> approvals) {
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<String, ApprovalState> e : approvals.entrySet()) {
            result.put(e.getKey(), e.getValue().name());
        }
        return result;
    }

    /**
     * Deserializes string values back to ApprovalState.
     * Unknown enum values default to {@link ApprovalState#PENDING}.
     */
    public static @NotNull Map<String, ApprovalState> deserializeApprovals(@Nullable Map<String, String> raw) {
        Map<String, ApprovalState> result = new LinkedHashMap<>();
        if (raw == null) return result;
        for (Map.Entry<String, String> e : raw.entrySet()) {
            try {
                result.put(e.getKey(), ApprovalState.valueOf(e.getValue()));
            } catch (IllegalArgumentException ignored) {
                result.put(e.getKey(), ApprovalState.PENDING);
            }
        }
        return result;
    }

    /**
     * Serializes a Long map to string values.
     */
    public static @NotNull Map<String, String> serializeLongs(@NotNull Map<String, Long> map) {
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<String, Long> e : map.entrySet()) {
            result.put(e.getKey(), Long.toString(e.getValue()));
        }
        return result;
    }

    /**
     * Deserializes string values back to Long.
     * Malformed entries are silently skipped.
     */
    public static @NotNull Map<String, Long> deserializeLongs(@Nullable Map<String, String> raw) {
        Map<String, Long> result = new LinkedHashMap<>();
        if (raw == null) return result;
        for (Map.Entry<String, String> e : raw.entrySet()) {
            try {
                result.put(e.getKey(), Long.parseLong(e.getValue()));
            } catch (NumberFormatException ignored) {
                // skip malformed entries
            }
        }
        return result;
    }

    /**
     * Serializes an Integer map to string values.
     */
    public static @NotNull Map<String, String> serializeInts(@NotNull Map<String, Integer> map) {
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> e : map.entrySet()) {
            result.put(e.getKey(), Integer.toString(e.getValue()));
        }
        return result;
    }

    /**
     * Deserializes string values back to Integer.
     * Malformed entries are silently skipped.
     */
    public static @NotNull Map<String, Integer> deserializeInts(@Nullable Map<String, String> raw) {
        Map<String, Integer> result = new LinkedHashMap<>();
        if (raw == null) return result;
        for (Map.Entry<String, String> e : raw.entrySet()) {
            try {
                result.put(e.getKey(), Integer.parseInt(e.getValue()));
            } catch (NumberFormatException ignored) {
                // skip malformed entries
            }
        }
        return result;
    }
}
