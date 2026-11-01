-- MEM-92: Soniox joins the voice providers as a speech-to-text-only connection.
ALTER TABLE chat_voice_connection DROP CONSTRAINT ck_chat_voice_connection_provider;
ALTER TABLE chat_voice_connection ADD CONSTRAINT ck_chat_voice_connection_provider
    CHECK (provider IN ('OPENAI', 'OPENAI_COMPATIBLE', 'ELEVENLABS', 'AZURE', 'SONIOX'));
