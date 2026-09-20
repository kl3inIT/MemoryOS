-- MEM-145 follow-up: the start page choice is withdrawn; Chat is always the entry point.
ALTER TABLE chat_preferences DROP CONSTRAINT ck_chat_preferences_start_page;
ALTER TABLE chat_preferences DROP COLUMN start_page;
