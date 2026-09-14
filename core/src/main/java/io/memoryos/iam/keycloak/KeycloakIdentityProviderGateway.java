package io.memoryos.iam.keycloak;

import io.memoryos.iam.identityprovider.IdentityProviderException;
import io.memoryos.iam.identityprovider.IdentityProviderFailureReason;
import io.memoryos.iam.identityprovider.IdentityProviderGateway;

import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.ProcessingException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.resource.IdentityProviderResource;
import org.keycloak.representations.idm.IdentityProviderRepresentation;
import org.springframework.stereotype.Component;

/**
 * Thin adapter over {@code realm().identityProviders()}. Translates Keycloak transport failures into
 * {@link IdentityProviderException}; it never exposes the stored client secret.
 */
@Component
public class KeycloakIdentityProviderGateway implements IdentityProviderGateway {

    private final Keycloak keycloak;
    private final String realm;

    public KeycloakIdentityProviderGateway(Keycloak keycloak, KeycloakAdminProperties properties) {
        this.keycloak = Objects.requireNonNull(keycloak, "keycloak must not be null");
        this.realm = Objects.requireNonNull(properties, "properties must not be null").realm();
    }

    public List<IdentityProviderRepresentation> findAll() {
        try {
            return keycloak.realm(realm).identityProviders().findAll();
        } catch (ProcessingException | WebApplicationException exception) {
            throw unavailable(exception);
        }
    }

    public Optional<IdentityProviderRepresentation> find(String alias) {
        try {
            return Optional.ofNullable(resource(alias).toRepresentation());
        } catch (NotFoundException exception) {
            return Optional.empty();
        } catch (ProcessingException | WebApplicationException exception) {
            throw unavailable(exception);
        }
    }

    public void create(IdentityProviderRepresentation representation) {
        try (Response response = keycloak.realm(realm).identityProviders().create(representation)) {
            if (response.getStatus() == Response.Status.CONFLICT.getStatusCode()) {
                throw new IdentityProviderException(
                        IdentityProviderFailureReason.ALIAS_CONFLICT,
                        "Keycloak reported a duplicate identity provider alias"
                );
            }
            if (response.getStatus() != Response.Status.CREATED.getStatusCode()) {
                throw unavailable("Keycloak identity provider creation returned HTTP "
                        + response.getStatus());
            }
        } catch (ProcessingException | WebApplicationException exception) {
            throw unavailable(exception);
        }
    }

    public void update(IdentityProviderRepresentation representation) {
        try {
            resource(representation.getAlias()).update(representation);
        } catch (NotFoundException exception) {
            throw notFound(representation.getAlias());
        } catch (ProcessingException | WebApplicationException exception) {
            throw unavailable(exception);
        }
    }

    public void delete(String alias) {
        try {
            resource(alias).remove();
        } catch (NotFoundException exception) {
            throw notFound(alias);
        } catch (ProcessingException | WebApplicationException exception) {
            throw unavailable(exception);
        }
    }

    private IdentityProviderResource resource(String alias) {
        return keycloak.realm(realm).identityProviders().get(alias);
    }

    private static IdentityProviderException notFound(String alias) {
        return new IdentityProviderException(
                IdentityProviderFailureReason.NOT_FOUND,
                "Keycloak has no identity provider with alias " + alias
        );
    }

    private static IdentityProviderException unavailable(String diagnosticMessage) {
        return new IdentityProviderException(
                IdentityProviderFailureReason.PROVIDER_UNAVAILABLE,
                diagnosticMessage
        );
    }

    private static IdentityProviderException unavailable(Throwable cause) {
        return new IdentityProviderException(
                IdentityProviderFailureReason.PROVIDER_UNAVAILABLE,
                "Keycloak identity provider request failed",
                cause
        );
    }
}
