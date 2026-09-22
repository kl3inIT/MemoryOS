-- MEM-92: a meeting the owner shares with the people who were in it. A reader reads; only the owner edits or
-- deletes, so the share tables carry no permission column, unlike an Agent's.
CREATE TABLE meeting_user_share (
    tenant_id UUID NOT NULL,
    meeting_id UUID NOT NULL,
    actor_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id, meeting_id, actor_id),
    FOREIGN KEY (tenant_id, meeting_id) REFERENCES meeting (tenant_id, id) ON DELETE CASCADE,
    FOREIGN KEY (tenant_id, actor_id) REFERENCES tenant_memberships (tenant_id, actor_id) ON DELETE CASCADE
);
CREATE INDEX ix_meeting_user_share_actor ON meeting_user_share (tenant_id, actor_id, meeting_id);

CREATE TABLE meeting_group_share (
    tenant_id UUID NOT NULL,
    meeting_id UUID NOT NULL,
    group_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id, meeting_id, group_id),
    FOREIGN KEY (tenant_id, meeting_id) REFERENCES meeting (tenant_id, id) ON DELETE CASCADE,
    FOREIGN KEY (tenant_id, group_id) REFERENCES iam_groups (tenant_id, id) ON DELETE CASCADE
);
CREATE INDEX ix_meeting_group_share_group ON meeting_group_share (tenant_id, group_id, meeting_id);
