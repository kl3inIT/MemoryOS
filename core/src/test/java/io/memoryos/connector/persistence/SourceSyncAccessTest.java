package io.memoryos.connector.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.zaxxer.hikari.HikariDataSource;
import io.memoryos.TestDatabase;
import io.memoryos.connector.DocumentAccess;
import io.memoryos.connector.DocumentSourceMetadata;
import io.memoryos.connector.GoogleDriveProvider.Permission;
import io.memoryos.connector.SourceSearchService;
import io.memoryos.connector.application.DefaultSourceDocumentAccessResolver;
import io.memoryos.shared.ActorId;
import io.memoryos.iam.tenant.TenantAccessResolver;
import io.memoryos.shared.TenantId;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.ObjectMapper;

/** Auto Sync enforcement over retained Google Drive permission snapshots (MEM-105). */
@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
class SourceSyncAccessTest {
    /** The serializer the snapshot repository uses, so fixtures match stored permissions. */
    private static final ObjectMapper JSON = new ObjectMapper();

    private HikariDataSource database;
    private JdbcClient jdbc;
    private TenantId tenant;
    private UUID drive;
    private UUID generation;
    private JdbcSourceDocumentRepository repository;
    private DefaultSourceDocumentAccessResolver access;
    private SourceSearchService search;
    private ActorId owner, mate, outsider, unverified, groupMember;
    private List<ActorId> readers;

    @BeforeEach
    void setUp() throws Exception {
        database = TestDatabase.freshPostgres();
        jdbc = JdbcClient.create(database);
        tenant = new TenantId(UUID.randomUUID());
        generation = UUID.randomUUID();
        jdbc.sql("INSERT INTO tenants(id,slug,display_name,status,bootstrap_reference) VALUES(:id,'sync','Sync','ACTIVE','TEST')")
                .param("id", tenant.value()).update();
        owner = member("owner@example.test", true);
        mate = member("Mate@Example.test", true);
        outsider = member("outsider@other.test", true);
        unverified = member("owner@example.test", false);
        groupMember = member(null, false);
        readers = List.of(owner, mate, outsider, unverified, groupMember);
        drive = pair("GOOGLE_DRIVE", "SYNC");
        jdbc.sql("INSERT INTO google_drive_sources(tenant_id,source_id) VALUES(:tenant,:source)")
                .param("tenant", tenant.value()).param("source", drive).update();
        UUID group = UUID.randomUUID();
        jdbc.sql("INSERT INTO iam_groups(tenant_id,id,name) VALUES(:tenant,:id,'Readers')")
                .param("tenant", tenant.value()).param("id", group).update();
        jdbc.sql("INSERT INTO iam_group_memberships(tenant_id,group_id,actor_id) VALUES(:tenant,:group,:actor)")
                .param("tenant", tenant.value()).param("group", group).param("actor", groupMember.value()).update();
        jdbc.sql("INSERT INTO source_group_grants(tenant_id,connector_credential_pair_id,group_id) VALUES(:tenant,:source,:group)")
                .param("tenant", tenant.value()).param("source", drive).param("group", group).update();
        repository = new JdbcSourceDocumentRepository(jdbc);
        var tenants = mock(TenantAccessResolver.class);
        when(tenants.findActiveTenant(any())).thenReturn(Optional.of(tenant));
        access = new DefaultSourceDocumentAccessResolver(tenants, repository);
        search = new SourceSearchService(tenants, repository);
    }

    @AfterEach
    void tearDown() {
        database.close();
    }

