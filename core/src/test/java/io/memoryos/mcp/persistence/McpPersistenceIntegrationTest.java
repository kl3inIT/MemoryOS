package io.memoryos.mcp.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.zaxxer.hikari.HikariDataSource;
import io.memoryos.TestDatabase;
import io.memoryos.mcp.McpAuthPerformer;
import io.memoryos.mcp.McpAuthType;
import io.memoryos.mcp.McpCredentialStatus;
import io.memoryos.mcp.McpOAuthClientSource;
import io.memoryos.mcp.McpOAuthProviderMode;
import io.memoryos.mcp.McpServerStatus;
import io.memoryos.mcp.McpTokenEndpointAuthMethod;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/** V69 schema, Hibernate validation of the MCP entities, and the database-enforced MCP invariants. */
@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
class McpPersistenceIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-09-15T08:00:00Z");

    private HikariDataSource dataSource;
    private JdbcClient jdbc;
    private TestDatabase.JpaHarness jpa;
    private TransactionTemplate tx;
    private JpaMcpServerRepository servers;
    private JpaMcpOAuthClientRepository clients;
    private JpaMcpServerToolRepository tools;
    private JpaMcpCredentialRepository credentials;
    private UUID tenant;
    private UUID group;
    private UUID actor;

    @BeforeEach
    void setup() throws Exception {
        dataSource = TestDatabase.freshPostgres();
        jdbc = JdbcClient.create(dataSource);
        // Validates every IAM, Chat and MCP entity against the migrated schema.
        jpa = TestDatabase.jpa(dataSource);
        tx = new TransactionTemplate(jpa.transactionManager());
        servers = jpa.repository(JpaMcpServerRepository.class);
        clients = jpa.repository(JpaMcpOAuthClientRepository.class);
        tools = jpa.repository(JpaMcpServerToolRepository.class);
        credentials = jpa.repository(JpaMcpCredentialRepository.class);
        jdbc.sql("ALTER TABLE tenants DROP CONSTRAINT IF EXISTS uq_tenants_deployment_slot").update();
        tenant = tenant();
        group = group(tenant);
        actor = actor();
    }

    @AfterEach
    void close() {
        if (jpa != null) jpa.close();
        if (dataSource != null) dataSource.close();
    }

    @Test
    void storesAServerWithSeveralOAuthClientsToolsAndPerUserCredentials() {
        UUID server = UUID.randomUUID();
        UUID tascoAuto = UUID.randomUUID();
        UUID tascoLand = UUID.randomUUID();
        inTx(() -> {
            servers.saveAndFlush(oauthServer(server, tenant, "drive", Set.of(group)));
            clients.saveAndFlush(client(tascoAuto, tenant, server, "Tasco Auto"));
            clients.saveAndFlush(client(tascoLand, tenant, server, "Tasco Land"));
            var tool = new McpServerToolEntity(UUID.randomUUID(), tenant, server, "search_files");
            tool.snapshot("Search files", "Search Drive files", "{\"type\": \"object\"}", "{\"readOnlyHint\": true}", true, NOW);
            tool.enabled(true);
            tools.saveAndFlush(tool);
            credentials.saveAndFlush(credential(tenant, server, actor, tascoLand));
        });

        inTx(() -> {
            var stored = servers.findByTenantIdAndId(tenant, server).orElseThrow();
            assertEquals("Google Drive", stored.name());
            assertEquals(McpAuthType.OAUTH, stored.authType());
            assertEquals(McpAuthPerformer.PER_USER, stored.authPerformer());
            assertEquals(McpOAuthProviderMode.KNOWN_PROVIDER, stored.oauthProviderMode());
            assertEquals(McpServerStatus.CREATED, stored.status());
            assertEquals(Set.of(group), stored.groupIds());
            assertTrue(servers.existsByTenantIdAndSlug(tenant, "drive"));
            assertEquals(List.of("Tasco Auto", "Tasco Land"), clients.findByTenantIdAndServerIdOrderByLabelAsc(tenant, server)
                    .stream().map(McpOAuthClientEntity::label).toList());
            var tool = tools.findByTenantIdAndServerIdOrderByNameAsc(tenant, server).getFirst();
            assertTrue(tool.enabled() && tool.readOnly());
            var credential = credentials.findByTenantIdAndServerIdAndOwnerActorId(tenant, server, actor).orElseThrow();
            assertEquals(tascoLand, credential.oauthClientId());
            assertEquals(McpCredentialStatus.ACTIVE, credential.status());
            assertTrue(credentials.findByTenantIdAndServerIdAndOwnerActorIdIsNull(tenant, server).isEmpty());
            assertTrue(servers.findByTenantIdAndId(tenant(), server).isEmpty());
        });
    }

    @Test
    void serverConfigurationInvariantsAreEnforcedByTheDatabase() {
        inTx(() -> servers.saveAndFlush(oauthServer(UUID.randomUUID(), tenant, "drive", Set.of())));

        assertRejected(() -> servers.saveAndFlush(oauthServer(UUID.randomUUID(), tenant, "drive", Set.of())));
        assertRejected(() -> servers.saveAndFlush(server(tenant, "nomode", McpAuthType.OAUTH, McpAuthPerformer.PER_USER, null, Set.of())));
        assertRejected(() -> servers.saveAndFlush(server(tenant, "moded", McpAuthType.API_TOKEN, McpAuthPerformer.ADMIN,
                McpOAuthProviderMode.AUTO_DISCOVERY, Set.of())));
        assertRejected(() -> servers.saveAndFlush(server(tenant, "open", McpAuthType.NONE, McpAuthPerformer.PER_USER, null, Set.of())));
        assertRejected(() -> servers.saveAndFlush(server(tenant, "Upper", McpAuthType.NONE, McpAuthPerformer.ADMIN, null, Set.of())));
        UUID foreignGroup = group(tenant());
        assertRejected(() -> servers.saveAndFlush(oauthServer(UUID.randomUUID(), tenant, "foreign", Set.of(foreignGroup))));

        UUID otherTenant = tenant();
        inTx(() -> servers.saveAndFlush(oauthServer(UUID.randomUUID(), otherTenant, "drive", Set.of())));
    }

    @Test
    void credentialsAreUniquePerOwnerAndBoundToClientsOfTheirOwnServer() {
        UUID server = UUID.randomUUID();
        UUID otherServer = UUID.randomUUID();
        UUID client = UUID.randomUUID();
        UUID otherClient = UUID.randomUUID();
        inTx(() -> {
            servers.saveAndFlush(oauthServer(server, tenant, "drive", Set.of()));
            servers.saveAndFlush(oauthServer(otherServer, tenant, "gmail", Set.of()));
            clients.saveAndFlush(client(client, tenant, server, "Tasco"));
            clients.saveAndFlush(client(otherClient, tenant, otherServer, "Tasco"));
            credentials.saveAndFlush(credential(tenant, server, actor, client));
            credentials.saveAndFlush(credential(tenant, server, null, client));
        });

        assertRejected(() -> credentials.saveAndFlush(credential(tenant, server, actor, client)));
        assertRejected(() -> credentials.saveAndFlush(credential(tenant, server, null, client)));
        UUID secondActor = actor();
        assertRejected(() -> credentials.saveAndFlush(credential(tenant, server, secondActor, otherClient)));
        assertRejected(() -> clients.saveAndFlush(client(UUID.randomUUID(), tenant, server, "Tasco")));

        inTx(() -> servers.deleteById(server));
        inTx(() -> {
            assertTrue(clients.findByTenantIdAndServerIdOrderByLabelAsc(tenant, server).isEmpty());
            assertTrue(credentials.findByTenantIdAndServerIdAndOwnerActorId(tenant, server, actor).isEmpty());
            assertEquals(1, clients.findByTenantIdAndServerIdOrderByLabelAsc(tenant, otherServer).size());
        });
    }

    @Test
    void toolSnapshotsMustBeObjectSchemasAndMcpManageIsAnOrdinaryGrant() {
        UUID server = UUID.randomUUID();
        inTx(() -> servers.saveAndFlush(oauthServer(server, tenant, "drive", Set.of())));

        assertRejected(() -> {
            var tool = new McpServerToolEntity(UUID.randomUUID(), tenant, server, "bad");
            tool.snapshot(null, "", "[]", "{}", false, NOW);
            tools.saveAndFlush(tool);
        });
        jdbc.sql("INSERT INTO iam_group_capability_grants (tenant_id, group_id, capability) VALUES (:tenant, :group, 'MCP_MANAGE')")
                .param("tenant", tenant).param("group", group).update();
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.sql(
                        "INSERT INTO iam_group_capability_grants (tenant_id, group_id, capability) VALUES (:tenant, :group, 'MCP_USE')")
                .param("tenant", tenant).param("group", group).update());
    }

    private McpServerEntity oauthServer(UUID id, UUID tenantId, String slug, Set<UUID> groups) {
        var server = new McpServerEntity(id, tenantId, slug, NOW);
        server.configure("Google Drive", "Drive MCP", "https://drivemcp.googleapis.com/mcp/v1", McpAuthType.OAUTH,
                McpAuthPerformer.PER_USER, McpOAuthProviderMode.KNOWN_PROVIDER,
                "[\"https://www.googleapis.com/auth/drive.readonly\"]", "{}", null, groups.isEmpty(), groups, NOW);
        return server;
    }

    private McpServerEntity server(UUID tenantId, String slug, McpAuthType type, McpAuthPerformer performer,
                                   @Nullable McpOAuthProviderMode mode, Set<UUID> groups) {
        var server = new McpServerEntity(UUID.randomUUID(), tenantId, slug, NOW);
        server.configure("Server", null, "http://10.0.0.5/mcp", type, performer, mode, "[]", "{}", null, true, groups, NOW);
        return server;
    }

    private static McpOAuthClientEntity client(UUID id, UUID tenantId, UUID serverId, String label) {
        var client = new McpOAuthClientEntity(id, tenantId, serverId, McpOAuthClientSource.ADMIN, NOW);
        client.configure(label, "https://accounts.google.com", label.replace(' ', '-') + ".apps.googleusercontent.com", "v1:sealed",
                McpTokenEndpointAuthMethod.CLIENT_SECRET_BASIC, "https://accounts.google.com/o/oauth2/v2/auth",
                "https://oauth2.googleapis.com/token", "https://oauth2.googleapis.com/revoke", null, null, true, NOW);
        return client;
    }

    private static McpCredentialEntity credential(UUID tenantId, UUID serverId, @Nullable UUID owner, UUID clientId) {
        var credential = new McpCredentialEntity(UUID.randomUUID(), tenantId, serverId, owner, NOW);
        credential.store(clientId, "v1:sealed-payload", NOW.plusSeconds(3600), NOW);
        return credential;
    }

    private void inTx(Runnable action) {
        tx.executeWithoutResult(status -> action.run());
    }

    private void assertRejected(Runnable action) {
        assertThrows(DataIntegrityViolationException.class, () -> inTx(action));
    }

    private UUID tenant() {
        UUID id = UUID.randomUUID();
        jdbc.sql("INSERT INTO tenants(id, slug, display_name, status, bootstrap_reference) VALUES (:id, :slug, 'MCP', 'ACTIVE', 'test')")
                .param("id", id).param("slug", id.toString()).update();
        return id;
    }

    private UUID group(UUID tenantId) {
        UUID id = UUID.randomUUID();
        jdbc.sql("INSERT INTO iam_groups(tenant_id, id, name) VALUES (:tenant, :id, :name)")
                .param("tenant", tenantId).param("id", id).param("name", "Group " + id).update();
        return id;
    }

    private UUID actor() {
        UUID id = UUID.randomUUID();
        jdbc.sql("INSERT INTO actors(id) VALUES (:id)").param("id", id).update();
        return id;
    }
}
