-- MEM-298: a bot switched public answers anyone who addresses it, as its own account, from its assistant's
-- attachments only. No bot is public until an administrator switches it.
ALTER TABLE channel_connections ADD COLUMN public_access BOOLEAN NOT NULL DEFAULT FALSE;

-- Chat's own record of the mode a channel conversation was started in: a public bot's conversation reads its
-- assistant's attachments whole and offers no code interpreter, and a conversation is continued only in the mode it
-- was started in, so switching a bot starts its conversations over.
ALTER TABLE chat_session ADD COLUMN channel_public BOOLEAN NOT NULL DEFAULT FALSE;
