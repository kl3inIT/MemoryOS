-- MEM-273 step 5d: a Lark bot's group chats are known as soon as it is in them (the bot-added event and Lark's list of
-- the bot's chats), before anyone asks there, so a group may have no last message yet.
ALTER TABLE channel_groups ALTER COLUMN last_message_at DROP NOT NULL;
