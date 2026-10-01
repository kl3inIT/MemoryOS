package io.memoryos.keycloak.cimd;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;
import java.io.IOException;
import java.net.URI;
import org.jboss.logging.Logger;
import org.keycloak.OAuthErrorException;
import org.keycloak.http.simple.SimpleHttp;
import org.keycloak.http.simple.SimpleHttpResponse;
import org.keycloak.models.ClientModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.protocol.oauth2.cimd.clientpolicy.executor.ClientIdMetadataDocumentExecutor;
import org.keycloak.protocol.oauth2.cimd.clientpolicy.executor.ClientIdMetadataDocumentExecutorFactoryProviderConfig;
import org.keycloak.protocol.oauth2.cimd.provider.ClientIdMetadataDocumentProvider;
import org.keycloak.representations.oidc.OIDCClientRepresentation;
import org.keycloak.services.clientpolicy.ClientPolicyException;
import org.keycloak.util.JsonSerialization;

/**
 * MEM-114: Keycloak 26.8's Client ID Metadata Document executor, reading a document leniently.
 *
 * <p>Keycloak parses a fetched document strictly, so a document carrying a property its representation does not
 * know is rejected as "Client Metadata fetch failed" (keycloak/keycloak#51236). ChatGPT's document declares
 * {@code token_endpoint_auth_methods_supported}, which RFC 7591 section 2 says an authorization server ignores.
 * This executor is the upstream fix (keycloak/keycloak#51235) applied to the one method it changes; every check
 * the built-in executor makes is inherited unchanged. Remove it once a Keycloak release carries that fix.
 */
public class LenientClientIdMetadataDocumentExecutor extends ClientIdMetadataDocumentExecutor {

    private static final Logger LOGGER = Logger.getLogger(LenientClientIdMetadataDocumentExecutor.class);

    // Scoped to fetched documents, so Dynamic Client Registration and every other reader keep strict parsing.
    private static final ObjectMapper DOCUMENTS = JsonSerialization.createObjectMapperWithDefaults()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    public LenientClientIdMetadataDocumentExecutor(KeycloakSession session,
                                                   ClientIdMetadataDocumentExecutorFactoryProviderConfig providerConfig) {
        super(session, providerConfig);
    }

    @Override
    protected Logger getLogger() {
        return LOGGER;
    }

    @Override
    public String getProviderId() {
        return LenientClientIdMetadataDocumentExecutorFactory.PROVIDER_ID;
    }

    /** Keycloak 26.8.0's implementation, except that the document is read as a tree and bound leniently. */
    @Override
    @SuppressWarnings("rawtypes") // the overridden method declares the raw provider type
    protected OIDCClientRepresentationWithCacheControl fetchClientMetadata(final URI clientIdURI, final boolean isUpdate,
                                                                           ClientIdMetadataDocumentProvider provider)
            throws ClientPolicyException {
        String clientId = clientIdURI.toString();
        var request = SimpleHttp.create(session)
                .withMaxConsumedResponseSize(providerConfig.getUpperLimitMetadataBytes())
                .doGet(clientId);
        try (SimpleHttpResponse response = request.asResponse()) {
            int status = response.getStatus();
            if (!isUpdate && status != Response.Status.OK.getStatusCode()) {
                getLogger().warnv("fetching client metadata for the first time failed: clientId = {0}", clientId);
                throw invalidClientIdMetadata(ERR_METADATA_FETCH_FAILED);
            }
            if (isUpdate && status != Response.Status.OK.getStatusCode()
                    && status != Response.Status.NOT_MODIFIED.getStatusCode()) {
                getLogger().warnv("fetching client metadata for updating failed: clientId = {0}", clientId);
                throw invalidClientIdMetadata(ERR_METADATA_FETCH_FAILED);
            }

            ClientMetadataCacheControl cacheControl = new ClientMetadataCacheControl(
                    response.getFirstHeader(HttpHeaders.CACHE_CONTROL),
                    providerConfig.getMinCacheTime(), providerConfig.getMaxCacheTime());

            if (isUpdate && status == Response.Status.NOT_MODIFIED.getStatusCode()) {
                ClientModel client = session.getContext().getRealm().getClientByClientId(clientId);
                provider.setCacheExpiryTimeToClientMetadata(client, cacheControl.getCacheExpiryTimeInSec());
                return null;
            }

            OIDCClientRepresentation clientOIDC = DOCUMENTS.treeToValue(response.asJson(), OIDCClientRepresentation.class);
            if (clientOIDC == null) {
                throw invalidClientIdMetadata(ERR_METADATA_FETCH_FAILED);
            }
            augmentClientOIDC(clientOIDC);
            return new OIDCClientRepresentationWithCacheControl(clientOIDC, cacheControl);
        } catch (IOException e) {
            getLogger().warnv("HTTP connection failure: {0}", e);
            throw new ClientPolicyException(OAuthErrorException.INVALID_REQUEST, ERR_METADATA_FETCH_FAILED);
        }
    }
}
