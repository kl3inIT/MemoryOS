-- MEM-281: each of MemoryOS's assistant templates is one system-owned persona row per Tenant, written from the
-- catalogue in code. A template row has no owner, is never shared organisation-wide and is never the built-in
-- assistant; at most one live row a template and Tenant.
ALTER TABLE persona ADD COLUMN template_key VARCHAR(64)
    CONSTRAINT ck_persona_template_key CHECK (template_key IS NULL OR template_key ~ '^[a-z0-9-]{1,64}$');
ALTER TABLE persona ADD CONSTRAINT ck_persona_template_owner CHECK (
    template_key IS NULL
    OR (builtin_key IS NULL AND owner_actor_id IS NULL AND owner_group_id IS NULL AND NOT is_public));
CREATE UNIQUE INDEX ux_persona_template ON persona (tenant_id, template_key)
    WHERE template_key IS NOT NULL AND deleted_at IS NULL;
