package io.memoryos.connector.source.persistence;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.memoryos.TestDatabase;
import io.memoryos.connector.SourceSearchService;
import io.memoryos.connector.SourceType;
import io.memoryos.document.DocumentId;
import io.memoryos.shared.ActorId;
import io.memoryos.iam.TenantAccessResolver;
import io.memoryos.shared.TenantId;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
class SourceSearchMetadataMigrationTest {
    @Test
    void backfillsUploadDatesWithoutPipelineDatesAndRechecksEachMappedSourcesAuthority() throws Exception {
        try (var database = TestDatabase.freshPostgres("34")) {
            var jdbc = JdbcClient.create(database);
            var tenant = new TenantId(UUID.randomUUID());
            UUID document = UUID.randomUUID(), generation = UUID.randomUUID(), file = UUID.randomUUID(), drive = UUID.randomUUID(), inactive = UUID.randomUUID();
            jdbc.sql("INSERT INTO tenants(id,slug,display_name,status,bootstrap_reference) VALUES(:id,'metadata','Metadata','ACTIVE','TEST')")
                    .param("id", tenant.value()).update();
            var actor = member(jdbc, tenant);
            jdbc.sql("""
                    INSERT INTO documents(id,tenant_id,status,title,content_generation,metadata_json)
                    VALUES(:id,:tenant,'ELIGIBLE','Original',:generation,'{"dc:creator":"Alice"}')
                    """).param("id", document).param("tenant", tenant.value()).param("generation", generation).update();
            seed(jdbc, tenant, file, file, document, false, true, "RESTRICTED");
            seed(jdbc, tenant, drive, drive, document, true, true, "RESTRICTED");
            seed(jdbc, tenant, inactive, file, document, false, false, "RESTRICTED");
            var flyway = Flyway.configure().dataSource(database).locations("classpath:db/migration").target("35").load();
            assertEquals(1, flyway.migrate().migrationsExecuted);
            assertEquals(0, flyway.migrate().migrationsExecuted);
            flyway.validate();
            // The read rules use later tables; V63 renames the Drive RESTRICTED access to PRIVATE.
            Flyway.configure().dataSource(database).locations("classpath:db/migration").load().migrate();

            var repository = new JdbcSourceDocumentRepository(jdbc);
            var tenants = mock(TenantAccessResolver.class);
            when(tenants.findActiveTenant(any())).thenReturn(Optional.of(tenant));
            var service = new SourceSearchService(tenants, repository);
            var scope = service.scope(actor);
            assertEquals(java.util.Map.of(file, SourceType.FILE), scope.sources());
            var visible = service.readableMetadata(scope, List.of(document)).get(document);
            assertEquals(1, visible.size());
            var origin = visible.getFirst();
            assertEquals(file, origin.sourceId());
            assertEquals(Instant.parse("2000-01-01T00:00:00Z"), origin.createdAt());
            assertEquals(Instant.parse("2001-01-01T00:00:00Z"), origin.updatedAt());
            assertEquals(List.of("Alice"), origin.authors());
            var indexed = service.indexMetadata(tenant, new DocumentId(document), generation);
            assertEquals(java.util.Set.of(file, drive, inactive), indexed.stream().map(io.memoryos.connector.DocumentSourceMetadata::sourceId)
                    .collect(java.util.stream.Collectors.toSet()));
            assertEquals(SourceType.GOOGLE_DRIVE, indexed.stream().filter(m -> m.sourceId().equals(drive)).findFirst().orElseThrow().type());
            assertTrue(service.indexMetadata(tenant, new DocumentId(document), UUID.randomUUID()).isEmpty());

            jdbc.sql("UPDATE connector_items SET updated_at=CURRENT_TIMESTAMP WHERE tenant_id=:tenant")
                    .param("tenant", tenant.value()).update();
            assertEquals(visible, service.readableMetadata(scope, List.of(document)).get(document));
            jdbc.sql("UPDATE connector_credential_pairs SET status='NOT_STARTED' WHERE id=:id").param("id", file).update();
            assertTrue(service.readableMetadata(scope, List.of(document)).isEmpty(), "A prefetched scope cannot retain revoked access");
            assertTrue(service.scope(actor).sources().isEmpty());
        }
    }

