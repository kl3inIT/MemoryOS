-- Web search is offered to the model, never forced: a turn is either off or automatic.
UPDATE chat_command SET web_search = 'auto' WHERE web_search = 'required';
ALTER TABLE chat_command DROP CONSTRAINT chat_command_web_search_check;
ALTER TABLE chat_command ADD CONSTRAINT chat_command_web_search_check CHECK (web_search IN ('off','auto'));
