package io.memoryos.connector;

import io.memoryos.connector.GoogleDriveAuthorizationService.Grant;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * The Google OAuth protocol for connecting a Google account to a Drive credential: the uploaded Web client, the
 * consent URL, the PKCE code exchange with ID-token validation, and refresh-token revocation. The browser session that
 * carries {@code state}, {@code nonce} and the code verifier between consent and callback belongs to the caller.
 * Failures never carry provider bodies or tokens.
 */
public interface GoogleDriveAccountClient {

    /**
     * Parses uploaded Google Web OAuth client JSON; {@code null} keeps the credential's current client.
     *
     * @throws GoogleDriveException {@code GOOGLE_DRIVE_OAUTH_CLIENT_INVALID}, or {@code GOOGLE_DRIVE_NOT_CONFIGURED}
     *                              when the deployment has no valid callback to match
     */
    @Nullable GoogleDriveOAuthClient parseClient(@Nullable String json);

    /** @throws GoogleDriveException {@code GOOGLE_DRIVE_NOT_CONFIGURED} when an OAuth endpoint or the callback is unusable */
    void requireConfigured();

    /** The Google consent URL for one pending authorization, bound to its state, nonce and S256 code challenge. */
    String authorizationUrl(String clientId, Consent consent);

    /**
     * Redeems the authorization code with the client and verifier of the same consent, validates the ID token (issuer,
     * audience, nonce, verified e-mail matching the Drive account) and returns the grant.
     *
     * @throws IllegalStateException without cause or provider detail when Google's response is not a valid grant
     */
    Grant exchange(String code, String codeVerifier, String nonce, GoogleDriveOAuthClient client);

    /** Revokes a refresh token at Google on a best-effort basis and clears the array; local disconnect has already committed. */
    void revoke(byte[] refreshToken);

    record Consent(String state, String nonce, String codeChallenge) {
        @Override public @NonNull String toString() { return "GoogleDriveConsent[redacted]"; }
    }
}