    @Test
    void malformedOptionalMetadataDoesNotDropAuthorizedDocumentsOrBreakABatch() throws Exception {
        try (var database = TestDatabase.freshPostgres()) {
            var jdbc = JdbcClient.create(database);
            var tenant = new TenantId(UUID.randomUUID());
            var generation = UUID.randomUUID();
            jdbc.sql("INSERT INTO tenants(id,slug,display_name,status,bootstrap_reference) VALUES(:id,'metadata','Metadata','ACTIVE','TEST')")
                    .param("id", tenant.value()).update();
            var actor = member(jdbc, tenant);
            var documents = new java.util.ArrayList<UUID>();
            var credential = UUID.randomUUID();
            for (String metadata : List.of("{broken", "null", "", "{\"dc:creator\":\"Alice\"}")) {
                var document = UUID.randomUUID();
                var source = documents.isEmpty() ? credential : UUID.randomUUID();
                documents.add(document);
                jdbc.sql("INSERT INTO documents(id,tenant_id,status,title,content_generation,metadata_json) VALUES(:id,:tenant,'ELIGIBLE','Title',:generation,:metadata)")
                        .param("id", document).param("tenant", tenant.value()).param("generation", generation).param("metadata", metadata).update();
                seed(jdbc, tenant, source, credential, document, false, true);
            }
            var tenants = mock(TenantAccessResolver.class);
            when(tenants.findActiveTenant(any())).thenReturn(Optional.of(tenant));
            var service = new SourceSearchService(tenants, new JdbcSourceDocumentRepository(jdbc));
            var visible = service.readableMetadata(service.scope(actor), documents);
            assertEquals(4, visible.size());
            for (int i = 0; i < documents.size(); i++) {
                var origin = visible.get(documents.get(i)).getFirst();
                assertEquals(i == 3 ? List.of("Alice") : List.of(), origin.authors());
                assertEquals(SourceType.FILE, origin.type());
                assertEquals(List.of(origin), service.indexMetadata(tenant, new DocumentId(documents.get(i)), generation));
            }
        }
    }

