package io.memoryos.iam;

import io.memoryos.shared.ActorId;

import java.util.List;

/**
 * SYSTEM_ADMIN-gated management of upstream OIDC identity providers on the configured Keycloak
 * realm. Keycloak owns provider configuration; the durable JIT allowlist lives in PostgreSQL.
 */
public interface IdentityProviderAdministration {

    List<IdentityProviderView> list(ActorId actorId);

    DiscoveredOidcProvider discover(ActorId actorId, String issuerUrl);

    IdentityProviderView create(ActorId actorId, IdentityProviderCommand command);

    IdentityProviderView update(ActorId actorId, String alias, IdentityProviderUpdate update);

    void delete(ActorId actorId, String alias);
}
