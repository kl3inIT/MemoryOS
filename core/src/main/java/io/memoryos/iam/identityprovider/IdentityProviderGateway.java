package io.memoryos.iam.identityprovider;

import java.util.List;
import java.util.Optional;

import org.keycloak.representations.idm.IdentityProviderRepresentation;

/**
 * The realm identity-provider resource boundary. Keycloak is the only supported provider plane;
 * representations are Keycloak-native because no provider-neutral adapter exists.
 */
public interface IdentityProviderGateway {

    List<IdentityProviderRepresentation> findAll();

    Optional<IdentityProviderRepresentation> find(String alias);

    void create(IdentityProviderRepresentation representation);

    void update(IdentityProviderRepresentation representation);

    void delete(String alias);
}
