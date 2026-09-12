package io.memoryos.document;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.zaxxer.hikari.HikariDataSource;
import io.memoryos.TestDatabase;
import io.memoryos.document.persistence.JdbcDocumentRepository;
import io.memoryos.document.persistence.JdbcExtractionArtifactRepository;
import io.memoryos.iam.TenantId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.ObjectMapper;

// SQL is exercised against the isolated, migrated Testcontainers database.
@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
@Testcontainers
class ExtractionArtifactLifecycleTest {
    private HikariDataSource source;

    @AfterEach
    void closeDatabase() {
        if (source != null) {
            source.close();
        }
    }

    private JdbcClient jdbc;
    private JdbcExtractionArtifactRepository artifacts;
    private JdbcDocumentRepository documents;
    private TransactionTemplate transaction;
    private TenantId tenant;

    @BeforeEach
    void setup() throws Exception {
        source = TestDatabase.freshPostgres();
        jdbc = JdbcClient.create(source);
        transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
        artifacts = new JdbcExtractionArtifactRepository(jdbc);
        documents = new JdbcDocumentRepository(jdbc, new ObjectMapper(), _ -> { });
        tenant = new TenantId(UUID.randomUUID());
        jdbc.sql("""
                INSERT INTO tenants(id,slug,display_name,status,bootstrap_reference)
                VALUES(:id,'artifact-test','Artifact test','ACTIVE','MEM-61')
                """).param("id", tenant.value()).update();
    }

    @Test
    void privateChunksKeepIdentityOnFirstPublicationReloadAndConventionRebuild() {
        var mapper = new ObjectMapper();
        var chunker = new io.memoryos.document.application.StructuredDocumentChunker(mapper);
        var storage = org.mockito.Mockito.mock(io.memoryos.objectstorage.ObjectStorage.class);
        var chunks = new io.memoryos.document.application.DocumentChunkService(
                new io.memoryos.document.persistence.JdbcDocumentChunkRepository(jdbc, mapper), storage, chunker);
        String json = "{\"schema\":\"memoryos-extraction-v1\",\"blocks\":[{\"kind\":\"TABLE\",\"table\":{\"cells\":[{\"row\":0,\"column\":0,\"text\":\"Private value\"}]}}]}";
        byte[] bytes = json.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        UUID artifact = UUID.randomUUID(), file = UUID.randomUUID();
        artifacts.stage(tenant, artifact, "extracted/" + tenant.value() + "/" + artifact,
                io.memoryos.document.application.StructuredDocumentChunker.sha256(json), bytes.length);
        artifacts.finishWrite(tenant, artifact);
        var id = transaction.execute(_ -> documents.publish(tenant, null, new DocumentContent("text/csv", "private.csv", "Private value",
                Map.of("origin", "USER_FILE", "user_file_id", file.toString()), json, artifact), "a".repeat(64)));
        var generation = jdbc.sql("SELECT content_generation FROM documents").query(UUID.class).single();
        org.mockito.Mockito.when(storage.open(org.mockito.ArgumentMatchers.any())).thenAnswer(_ -> {
            var content = org.mockito.Mockito.mock(io.memoryos.objectstorage.ObjectContent.class);
            org.mockito.Mockito.when(content.inputStream()).thenReturn(new java.io.ByteArrayInputStream(bytes));
            return content;
        });
        var first = transaction.execute(_ -> chunks.prepare(tenant, id, generation).orElseThrow());
        assertEquals(file, java.util.Objects.requireNonNull(first).userFileId());
        assertEquals(first, transaction.execute(_ -> chunks.prepare(tenant, id, generation).orElseThrow()));
        org.mockito.Mockito.verify(storage).open(org.mockito.ArgumentMatchers.any());
        jdbc.sql("UPDATE documents SET chunk_convention='previous-convention' WHERE id=:id")
                .param("id", java.util.Objects.requireNonNull(id).value()).update();
        assertEquals(first, transaction.execute(_ -> chunks.prepare(tenant, id, generation).orElseThrow()));
        org.mockito.Mockito.verify(storage, org.mockito.Mockito.times(2)).open(org.mockito.ArgumentMatchers.any());
        assertEquals(DocumentChunk.CONVENTION, jdbc.sql("SELECT chunk_convention FROM documents").query(String.class).single());
        assertEquals(0, jdbc.sql("SELECT count(*) FROM document_artifact_readers").query(Integer.class).single());
        assertEquals(artifact, jdbc.sql("SELECT extraction_artifact_id FROM documents").query(UUID.class).single());
    }

