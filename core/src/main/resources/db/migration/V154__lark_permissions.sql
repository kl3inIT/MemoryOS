-- MEM-245: Lark sharing read for SYNC Sources. Two parts, as Google Drive keeps a file's permissions apart from
-- group membership: what each object is shared with, and who the chats, departments and spaces named there contain.

-- What Lark answered for one object at its last complete read: its collaborators, the inherited ones included, and
-- its link setting. A read that fails keeps the last complete answer in force; before the first one nobody reads.
CREATE TABLE lark_acl_snapshots (
    tenant_id UUID NOT NULL,
    source_id UUID NOT NULL,
    file_id VARCHAR(256) NOT NULL CHECK (CHAR_LENGTH(BTRIM(file_id)) > 0),
    observation_revision BIGINT NOT NULL DEFAULT 0 CHECK (observation_revision >= 0),
    -- [{"type": Lark's member_type, "id": Lark's member_id}], sorted, so an unchanged answer compares equal.
    members_json JSONB,
    link_share VARCHAR(32),
    status VARCHAR(16) NOT NULL CHECK (status IN ('SUCCEEDED', 'FAILED')),
    last_attempt_at TIMESTAMPTZ NOT NULL,
    last_success_at TIMESTAMPTZ,
    error_code VARCHAR(64),
    PRIMARY KEY (tenant_id, source_id, file_id),
    FOREIGN KEY (tenant_id, source_id) REFERENCES lark_sources (tenant_id, source_id) ON DELETE CASCADE,
    CONSTRAINT ck_lark_acl_success CHECK (
        (observation_revision = 0 AND members_json IS NULL AND last_success_at IS NULL)
        OR (observation_revision > 0 AND members_json IS NOT NULL AND jsonb_typeof(members_json) = 'array'
            AND last_success_at IS NOT NULL)),
    CONSTRAINT ck_lark_acl_attempt CHECK (
        (status = 'FAILED' AND error_code IS NOT NULL AND error_code ~ '^[A-Z][A-Z0-9_]{0,63}$')
        OR (status = 'SUCCEEDED' AND observation_revision > 0 AND error_code IS NULL))
);

-- The directory of a credential's Lark tenant, stored as generations like Google Group membership (ADR 0013): only
-- a run that read everything becomes active, so a run that stops half way never removes anybody's access.
CREATE TABLE lark_directory_runs (
    tenant_id UUID NOT NULL,
    credential_id UUID NOT NULL,
    generation BIGINT NOT NULL CHECK (generation > 0),
    status VARCHAR(16) NOT NULL CHECK (status IN ('RUNNING', 'COMPLETED', 'FAILED')),
    error_code VARCHAR(64),
    started_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    finished_at TIMESTAMPTZ,
    PRIMARY KEY (tenant_id, credential_id, generation),
    FOREIGN KEY (tenant_id, credential_id) REFERENCES lark_credentials (tenant_id, credential_id) ON DELETE CASCADE,
    CHECK ((status = 'RUNNING') = (finished_at IS NULL)),
    CHECK ((status = 'FAILED') = (error_code IS NOT NULL))
);
-- One run advances at a time for each credential, whichever Source's sync step drives it.
CREATE UNIQUE INDEX ux_lark_directory_running ON lark_directory_runs (tenant_id, credential_id)
    WHERE status = 'RUNNING';

-- What a run still has to list. A root is what a snapshot names (a chat, a department, a space); a group is what is
-- listed for it: the root itself, a department below it, or a chat or department that is a member of a space.
CREATE TABLE lark_directory_work (
    tenant_id UUID NOT NULL,
    credential_id UUID NOT NULL,
    generation BIGINT NOT NULL,
    root_kind VARCHAR(16) NOT NULL CHECK (root_kind IN ('CHAT', 'DEPARTMENT', 'SPACE')),
    root_id VARCHAR(128) NOT NULL CHECK (CHAR_LENGTH(root_id) > 0),
    kind VARCHAR(16) NOT NULL CHECK (kind IN ('CHAT', 'DEPARTMENT', 'SPACE')),
    group_id VARCHAR(128) NOT NULL CHECK (CHAR_LENGTH(group_id) > 0),
    -- A department's sub-departments are listed before its own members.
    children_listed BOOLEAN NOT NULL DEFAULT FALSE,
    page_token TEXT,
    done BOOLEAN NOT NULL DEFAULT FALSE,
    PRIMARY KEY (tenant_id, credential_id, generation, root_kind, root_id, kind, group_id),
    FOREIGN KEY (tenant_id, credential_id, generation)
        REFERENCES lark_directory_runs (tenant_id, credential_id, generation) ON DELETE CASCADE
);

-- The people of a root: its own members and those of everything listed for it.
CREATE TABLE lark_directory_members (
    tenant_id UUID NOT NULL,
    credential_id UUID NOT NULL,
    generation BIGINT NOT NULL,
    root_kind VARCHAR(16) NOT NULL CHECK (root_kind IN ('CHAT', 'DEPARTMENT', 'SPACE')),
    root_id VARCHAR(128) NOT NULL,
    open_id VARCHAR(128) NOT NULL CHECK (CHAR_LENGTH(open_id) > 0),
    PRIMARY KEY (tenant_id, credential_id, generation, root_kind, root_id, open_id),
    FOREIGN KEY (tenant_id, credential_id, generation)
        REFERENCES lark_directory_runs (tenant_id, credential_id, generation) ON DELETE CASCADE
);
CREATE INDEX ix_lark_directory_members_user
    ON lark_directory_members (tenant_id, credential_id, generation, open_id);

-- A person Lark was asked about. The email is what a reader's verified login email is matched with (ADR 0011); a
-- person without one, or one who left, is kept without it so the run does not ask again.
CREATE TABLE lark_directory_users (
    tenant_id UUID NOT NULL,
    credential_id UUID NOT NULL,
    generation BIGINT NOT NULL,
    open_id VARCHAR(128) NOT NULL CHECK (CHAR_LENGTH(open_id) > 0),
    email VARCHAR(320) CHECK (email = LOWER(email) AND CHAR_LENGTH(email) > 0),
    PRIMARY KEY (tenant_id, credential_id, generation, open_id),
    FOREIGN KEY (tenant_id, credential_id, generation)
        REFERENCES lark_directory_runs (tenant_id, credential_id, generation) ON DELETE CASCADE
);
CREATE INDEX ix_lark_directory_users_email
    ON lark_directory_users (tenant_id, email, credential_id, generation) WHERE email IS NOT NULL;

ALTER TABLE lark_credentials ADD COLUMN active_directory_generation BIGINT;
ALTER TABLE lark_credentials ADD CONSTRAINT fk_lark_active_directory_generation
    FOREIGN KEY (tenant_id, credential_id, active_directory_generation)
    REFERENCES lark_directory_runs (tenant_id, credential_id, generation) DEFERRABLE INITIALLY DEFERRED;
