package io.memoryos.integration.microsoft;

import io.memoryos.connector.SharePointGateway;

/** Separates Entra token acquisition from the Microsoft calls that carry the token. */
interface SharePointTokenSource {
    String GRAPH_SCOPE = "https://graph.microsoft.com/.default";

    /** The Entra application of one credential, for one provider session; nothing is asked of Entra yet. */
    Tokens open(SharePointGateway.Credential credential);

    /** Tokens of one application. Microsoft Graph and SharePoint REST each take a token of their own scope. */
    interface Tokens {
        String token(String scope);
    }
}
