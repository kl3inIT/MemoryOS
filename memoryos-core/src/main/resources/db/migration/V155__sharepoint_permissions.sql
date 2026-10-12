-- MEM-136: SharePoint's own permissions read for SYNC Sources, as Onyx EE reads them. Two parts, as Google Drive and
-- Lark keep them: who may read each item, and who the groups named there contain.

-- Where SharePoint keeps an item: the list and the row of a file, and the site that owns it. Its role assignments
-- are read there over SharePoint REST, by the list's identifier and never by a library title. An item acquired
-- before this migration has none until a run asks Microsoft again.
ALTER TABLE sharepoint_items
    ADD COLUMN list_id VARCHAR(64),
    ADD COLUMN list_item_id VARCHAR(32),
    ADD COLUMN web_url VARCHAR(2048);

-- Who SharePoint let read one item at its last complete read: everyone, people by email, and groups by the name
-- group membership uses for them. A read that fails keeps the last complete answer in force; before the first one
-- nobody reads. An item that is forgotten takes its answer with it.
CREATE TABLE sharepoint_acl_snapshots (
    tenant_id UUID NOT NULL,
    source_id UUID NOT NULL,
    file_id VARCHAR(512) NOT NULL,
    observation_revision BIGINT NOT NULL DEFAULT 0 CHECK (observation_revision >= 0),
    access_public BOOLEAN NOT NULL DEFAULT FALSE,
    -- Sorted arrays of text, so an unchanged answer compares equal.
    user_emails JSONB,
    group_ids JSONB,
    status VARCHAR(16) NOT NULL CHECK (status IN ('SUCCEEDED', 'FAILED')),
    last_attempt_at TIMESTAMPTZ NOT NULL,
    last_success_at TIMESTAMPTZ,
    error_code VARCHAR(64),
    PRIMARY KEY (tenant_id, source_id, file_id),
    FOREIGN KEY (tenant_id, source_id, file_id)
        REFERENCES sharepoint_items (tenant_id, source_id, provider_file_id) ON DELETE CASCADE,
    CONSTRAINT ck_sharepoint_acl_success CHECK (
        (observation_revision = 0 AND user_emails IS NULL AND group_ids IS NULL AND NOT access_public
            AND last_success_at IS NULL)
        OR (observation_revision > 0 AND jsonb_typeof(user_emails) = 'array' AND jsonb_typeof(group_ids) = 'array'
            AND last_success_at IS NOT NULL)),
    CONSTRAINT ck_sharepoint_acl_attempt CHECK (
        (status = 'FAILED' AND error_code IS NOT NULL AND error_code ~ '^[A-Z][A-Z0-9_]{0,63}$')
        OR (status = 'SUCCEEDED' AND observation_revision > 0 AND error_code IS NULL))
);

-- Group membership of a Source, stored as generations like Google Group membership (ADR 0013): only a read that
-- reached every group becomes active, so one that stops half way never removes anybody's access. SharePoint groups
-- belong to a site, so membership is kept for each Source rather than for its credential.
CREATE TABLE sharepoint_group_runs (
    tenant_id UUID NOT NULL,
    source_id UUID NOT NULL,
    generation BIGINT NOT NULL CHECK (generation > 0),
    status VARCHAR(16) NOT NULL CHECK (status IN ('RUNNING', 'COMPLETED', 'FAILED')),
    error_code VARCHAR(64),
    started_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    finished_at TIMESTAMPTZ,
    PRIMARY KEY (tenant_id, source_id, generation),
    FOREIGN KEY (tenant_id, source_id) REFERENCES sharepoint_sources (tenant_id, source_id) ON DELETE CASCADE,
    CHECK ((status = 'RUNNING') = (finished_at IS NULL)),
    CHECK ((status = 'FAILED') = (error_code IS NOT NULL))
);
CREATE UNIQUE INDEX ux_sharepoint_group_running ON sharepoint_group_runs (tenant_id, source_id)
    WHERE status = 'RUNNING';

