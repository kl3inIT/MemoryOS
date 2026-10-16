-- MEM-287: each tool call of an answer, replayed to the model in later turns of its branch as Dust and Anthropic replay
-- earlier tool work. The result is null where it is not kept: a tool returning organization or personal content keeps
-- only its call, so a revoked permission, a deleted file or a forgotten memory is not replayed.
CREATE TABLE chat_message_tool_calls (
    message_id UUID NOT NULL REFERENCES chat_message (id) ON DELETE CASCADE,
    position INTEGER NOT NULL,
    call_id VARCHAR(256) NOT NULL,
    tool_name VARCHAR(64) NOT NULL,
    arguments TEXT NOT NULL,
    result TEXT,
    PRIMARY KEY (message_id, position),
    CONSTRAINT ck_chat_message_tool_calls_position CHECK (position >= 0),
    CONSTRAINT ck_chat_message_tool_calls_arguments CHECK (octet_length(arguments) <= 65536),
    CONSTRAINT ck_chat_message_tool_calls_result CHECK (result IS NULL OR octet_length(result) <= 65536)
);
