-- ADR 0033: the OpenSandbox sandbox (MEM-270, MEM-271) is removed and run_python runs in memoryos-interpreter again.
-- This drops what V179 and V184 created for the conversations' sandboxes. The files the sandbox handed to people are
-- generated-file artifacts (V71), which stay; the per-Tenant Code Interpreter switch (V71) stays and governs
-- run_python again.

-- The Worker's five-minute sweep of sandboxes, which no Worker schedules any more.
DELETE FROM scheduled_tasks WHERE task_name = 'memoryos-sandbox-reconciliation-v1';

-- The archive of each conversation's /workspace (V184) and the conversations' sandboxes (V179). The archives' bytes
-- stay in the object store under their raw/ keys; the release takes the list of those keys before this runs.
DROP TABLE sandbox_workspace_archives;
DROP TABLE sandbox_workspaces;