-- What a read still has to ask Microsoft. A site is asked for its role assignments, which name groups; a group is
-- asked for its direct members, which may name more groups; the directory is asked for every group it has.
CREATE TABLE sharepoint_group_work (
    tenant_id UUID NOT NULL,
    source_id UUID NOT NULL,
    generation BIGINT NOT NULL,
    kind VARCHAR(16) NOT NULL CHECK (kind IN ('WEB', 'SITE_GROUP', 'ENTRA_GROUP', 'DIRECTORY')),
    -- The site's address for WEB, the group's name in membership for a group, '*' for the directory.
    work_key VARCHAR(512) NOT NULL CHECK (CHAR_LENGTH(work_key) > 0),
    -- The site a SharePoint group belongs to.
    web_url VARCHAR(512),
    -- How Microsoft is asked for the group: its number in the site, or its identifier in the directory.
    provider_ref VARCHAR(64),
    page_link TEXT,
    done BOOLEAN NOT NULL DEFAULT FALSE,
    PRIMARY KEY (tenant_id, source_id, generation, kind, work_key),
    FOREIGN KEY (tenant_id, source_id, generation)
        REFERENCES sharepoint_group_runs (tenant_id, source_id, generation) ON DELETE CASCADE,
    CHECK ((kind = 'SITE_GROUP') = (web_url IS NOT NULL)),
    CHECK ((kind IN ('SITE_GROUP', 'ENTRA_GROUP')) = (provider_ref IS NOT NULL))
);
CREATE INDEX ix_sharepoint_group_work_pending
    ON sharepoint_group_work (tenant_id, source_id, generation, kind, work_key) WHERE NOT done;

-- The direct members of a group. The email is what a reader's verified login email is matched with (ADR 0011).
CREATE TABLE sharepoint_group_members (
    tenant_id UUID NOT NULL,
    source_id UUID NOT NULL,
    generation BIGINT NOT NULL,
    group_id VARCHAR(512) NOT NULL CHECK (CHAR_LENGTH(group_id) > 0),
    member_email VARCHAR(320) NOT NULL CHECK (member_email = LOWER(member_email) AND CHAR_LENGTH(member_email) > 0),
    PRIMARY KEY (tenant_id, source_id, generation, group_id, member_email),
    FOREIGN KEY (tenant_id, source_id, generation)
        REFERENCES sharepoint_group_runs (tenant_id, source_id, generation) ON DELETE CASCADE
);
CREATE INDEX ix_sharepoint_group_members_email
    ON sharepoint_group_members (tenant_id, member_email, source_id, generation);

ALTER TABLE sharepoint_sources
    -- A sharing link open to the organization or to anyone makes its file readable by every reader.
    ADD COLUMN treat_sharing_links_as_public BOOLEAN NOT NULL DEFAULT FALSE,
    -- When the permissions of every item the Source holds are read again; a change of permissions alone is not in
    -- SharePoint's change log.
    ADD COLUMN next_permission_sync_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ADD COLUMN active_group_generation BIGINT;
ALTER TABLE sharepoint_sources ADD CONSTRAINT fk_sharepoint_active_group_generation
    FOREIGN KEY (tenant_id, source_id, active_group_generation)
    REFERENCES sharepoint_group_runs (tenant_id, source_id, generation) DEFERRABLE INITIALLY DEFERRED;

-- A run reads content first, then, for a SYNC Source, group membership and permissions. The phase and where the
-- permission read stands are kept so a handed-back run resumes instead of listing its content again.
ALTER TABLE sharepoint_sync_runs
    ADD COLUMN phase VARCHAR(16) NOT NULL DEFAULT 'CONTENT'
        CHECK (phase IN ('CONTENT', 'GROUPS', 'PERMISSIONS', 'FINISH')),
    ADD COLUMN permission_checkpoint VARCHAR(512);
