-- MEM-285: a group of a provider that neither names organisations nor lists a group's people (Zalo) is answered only
-- once an administrator allows it, as OpenClaw's group allowlist. Null until allowed; no existing group is allowed,
-- since nobody admitted them. Other providers' groups ignore it.
ALTER TABLE channel_groups ADD COLUMN allowed_at TIMESTAMP WITH TIME ZONE;
