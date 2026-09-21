-- MEM-90: Google Group membership read through a service account, so Auto Sync grants to a Google Group admit its
-- members. Membership belongs to the credential and is stored as generations: only a completed run becomes active.
CREATE TABLE google_group_sync_runs (
    tenant_id UUID NOT NULL,
    credential_id UUID NOT NULL,
    generation BIGINT NOT NULL CHECK (generation > 0),
    status VARCHAR(16) NOT NULL CHECK (status IN ('RUNNING', 'COMPLETED', 'FAILED')),
    groups_listed BOOLEAN NOT NULL DEFAULT FALSE,
    groups_page_token TEXT,
    members_page_token TEXT,
    error_code VARCHAR(64),
    error_message VARCHAR(2000),
    started_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    finished_at TIMESTAMPTZ,
    PRIMARY KEY (tenant_id, credential_id, generation),
    FOREIGN KEY (tenant_id, credential_id) REFERENCES google_drive_credentials (tenant_id, credential_id) ON DELETE CASCADE,
    CHECK ((status = 'RUNNING') = (finished_at IS NULL)),
    CHECK ((status = 'FAILED') = (error_code IS NOT NULL))
);
-- One run advances at a time for each credential, whichever Source's sync step drives it.
CREATE UNIQUE INDEX ux_google_group_sync_running ON google_group_sync_runs (tenant_id, credential_id)
    WHERE status = 'RUNNING';

CREATE TABLE google_group_sync_groups (
    tenant_id UUID NOT NULL,
    credential_id UUID NOT NULL,
    generation BIGINT NOT NULL,
    group_email VARCHAR(320) NOT NULL CHECK (group_email = LOWER(group_email) AND CHAR_LENGTH(group_email) > 0),
    -- A CUSTOMER member admits every user of the primary admin's domain.
    whole_domain BOOLEAN NOT NULL DEFAULT FALSE,
    members_listed BOOLEAN NOT NULL DEFAULT FALSE,
    PRIMARY KEY (tenant_id, credential_id, generation, group_email),
    FOREIGN KEY (tenant_id, credential_id, generation)
        REFERENCES google_group_sync_runs (tenant_id, credential_id, generation) ON DELETE CASCADE
);

CREATE TABLE google_group_members (
    tenant_id UUID NOT NULL,
    credential_id UUID NOT NULL,
    generation BIGINT NOT NULL,
    group_email VARCHAR(320) NOT NULL,
    member_email VARCHAR(320) NOT NULL CHECK (member_email = LOWER(member_email) AND CHAR_LENGTH(member_email) > 0),
    PRIMARY KEY (tenant_id, credential_id, generation, group_email, member_email),
    FOREIGN KEY (tenant_id, credential_id, generation, group_email)
        REFERENCES google_group_sync_groups (tenant_id, credential_id, generation, group_email) ON DELETE CASCADE
);
CREATE INDEX ix_google_group_members_member ON google_group_members (tenant_id, member_email, credential_id, generation);

ALTER TABLE google_drive_credentials ADD COLUMN active_group_generation BIGINT;
ALTER TABLE google_drive_credentials ADD CONSTRAINT fk_google_drive_active_group_generation
    FOREIGN KEY (tenant_id, credential_id, active_group_generation)
    REFERENCES google_group_sync_runs (tenant_id, credential_id, generation) DEFERRABLE INITIALLY DEFERRED;
