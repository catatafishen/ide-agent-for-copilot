package com.github.catatafishen.agentbridge.agent.codex;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link CodexCredentials#parseCredentials(String)}.
 * Tests the pure JSON parsing logic without any filesystem dependency.
 */
class CodexCredentialsParseTest {

    // ── API key auth ──────────────────────────────────────────────────────────

    @Nested
    class ApiKeyAuth {

        @Test
        void validApiKey_loggedIn() {
            CodexCredentials creds = CodexCredentials.parseCredentials("{\"api_key\":\"sk-abc123\"}");
            assertTrue(creds.isLoggedIn());
            assertNull(creds.getDisplayName());
        }

        @Test
        void blankApiKey_notLoggedIn() {
            CodexCredentials creds = CodexCredentials.parseCredentials("{\"api_key\":\"\"}");
            assertFalse(creds.isLoggedIn());
        }

        @Test
        void whitespaceOnlyApiKey_notLoggedIn() {
            CodexCredentials creds = CodexCredentials.parseCredentials("{\"api_key\":\"   \"}");
            assertFalse(creds.isLoggedIn());
        }

        @Test
        void nullApiKey_notLoggedIn() {
            CodexCredentials creds = CodexCredentials.parseCredentials("{\"api_key\":null}");
            assertFalse(creds.isLoggedIn());
        }

        @Test
        void apiKeyTakesPriorityOverAccessToken() {
            String json = "{\"api_key\":\"sk-key\",\"access_token\":\"tok-123\"}";
            CodexCredentials creds = CodexCredentials.parseCredentials(json);
            assertTrue(creds.isLoggedIn());
            // API key path returns null displayName
            assertNull(creds.getDisplayName());
        }
    }

    // ── Nested tokens format (device auth) ────────────────────────────────────

    @Nested
    class NestedTokensAuth {

        @Test
        void validNestedToken_loggedIn() {
            String json = """
                {
                  "auth_mode": "chatgpt",
                  "tokens": { "access_token": "tok-device-xyz" },
                  "last_refresh": "2024-01-01"
                }
                """;
            CodexCredentials creds = CodexCredentials.parseCredentials(json);
            assertTrue(creds.isLoggedIn());
        }

        @Test
        void blankNestedToken_notLoggedIn() {
            CodexCredentials creds = CodexCredentials.parseCredentials(
                "{\"tokens\":{\"access_token\":\"\"}}");
            assertFalse(creds.isLoggedIn());
        }

        @Test
        void nullNestedToken_notLoggedIn() {
            CodexCredentials creds = CodexCredentials.parseCredentials(
                "{\"tokens\":{\"access_token\":null}}");
            assertFalse(creds.isLoggedIn());
        }

        @Test
        void tokensFieldNotAnObject_skipped() {
            CodexCredentials creds = CodexCredentials.parseCredentials(
                "{\"tokens\":\"not-an-object\"}");
            assertFalse(creds.isLoggedIn());
        }

        @Test
        void tokensWithoutAccessToken_notLoggedIn() {
            CodexCredentials creds = CodexCredentials.parseCredentials(
                "{\"tokens\":{\"refresh_token\":\"tok-refresh\"}}");
            assertFalse(creds.isLoggedIn());
        }
    }

    // ── Flat OAuth / ChatGPT format ───────────────────────────────────────────

    @Nested
    class FlatOAuthAuth {

        @Test
        void validTokenWithFutureExpiry_loggedIn() {
            long futureSeconds = Instant.now().plus(365, ChronoUnit.DAYS).getEpochSecond();
            String json = "{\"access_token\":\"tok-oauth\",\"expires_at\":" + futureSeconds + "}";
            CodexCredentials creds = CodexCredentials.parseCredentials(json);
            assertTrue(creds.isLoggedIn());
        }

        @Test
        void validTokenWithEmail_returnsDisplayName() {
            long futureSeconds = Instant.now().plus(365, ChronoUnit.DAYS).getEpochSecond();
            String json = "{\"access_token\":\"tok-oauth\",\"expires_at\":" + futureSeconds
                + ",\"email\":\"user@example.com\"}";
            CodexCredentials creds = CodexCredentials.parseCredentials(json);
            assertTrue(creds.isLoggedIn());
            assertEquals("user@example.com", creds.getDisplayName());
        }

        @Test
        void validTokenWithoutEmail_nullDisplayName() {
            long futureSeconds = Instant.now().plus(365, ChronoUnit.DAYS).getEpochSecond();
            String json = "{\"access_token\":\"tok-oauth\",\"expires_at\":" + futureSeconds + "}";
            CodexCredentials creds = CodexCredentials.parseCredentials(json);
            assertTrue(creds.isLoggedIn());
            assertNull(creds.getDisplayName());
        }

        @Test
        void expiredToken_notLoggedIn() {
            long pastSeconds = Instant.now().minus(365, ChronoUnit.DAYS).getEpochSecond();
            String json = "{\"access_token\":\"tok-old\",\"expires_at\":" + pastSeconds + "}";
            CodexCredentials creds = CodexCredentials.parseCredentials(json);
            assertFalse(creds.isLoggedIn());
        }

        @Test
        void noExpiresAt_loggedIn() {
            CodexCredentials creds = CodexCredentials.parseCredentials(
                "{\"access_token\":\"tok-no-expiry\"}");
            assertTrue(creds.isLoggedIn());
        }

        @Test
        void expiresAtZero_treatedAsNoExpiry() {
            CodexCredentials creds = CodexCredentials.parseCredentials(
                "{\"access_token\":\"tok-zero\",\"expires_at\":0}");
            assertTrue(creds.isLoggedIn());
        }

        @Test
        void blankFlatToken_notLoggedIn() {
            CodexCredentials creds = CodexCredentials.parseCredentials(
                "{\"access_token\":\"\"}");
            assertFalse(creds.isLoggedIn());
        }

        @Test
        void nullFlatToken_notLoggedIn() {
            CodexCredentials creds = CodexCredentials.parseCredentials(
                "{\"access_token\":null}");
            assertFalse(creds.isLoggedIn());
        }
    }

    // ── Edge cases / error handling ───────────────────────────────────────────

    @Nested
    class ErrorHandling {

        @Test
        void emptyJsonObject_notLoggedIn() {
            CodexCredentials creds = CodexCredentials.parseCredentials("{}");
            assertFalse(creds.isLoggedIn());
            assertNull(creds.getDisplayName());
        }

        @Test
        void malformedJson_notLoggedIn() {
            CodexCredentials creds = CodexCredentials.parseCredentials("this is not JSON {{{");
            assertFalse(creds.isLoggedIn());
        }

        @Test
        void emptyString_notLoggedIn() {
            CodexCredentials creds = CodexCredentials.parseCredentials("");
            assertFalse(creds.isLoggedIn());
        }

        @Test
        void jsonArray_notLoggedIn() {
            CodexCredentials creds = CodexCredentials.parseCredentials("[1, 2, 3]");
            assertFalse(creds.isLoggedIn());
        }

        @Test
        void jsonPrimitive_notLoggedIn() {
            CodexCredentials creds = CodexCredentials.parseCredentials("\"just a string\"");
            assertFalse(creds.isLoggedIn());
        }

        @Test
        void unrelatedFields_notLoggedIn() {
            CodexCredentials creds = CodexCredentials.parseCredentials(
                "{\"foo\":\"bar\",\"count\":42}");
            assertFalse(creds.isLoggedIn());
        }
    }
}
