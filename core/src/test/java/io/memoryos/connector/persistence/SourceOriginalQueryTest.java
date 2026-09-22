package io.memoryos.connector.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.memoryos.TestDatabase;
import io.memoryos.iam.identity.ActorId;
import io.memoryos.iam.tenant.TenantId;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

class SourceOriginalQueryTest {
    private static final String SHA = "b".repeat(64);

    @Test
    void servesOnlyTheReadableCurrentVersionThatProducedTheDocument() throws Exception {
        try (var database = TestDatabase.freshPostgres()) {
            var jdbc = JdbcClient.create(database);
            var tenant = new TenantId(UUID.randomUUID());
            jdbc.sql("INSERT INTO tenants(id,slug,display_name,status,bootstrap_reference) VALUES(:id,'originals','Originals','ACTIVE',:id)")
                    .param("id", tenant.value()).update();
            var reader = member(jdbc, tenant);
            UUID document = UUID.randomUUID(), source = UUID.randomUUID();
            jdbc.sql("""
                    INSERT INTO documents(id,tenant_id,status,title,content_generation,media_type,source_content_sha256)
                    VALUES(:id,:tenant,'ELIGIBLE','Handbook',:generation,'application/pdf',:sha)
                    """).param("id", document).param("tenant", tenant.value()).param("generation", UUID.randomUUID())
                    .param("sha", SHA).update();
            seed(jdbc, tenant, source, document, "PUBLIC", "drive-file-0001");
            var repository = new JdbcSourceDocumentRepository(jdbc);

            var original = original(repository, tenant, reader, document).orElseThrow();
            assertEquals("handbook.pdf", original.filename());
            assertEquals(SHA, original.metadata().checksum().value());
            assertEquals("drive-file-0001", repository.sourceMetadata(tenant, List.of(document), reader, null)
                    .get(document).getFirst().providerFileId());

            // A stranger outside the Tenant has no readable mapping.
            var stranger = new ActorId(UUID.randomUUID());
            jdbc.sql("INSERT INTO actors(id) VALUES(:id)").param("id", stranger.value()).update();
            assertTrue(original(repository, tenant, stranger, document).isEmpty());

            // A newer item version that has not yet produced the Document is not served as its original.
            jdbc.sql("UPDATE connector_item_versions SET content_sha256=REPEAT('c',64) WHERE tenant_id=:tenant AND id=:id")
                    .param("tenant", tenant.value()).param("id", source).update();
            assertTrue(original(repository, tenant, reader, document).isEmpty());
            jdbc.sql("UPDATE connector_item_versions SET content_sha256=:sha WHERE tenant_id=:tenant AND id=:id")
                    .param("sha", SHA).param("tenant", tenant.value()).param("id", source).update();

            // An object being deleted is not served; ACTIVE objects keep their past staging expiry.
            jdbc.sql("UPDATE stored_objects SET state='DELETE_PENDING' WHERE tenant_id=:tenant AND id=:id")
                    .param("tenant", tenant.value()).param("id", source).update();
            assertTrue(original(repository, tenant, reader, document).isEmpty());
            jdbc.sql("UPDATE stored_objects SET state='ACTIVE' WHERE tenant_id=:tenant AND id=:id")
                    .param("tenant", tenant.value()).param("id", source).update();
            assertTrue(original(repository, tenant, reader, document).isPresent());

            // Private Sources require a Group grant.
            jdbc.sql("UPDATE connector_credential_pairs SET access_type='PRIVATE' WHERE tenant_id=:tenant AND id=:id")
                    .param("tenant", tenant.value()).param("id", source).update();
            assertTrue(original(repository, tenant, reader, document).isEmpty());
            jdbc.sql("UPDATE connector_credential_pairs SET access_type='PUBLIC' WHERE tenant_id=:tenant AND id=:id")
                    .param("tenant", tenant.value()).param("id", source).update();
            jdbc.sql("UPDATE documents SET media_type='text/plain' WHERE tenant_id=:tenant AND id=:id")
                    .param("tenant", tenant.value()).param("id", document).update();

            // A Document that is not a PDF still has the original it was extracted from: the viewer shows every type.
            assertEquals("handbook.pdf", original(repository, tenant, reader, document).orElseThrow().filename());
            assertTrue(original(repository, tenant, stranger, document).isEmpty());
        }
    }

    private static java.util.Optional<io.memoryos.objectstorage.StoredObjectReference> original(
            JdbcSourceDocumentRepository repository, TenantId tenant, ActorId actor, UUID document) {
        return java.util.Optional.ofNullable(repository.originals(tenant, actor, java.util.Set.of(document)).get(document));
    }

    private static ActorId member(JdbcClient jdbc, TenantId tenant) {
        var actor = new ActorId(UUID.randomUUID());
        jdbc.sql("INSERT INTO actors(id) VALUES(:id)").param("id", actor.value()).update();
        jdbc.sql("INSERT INTO tenant_memberships(tenant_id,actor_id,role,status) VALUES(:tenant,:actor,'MEMBER','ACTIVE')")
                .param("tenant", tenant.value()).param("actor", actor.value()).update();
        return actor;
    }

    private static void seed(JdbcClient jdbc, TenantId tenant, UUID source, UUID document, String access, String providerFileId) {
        jdbc.sql("INSERT INTO credentials(id,tenant_id,name,credential_kind,status) VALUES(:id,:tenant,'Test','NO_AUTH','ACTIVE')")
                .param("id", source).param("tenant", tenant.value()).update();
        jdbc.sql("INSERT INTO connectors(id,tenant_id,name,connector_type,status) VALUES(:id,:tenant,'Test','FILE','ACTIVE')")
                .param("id", source).param("tenant", tenant.value()).update();
        jdbc.sql("INSERT INTO connector_credential_pairs(id,tenant_id,connector_id,credential_id,access_type,status) VALUES(:id,:tenant,:id,:id,:access,'ACTIVE')")
                .param("id", source).param("tenant", tenant.value()).param("access", access).update();
        for (String sql : List.of(
                "INSERT INTO stored_objects(id,tenant_id,object_key,filename,declared_media_type,size_bytes,content_sha256,state,expires_at) VALUES(:id,:tenant,'raw/' || CAST(:tenant AS TEXT) || '/' || CAST(:id AS TEXT),'handbook.pdf','application/pdf',653,:sha,'ACTIVE',CURRENT_TIMESTAMP)",
                "INSERT INTO connector_items(id,tenant_id,connector_id,content_sha256,status,provider_file_id) VALUES(:id,:tenant,:id,:sha,'INDEXED',:provider)",
                "INSERT INTO connector_item_versions(id,tenant_id,connector_id,connector_item_id,revision_number,filename,content_sha256,size_bytes,stored_object_id) VALUES(:id,:tenant,:id,:id,1,'handbook.pdf',:sha,653,:id)",
                "UPDATE connector_items SET current_version_id=:id WHERE tenant_id=:tenant AND id=:id")) {
            jdbc.sql(sql).param("id", source).param("tenant", tenant.value()).param("sha", SHA).param("provider", providerFileId).update();
        }
        jdbc.sql("INSERT INTO documents_by_connector_credential_pair(tenant_id,connector_id,connector_credential_pair_id,document_id,connector_item_id,retrieval_eligible) VALUES(:tenant,:id,:id,:document,:id,TRUE)")
                .param("id", source).param("tenant", tenant.value()).param("document", document).update();
    }
}
