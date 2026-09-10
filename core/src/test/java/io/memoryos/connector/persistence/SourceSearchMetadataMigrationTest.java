package io.memoryos.connector.persistence;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.memoryos.TestDatabase;
import io.memoryos.connector.SourceSearchService;
import io.memoryos.connector.SourceType;
import io.memoryos.document.DocumentId;
import io.memoryos.iam.ActorId;
import io.memoryos.iam.TenantAccessResolver;
import io.memoryos.iam.TenantId;
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
            jdbc.sql("""
                    INSERT INTO documents(id,tenant_id,status,title,content_generation,metadata_json)
                    VALUES(:id,:tenant,'ELIGIBLE','Original',:generation,'{"dc:creator":"Alice"}')
                    """).param("id", document).param("tenant", tenant.value()).param("generation", generation).update();
            seed(jdbc, tenant, file, file, document, false, true);
            seed(jdbc, tenant, drive, drive, document, true, true);
            seed(jdbc, tenant, inactive, file, document, false, false);
            var flyway = Flyway.configure().dataSource(database).locations("classpath:db/migration").load();
            assertEquals(1, flyway.migrate().migrationsExecuted);
            assertEquals(0, flyway.migrate().migrationsExecuted);
            flyway.validate();

            var repository = new JdbcSourceDocumentRepository(jdbc);
            var tenants = mock(TenantAccessResolver.class);
            when(tenants.findActiveTenant(any())).thenReturn(Optional.of(tenant));
            var service = new SourceSearchService(tenants, repository);
            var scope = service.scope(new ActorId(UUID.randomUUID()));
            assertEquals(java.util.Map.of(file, SourceType.FILE), scope.sources());
            var visible = service.readableMetadata(scope, List.of(document)).get(document);
            assertEquals(1, visible.size());
            var origin = visible.getFirst();
            assertEquals(file, origin.sourceId());
            assertEquals(Instant.parse("2000-01-01T00:00:00Z"), origin.createdAt());
            assertEquals(Instant.parse("2001-01-01T00:00:00Z"), origin.updatedAt());
            assertEquals(List.of("Alice"), origin.authors());
            var indexed = service.indexMetadata(tenant, new DocumentId(document), generation);
            assertEquals(3, indexed.size());
            var remote = indexed.stream().filter(m -> m.type() == SourceType.GOOGLE_DRIVE).findFirst().orElseThrow();
            assertNull(remote.createdAt()); assertNull(remote.updatedAt());
            assertTrue(service.indexMetadata(tenant, new DocumentId(document), UUID.randomUUID()).isEmpty());

            jdbc.sql("UPDATE connector_items SET updated_at=CURRENT_TIMESTAMP WHERE tenant_id=:tenant")
                    .param("tenant", tenant.value()).update();
            assertEquals(visible, service.readableMetadata(scope, List.of(document)).get(document));
            jdbc.sql("UPDATE connector_credential_pairs SET status='NOT_STARTED' WHERE id=:id").param("id", file).update();
            assertTrue(service.readableMetadata(scope, List.of(document)).isEmpty(), "A prefetched scope cannot retain revoked access");
            assertTrue(service.scope(new ActorId(UUID.randomUUID())).sources().isEmpty());
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
            var visible = service.readableMetadata(service.scope(new ActorId(UUID.randomUUID())), documents);
            assertEquals(4, visible.size());
            for (int i = 0; i < documents.size(); i++) {
                var origin = visible.get(documents.get(i)).getFirst();
                assertEquals(i == 3 ? List.of("Alice") : List.of(), origin.authors());
                assertEquals(SourceType.FILE, origin.type());
                assertEquals(List.of(origin), service.indexMetadata(tenant, new DocumentId(documents.get(i)), generation));
            }
        }
    }

    private static void seed(JdbcClient jdbc, TenantId tenant, UUID source, UUID credential, UUID document, boolean drive, boolean active) {
        if (source.equals(credential)) jdbc.sql("INSERT INTO credentials(id,tenant_id,name,credential_kind,status) VALUES(:id,:tenant,'Test',:kind,'ACTIVE')")
                .param("id", credential).param("tenant", tenant.value()).param("kind", drive ? "GOOGLE_OAUTH" : "NO_AUTH").update();
        jdbc.sql("INSERT INTO connectors(id,tenant_id,name,connector_type,status) VALUES(:id,:tenant,'Test',:type,'ACTIVE')")
                .param("id", source).param("tenant", tenant.value()).param("type", drive ? "GOOGLE_DRIVE" : "FILE").update();
        jdbc.sql("INSERT INTO connector_credential_pairs(id,tenant_id,connector_id,credential_id,access_type,status) VALUES(:id,:tenant,:id,:credential,:access,:status)")
                .param("id", source).param("tenant", tenant.value()).param("credential", credential)
                .param("access", drive ? "RESTRICTED" : "PUBLIC").param("status", active ? "ACTIVE" : "NOT_STARTED").update();
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
