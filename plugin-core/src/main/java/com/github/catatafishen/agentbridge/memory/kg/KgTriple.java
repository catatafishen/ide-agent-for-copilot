package com.github.catatafishen.agentbridge.memory.kg;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.util.regex.Pattern;

public record KgTriple(
    long id,
    @NotNull String subject,
    @NotNull String predicate,
    @NotNull String object,
    @Nullable Instant validFrom,
    @Nullable Instant validUntil,
    @Nullable String sourceDrawer,
    @NotNull Instant createdAt,
    @NotNull String evidence
) {

    /**
     * Maximum length for entity names (subject, predicate).
     */
    public static final int MAX_NAME_LENGTH = 128;

    /**
     * Maximum length for object content.
     */
    public static final int MAX_CONTENT_LENGTH = 100_000;

    /**
     * Safe characters for entity names: letters, digits, spaces, hyphens, underscores, dots.
     */
    private static final Pattern SAFE_NAME = Pattern.compile("[a-zA-Z0-9 _\\-.]+");

    /**
     * Sanitize an entity name (subject or predicate): trim, enforce max length,
     * strip unsafe characters, prevent path traversal.
     *
     * @param name the raw name
     * @return sanitized name
     * @throws IllegalArgumentException if name is empty after sanitization
     */
    public static @NotNull String sanitizeName(@NotNull String name) {
        String sanitized = name.strip();
        sanitized = sanitized.replace("\0", "");
        sanitized = sanitized.replace("..", "");
        sanitized = sanitized.replace("/", "");
        sanitized = sanitized.replace("\\", "");
        if (sanitized.length() > MAX_NAME_LENGTH) {
            sanitized = sanitized.substring(0, MAX_NAME_LENGTH);
        }
        if (sanitized.isEmpty()) {
            throw new IllegalArgumentException("Name is empty after sanitization");
        }
        return sanitized;
    }

    /**
     * Sanitize object content: strip null bytes, enforce max length.
     *
     * @param content the raw content
     * @return sanitized content
     * @throws IllegalArgumentException if content is empty after sanitization
     */
    public static @NotNull String sanitizeContent(@NotNull String content) {
        String sanitized = content.replace("\0", "");
        if (sanitized.length() > MAX_CONTENT_LENGTH) {
            sanitized = sanitized.substring(0, MAX_CONTENT_LENGTH);
        }
        if (sanitized.isBlank()) {
            throw new IllegalArgumentException("Content is empty after sanitization");
        }
        return sanitized;
    }

    /**
     * Check whether a name contains only safe characters.
     */
    public static boolean isSafeName(@NotNull String name) {
        return SAFE_NAME.matcher(name).matches();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private long id;
        private String subject = "";
        private String predicate = "";
        private String object = "";
        private Instant validFrom;
        private Instant validUntil;
        private String sourceDrawer;
        private Instant createdAt = Instant.now();
        private String evidence = "";

        public Builder id(long id) {
            this.id = id;
            return this;
        }

        public Builder subject(@NotNull String subject) {
            this.subject = sanitizeName(subject);
            return this;
        }

        public Builder predicate(@NotNull String predicate) {
            this.predicate = sanitizeName(predicate);
            return this;
        }

        public Builder object(@NotNull String object) {
            this.object = sanitizeContent(object);
            return this;
        }

        public Builder validFrom(@Nullable Instant validFrom) {
            this.validFrom = validFrom;
            return this;
        }

        public Builder validUntil(@Nullable Instant validUntil) {
            this.validUntil = validUntil;
            return this;
        }

        public Builder sourceDrawer(@Nullable String sourceDrawer) {
            this.sourceDrawer = sourceDrawer;
            return this;
        }

        public Builder createdAt(@NotNull Instant createdAt) {
            this.createdAt = createdAt;
            return this;
        }

        /**
         * JSON array of evidence references (FQNs, file:line refs).
         */
        public Builder evidence(@NotNull String evidence) {
            this.evidence = evidence;
            return this;
        }

        public KgTriple build() {
            return new KgTriple(id, subject, predicate, object, validFrom, validUntil, sourceDrawer, createdAt, evidence);
        }
    }
}
