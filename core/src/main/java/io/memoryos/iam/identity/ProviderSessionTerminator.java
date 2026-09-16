package io.memoryos.iam.identity;

/**
 * Ends one identity-provider browser session, identified by the OIDC {@code sid} claim of the ID token that opened
 * the application session, so application sign-out does not have to send the browser to a provider logout page.
 */
public interface ProviderSessionTerminator {

    /**
     * @param providerSessionId the provider session identifier ({@code sid})
     * @return {@code true} when the provider no longer holds the session, whether it ended now or had already
     *         ended; {@code false} when the provider could not be reached or refused the request
     */
    boolean end(String providerSessionId);
}