    @Test
    void privateSourcesRequireCurrentGroupMembershipRegardlessOfManagementOrCreationAuthority() throws Exception {
        try (var database = TestDatabase.freshPostgres()) {
            var jdbc = JdbcClient.create(database);
            var tenant = new TenantId(UUID.randomUUID());
            jdbc.sql("INSERT INTO tenants(id,slug,display_name,status,bootstrap_reference) VALUES(:id,'private-search','Private search','ACTIVE','TEST')")
                    .param("id", tenant.value()).update();
            var member = member(jdbc, tenant);
            var outsider = member(jdbc, tenant);
            var manager = member(jdbc, tenant);
            UUID group = UUID.randomUUID(), globalGroup = UUID.randomUUID();
            for (var id : List.of(group, globalGroup)) {
                jdbc.sql("INSERT INTO iam_groups(tenant_id,id,name) VALUES(:tenant,:id,CAST(:id AS TEXT))")
                        .param("tenant", tenant.value()).param("id", id).update();
            }
            jdbc.sql("INSERT INTO iam_group_memberships(tenant_id,group_id,actor_id) VALUES(:tenant,:group,:actor)")
                    .param("tenant", tenant.value()).param("group", group).param("actor", member.value()).update();
            jdbc.sql("INSERT INTO iam_group_memberships(tenant_id,group_id,actor_id) VALUES(:tenant,:group,:actor)")
                    .param("tenant", tenant.value()).param("group", globalGroup).param("actor", manager.value()).update();
            jdbc.sql("INSERT INTO iam_group_capability_grants(tenant_id,group_id,capability) VALUES(:tenant,:group,'SOURCES_MANAGE')")
                    .param("tenant", tenant.value()).param("group", globalGroup).update();
            UUID mixed = UUID.randomUUID(), privateDoc = UUID.randomUUID(), driveDoc = UUID.randomUUID(), orphanDoc = UUID.randomUUID();
            var generation = UUID.randomUUID();
            for (var document : List.of(mixed, privateDoc, driveDoc, orphanDoc)) {
                jdbc.sql("INSERT INTO documents(id,tenant_id,status,title,content_generation) VALUES(:id,:tenant,'ELIGIBLE','Evidence',:generation)")
                        .param("id", document).param("tenant", tenant.value()).param("generation", generation).update();
            }
            UUID publicFile = UUID.randomUUID(), mixedPrivate = UUID.randomUUID(), privateFile = UUID.randomUUID(),
                    drive = UUID.randomUUID(), mixedDrive = UUID.randomUUID(), orphan = UUID.randomUUID();
            seed(jdbc, tenant, publicFile, publicFile, mixed, false, true);
            seed(jdbc, tenant, mixedPrivate, publicFile, mixed, false, true);
            seed(jdbc, tenant, privateFile, publicFile, privateDoc, false, true);
            seed(jdbc, tenant, drive, drive, driveDoc, true, true);
            seed(jdbc, tenant, mixedDrive, drive, mixed, true, true);
            seed(jdbc, tenant, orphan, publicFile, orphanDoc, false, true);
            jdbc.sql("UPDATE connector_credential_pairs SET access_type='PRIVATE',created_by_actor_id=:actor WHERE id IN (:ids)")
                    .param("actor", manager.value()).param("ids", List.of(mixedPrivate, privateFile, drive, mixedDrive, orphan)).update();
            for (var source : List.of(mixedPrivate, privateFile, drive, mixedDrive)) {
                jdbc.sql("INSERT INTO source_group_grants(tenant_id,connector_credential_pair_id,group_id) VALUES(:tenant,:source,:group)")
                        .param("tenant", tenant.value()).param("source", source).param("group", group).update();
            }
            var repository = new JdbcSourceDocumentRepository(jdbc);
            var tenants = mock(TenantAccessResolver.class);
            when(tenants.findActiveTenant(any())).thenReturn(Optional.of(tenant));
            var search = new SourceSearchService(tenants, repository);
            var access = new io.memoryos.connector.source.DefaultSourceDocumentAccessResolver(tenants, repository);
            var ids = List.of(mixed, privateDoc, driveDoc, orphanDoc);
            assertEquals(java.util.Set.of(mixed, privateDoc, driveDoc), access.readableDocuments(member, ids));
            for (var actor : List.of(outsider, manager)) {
                assertEquals(java.util.Set.of(mixed), access.readableDocuments(actor, ids));
                assertFalse(access.canRead(actor, new DocumentId(privateDoc)));
                assertFalse(access.canRead(actor, new DocumentId(orphanDoc)));
                assertFalse(access.canRead(actor, new DocumentId(driveDoc)));
                assertEquals(java.util.Set.of(publicFile), search.scope(actor).sources().keySet());
                assertEquals(List.of(publicFile), search.options(actor, 0, 100).stream().map(SourceSearchService.SourceOption::id).toList());
                assertEquals(List.of(publicFile), search.readableMetadata(search.scope(actor), ids).get(mixed).stream()
                        .map(io.memoryos.connector.DocumentSourceMetadata::sourceId).toList());
            }
            var scope = search.scope(member);
            assertEquals(java.util.Set.of(publicFile, mixedPrivate, privateFile, drive, mixedDrive), scope.sources().keySet());
            assertEquals(SourceType.GOOGLE_DRIVE, scope.sources().get(drive));
            assertEquals(scope.sources().keySet(), search.options(member, 0, 100).stream()
                    .map(SourceSearchService.SourceOption::id).collect(java.util.stream.Collectors.toSet()));
            var driveScope = new io.memoryos.connector.SourceSearchScope(tenant, member, java.util.Map.of(drive, SourceType.GOOGLE_DRIVE));
            assertEquals(java.util.Set.of(driveDoc), search.readableMetadata(driveScope, ids).keySet());
            var narrowed = new io.memoryos.connector.SourceSearchScope(scope.tenant(), scope.actor(), java.util.Map.of(mixedPrivate, SourceType.FILE));
            assertEquals(List.of(mixedPrivate), search.readableMetadata(narrowed, ids).get(mixed).stream()
                    .map(io.memoryos.connector.DocumentSourceMetadata::sourceId).toList());
            assertEquals(java.util.Set.of(mixed), search.readableMetadata(narrowed, ids).keySet());
            assertEquals(java.util.Set.of(publicFile, mixedPrivate, mixedDrive), search.indexMetadata(tenant, new DocumentId(mixed), generation)
                    .stream().map(io.memoryos.connector.DocumentSourceMetadata::sourceId).collect(java.util.stream.Collectors.toSet()));
            assertEquals(List.of(privateFile), search.indexMetadata(tenant, new DocumentId(privateDoc), generation)
                    .stream().map(io.memoryos.connector.DocumentSourceMetadata::sourceId).toList());
            assertEquals(List.of(drive), search.indexMetadata(tenant, new DocumentId(driveDoc), generation)
                    .stream().map(io.memoryos.connector.DocumentSourceMetadata::sourceId).toList());
            // The page read gives each document what the single read gives it for its own generation.
            var page = search.indexMetadata(tenant, java.util.Map.of(new DocumentId(mixed), generation,
                    new DocumentId(privateDoc), generation, new DocumentId(driveDoc), UUID.randomUUID()));
            assertEquals(search.indexMetadata(tenant, new DocumentId(mixed), generation), page.get(new DocumentId(mixed)));
            assertEquals(search.indexMetadata(tenant, new DocumentId(privateDoc), generation), page.get(new DocumentId(privateDoc)));
            assertEquals(List.of(), page.get(new DocumentId(driveDoc)), "Another generation has no index metadata");
            assertTrue(access.canRead(member, new DocumentId(driveDoc)));

            jdbc.sql("UPDATE connector_credential_pairs SET access_type='PUBLIC' WHERE id=:id").param("id", drive).update();
            assertTrue(access.canRead(outsider, new DocumentId(driveDoc)), "A public Drive source admits every member");
            assertTrue(search.scope(outsider).sources().containsKey(drive));
            assertEquals(java.util.Set.of(driveDoc), search.readableMetadata(driveScope, ids).keySet());
            assertEquals(List.of(drive), search.indexMetadata(tenant, new DocumentId(driveDoc), generation)
                    .stream().map(io.memoryos.connector.DocumentSourceMetadata::sourceId).toList());
            jdbc.sql("UPDATE connector_credential_pairs SET access_type='PRIVATE',status='INDEXING' WHERE id=:id")
                    .param("id", drive).update();
            assertTrue(access.canRead(member, new DocumentId(driveDoc)), "Other items indexing must not hide eligible documents");
            assertTrue(search.scope(member).sources().containsKey(drive));
            assertEquals(java.util.Set.of(driveDoc), search.readableMetadata(driveScope, ids).keySet());
            jdbc.sql("UPDATE connector_credential_pairs SET status='DELETING' WHERE id=:id").param("id", drive).update();
            assertFalse(access.canRead(member, new DocumentId(driveDoc)));
            assertFalse(search.scope(member).sources().containsKey(drive));
            assertTrue(search.readableMetadata(driveScope, ids).isEmpty());
            jdbc.sql("UPDATE connector_credential_pairs SET status='ACTIVE' WHERE id=:id").param("id", drive).update();
            jdbc.sql("DELETE FROM source_group_grants WHERE tenant_id=:tenant AND connector_credential_pair_id=:id")
                    .param("tenant", tenant.value()).param("id", drive).update();
            assertFalse(access.canRead(member, new DocumentId(driveDoc)), "An unshared Drive source is not readable");
            assertTrue(search.readableMetadata(driveScope, ids).isEmpty(), "Existing scopes recheck removed associations");
            jdbc.sql("INSERT INTO source_group_grants(tenant_id,connector_credential_pair_id,group_id) VALUES(:tenant,:source,:group)")
                    .param("tenant", tenant.value()).param("source", drive).param("group", group).update();
            assertTrue(access.canRead(member, new DocumentId(driveDoc)));
            jdbc.sql("DELETE FROM iam_group_memberships WHERE tenant_id=:tenant AND group_id=:group")
                    .param("tenant", tenant.value()).param("group", group).update();
            assertEquals(java.util.Set.of(mixed), access.readableDocuments(member, ids));
            var revoked = search.readableMetadata(scope, ids);
            assertEquals(java.util.Set.of(mixed), revoked.keySet());
            assertEquals(List.of(publicFile), revoked.get(mixed).stream().map(io.memoryos.connector.DocumentSourceMetadata::sourceId).toList());
            assertTrue(search.readableMetadata(narrowed, ids).isEmpty(), "A narrowed private scope must not fall back to another public origin");
            assertTrue(search.readableMetadata(driveScope, ids).isEmpty());
            jdbc.sql("UPDATE tenant_memberships SET status='INACTIVE' WHERE tenant_id=:tenant AND actor_id=:actor")
                    .param("tenant", tenant.value()).param("actor", member.value()).update();
            assertTrue(access.readableDocuments(member, ids).isEmpty());
            assertTrue(search.readableMetadata(scope, ids).isEmpty());
            assertTrue(search.options(member, 0, 100).isEmpty());
            jdbc.sql("UPDATE tenants SET status='INACTIVE' WHERE id=:tenant").param("tenant", tenant.value()).update();
            assertTrue(access.readableDocuments(outsider, ids).isEmpty());
        }
    }

