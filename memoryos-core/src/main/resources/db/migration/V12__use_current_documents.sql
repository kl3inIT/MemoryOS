-- Dev/staging cutover: retain current metadata/reference, discard extraction history.
-- Legacy documents with no artifact can be reindexed through the normal FILE command.

ALTER TABLE documents ADD COLUMN title VARCHAR(255);
ALTER TABLE documents ADD COLUMN media_type VARCHAR(160);
ALTER TABLE documents ADD COLUMN source_content_sha256 VARCHAR(64);
ALTER TABLE documents ADD COLUMN metadata_json TEXT;
ALTER TABLE documents ADD COLUMN extraction_artifact_id UUID;
UPDATE documents SET
    title=(SELECT v.title FROM document_versions v WHERE v.tenant_id=documents.tenant_id AND v.id=documents.current_version_id),
    media_type=(SELECT v.media_type FROM document_versions v WHERE v.tenant_id=documents.tenant_id AND v.id=documents.current_version_id),
    source_content_sha256=(SELECT v.source_content_sha256 FROM document_versions v WHERE v.tenant_id=documents.tenant_id AND v.id=documents.current_version_id),
    metadata_json=(SELECT v.metadata_json FROM document_versions v WHERE v.tenant_id=documents.tenant_id AND v.id=documents.current_version_id),
    extraction_artifact_id=(SELECT v.extraction_artifact_id FROM document_versions v WHERE v.tenant_id=documents.tenant_id AND v.id=documents.current_version_id);
ALTER TABLE documents ADD CONSTRAINT fk_document_extraction_artifact
    FOREIGN KEY (tenant_id, extraction_artifact_id)
    REFERENCES document_extraction_artifacts(tenant_id, id) ON DELETE RESTRICT;
ALTER TABLE documents DROP CONSTRAINT fk_documents_current_version;
ALTER TABLE documents DROP COLUMN current_version_id;
DROP TABLE document_versions;
DROP TABLE document_processing_attempts;