    @Test
    void autoSyncFollowsEachInterpretedGooglePermissionAndIndexesTheSameDecision() {
        var cases = new LinkedHashMap<String, Case>();
        cases.put("user", new Case(document("user", List.of(user("OWNER@example.test", null, null))), Set.of(owner),
                false, Set.of("google_user:owner@example.test")));
        cases.put("domain", new Case(document("domain", List.of(domain("example.test", null))), Set.of(owner, mate),
                false, Set.of("google_domain:example.test")));
        cases.put("link-only domain", new Case(document("link", List.of(domain("example.test", false))), Set.of(), false, Set.of()));
        cases.put("anyone", new Case(document("anyone", List.of(anyone(false))), Set.copyOf(readers), true, Set.of()));
        cases.put("group", new Case(document("group", List.of(group("Team@example.test"))), Set.of(), false,
                Set.of("google_group:team@example.test")));
        cases.put("deleted", new Case(document("deleted", List.of(user("owner@example.test", true, null))), Set.of(), false, Set.of()));
        cases.put("expired", new Case(document("expired", List.of(user("owner@example.test", null,
                Instant.now().minus(Duration.ofDays(1))))), Set.of(), false, Set.of()));
        cases.put("expiring", new Case(document("expiring", List.of(user("owner@example.test", null,
                Instant.now().plus(Duration.ofDays(1))))), Set.of(owner), false, Set.of("google_user:owner@example.test")));
        cases.put("unobserved", new Case(document("unobserved", null), Set.of(), false, Set.of()));
        var failed = document("failed", null);
        failedAttempt("failed");
        cases.put("failed only", new Case(failed, Set.of(), false, Set.of()));

        var ids = cases.values().stream().map(Case::document).toList();
        for (var reader : readers) {
            var expected = cases.values().stream().filter(c -> c.readers().contains(reader)).map(Case::document)
                    .collect(Collectors.toSet());
            assertEquals(expected, access.readableDocuments(reader, ids), "readable documents of " + name(reader));
        }
        for (var entry : cases.entrySet()) {
            var indexed = repository.documentAccess(tenant, entry.getValue().document());
            assertEquals(new DocumentAccess(entry.getValue().everyone(), entry.getValue().tokens()), indexed, entry.getKey());
            for (var reader : readers) {
                boolean filtered = indexed.everyone() || indexed.tokens().stream()
                        .anyMatch(repository.actorAccessTokens(tenant, reader)::contains);
                assertEquals(filtered, access.canRead(reader, new io.memoryos.document.DocumentId(entry.getValue().document())),
                        "index and recheck agree for " + entry.getKey() + " and " + name(reader));
            }
        }
        var scope = search.scope(outsider);
        assertTrue(scope.sources().containsKey(drive), "Every active member sees the Source; documents are rechecked");
        assertEquals(Set.of(cases.get("anyone").document()), search.readableMetadata(scope, ids).keySet());
        assertEquals(Set.of(cases.get("user").document(), cases.get("domain").document(), cases.get("anyone").document(),
                cases.get("expiring").document()), search.readableMetadata(search.scope(owner), ids).keySet());
    }

    @Test
    void readerTokensComeOnlyFromAVerifiedEmailOfAnActiveMember() {
        assertEquals(Set.of("google_user:owner@example.test", "google_domain:example.test"),
                repository.actorAccessTokens(tenant, owner));
        assertEquals(Set.of("google_user:mate@example.test", "google_domain:example.test"),
                repository.actorAccessTokens(tenant, mate));
        assertTrue(repository.actorAccessTokens(tenant, unverified).isEmpty());
        assertEquals(1, repository.actorAccessTokens(tenant, groupMember).size());
        jdbc.sql("UPDATE tenant_memberships SET status='INACTIVE' WHERE tenant_id=:tenant AND actor_id=:actor")
                .param("tenant", tenant.value()).param("actor", owner.value()).update();
        assertTrue(repository.actorAccessTokens(tenant, owner).isEmpty());
        assertFalse(search.scope(owner).sources().containsKey(drive));
    }

