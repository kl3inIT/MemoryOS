# 33. Code execution runs in memoryos-interpreter again; no Chat sandbox

Date: 2026-11-22

## Status

Accepted by the owner on 2026-11-22 and implemented in step 2 of the
[capstone cleanup](../increments/active/capstone-cleanup/plan.md). Supersedes
[ADR 0029](0029-chat-sandbox-through-opensandbox.md).

## Context

ADR 0029 gave each Chat conversation an OpenSandbox sandbox behind a `sandbox` capability (MEM-270): a second
service on the Docker socket, a computer image, two tables, a Worker sweep, workspace archives in Object Storage and
four tools (`bash`, `bash_output`, `edit_file`, `publish_file`). MEM-271 then moved `run_python` onto that sandbox
and took `memoryos-interpreter` (MEM-110) out of the release. Both were built from 2026-10-15, after the SEP490
requirements froze on 2026-10-11.

MemoryOS now serves only the SEP490 capstone. Report 3 names code execution as `run_python` in Chat (FE-04), not a
per-conversation computer; the Report 4 diagrams already leave the sandbox out. The vendored Onyx python-sandbox
(`memoryos-interpreter`) ran `run_python` in production until 2026-10-18 and its source stayed in the repository.

## Decision

- **`run_python` calls `memoryos-interpreter` again,** as before MEM-271: `InterpreterClient` stages the turn's
  attachments and the originals behind its search hits, runs the code with Onyx's streaming execute and copies each
  generated file into an owner-authorized Chat artifact. The presentation preview converts a deck in the
  interpreter as before. The tool is offered when the deployment configures the interpreter, the Tenant's Code
  Interpreter switch is on and the service is healthy; the Code Interpreter page (`/admin/code-interpreter`,
  `/api/chat/interpreter`) holds that switch and the health check.
- **Removed.** The core capability `io.memoryos.sandbox` with its persistence and Worker sweep, `SandboxTools` and its
  four tools, `ChatSandboxController` and its contracts, the Sandbox page, `memoryos-sandbox` (the server and computer
  images), `memoryos-integration-opensandbox` and the OpenSandbox SDK, the streamed object write only workspace
  archives used, and a code event's command title.
- **Data.** Migration V203 drops `sandbox_workspaces` and `sandbox_workspace_archives` (V179, V184) and the Worker's
  `memoryos-sandbox-reconciliation-v1` task. Files the sandbox handed to people are ordinary generated-file artifacts
  and stay. The archives' bytes stay in the object store under their `raw/` keys; their list is read before the
  migration runs.
- **Deployment.** Releases build, publish and deploy `memoryos-interpreter` and `memoryos-interpreter-executor` and no
  sandbox image; `deploy.sh` still restores the sandbox server when it rolls back to a runtime from before this change,
  and takes down the one such a runtime left.

## Consequences

- One service on a host holds the Docker socket again, the interpreter, as under MEM-110.
- A conversation has no files that outlive a run: each `run_python` call starts in a fresh executor, and the model
  is told so. The interpreter's own limits (60 s, 1 GiB, 30 s CPU, no network) apply again.
- The previous release cannot run on the new schema: rolling back past V203 is a database restore
  ([ADR 0018](0018-schema-changes-preserve-data.md)).
- Whoever brings a per-conversation computer back starts from a new design; the removed code is on the tag
  `archive/pre-capstone-cleanup` and in the history.
