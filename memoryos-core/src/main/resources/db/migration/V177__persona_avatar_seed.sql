-- MEM-273: an agent's face is drawn from a stored seed (DiceBear thumbs); icons are retired for agents.
-- The volatile default gives every existing row, and every raw insert, its own random seed.
ALTER TABLE persona ADD COLUMN avatar_seed varchar(64) NOT NULL DEFAULT gen_random_uuid()::text
    CONSTRAINT ck_persona_avatar_seed CHECK (avatar_seed ~ '^[A-Za-z0-9_-]{1,64}$');

UPDATE persona SET avatar_seed = 'memoryos' WHERE builtin_key IS NOT NULL;

ALTER TABLE persona DROP COLUMN icon_name;
