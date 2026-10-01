package io.memoryos.iam;

import io.memoryos.shared.ActorId;
import java.util.List;

/**
 * MEM-114: the member's own grants to the MCP endpoint's outside clients. Keycloak keeps them as user consents with an
 * offline session, so revoking one also ends the client's refresh token; an access token it already holds lives until
 * it expires.
 */
public interface McpClientGrants {

    /** The OAuth scope that lets a client read knowledge through the MCP endpoint. */
    String KNOWLEDGE_READ_SCOPE = "knowledge:read";

    /** The confidential client the realm script creates for ChatGPT; Claude identifies itself by a metadata document. */
    String CHATGPT_CLIENT_ID = "memoryos-chatgpt";

    /** The member's grants that carry {@link #KNOWLEDGE_READ_SCOPE}, newest first. */
    List<McpClientGrant> list(ActorId actor);

    /**
     * Revokes the member's grant to one client of {@link #list}.
     *
     * @throws McpClientGrantException {@code NOT_FOUND} when the member has no such grant
     */
    void revoke(ActorId actor, String clientId);
}
