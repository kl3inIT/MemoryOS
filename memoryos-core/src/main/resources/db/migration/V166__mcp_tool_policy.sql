-- MEM-264: one policy per tool replaces the enabled and approval_required pair. A policy an administrator chose is
-- kept and marked customized; every other tool takes the default of its risk: read-only tools run, the rest ask.
ALTER TABLE mcp_server_tool
    ADD COLUMN policy VARCHAR(8) NOT NULL DEFAULT 'OFF' CHECK (policy IN ('ALWAYS', 'ASK', 'OFF')),
    ADD COLUMN policy_customized BOOLEAN NOT NULL DEFAULT FALSE;

UPDATE mcp_server_tool SET
    policy = CASE
        WHEN NOT enabled THEN 'OFF'
        WHEN approval_required THEN 'ASK'
        ELSE 'ALWAYS'
    END,
    -- MEM-252 started a read-only tool on without asking and any other tool off; anything else was chosen.
    policy_customized = CASE
        WHEN read_only THEN NOT (enabled AND NOT approval_required)
        ELSE enabled AND NOT approval_required
    END;

-- A tool that may change data and was left off takes the new default; a destructive one never runs unasked.
UPDATE mcp_server_tool SET policy = 'ASK' WHERE NOT read_only AND NOT policy_customized;
UPDATE mcp_server_tool SET policy = 'ASK'
WHERE NOT read_only AND policy = 'ALWAYS' AND annotations ->> 'destructiveHint' = 'true';

ALTER TABLE mcp_server_tool DROP COLUMN enabled, DROP COLUMN approval_required;
ALTER TABLE mcp_server_tool ALTER COLUMN policy DROP DEFAULT;
