-- MEM-148: Onyx "Collapse Large Pastes". With it on, the Chat composer collapses a paste of more than 3 lines or
-- 200 characters into a tile whose full text is appended to the message on send. Off by default, as in Onyx.
ALTER TABLE chat_preferences ADD COLUMN paste_as_tile BOOLEAN NOT NULL DEFAULT FALSE;