    @Test
    void lastSuccessfulGrantsHoldWhileTheFileStaysInTheSourceAndModesSwitchEnforcement() {
        var document = document("kept", List.of(user("owner@example.test", null, null)));
        var id = new io.memoryos.document.DocumentId(document);
        failedAttempt("kept");
        jdbc.sql("UPDATE google_drive_sources SET revision=revision+1,generation=generation+1 WHERE source_id=:source")
                .param("source", drive).update();
        assertTrue(access.canRead(owner, id), "A later failure and revision changes keep the last successful grants");
        assertEquals(Set.of("google_user:owner@example.test"), repository.documentAccess(tenant, document).tokens());

        mode("PRIVATE");
        assertEquals(Set.of(groupMember), readersOf(id));
        mode("PUBLIC");
        assertEquals(Set.copyOf(readers), readersOf(id));
        mode("SYNC");
        assertEquals(Set.of(owner), readersOf(id));

        var file = pair("FILE", "PUBLIC");
        map(file, document, null);
        assertEquals(Set.copyOf(readers), readersOf(id), "Another public origin still admits every member");
        assertTrue(repository.documentAccess(tenant, document).everyone());
        assertEquals(List.of(drive), search.readableMetadata(search.scope(owner), List.of(document)).get(document).stream()
                .map(DocumentSourceMetadata::sourceId).filter(drive::equals).toList());
        assertTrue(search.readableMetadata(search.scope(outsider), List.of(document)).get(document).stream()
                .map(DocumentSourceMetadata::sourceId).noneMatch(drive::equals), "The SYNC origin stays hidden from outsiders");

        jdbc.sql("UPDATE documents_by_connector_credential_pair SET retrieval_eligible=FALSE WHERE connector_credential_pair_id=:source")
                .param("source", drive).update();
        jdbc.sql("DELETE FROM documents_by_connector_credential_pair WHERE connector_credential_pair_id=:file")
                .param("file", file).update();
        assertEquals(Set.of(), readersOf(id), "A deselected file loses its retained grants");
        assertEquals(new DocumentAccess(false, Set.of()), repository.documentAccess(tenant, document));
    }

    @Test
    void googleGroupGrantsAdmitMembersOfTheActiveGenerationOfAnActiveServiceAccount() {
        var team = new io.memoryos.document.DocumentId(document("team", List.of(group("team@example.test"))));
        var everyone = new io.memoryos.document.DocumentId(document("all", List.of(group("all@example.test"))));
        assertEquals(Set.of(), readersOf(team), "No membership has been read yet");

        UUID credential = serviceAccount("admin@example.test");
        generation(credential, 1, "COMPLETED", Map.of("team@example.test", List.of("owner@example.test")), Set.of("all@example.test"));
        assertEquals(Set.of(), readersOf(team), "A completed run grants nothing until it is the active generation");
        activate(credential, 1);
        assertEquals(Set.of(owner), readersOf(team));
        assertEquals(Set.of(owner, mate), readersOf(everyone), "A whole-customer member admits the admin's domain only");
        assertTrue(repository.actorAccessTokens(tenant, owner).containsAll(
                Set.of("google_group:team@example.test", "google_group:all@example.test")));

        generation(credential, 2, "RUNNING", Map.of("team@example.test", List.of("mate@example.test")), Set.of());
        assertEquals(Set.of(owner), readersOf(team), "A run in progress never replaces the active generation");

        jdbc.sql("UPDATE google_drive_credentials SET connection_status='NEEDS_REAUTHORIZATION' WHERE credential_id=:id")
                .param("id", credential).update();
        assertEquals(Set.of(), readersOf(team), "A credential that lost its authority stops granting membership");
    }

    private UUID serviceAccount(String adminEmail) {
        UUID id = UUID.randomUUID();
        jdbc.sql("INSERT INTO credentials(id,tenant_id,name,credential_kind,status) VALUES(:id,:tenant,'Workspace','GOOGLE_SERVICE_ACCOUNT','ACTIVE')")
                .param("id", id).param("tenant", tenant.value()).update();
        jdbc.sql("""
                INSERT INTO google_drive_credentials(tenant_id,credential_id,account_subject,account_email,granted_scopes,
                    connection_status,auth_method,service_account_email,service_account_key_ciphertext,
                    service_account_key_nonce,service_account_key_version)
                VALUES(:tenant,:id,'1045',:admin,'drive','ACTIVE','SERVICE_ACCOUNT','indexer@test.iam.gserviceaccount.com',
                    DECODE(REPEAT('ab',32),'hex'),DECODE(REPEAT('cd',12),'hex'),'v1')
                """).param("tenant", tenant.value()).param("id", id).param("admin", adminEmail).update();
        return id;
    }

