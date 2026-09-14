package io.memoryos.iam.identityprovider;

import io.memoryos.iam.group.IamAuthorization;
import io.memoryos.iam.group.IamCapability;
import io.memoryos.iam.identity.ActorId;
import io.memoryos.iam.identityprovider.persistence.JitAllowlistRepository;
import io.memoryos.iam.keycloak.DiscoveredOidcProvider;
import io.memoryos.iam.keycloak.OidcDiscoveryClient;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.keycloak.representations.idm.IdentityProviderRepresentation;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * SYSTEM_ADMIN-gated orchestration over the Keycloak identity-provider resource and the durable JIT
 * allowlist. Provider IO happens outside the authorization lock; durable writes re-authorize under
 * the exclusive Tenant lock in the same transaction.
 */
@Service
public class DefaultIdentityProviderAdministration implements IdentityProviderAdministration {

    private static final String OIDC = "oidc";

    private final IamAuthorization authorization;
    private final IdentityProviderGateway gateway;
    private final OidcDiscoveryClient discovery;
    private final JitAllowlistRepository allowlist;
    private final TransactionTemplate transactions;

    public DefaultIdentityProviderAdministration(
            IamAuthorization authorization,
            IdentityProviderGateway gateway,
            OidcDiscoveryClient discovery,
            JitAllowlistRepository allowlist,
            PlatformTransactionManager transactionManager
    ) {
        this.authorization = Objects.requireNonNull(authorization, "authorization must not be null");
        this.gateway = Objects.requireNonNull(gateway, "gateway must not be null");
        this.discovery = Objects.requireNonNull(discovery, "discovery must not be null");
        this.allowlist = Objects.requireNonNull(allowlist, "allowlist must not be null");
        this.transactions = new TransactionTemplate(Objects.requireNonNull(
                transactionManager,
                "transactionManager must not be null"
        ));
    }

    @Override
    public List<IdentityProviderView> list(ActorId actorId) {
        requireAdmin(actorId);
        Set<String> jitAliases = allowlist.allowedAliases();
        return gateway.findAll().stream()
                .filter(provider -> OIDC.equals(provider.getProviderId()))
                .map(provider -> toView(provider, jitAliases.contains(provider.getAlias())))
                .toList();
    }

    @Override
    public DiscoveredOidcProvider discover(ActorId actorId, String issuerUrl) {
        requireAdmin(actorId);
        return discovery.discover(issuerUrl);
    }

    @Override
    public IdentityProviderView create(ActorId actorId, IdentityProviderCommand command) {
        requireAdmin(actorId);
        Objects.requireNonNull(command, "command must not be null");
        DiscoveredOidcProvider discovered = discovery.discover(command.issuer());
        gateway.create(toRepresentation(command, discovered));
        if (command.jitAllowed()) {
            transactions.executeWithoutResult(_ -> {
                authorization.lockAndRequireAdministration(actorId);
                allowlist.allow(command.alias(), actorId);
            });
        }
        return toView(gateway.find(command.alias()).orElseThrow(
                DefaultIdentityProviderAdministration::unavailableAfterWrite), command.jitAllowed());
    }

    @Override
    public IdentityProviderView update(ActorId actorId, String alias, IdentityProviderUpdate update) {
        requireAdmin(actorId);
        Objects.requireNonNull(update, "update must not be null");
        IdentityProviderRepresentation existing = gateway.find(alias)
                .orElseThrow(() -> notFound(alias));
        String targetAlias = update.alias() == null ? alias : update.alias();
        if (!targetAlias.equals(alias) && gateway.find(targetAlias).isPresent()) {
            throw new IdentityProviderException(
                    IdentityProviderFailureReason.ALIAS_CONFLICT,
                    "An identity provider with alias " + targetAlias + " already exists"
            );
        }
        String targetIssuer = update.issuer() == null ? issuerOf(existing) : update.issuer();
        if (!targetIssuer.equals(issuerOf(existing))) {
            applyDiscovered(existing, discovery.discover(targetIssuer));
        }
        apply(existing, update);
        if (targetAlias.equals(alias)) {
            gateway.update(existing);
        } else {
            existing.setAlias(targetAlias);
            gateway.create(existing);
            gateway.delete(alias);
        }
        String effectiveAlias = targetAlias;
        transactions.executeWithoutResult(_ -> {
            authorization.lockAndRequireAdministration(actorId);
            if (!effectiveAlias.equals(alias)) {
                allowlist.disallow(alias);
            }
            if (update.jitAllowed()) {
                allowlist.allow(effectiveAlias, actorId);
            } else {
                allowlist.disallow(effectiveAlias);
            }
        });
        return toView(gateway.find(effectiveAlias).orElseThrow(
                DefaultIdentityProviderAdministration::unavailableAfterWrite), update.jitAllowed());
    }

