package io.memoryos.iam.identityprovider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.memoryos.TestDatabase;
import io.memoryos.TestDatabase.JpaHarness;
import io.memoryos.iam.IamException;
import io.memoryos.iam.IamFailureReason;
import io.memoryos.iam.group.DefaultGroupProvisioner;
import io.memoryos.iam.group.DefaultIamAuthorization;
import io.memoryos.iam.group.GroupProvisioner;
import io.memoryos.iam.group.persistence.GroupCapabilityGrantRepository;
import io.memoryos.iam.group.persistence.GroupMembershipRepository;
import io.memoryos.iam.group.persistence.GroupRepository;
import io.memoryos.iam.group.persistence.IamAuthorizationRepository;
import io.memoryos.iam.group.persistence.IamLockRepository;
import io.memoryos.iam.identity.ActorId;
import io.memoryos.iam.identity.ExternalIdentity;
import io.memoryos.iam.identity.persistence.JpaExternalIdentityRegistry;
import io.memoryos.iam.identityprovider.persistence.JitAllowlistRepository;
import io.memoryos.iam.keycloak.DiscoveredOidcProvider;
import io.memoryos.iam.keycloak.OidcDiscoveryClient;
import io.memoryos.iam.tenant.TenantId;
import io.memoryos.iam.tenant.bootstrap.DefaultInitialTenantBootstrapper;
import io.memoryos.iam.tenant.bootstrap.InitialTenantBootstrapRequest;
import io.memoryos.iam.tenant.bootstrap.InitialTenantBootstrapper;
import io.memoryos.iam.tenant.persistence.JpaTenantRepository;

import com.zaxxer.hikari.HikariDataSource;

import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.keycloak.representations.idm.IdentityProviderRepresentation;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
class DefaultIdentityProviderAdministrationTest {

    private static final TenantId TENANT = new TenantId(UUID.fromString(
            "10000000-0000-0000-0000-000000000095"));
    private static final String ISSUER = "https://keycloak.example/realms/memoryos";
    private static final String UPSTREAM_ISSUER = "https://sso.example/realms/tasco";
    private static final DiscoveredOidcProvider DISCOVERED = new DiscoveredOidcProvider(
            UPSTREAM_ISSUER,
            UPSTREAM_ISSUER + "/protocol/openid-connect/auth",
            UPSTREAM_ISSUER + "/protocol/openid-connect/token",
            UPSTREAM_ISSUER + "/protocol/openid-connect/logout",
            UPSTREAM_ISSUER + "/protocol/openid-connect/userinfo",
            UPSTREAM_ISSUER + "/protocol/openid-connect/certs"
    );

    private HikariDataSource dataSource;
    private JdbcClient jdbc;
    private JpaHarness jpa;
    private JitAllowlistRepository allowlist;
    private FakeGateway gateway;
    private DefaultIdentityProviderAdministration administration;
    private ActorId admin;

    @BeforeEach
    void setUp() throws SQLException {
        dataSource = TestDatabase.freshPostgres();
        jdbc = JdbcClient.create(dataSource);
        jpa = TestDatabase.jpa(dataSource);
        var tenants = new JpaTenantRepository(jpa.entityManager());
        var identities = new JpaExternalIdentityRegistry(jpa.entityManager());
        var locks = new IamLockRepository(jdbc);
        GroupProvisioner groups = new DefaultGroupProvisioner(
                new GroupRepository(jpa.entityManager()),
                new GroupMembershipRepository(jpa.entityManager()),
                new GroupCapabilityGrantRepository(jpa.entityManager())
        );
        var bootstrapper = TestDatabase.transactionalProxy(
                new DefaultInitialTenantBootstrapper(tenants, locks, identities, identities, groups),
                InitialTenantBootstrapper.class,
                jpa.transactionManager()
        );
        admin = bootstrapper.bootstrap(new InitialTenantBootstrapRequest(
                TENANT, new ExternalIdentity(ISSUER, "owner"), "tasco", "Tasco", "TEST-MEM-95"
        )).ownerActorId();
        allowlist = new JitAllowlistRepository(jdbc);
        gateway = new FakeGateway();
        administration = new DefaultIdentityProviderAdministration(
                new DefaultIamAuthorization(new IamAuthorizationRepository(jdbc), locks),
                gateway,
                new StubDiscovery(),
                allowlist,
                jpa.transactionManager()
        );
    }

    @AfterEach
    void tearDown() {
        jpa.close();
        dataSource.close();
    }

    @Test
    void createsOidcProviderWithDiscoveredEndpointsAndOptionalJitGrant() {
        var view = administration.create(admin, new IdentityProviderCommand(
                "tasco", "Tasco SSO", UPSTREAM_ISSUER, "memoryos-broker", "s3cret", true
        ));

        assertEquals("tasco", view.alias());
        assertEquals("Tasco SSO", view.displayName());
        assertEquals(UPSTREAM_ISSUER, view.issuer());
        assertEquals("memoryos-broker", view.clientId());
        assertTrue(view.enabled());
        assertTrue(view.jitAllowed());

        var stored = gateway.providers.get("tasco");
        assertEquals("oidc", stored.getProviderId());
        assertEquals(DISCOVERED.authorizationUrl(), stored.getConfig().get("authorizationUrl"));
        assertEquals(DISCOVERED.jwksUrl(), stored.getConfig().get("jwksUrl"));
        assertEquals("S256", stored.getConfig().get("pkceMethod"));
        assertEquals("client_secret_basic", stored.getConfig().get("clientAuthMethod"));
        assertEquals("s3cret", stored.getConfig().get("clientSecret"));
        assertTrue(allowlist.allowedAliases().contains("tasco"));
    }

