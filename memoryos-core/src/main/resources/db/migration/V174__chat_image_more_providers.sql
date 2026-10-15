-- Gemini, OpenRouter, xAI and fal.ai image adapters, and the custom HTTP connection whose request shapes the
-- manager describes: widen the named provider CHECK that V58 added and store the custom connection's template.
ALTER TABLE chat_image_connection DROP CONSTRAINT ck_chat_image_connection_provider;

ALTER TABLE chat_image_connection
    ADD CONSTRAINT ck_chat_image_connection_provider
        CHECK (provider IN ('OPENAI_IMAGE', 'CLOUDFLARE_WORKERS_AI', 'GEMINI_IMAGE', 'OPENROUTER_IMAGE', 'XAI_IMAGE',
                            'FAL_AI', 'CUSTOM_HTTP'));

ALTER TABLE chat_image_connection ADD COLUMN template TEXT;
ALTER TABLE chat_image_connection
    ADD CONSTRAINT ck_chat_image_connection_template CHECK ((provider = 'CUSTOM_HTTP') = (template IS NOT NULL));
