ALTER TABLE organization_invitations
    ADD CONSTRAINT fk_organization_invitations_organization
        FOREIGN KEY (organization_id) REFERENCES organizations (id) ON DELETE RESTRICT;

ALTER TABLE organization_invitations DROP CONSTRAINT fk_organization_invitations_workspace;
ALTER TABLE organization_invitations DROP COLUMN default_workspace_id;

DROP TABLE workspace_memberships;

ALTER TABLE organizations DROP CONSTRAINT fk_organizations_default_workspace;
ALTER TABLE organizations DROP COLUMN default_workspace_id;

DROP TABLE workspaces;
