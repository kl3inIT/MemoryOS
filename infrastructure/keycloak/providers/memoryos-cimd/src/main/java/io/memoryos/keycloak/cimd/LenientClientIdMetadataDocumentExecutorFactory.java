package io.memoryos.keycloak.cimd;

import org.keycloak.models.KeycloakSession;
import org.keycloak.protocol.oauth2.cimd.clientpolicy.executor.ClientIdMetadataDocumentExecutor;
import org.keycloak.protocol.oauth2.cimd.clientpolicy.executor.ClientIdMetadataDocumentExecutorFactory;
import org.keycloak.services.clientpolicy.executor.ClientPolicyExecutorProvider;

/**
 * MEM-114: registers {@link LenientClientIdMetadataDocumentExecutor} under its own id, with the built-in executor's
 * configuration properties, so a client profile names it in place of {@code client-id-metadata-document}.
 */
public class LenientClientIdMetadataDocumentExecutorFactory extends ClientIdMetadataDocumentExecutorFactory {

    public static final String PROVIDER_ID = "memoryos-client-id-metadata-document";

    @Override
    public ClientPolicyExecutorProvider<ClientIdMetadataDocumentExecutor.Configuration> create(KeycloakSession session) {
        return new LenientClientIdMetadataDocumentExecutor(session, providerConfig);
    }

    @Override
    public String getId() {
        return PROVIDER_ID;
    }

    @Override
    public String getHelpText() {
        return "Client ID Metadata Document executor that ignores document properties Keycloak does not know "
                + "(keycloak/keycloak#51236). MemoryOS uses it until a Keycloak release carries the upstream fix.";
    }
}
