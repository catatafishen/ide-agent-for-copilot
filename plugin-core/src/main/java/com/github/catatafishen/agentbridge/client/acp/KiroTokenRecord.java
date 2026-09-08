package com.github.catatafishen.agentbridge.client.acp;

import org.jetbrains.annotations.Nullable;

/**
 * Holds the Kiro CLI OIDC token fields read from the local SQLite database during a
 * {@code _kiro/auth/getAccessToken} reverse callback.
 *
 * @param accessToken the raw OIDC access token string
 * @param expiresAt   ISO-8601 expiry instant (e.g. {@code 2026-08-26T07:25:55Z})
 * @param profileArn  Q Developer profile ARN, or {@code null} for Builder ID / free-tier accounts
 */
public record KiroTokenRecord(
        String accessToken,
        String expiresAt,
        @Nullable String profileArn) {
}
