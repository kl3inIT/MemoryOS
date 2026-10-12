CREATE TABLE organization_invitations (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL,
    default_workspace_id UUID NOT NULL,
    normalized_email VARCHAR(254) NOT NULL,
    open_email_key VARCHAR(254),
    secret_digest VARCHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL,
    created_by_actor_id UUID NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    accepted_by_actor_id UUID,
    accepted_at TIMESTAMP WITH TIME ZONE,
    revoked_by_actor_id UUID,
    revoked_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT fk_organization_invitations_workspace
        FOREIGN KEY (organization_id, default_workspace_id)
        REFERENCES workspaces (organization_id, id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_organization_invitations_creator
        FOREIGN KEY (created_by_actor_id) REFERENCES actors (id) ON DELETE RESTRICT,
    CONSTRAINT fk_organization_invitations_accepted_actor
        FOREIGN KEY (accepted_by_actor_id) REFERENCES actors (id) ON DELETE RESTRICT,
    CONSTRAINT fk_organization_invitations_revoking_actor
        FOREIGN KEY (revoked_by_actor_id) REFERENCES actors (id) ON DELETE RESTRICT,
    CONSTRAINT ck_organization_invitations_status CHECK (
        status IN ('PENDING', 'ACCEPTED', 'EXPIRED', 'REVOKED')
    ),
    CONSTRAINT ck_organization_invitations_lifecycle CHECK (
        (
            status = 'PENDING'
            AND open_email_key = normalized_email
            AND accepted_by_actor_id IS NULL
            AND accepted_at IS NULL
            AND revoked_by_actor_id IS NULL
            AND revoked_at IS NULL
        )
        OR (
            status = 'ACCEPTED'
            AND open_email_key IS NULL
            AND accepted_by_actor_id IS NOT NULL
            AND accepted_at IS NOT NULL
            AND revoked_by_actor_id IS NULL
            AND revoked_at IS NULL
        )
        OR (
            status = 'EXPIRED'
            AND open_email_key IS NULL
            AND accepted_by_actor_id IS NULL
            AND accepted_at IS NULL
            AND revoked_by_actor_id IS NULL
            AND revoked_at IS NULL
        )
        OR (
            status = 'REVOKED'
            AND open_email_key IS NULL
            AND accepted_by_actor_id IS NULL
            AND accepted_at IS NULL
            AND revoked_by_actor_id IS NOT NULL
            AND revoked_at IS NOT NULL
        )
    )
);

CREATE UNIQUE INDEX uq_organization_invitations_secret_digest
    ON organization_invitations (secret_digest);

CREATE UNIQUE INDEX uq_organization_invitations_open_email
    ON organization_invitations (organization_id, open_email_key);

CREATE INDEX ix_organization_invitations_organization_created
    ON organization_invitations (organization_id, created_at DESC);