    private void generation(UUID credential, long generation, String status, Map<String, List<String>> members,
            Set<String> wholeDomain) {
        jdbc.sql("""
                INSERT INTO google_group_sync_runs(tenant_id,credential_id,generation,status,groups_listed,finished_at)
                VALUES(:tenant,:credential,:generation,:status,TRUE,CASE WHEN :status='RUNNING' THEN NULL ELSE CURRENT_TIMESTAMP END)
                """).param("tenant", tenant.value()).param("credential", credential).param("generation", generation)
                .param("status", status).update();
        var groups = new java.util.TreeSet<>(members.keySet());
        groups.addAll(wholeDomain);
        for (String group : groups) {
            jdbc.sql("""
                    INSERT INTO google_group_sync_groups(tenant_id,credential_id,generation,group_email,whole_domain,members_listed)
                    VALUES(:tenant,:credential,:generation,:group,:whole,TRUE)
                    """).param("tenant", tenant.value()).param("credential", credential).param("generation", generation)
                    .param("group", group).param("whole", wholeDomain.contains(group)).update();
            for (String member : members.getOrDefault(group, List.of())) {
                jdbc.sql("""
                        INSERT INTO google_group_members(tenant_id,credential_id,generation,group_email,member_email)
                        VALUES(:tenant,:credential,:generation,:group,:member)
                        """).param("tenant", tenant.value()).param("credential", credential).param("generation", generation)
                        .param("group", group).param("member", member).update();
            }
        }
    }

    private void activate(UUID credential, long generation) {
        jdbc.sql("UPDATE google_drive_credentials SET active_group_generation=:generation WHERE credential_id=:id")
                .param("generation", generation).param("id", credential).update();
    }

    private Set<ActorId> readersOf(io.memoryos.document.DocumentId document) {
        return readers.stream().filter(reader -> access.canRead(reader, document)).collect(Collectors.toSet());
    }

    private String name(ActorId reader) {
        return List.of("owner", "mate", "outsider", "unverified", "groupMember").get(readers.indexOf(reader));
    }

    private void mode(String access) {
        jdbc.sql("UPDATE connector_credential_pairs SET access_type=:access WHERE id=:source")
                .param("access", access).param("source", drive).update();
    }

    private ActorId member(@Nullable String email, boolean verified) {
        var actor = new ActorId(UUID.randomUUID());
        jdbc.sql("INSERT INTO actors(id) VALUES(:id)").param("id", actor.value()).update();
        jdbc.sql("INSERT INTO tenant_memberships(tenant_id,actor_id,role,status) VALUES(:tenant,:actor,'MEMBER','ACTIVE')")
                .param("tenant", tenant.value()).param("actor", actor.value()).update();
        jdbc.sql("INSERT INTO external_identity_bindings(issuer,subject,actor_id) VALUES('https://idp.test',CAST(:actor AS TEXT),:actor)")
                .param("actor", actor.value()).update();
        jdbc.sql("""
                INSERT INTO actor_profiles(actor_id,issuer,subject,email,email_verified,observed_at)
                VALUES(:actor,'https://idp.test',CAST(:actor AS TEXT),:email,:verified,CURRENT_TIMESTAMP)
                """).param("actor", actor.value()).param("email", email).param("verified", verified).update();
        return actor;
    }

    private UUID pair(String type, String accessType) {
        UUID id = UUID.randomUUID();
        jdbc.sql("INSERT INTO credentials(id,tenant_id,name,credential_kind,status) VALUES(:id,:tenant,'Test',:kind,'ACTIVE')")
                .param("id", id).param("tenant", tenant.value())
                .param("kind", "FILE".equals(type) ? "NO_AUTH" : "GOOGLE_OAUTH").update();
        jdbc.sql("INSERT INTO connectors(id,tenant_id,name,connector_type,status) VALUES(:id,:tenant,:type,:type,'ACTIVE')")
                .param("id", id).param("tenant", tenant.value()).param("type", type).update();
        jdbc.sql("""
                INSERT INTO connector_credential_pairs(id,tenant_id,connector_id,credential_id,access_type,status)
                VALUES(:id,:tenant,:id,:id,:access,'ACTIVE')
                """).param("id", id).param("tenant", tenant.value()).param("access", accessType).update();
        return id;
    }

