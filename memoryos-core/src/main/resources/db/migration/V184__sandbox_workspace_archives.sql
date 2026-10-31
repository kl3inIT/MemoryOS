-- MEM-270: a conversation's /workspace outlives its sandbox, as Onyx Craft tars the workspace to its file store and
-- restores it on the next use. Each archive is written once under a new key and recorded here before its upload, so
-- an upload that failed, an archive a later one replaced and the archive of a deleted conversation are all found
-- and removed by the Worker. No foreign key: a deleted conversation leaves its rows for that sweep.
CREATE TABLE sandbox_workspace_archives (
    object_key VARCHAR(240) PRIMARY KEY,
    tenant_id UUID NOT NULL,
    conversation_id UUID NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX ix_sandbox_workspace_archives_created ON sandbox_workspace_archives (created_at);

-- The archive a new sandbox of the conversation starts from.
ALTER TABLE sandbox_workspaces ADD COLUMN archive_key VARCHAR(240);
ALTER TABLE sandbox_workspaces ADD COLUMN archived_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE sandbox_workspaces ADD CONSTRAINT ck_sandbox_workspace_archive
    CHECK ((archive_key IS NULL) = (archived_at IS NULL));
CREATE INDEX ix_sandbox_workspaces_archive ON sandbox_workspaces (archive_key) WHERE archive_key IS NOT NULL;
