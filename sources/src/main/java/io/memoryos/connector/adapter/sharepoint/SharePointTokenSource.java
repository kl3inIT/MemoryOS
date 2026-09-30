package io.memoryos.connector.adapter.sharepoint;

import io.memoryos.connector.SharePointGateway;

/** Separates Entra token acquisition from the Graph calls that carry the token. */
interface SharePointTokenSource {
    String token(SharePointGateway.Credential credential);
}
