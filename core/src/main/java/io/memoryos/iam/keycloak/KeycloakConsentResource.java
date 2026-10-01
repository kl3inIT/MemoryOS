package io.memoryos.iam.keycloak;

import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.Encoded;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;

/**
 * Keycloak's consent revocation with the client ID as one path segment. The admin client's own
 * {@code UserResource.revokeConsent} sends a path parameter's slashes raw, so a client ID that is a URL, as Claude's is,
 * never reaches the resource; {@link Encoded} makes RESTEasy encode them, which Keycloak 26.8 accepts.
 */
@Path("/admin/realms/{realm}/users/{user}/consents/{client}")
interface KeycloakConsentResource {

    @DELETE
    void revoke(@PathParam("realm") String realm, @PathParam("user") String user,
                @PathParam("client") @Encoded String clientId);
}
