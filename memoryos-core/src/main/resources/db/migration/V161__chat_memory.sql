-- MEM-250: what Chat remembers about one member across conversations (Onyx `## User Memories`), one document per
-- Tenant membership. The switch sits in the same row as the text so the two never disagree; a missing row reads as
-- an empty memory that is switched on. `revision` lets two conversations of one person write without overwriting.
CREATE TABLE chat_memory (
    tenant_id UUID NOT NULL,
    actor_id UUID NOT NULL,
    content VARCHAR(4000) NOT NULL DEFAULT '',
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    revision BIGINT NOT NULL DEFAULT 0 CHECK (revision >= 0),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id, actor_id),
    FOREIGN KEY (tenant_id, actor_id) REFERENCES tenant_memberships(tenant_id, actor_id) ON DELETE CASCADE
);
