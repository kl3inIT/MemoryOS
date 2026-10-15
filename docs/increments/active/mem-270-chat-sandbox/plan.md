# Plan — MEM-270 Chat sandbox

Design: [design.md](design.md).

Status 2026-10-15: step 0 done (references read, probe on staging recorded in the design). Step 1 merged in PR #684
(`memoryos-sandbox/` server and computer images, the `sandbox` Compose service, CI job, `deploy.sh`, runbook, ADR
0029). Step 2 in progress on `dathip04/mem-270-sandbox-capability`: the `sandbox` capability (V174
`sandbox_workspaces`, `SandboxService`, `SandboxReconciliation`), `memoryos-integration-opensandbox`, the Worker task,
API and Worker configuration, spec and test matrix; `PostgresSandboxServiceTest` (8), the architecture tests and the
integration's `check` pass on the staging host. Comes before MEM-265 (owner, 2026-10-15).

| Step | Change | Verification |
| --- | --- | --- |
| 0. Probe | OpenSandbox 1.1.1 on staging from a Java program on the SDK with memoryos-core's platforms | Done; results in the design |
| 1. Runtime | `opensandbox-server` in `compose.base.yaml` on the internal network only, with an API key from a secret file, `publish_host` on the bridge gateway, `[egress] mode = "dns+nft"`, capabilities dropped, `no-new-privileges`, a pid limit; the sandbox image built from the executor image with user 1000, `/workspace` and `git`, published by CI and pinned by digest; runbook section beside the interpreter's. ADR for a second service holding the Docker socket and the `sandbox` capability, written when this step starts | Staging: server healthy; a sandbox created from the API container; a sandbox cannot reach the host or MemoryOS services |
| 2. Capability | `io.memoryos.sandbox` in `core`: `SandboxService` (`acquire` per conversation, `run`, `read`, `write`, `saveWorkspace`), the runtime interface, the `sandbox_workspaces` table with `revision`; `memoryos-integration-opensandbox` with the SDK; renew on every call, a new sandbox and the saved workspace on `SandboxNotFoundException`, 4-hour total life, the running-sandbox bound; Worker task every 5 minutes for sandboxes without a row and workspaces of deleted conversations | Integration tests against an OpenSandbox server in Testcontainers: acquire twice returns the same sandbox; an expired sandbox is recreated with its files; the bound refuses one more; a creation race leaves one sandbox |
| 3. Tools | `SandboxTools` as in the design's Tools section: `bash` (command file, `setsid timeout`, head and tail within 30,000 characters with the full output's path, exit 124 as a timeout, user 1000, renew), `read_file`, `write_file`, `edit_file`, `list_files`, `grep_files`, `glob`, background commands with `bash_output` and `kill_shell`, `publish_file` into the Library; tool guidance from agents-flex and spring-ai-agent-utils; the step titled by `description`, then the command and its output; an agent tool-policy switch beside `code_interpreter`; the administrator's domain allowlist and on/off | Executor and API tests for registration and output shaping; browser check of the timeline |
| 4. Files | Conversation attachments copied into `/workspace/attachments` when the sandbox starts; the workspace archived to Object Storage at the end of each turn that used the sandbox and restored into a new sandbox | Staging acceptance below |
| 5. Documents | `docs/specs/chat.md`, a sandbox spec, `docs/tests/`, `ARCHITECTURE.md`, the CI/CD runbook, roadmap | Reviewed with each change |

## Acceptance

- [ ] A workbook sent to Chat is cleaned and summed by month, and a follow-up "add a chart" works on the same file.
- [ ] After the sandbox has expired, the next question in the conversation finds its files.
- [ ] `sleep 300` stops after 60 seconds with the output so far and is reported as a timeout.
- [ ] A domain outside the allowlist, a direct IP and the host's ports are unreachable.
- [ ] Past the running-sandbox bound, the tool answers that the computer is busy.
- [ ] A file the agent publishes appears in the Library.
