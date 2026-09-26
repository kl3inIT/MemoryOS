package io.memoryos.iam.identityprovider;

import io.memoryos.iam.IdentityProviderAdministration;
import io.memoryos.iam.IdentityProviderCommand;
import io.memoryos.iam.IdentityProviderException;
import io.memoryos.iam.IdentityProviderFailureReason;
import io.memoryos.iam.IdentityProviderUpdate;
import io.memoryos.iam.IdentityProviderView;
import io.memoryos.shared.TenantId;

import io.memoryos.audit.AuditAction;
import io.memoryos.audit.AuditRecord;
import io.memoryos.audit.AuditTrail;
import io.memoryos.iam.IamAuthorization;
import io.memoryos.iam.IamCapability;
import io.memoryos.shared.ActorId;
import io.memoryos.iam.identityprovider.persistence.JitAllowlistRepository;
import io.memoryos.iam.DiscoveredOidcProvider;
import io.memoryos.iam.keycloak.OidcDiscoveryClient;

import java.util.HashMap;
import java.util.LinkedHashMap;
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
    private final AuditTrail audit;

    public DefaultIdentityProviderAdministration(
            IamAuthorization authorization,
            IdentityProviderGateway gateway,
            OidcDiscoveryClient discovery,
            JitAllowlistRepository allowlist,
            PlatformTransactionManager transactionManager,
            AuditTrail audit
    ) {
        this.audit = Objects.requireNonNull(audit, "audit must not be null");
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
        var tenant = requireAdmin(actorId);
        Objects.requireNonNull(command, "command must not be null");
        DiscoveredOidcProvider discovered = discovery.discover(command.issuer());
        gateway.create(toRepresentation(command, discovered));
        if (command.jitAllowed()) {
            transactions.executeWithoutResult(_ -> {
                authorization.lockAndRequireAdministration(actorId);
                allowlist.allow(command.alias(), actorId);
            });
        }
        var created = toView(gateway.find(command.alias()).orElseThrow(
                DefaultIdentityProviderAdministration::unavailableAfterWrite), command.jitAllowed());
        // Settled in Keycloak: no database transaction covers it, so the event is written on its own.
        audit.recordSeparately(AuditRecord.of(AuditAction.IDENTITY_PROVIDER_CREATE, tenant).actor(actorId)
                .resource("IDENTITY_PROVIDER", command.alias(), command.displayName())
                .detail("after", facts(command.alias(), command.issuer(), command.jitAllowed())).build());
        return created;
    }

    @Override
    public IdentityProviderView update(ActorId actorId, String alias, IdentityProviderUpdate update) {
        var tenant = requireAdmin(actorId);
        Objects.requireNonNull(update, "update must not be null");
        IdentityProviderRepresentation existing = gateway.find(alias)
                .orElseThrow(() -> notFound(alias));
        var before = facts(alias, issuerOf(existing), allowlist.allowedAliases().contains(alias));
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
        transactions.executeWithoutResult(_ -> {
            authorization.lockAndRequireAdministration(actorId);
            if (!targetAlias.equals(alias)) {
                allowlist.disallow(alias);
            }
            if (update.jitAllowed()) {
                allowlist.allow(targetAlias, actorId);
            } else {
                allowlist.disallow(targetAlias);
            }
        });
        var updated = toView(gateway.find(targetAlias).orElseThrow(
                DefaultIdentityProviderAdministration::unavailableAfterWrite), update.jitAllowed());
        audit.recordSeparately(AuditRecord.of(AuditAction.IDENTITY_PROVIDER_UPDATE, tenant).actor(actorId)
                .resource("IDENTITY_PROVIDER", targetAlias, existing.getDisplayName())
                .detail("before", before).detail("after", facts(targetAlias, targetIssuer, update.jitAllowed())).build());
        return updated;
    }

    @Override
    public void delete(ActorId actorId, String alias) {
        var tenant = requireAdmin(actorId);
        transactions.executeWithoutResult(_ -> {
            authorization.lockAndRequireAdministration(actorId);
            allowlist.disallow(alias);
        });
        gateway.delete(alias);
        audit.recordSeparately(AuditRecord.of(AuditAction.IDENTITY_PROVIDER_DELETE, tenant).actor(actorId)
                .resource("IDENTITY_PROVIDER", alias, alias).detail("alias", alias).build());
    }

    /** What decides who can sign in through a provider; its client secret is never recorded. */
    private static Map<String, Object> facts(String alias, String issuer, boolean jitAllowed) {
        var facts = new LinkedHashMap<String, Object>();
        facts.put("alias", alias);
        facts.put("issuer", issuer);
        facts.put("jitAllowed", jitAllowed);
        return facts;
    }

    private TenantId requireAdmin(ActorId actorId) {
        return authorization.require(
                Objects.requireNonNull(actorId, "actorId must not be null"),
                IamCapability.SYSTEM_ADMIN,
                false
        ).tenantId();
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
        syncBackchannelLogout(config);
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
        syncBackchannelLogout(config);
        existing.setConfig(config);
    }

    // Sign-out deletes the Keycloak session through the admin API, which reaches the upstream provider
    // only by back-channel logout; without it the upstream session signs the browser straight back in.
    private static void syncBackchannelLogout(Map<String, String> config) {
        String logoutUrl = config.get("logoutUrl");
        if (logoutUrl == null || logoutUrl.isBlank()) {
            config.remove("backchannelSupported");
        } else {
            config.put("backchannelSupported", "true");
        }
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
