-- MEM-299: the assistant a member's direct messages to a bot answer with, in place of the bot's own. Deleting the
-- assistant clears the choice; one deleted softly, or no longer usable, is ignored when a message is answered.
ALTER TABLE chat_preferences ADD COLUMN channel_persona_id UUID;
ALTER TABLE chat_preferences ADD CONSTRAINT fk_chat_preferences_channel_persona
    FOREIGN KEY (tenant_id, channel_persona_id) REFERENCES persona (tenant_id, id) ON DELETE SET NULL (channel_persona_id);
