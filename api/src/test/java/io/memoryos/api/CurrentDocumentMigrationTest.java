package io.memoryos.api;

import static org.junit.jupiter.api.Assertions.*;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@Testcontainers(disabledWithoutDocker = true)
class CurrentDocumentMigrationTest {
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(DockerImageName.parse(
            "postgres:17.11-alpine3.24@sha256:18cfe3ef5e6815560c98237d6216d1e5119702fb0f3894c8785dd58b8bbe5d73")
            .asCompatibleSubstituteFor("postgres"));

    @Test
    void keepsCurrentMetadataAndArtifactWithoutBlockingLegacyDevDocuments() {
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .target("10").load().migrate();
        var jdbc = JdbcClient.create(new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
        UUID tenant = UUID.randomUUID();
        UUID artifact = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO tenants(id,slug,display_name,status,bootstrap_reference)
                VALUES(:tenant,'migration','Migration','ACTIVE','MEM-61')
                """).param("tenant", tenant).update();
        jdbc.sql("""
                INSERT INTO document_extraction_artifacts(id,tenant_id,object_key,content_sha256,size_bytes,state,expires_at,write_complete)
                VALUES(:id,:tenant,'extracted/test',:sha,20,'ACTIVE',CURRENT_TIMESTAMP,TRUE)
                """).param("id", artifact).param("tenant", tenant).param("sha", "a".repeat(64)).update();
        for (boolean legacy : new boolean[]{false, true}) {
            UUID document = UUID.randomUUID();
            jdbc.sql("INSERT INTO documents(id,tenant_id,status) VALUES(:id,:tenant,'ELIGIBLE')")
                    .param("id", document).param("tenant", tenant).update();
            for (int version = 1; version <= 2; version++) {
                UUID versionId = UUID.randomUUID();
                jdbc.sql("""
                        INSERT INTO document_versions(id,tenant_id,document_id,version_number,title,media_type,
                            normalized_text,source_content_sha256,metadata_json,extraction_artifact_id)
                        VALUES(:id,:tenant,:document,:version,:title,'text/plain','derived text',:sha,'{}',:artifact)
                        """).param("id", versionId).param("tenant", tenant).param("document", document)
                        .param("version", version).param("title", "title-" + version)
                        .param("sha", Integer.toString(version).repeat(64))
                        .param("artifact", legacy ? null : artifact).update();
                jdbc.sql("UPDATE documents SET current_version_id=:version WHERE id=:id")
                        .param("version", versionId).param("id", document).update();
            }
        }
        assertEquals(1, Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .load().migrate().migrationsExecuted);
        assertEquals(2, jdbc.sql("SELECT count(*) FROM documents WHERE title='title-2'").query(Integer.class).single());
        assertEquals(1, jdbc.sql("SELECT count(*) FROM documents WHERE extraction_artifact_id IS NULL").query(Integer.class).single());
        assertEquals(artifact, jdbc.sql("SELECT extraction_artifact_id FROM documents WHERE extraction_artifact_id IS NOT NULL")
                .query(UUID.class).single());
        assertEquals(0, jdbc.sql("""
                SELECT count(*) FROM information_schema.tables WHERE table_schema='public'
                AND table_name IN ('document_versions','document_processing_attempts')
                """).query(Integer.class).single());
        assertEquals(0, jdbc.sql("""
                SELECT count(*) FROM information_schema.columns WHERE table_schema='public' AND table_name='documents'
                AND column_name IN ('current_version_id','normalized_text','processing_profile')
                """).query(Integer.class).single());
    }
}
