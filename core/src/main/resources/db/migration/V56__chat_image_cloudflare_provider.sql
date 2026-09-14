-- MEM-97: allow the Cloudflare Workers AI native image adapter as a per-tenant image provider.
-- V54 created an anonymous single-column CHECK on chat_image_connection.provider; drop it by
-- discovered name (robust to PostgreSQL auto-naming) before re-adding the widened, named constraint.
DO $$
DECLARE
    existing text;
BEGIN
    SELECT conname INTO existing
    FROM pg_constraint
    WHERE conrelid = 'chat_image_connection'::regclass
      AND contype = 'c'
      AND pg_get_constraintdef(oid) ILIKE '%provider%';
    IF existing IS NOT NULL THEN
        EXECUTE format('ALTER TABLE chat_image_connection DROP CONSTRAINT %I', existing);
    END IF;
END $$;

ALTER TABLE chat_image_connection
    ADD CONSTRAINT ck_chat_image_connection_provider
        CHECK (provider IN ('OPENAI_IMAGE', 'CLOUDFLARE_WORKERS_AI'));
