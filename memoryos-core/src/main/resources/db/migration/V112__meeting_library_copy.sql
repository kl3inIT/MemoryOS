-- MEM-92: a meeting's minutes can be taken into the owner's file library and attached to a conversation, the same
-- way a generated file or an image is. The copy remembers the meeting, so asking twice returns the same file and a
-- rewritten set of minutes replaces it.
ALTER TABLE chat_user_file DROP CONSTRAINT ck_chat_user_file_copied_from;
ALTER TABLE chat_user_file ADD CONSTRAINT ck_chat_user_file_copied_from CHECK (
    (copied_from_source IS NULL AND copied_from_id IS NULL)
    OR (copied_from_source IN ('GENERATED', 'IMAGE', 'MEETING') AND copied_from_id IS NOT NULL));
