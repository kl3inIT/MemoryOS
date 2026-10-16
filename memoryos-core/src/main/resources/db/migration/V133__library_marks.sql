-- Library hub: the marks a person leaves on what their library shows them, whoever owns it. A star on a reachable
-- row (a meeting shared with them, an Agent's file, a Source document) and the time they last opened any row. A mark
-- is never authority: every read resolves the marked id through the capability that owns it and drops what no
-- longer resolves. Owned files keep their own favorite_at; their stars are not written here.
CREATE TABLE library_mark (
    tenant_id UUID NOT NULL,
    actor_id UUID NOT NULL,
    kind VARCHAR(16) NOT NULL
        CHECK (kind IN ('UPLOAD', 'GENERATED', 'IMAGE', 'MEETING', 'AGENT_FILE', 'DOCUMENT')),
    item_id UUID NOT NULL,
    starred_at TIMESTAMPTZ,
    opened_at TIMESTAMPTZ,
    PRIMARY KEY (tenant_id, actor_id, kind, item_id),
    FOREIGN KEY (tenant_id, actor_id) REFERENCES tenant_memberships (tenant_id, actor_id) ON DELETE CASCADE,
    -- Only a reachable kind is starred here.
    CHECK (starred_at IS NULL OR kind IN ('MEETING', 'AGENT_FILE', 'DOCUMENT')),
    -- A row that marks nothing is deleted, never kept empty.
    CHECK (starred_at IS NOT NULL OR opened_at IS NOT NULL)
);
CREATE INDEX ix_library_mark_opened ON library_mark (tenant_id, actor_id, opened_at DESC) WHERE opened_at IS NOT NULL;
CREATE INDEX ix_library_mark_starred ON library_mark (tenant_id, actor_id, starred_at DESC) WHERE starred_at IS NOT NULL;
