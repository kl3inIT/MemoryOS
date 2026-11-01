-- Chat limits move to where they belong: the deployment owns the maximum a file library may hold, and the
-- person owns how long their own conversations are kept.

-- How much one person's library may hold is now `memoryos.chat.storage.library-bytes`, read from the
-- environment like every other deployment ceiling, so neither the Tenant limit nor its exceptions is recorded.
DROP TABLE chat_storage_quota;
ALTER TABLE chat_settings DROP COLUMN storage_quota_bytes;

-- Retention deletes a person's own conversations, so it is their setting, not an administrator's.
ALTER TABLE chat_preferences ADD COLUMN retention_days INTEGER
    CHECK (retention_days IS NULL OR retention_days BETWEEN 1 AND 3650);

-- A Tenant that had recorded a policy keeps it: every member of that Tenant starts from the number that was
-- already deleting their conversations, rather than silently losing it.
INSERT INTO chat_preferences (tenant_id, actor_id, retention_days)
SELECT m.tenant_id, m.actor_id, s.chat_retention_days
FROM chat_settings s JOIN tenant_memberships m ON m.tenant_id = s.tenant_id
WHERE s.chat_retention_days IS NOT NULL
ON CONFLICT (tenant_id, actor_id) DO UPDATE SET retention_days = EXCLUDED.retention_days;

ALTER TABLE chat_settings DROP COLUMN chat_retention_days;
