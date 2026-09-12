-- Existing conversation names, including manually chosen names, are preserved.
ALTER TABLE chat_session ADD COLUMN title_naming_pending boolean NOT NULL DEFAULT false;
ALTER TABLE chat_session ALTER COLUMN title_naming_pending SET DEFAULT true;