    /** A Drive document mapped through an item for {@code fileId}; {@code permissions} null means no successful read. */
    private UUID document(String fileId, @Nullable List<Permission> permissions) {
        UUID document = UUID.randomUUID();
        jdbc.sql("INSERT INTO documents(id,tenant_id,status,title,content_generation) VALUES(:id,:tenant,'ELIGIBLE',:title,:generation)")
                .param("id", document).param("tenant", tenant.value()).param("title", fileId).param("generation", generation).update();
        map(drive, document, fileId);
        if (permissions != null) {
            UUID operation = UUID.randomUUID();
            jdbc.sql("""
                    INSERT INTO google_drive_acl_snapshots(tenant_id,source_id,file_id,observation_revision,permissions_json,status,
                        last_attempt_at,attempt_operation_id,attempt_credential_id,attempt_credential_revision,attempt_scope_revision,
                        attempt_generation,last_success_at,success_operation_id,success_credential_id,success_credential_revision,
                        success_scope_revision,success_generation)
                    VALUES(:tenant,:source,:file,1,CAST(:permissions AS jsonb),'SUCCEEDED',CURRENT_TIMESTAMP,:operation,:source,1,1,0,
                        CURRENT_TIMESTAMP,:operation,:source,1,1,0)
                    """).param("tenant", tenant.value()).param("source", drive).param("file", fileId)
                    .param("permissions", JSON.writeValueAsString(permissions)).param("operation", operation).update();
        }
        return document;
    }

    /** Records a failed permission read; a prior successful snapshot is retained, as the repository does. */
    private void failedAttempt(String fileId) {
        jdbc.sql("""
                INSERT INTO google_drive_acl_snapshots(tenant_id,source_id,file_id,status,last_attempt_at,attempt_operation_id,
                    attempt_credential_id,attempt_credential_revision,attempt_scope_revision,attempt_generation,error_code)
                VALUES(:tenant,:source,:file,'FAILED',CURRENT_TIMESTAMP,:operation,:source,2,2,1,'SOURCE_GOOGLE_ACCESS_DENIED')
                ON CONFLICT (tenant_id,source_id,file_id) DO UPDATE SET status='FAILED',error_code=EXCLUDED.error_code,
                    last_attempt_at=EXCLUDED.last_attempt_at,attempt_operation_id=EXCLUDED.attempt_operation_id,
                    attempt_credential_revision=2,attempt_scope_revision=2,attempt_generation=1
                """).param("tenant", tenant.value()).param("source", drive).param("file", fileId)
                .param("operation", UUID.randomUUID()).update();
    }

    private void map(UUID source, UUID document, @Nullable String fileId) {
        UUID item = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO connector_items(id,tenant_id,connector_id,content_sha256,status,provider_file_id)
                VALUES(:id,:tenant,:connector,REPEAT('a',64),'INDEXED',:file)
                """).param("id", item).param("tenant", tenant.value()).param("connector", source).param("file", fileId).update();
        jdbc.sql("""
                INSERT INTO documents_by_connector_credential_pair(tenant_id,connector_id,connector_credential_pair_id,document_id,
                    connector_item_id,retrieval_eligible)
                VALUES(:tenant,:source,:source,:document,:item,TRUE)
                """).param("tenant", tenant.value()).param("source", source).param("document", document).param("item", item).update();
    }

    private static Permission user(String email, @Nullable Boolean deleted, @Nullable Instant expires) {
        return new Permission("user-" + email, "user", "writer", email, null, expires, null, deleted, null, List.of(), null, null);
    }

    private static Permission domain(String domain, @Nullable Boolean discoverable) {
        return new Permission("domain-" + domain, "domain", "reader", null, domain, null, discoverable, null, null, List.of(), null, null);
    }

    private static Permission anyone(boolean discoverable) {
        return new Permission("anyoneWithLink", "anyone", "reader", null, null, null, discoverable, null, null, List.of(), null, null);
    }

    private static Permission group(String email) {
        return new Permission("group-" + email, "group", "reader", email, null, null, null, null, null, List.of(), null, null);
    }

    private record Case(UUID document, Set<ActorId> readers, boolean everyone, Set<String> tokens) {}
}
