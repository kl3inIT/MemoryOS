package io.memoryos.document;

import static org.junit.jupiter.api.Assertions.*;

import io.memoryos.TestDatabase;
import io.memoryos.document.persistence.JdbcDocumentRepository;
import io.memoryos.document.persistence.JdbcExtractionArtifactRepository;
import io.memoryos.tenant.TenantId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.ObjectMapper;

@Testcontainers(disabledWithoutDocker = true)
class ExtractionArtifactLifecycleTest {
    private JdbcClient jdbc;
    private JdbcExtractionArtifactRepository artifacts;
    private JdbcDocumentRepository documents;
    private TransactionTemplate transaction;
    private TenantId tenant;

    @BeforeEach
    void setup() throws Exception {
        var source = TestDatabase.freshPostgres();
        jdbc = JdbcClient.create(source);
        transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
        artifacts = new JdbcExtractionArtifactRepository(jdbc);
        documents = new JdbcDocumentRepository(jdbc, new ObjectMapper());
        tenant = new TenantId(UUID.randomUUID());
        jdbc.sql("""
                INSERT INTO tenants(id,slug,display_name,status,bootstrap_reference)
                VALUES(:id,'artifact-test','Artifact test','ACTIVE','MEM-61')
                """).param("id", tenant.value()).update();
    }

    @Test
    void reprocessingReplacesCurrentArtifactWithoutCreatingVersionHistory() {
        UUID first = stage(true);
        DocumentId id = transaction.execute(s -> documents.publish(tenant, null, content(first, "v1"), "a".repeat(64)));
        UUID second = stage(true);
        transaction.executeWithoutResult(s -> documents.publish(tenant, id, content(second, "v2"), "a".repeat(64)));
        assertEquals(1, jdbc.sql("SELECT count(*) FROM documents").query(Integer.class).single());
        assertEquals(second, jdbc.sql("SELECT extraction_artifact_id FROM documents").query(UUID.class).single());
        var old = artifacts.claimCleanup();
        assertEquals(List.of(first), old.stream().map(JdbcExtractionArtifactRepository.CleanupArtifact::id).toList());
        artifacts.remove(old.getFirst());
        transaction.executeWithoutResult(s -> documents.removeUnreferenced(tenant, List.of(id)));
        assertEquals(List.of(second), artifacts.claimCleanup().stream()
                .map(JdbcExtractionArtifactRepository.CleanupArtifact::id).toList());
    }

    @Test
    void failedReplacementKeepsPreviousReferenceAndRetryMayProduceDifferentContent() {
        UUID first = stage(true);
        var id = transaction.execute(s -> documents.publish(tenant, null, content(first, "old"), "a".repeat(64)));
        UUID failed = stage(true);
        assertThrows(IllegalStateException.class, () -> transaction.executeWithoutResult(s -> {
            documents.publish(tenant, id, content(failed, "changed"), "a".repeat(64));
            throw new IllegalStateException("stale claim");
        }));
        assertEquals(first, jdbc.sql("SELECT extraction_artifact_id FROM documents").query(UUID.class).single());
        assertTrue(artifacts.claimCleanup().isEmpty());
        UUID retried = stage(true);
        transaction.executeWithoutResult(s -> documents.publish(tenant, id, content(retried, "different again"), "a".repeat(64)));
        assertEquals(retried, jdbc.sql("SELECT extraction_artifact_id FROM documents").query(UUID.class).single());
        assertEquals(1, jdbc.sql("SELECT count(*) FROM documents").query(Integer.class).single());
        assertTrue(jdbc.sql("SELECT metadata_json FROM documents").query(String.class).single().contains("different again"));
    }

    @Test
    void failedPublicationRollsBackArtifactAdoption() {
        UUID artifact = stage(true);
        assertThrows(IllegalStateException.class, () -> transaction.executeWithoutResult(s -> {
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
        jdbc.sql("UPDATE document_extraction_artifacts SET expires_at=CURRENT_TIMESTAMP - INTERVAL '2 hours'").update();
        var claim = artifacts.claimCleanup().getFirst();
        artifacts.remove(claim);
        assertEquals(1, jdbc.sql("SELECT count(*) FROM document_extraction_artifacts").query(Integer.class).single());
        artifacts.finishWrite(tenant, artifact);
        assertThrows(IllegalStateException.class, () -> transaction.executeWithoutResult(s ->
                documents.publish(tenant, null, content(artifact, "v1"), "a".repeat(64))));
        // The old token is still valid; its retried physical delete can now remove the tombstone.
        artifacts.remove(claim);
        assertEquals(0, jdbc.sql("SELECT count(*) FROM document_extraction_artifacts").query(Integer.class).single());
    }

    @Test
    void incompleteWriteCannotBeAdopted() {
        UUID artifact = stage(false);
        assertThrows(IllegalStateException.class, () -> transaction.executeWithoutResult(s ->
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