    @Test
    void reprocessingReplacesCurrentArtifactWithoutCreatingVersionHistory() {
        UUID first = stage(true);
        DocumentId id = transaction.execute(_ -> documents.publish(tenant, null, content(first, "v1"), "a".repeat(64)));
        UUID second = stage(true);
        transaction.executeWithoutResult(_ -> documents.publish(tenant, id, content(second, "v2"), "a".repeat(64)));
        assertEquals(1, jdbc.sql("SELECT count(*) FROM documents").query(Integer.class).single());
        assertEquals(second, jdbc.sql("SELECT extraction_artifact_id FROM documents").query(UUID.class).single());
        var old = artifacts.claimCleanup();
        assertEquals(List.of(first), old.stream().map(JdbcExtractionArtifactRepository.CleanupArtifact::id).toList());
        artifacts.remove(old.getFirst());
        transaction.executeWithoutResult(_ -> documents.removeUnreferenced(tenant, List.of(id)));
        assertEquals(List.of(second), artifacts.claimCleanup().stream()
                .map(JdbcExtractionArtifactRepository.CleanupArtifact::id).toList());
    }

    @Test
    void failedReplacementKeepsPreviousReferenceAndRetryMayProduceDifferentContent() {
        UUID first = stage(true);
        var id = transaction.execute(_ -> documents.publish(tenant, null, content(first, "old"), "a".repeat(64)));
        UUID failed = stage(true);
        assertThrows(IllegalStateException.class, () -> transaction.executeWithoutResult(_ -> {
            documents.publish(tenant, id, content(failed, "changed"), "a".repeat(64));
            throw new IllegalStateException("stale claim");
        }));
        assertEquals(first, jdbc.sql("SELECT extraction_artifact_id FROM documents").query(UUID.class).single());
        assertTrue(artifacts.claimCleanup().isEmpty());
        UUID retried = stage(true);
        transaction.executeWithoutResult(_ -> documents.publish(tenant, id, content(retried, "different again"), "a".repeat(64)));
        assertEquals(retried, jdbc.sql("SELECT extraction_artifact_id FROM documents").query(UUID.class).single());
        assertEquals(1, jdbc.sql("SELECT count(*) FROM documents").query(Integer.class).single());
        assertTrue(jdbc.sql("SELECT metadata_json FROM documents").query(String.class).single().contains("different again"));
    }

    @Test
    void failedPublicationRollsBackArtifactAdoption() {
        UUID artifact = stage(true);
        assertThrows(IllegalStateException.class, () -> transaction.executeWithoutResult(_ -> {
            documents.publish(tenant, null, content(artifact, "v1"), "a".repeat(64));
            throw new IllegalStateException("claim was revoked");
        }));
        assertEquals("STAGED", jdbc.sql("SELECT state FROM document_extraction_artifacts")
                .query(String.class).single());
        assertEquals(0, jdbc.sql("SELECT count(*) FROM documents").query(Integer.class).single());
    }

    @Test
    void uncertainWriteRetainsTombstoneUntilWriterFinishesAndCannotPublishLate() {
        UUID artifact = stage(false);
        jdbc.sql("UPDATE document_extraction_artifacts SET expires_at=CURRENT_TIMESTAMP - INTERVAL '2 hours' "
                        + "WHERE tenant_id=:tenant AND id=:id")
                .param("tenant", tenant.value())
                .param("id", artifact)
                .update();
        var claim = artifacts.claimCleanup().getFirst();
        artifacts.remove(claim);
        assertEquals(1, jdbc.sql("SELECT count(*) FROM document_extraction_artifacts").query(Integer.class).single());
        artifacts.finishWrite(tenant, artifact);
        assertThrows(IllegalStateException.class, () -> transaction.executeWithoutResult(_ ->
                documents.publish(tenant, null, content(artifact, "v1"), "a".repeat(64))));
        // The old token is still valid; its retried physical delete can now remove the tombstone.
        artifacts.remove(claim);
        assertEquals(0, jdbc.sql("SELECT count(*) FROM document_extraction_artifacts").query(Integer.class).single());
    }

    @Test
    void incompleteWriteCannotBeAdopted() {
        UUID artifact = stage(false);
        assertThrows(IllegalStateException.class, () -> transaction.executeWithoutResult(_ ->
                documents.publish(tenant, null, content(artifact, "v1"), "a".repeat(64))));
    }

    private UUID stage(boolean finished) {
        UUID id = UUID.randomUUID();
        artifacts.stage(tenant, id, "extracted/" + tenant.value() + "/" + id, "b".repeat(64), 20);
        if (finished) artifacts.finishWrite(tenant, id);
        return id;
    }

    private DocumentContent content(UUID artifact, String profile) {
        return new DocumentContent("text/plain", "test", "test " + profile,
                Map.of("parser_configuration", profile), "{}", artifact);
    }
}
