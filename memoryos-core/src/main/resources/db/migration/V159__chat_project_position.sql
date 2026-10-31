-- A person chooses the order of their Projects. Existing rows keep the order the list showed
-- (most recently updated first) as 0..n-1 per owner, so nothing moves on deploy; a new Project
-- takes one less than the owner's lowest position, so values may be negative.
ALTER TABLE chat_project ADD COLUMN position INTEGER;

UPDATE chat_project p
SET position = ranked.position
FROM (
    SELECT id, (row_number() OVER (PARTITION BY tenant_id, owner_actor_id ORDER BY updated_at DESC, id) - 1)::int AS position
    FROM chat_project
) ranked
WHERE p.id = ranked.id;

ALTER TABLE chat_project ALTER COLUMN position SET NOT NULL;

-- The list now reads position first; the old index no longer serves its order and would be dead weight.
DROP INDEX ix_chat_project_owner;
CREATE INDEX ix_chat_project_owner ON chat_project(tenant_id, owner_actor_id, position, updated_at DESC, id);