    @Test
    void createWithoutJitLeavesTheAllowlistUntouched() {
        administration.create(admin, new IdentityProviderCommand(
                "tasco", "Tasco SSO", UPSTREAM_ISSUER, "memoryos-broker", "s3cret", false
        ));
        assertFalse(allowlist.allowedAliases().contains("tasco"));
    }

    @Test
    void updateKeepsStoredSecretWhenNullAndSyncsJitFlag() {
        administration.create(admin, new IdentityProviderCommand(
                "tasco", "Tasco SSO", UPSTREAM_ISSUER, "memoryos-broker", "s3cret", true
        ));

        var view = administration.update(admin, "tasco", new IdentityProviderUpdate(
                null, "Tasco Renamed", null, "memoryos-broker-2", null, false, false
        ));

        assertEquals("Tasco Renamed", view.displayName());
        assertFalse(view.enabled());
        assertFalse(view.jitAllowed());
        assertEquals("s3cret", gateway.providers.get("tasco").getConfig().get("clientSecret"));
        assertEquals("memoryos-broker-2", gateway.providers.get("tasco").getConfig().get("clientId"));
        assertFalse(allowlist.allowedAliases().contains("tasco"));
    }

    @Test
    void updateReplacesSecretWhenProvided() {
        administration.create(admin, new IdentityProviderCommand(
                "tasco", "Tasco SSO", UPSTREAM_ISSUER, "memoryos-broker", "s3cret", false
        ));
        administration.update(admin, "tasco", new IdentityProviderUpdate(
                null, "Tasco SSO", null, "memoryos-broker", "rotated", true, false
        ));
        assertEquals("rotated", gateway.providers.get("tasco").getConfig().get("clientSecret"));
    }

    @Test
    void deleteRemovesProviderAndJitAlias() {
        administration.create(admin, new IdentityProviderCommand(
                "tasco", "Tasco SSO", UPSTREAM_ISSUER, "memoryos-broker", "s3cret", true
        ));
        administration.delete(admin, "tasco");
        assertFalse(gateway.providers.containsKey("tasco"));
        assertFalse(allowlist.allowedAliases().contains("tasco"));
    }

    @Test
    void listFiltersToOidcProvidersAndReportsJitState() {
        administration.create(admin, new IdentityProviderCommand(
                "tasco", "Tasco SSO", UPSTREAM_ISSUER, "memoryos-broker", "s3cret", true
        ));
        var saml = new IdentityProviderRepresentation();
        saml.setAlias("corp-saml");
        saml.setProviderId("saml");
        saml.setEnabled(true);
        saml.setConfig(Map.of());
        gateway.providers.put("corp-saml", saml);

        var views = administration.list(admin);
        assertEquals(1, views.size());
        assertEquals("tasco", views.getFirst().alias());
        assertTrue(views.getFirst().jitAllowed());
    }

    @Test
    void missingProviderFailsNotFound() {
        var exception = assertThrows(IdentityProviderException.class,
                () -> administration.update(admin, "ghost", new IdentityProviderUpdate(
                        null, "Ghost", null, "client", null, true, false
                )));
        assertEquals(IdentityProviderFailureReason.NOT_FOUND.code(), exception.code());
    }

    @Test
    void nonAdminCannotReadOrMutateProviders() {
        var outsider = new ActorId(UUID.randomUUID());
        for (var call : List.<Runnable>of(
                () -> administration.list(outsider),
                () -> administration.discover(outsider, UPSTREAM_ISSUER),
                () -> administration.create(outsider, new IdentityProviderCommand(
                        "tasco", "Tasco", UPSTREAM_ISSUER, "c", "s", false)),
                () -> administration.update(outsider, "tasco", new IdentityProviderUpdate(
                        null, "Tasco", null, "c", null, true, false)),
                () -> administration.delete(outsider, "tasco")
        )) {
            var exception = assertThrows(IamException.class, call::run);
            assertEquals(IamFailureReason.ACCESS_DENIED.code(), exception.code());
        }
        assertTrue(gateway.providers.isEmpty());
        assertTrue(allowlist.allowedAliases().isEmpty());
    }

    private static final class FakeGateway implements IdentityProviderGateway {
        private final Map<String, IdentityProviderRepresentation> providers = new LinkedHashMap<>();

        @Override
        public List<IdentityProviderRepresentation> findAll() {
            return List.copyOf(providers.values());
        }

        @Override
        public Optional<IdentityProviderRepresentation> find(String alias) {
            return Optional.ofNullable(providers.get(alias));
        }

        @Override
        public void create(IdentityProviderRepresentation representation) {
            if (providers.putIfAbsent(representation.getAlias(), representation) != null) {
                throw new IdentityProviderException(
                        IdentityProviderFailureReason.ALIAS_CONFLICT,
                        "duplicate alias"
                );
            }
        }

        @Override
        public void update(IdentityProviderRepresentation representation) {
            providers.put(representation.getAlias(), representation);
        }

        @Override
        public void delete(String alias) {
            providers.remove(alias);
        }
    }

    private static final class StubDiscovery extends OidcDiscoveryClient {
        @Override
        public DiscoveredOidcProvider discover(String issuerUrl) {
            assertEquals(UPSTREAM_ISSUER, issuerUrl);
            return DISCOVERED;
        }
    }
}
