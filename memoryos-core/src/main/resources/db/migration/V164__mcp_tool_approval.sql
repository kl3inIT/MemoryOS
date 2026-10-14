-- MEM-253: an enabled tool either runs when the model calls it or waits for the person to approve the call.
-- A tool the server does not declare read-only may change data, so it asks first from now on.
ALTER TABLE mcp_server_tool ADD COLUMN approval_required BOOLEAN NOT NULL DEFAULT FALSE;
UPDATE mcp_server_tool SET approval_required = NOT read_only;