    @Override
    public void delete(ActorId actorId, String alias) {
        requireAdmin(actorId);
        transactions.executeWithoutResult(_ -> {
            authorization.lockAndRequireAdministration(actorId);
            allowlist.disallow(alias);
        });
        gateway.delete(alias);
    }

    private void requireAdmin(ActorId actorId) {
        authorization.require(
                Objects.requireNonNull(actorId, "actorId must not be null"),
                IamCapability.SYSTEM_ADMIN,
                false
        );
    }

    private static IdentityProviderRepresentation toRepresentation(
            IdentityProviderCommand command,
            DiscoveredOidcProvider discovered
    ) {
        var representation = new IdentityProviderRepresentation();
        representation.setAlias(command.alias());
        representation.setDisplayName(command.displayName());
        representation.setProviderId(OIDC);
        representation.setEnabled(true);
        representation.setTrustEmail(true);

        Map<String, String> config = new HashMap<>();
        config.put("issuer", command.issuer());
        config.put("authorizationUrl", discovered.authorizationUrl());
        config.put("tokenUrl", discovered.tokenUrl());
        if (discovered.logoutUrl() != null) {
            config.put("logoutUrl", discovered.logoutUrl());
        }
        if (discovered.userInfoUrl() != null) {
            config.put("userInfoUrl", discovered.userInfoUrl());
        }
        config.put("jwksUrl", discovered.jwksUrl());
        config.put("validateSignature", "true");
        config.put("useJwksUrl", "true");
        config.put("pkceEnabled", "true");
        config.put("pkceMethod", "S256");
        config.put("clientAuthMethod", "client_secret_basic");
        config.put("clientId", command.clientId());
        config.put("clientSecret", command.clientSecret());
        config.put("syncMode", "IMPORT");
        config.put("defaultScope", "openid");
        config.put("hideOnLoginPage", "false");
        representation.setConfig(config);
        return representation;
    }

    private static String issuerOf(IdentityProviderRepresentation provider) {
        Map<String, String> config = provider.getConfig();
        return config == null ? "" : config.getOrDefault("issuer", "");
    }

    private static void applyDiscovered(
            IdentityProviderRepresentation existing,
            DiscoveredOidcProvider discovered
    ) {
        Map<String, String> config = new HashMap<>(
                existing.getConfig() == null ? Map.of() : existing.getConfig()
        );
        config.put("issuer", discovered.issuer());
        config.put("authorizationUrl", discovered.authorizationUrl());
        config.put("tokenUrl", discovered.tokenUrl());
        config.put("jwksUrl", discovered.jwksUrl());
        if (discovered.logoutUrl() != null) {
            config.put("logoutUrl", discovered.logoutUrl());
        } else {
            config.remove("logoutUrl");
        }
        if (discovered.userInfoUrl() != null) {
            config.put("userInfoUrl", discovered.userInfoUrl());
        } else {
            config.remove("userInfoUrl");
        }
        existing.setConfig(config);
    }

    private static void apply(IdentityProviderRepresentation existing, IdentityProviderUpdate update) {
        existing.setDisplayName(update.displayName());
        existing.setEnabled(update.enabled());
        Map<String, String> config = new HashMap<>(
                existing.getConfig() == null ? Map.of() : existing.getConfig()
        );
        config.put("clientId", update.clientId());
        if (update.clientSecret() != null) {
            config.put("clientSecret", update.clientSecret());
        }
        existing.setConfig(config);
    }

    private static IdentityProviderView toView(
            IdentityProviderRepresentation provider,
            boolean jitAllowed
    ) {
        Map<String, String> config = provider.getConfig() == null ? Map.of() : provider.getConfig();
        String displayName = provider.getDisplayName();
        return new IdentityProviderView(
                provider.getAlias(),
                displayName == null || displayName.isBlank() ? provider.getAlias() : displayName,
                config.getOrDefault("issuer", ""),
                config.getOrDefault("clientId", ""),
                provider.isEnabled(),
                jitAllowed
        );
    }

    private static IdentityProviderException notFound(String alias) {
        return new IdentityProviderException(
                IdentityProviderFailureReason.NOT_FOUND,
                "Keycloak has no identity provider with alias " + alias
        );
    }

    private static IdentityProviderException unavailableAfterWrite() {
        return new IdentityProviderException(
                IdentityProviderFailureReason.PROVIDER_UNAVAILABLE,
                "Keycloak did not return the identity provider after a successful write"
        );
    }
}