    private static ActorId member(JdbcClient jdbc, TenantId tenant) {
        var actor = new ActorId(UUID.randomUUID());
        jdbc.sql("INSERT INTO actors(id) VALUES(:id)").param("id", actor.value()).update();
        jdbc.sql("INSERT INTO tenant_memberships(tenant_id,actor_id,role,status) VALUES(:tenant,:actor,'MEMBER','ACTIVE')")
                .param("tenant", tenant.value()).param("actor", actor.value()).update();
        return actor;
    }

    private static void seed(JdbcClient jdbc, TenantId tenant, UUID source, UUID credential, UUID document, boolean drive, boolean active) {
        seed(jdbc, tenant, source, credential, document, drive, active, "PRIVATE");
    }

    /** {@code driveAccess} is the stored non-public value of the schema under test (RESTRICTED before V63). */
    private static void seed(JdbcClient jdbc, TenantId tenant, UUID source, UUID credential, UUID document, boolean drive, boolean active,
            String driveAccess) {
        if (source.equals(credential)) jdbc.sql("INSERT INTO credentials(id,tenant_id,name,credential_kind,status) VALUES(:id,:tenant,'Test',:kind,'ACTIVE')")
                .param("id", credential).param("tenant", tenant.value()).param("kind", drive ? "GOOGLE_OAUTH" : "NO_AUTH").update();
        jdbc.sql("INSERT INTO connectors(id,tenant_id,name,connector_type,status) VALUES(:id,:tenant,'Test',:type,'ACTIVE')")
                .param("id", source).param("tenant", tenant.value()).param("type", drive ? "GOOGLE_DRIVE" : "FILE").update();
        jdbc.sql("INSERT INTO connector_credential_pairs(id,tenant_id,connector_id,credential_id,access_type,status) VALUES(:id,:tenant,:id,:credential,:access,:status)")
                .param("id", source).param("tenant", tenant.value()).param("credential", credential)
                .param("access", drive ? driveAccess : "PUBLIC").param("status", active ? "ACTIVE" : "NOT_STARTED").update();
        for (String sql : List.of(
                "INSERT INTO stored_objects(id,tenant_id,object_key,filename,declared_media_type,size_bytes,content_sha256,state,expires_at) VALUES(:id,:tenant,CAST(:id AS TEXT),'test.txt','text/plain',1,REPEAT('a',64),'ACTIVE',CURRENT_TIMESTAMP)",
                "INSERT INTO connector_items(id,tenant_id,connector_id,content_sha256,status,created_at,updated_at) VALUES(:id,:tenant,:id,REPEAT('a',64),'INDEXED','2000-01-01T00:00:00Z','2026-09-10T00:00:00Z')",
                "INSERT INTO connector_item_versions(id,tenant_id,connector_id,connector_item_id,revision_number,filename,content_sha256,size_bytes,stored_object_id,created_at) VALUES(:id,:tenant,:id,:id,1,'test.txt',REPEAT('a',64),1,:id,'2001-01-01T00:00:00Z')",
                "UPDATE connector_items SET current_version_id=:id WHERE tenant_id=:tenant AND id=:id")) {
            jdbc.sql(sql).param("id", source).param("tenant", tenant.value()).update();
        }
        jdbc.sql("INSERT INTO documents_by_connector_credential_pair(tenant_id,connector_id,connector_credential_pair_id,document_id,connector_item_id,retrieval_eligible) VALUES(:tenant,:id,:id,:document,:id,TRUE)")
                .param("id", source).param("tenant", tenant.value()).param("document", document).update();
    }
}
