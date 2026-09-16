package io.memoryos.iam.keycloak;

import io.memoryos.iam.identity.ProviderSessionTerminator;

import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.ProcessingException;
import jakarta.ws.rs.WebApplicationException;

import java.util.Objects;

import org.keycloak.admin.client.Keycloak;
import org.springframework.stereotype.Component;

/**
 * Deletes one Keycloak user session through the realm admin API ({@code DELETE /admin/realms/{realm}/sessions/{id}}),
 * authorized by the provisioner service account's {@code manage-users} role. A session Keycloak no longer knows has
 * already ended.
 */
@Component
class KeycloakProviderSessionTerminator implements ProviderSessionTerminator {

    private final Keycloak keycloak;
    private final String realm;

    KeycloakProviderSessionTerminator(Keycloak keycloak, KeycloakAdminProperties properties) {
        this.keycloak = Objects.requireNonNull(keycloak, "keycloak must not be null");
        this.realm = Objects.requireNonNull(properties, "properties must not be null").realm();
    }

    @Override
    public boolean end(String providerSessionId) {
        if (providerSessionId == null || providerSessionId.isBlank()) {
            return false;
        }
        try {
            keycloak.realm(realm).deleteSession(providerSessionId, false);
            return true;
        } catch (NotFoundException exception) {
            return true;
        } catch (ProcessingException | WebApplicationException exception) {
            return false;
        }
    }
}
