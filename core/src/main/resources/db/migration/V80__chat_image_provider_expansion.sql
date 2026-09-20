-- Image provider expansion: Azure OpenAI, Google Gemini and OpenAI-compatible image connections.
ALTER TABLE chat_image_connection DROP CONSTRAINT ck_chat_image_connection_provider;

ALTER TABLE chat_image_connection
    ADD CONSTRAINT ck_chat_image_connection_provider
        CHECK (provider IN ('OPENAI_IMAGE', 'AZURE_OPENAI_IMAGE', 'GOOGLE_GEMINI_IMAGE',
                            'CLOUDFLARE_WORKERS_AI', 'OPENAI_COMPATIBLE_IMAGE'));
