-- Deep research agent tree, committed with the terminal outcome like activity: cycle, tab, task, status, intermediate
-- report, citation mapping and the agent's own tool steps. Onyx stores tool_call rows; history reads the tree per message.
ALTER TABLE chat_message ADD COLUMN research_agents jsonb NOT NULL DEFAULT '[]'::jsonb;

-- Every existing row holds the empty default, so skipping validation loses nothing and avoids scanning the table.
ALTER TABLE chat_message ADD CONSTRAINT chat_message_research_agents_bounded CHECK (
    jsonb_typeof(research_agents) = 'array'
    AND jsonb_array_length(research_agents) <= 64
    AND octet_length(research_agents::text) <= 4194304
    AND (role = 'ASSISTANT' OR research_agents = '[]'::jsonb)
) NOT VALID;
