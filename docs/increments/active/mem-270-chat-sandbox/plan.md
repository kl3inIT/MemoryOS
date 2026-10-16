# Plan — MEM-270 Chat sandbox

Design: [design.md](design.md).

Status 2026-10-16: step 0 done (references read, probe on staging recorded in the design). Step 1 merged in PR #684
(`memoryos-sandbox/` server and computer images, the `sandbox` Compose service, CI job, `deploy.sh`, runbook, ADR
0029). Step 2 merged in PR #693 (the `sandbox` capability, V179 `sandbox_workspaces`, `memoryos-integration-opensandbox`,
the Worker's reconciliation). Step 3 in progress on `dathip04/mem-270-sandbox-tools`: `SandboxTools` registered under
`code_interpreter`, the prompt's Sandbox section, timeline titles, the agent label "Sandbox"; the Sandbox admin page
follows in its own pull request. Comes before MEM-265 (owner, 2026-10-15).

| Step | Change | Verification |
| --- | --- | --- |
| 0. Probe | OpenSandbox 1.1.1 on staging from a Java program on the SDK with memoryos-core's platforms | Done; results in the design |
| 1. Runtime | `opensandbox-server` in `compose.base.yaml` on the internal network only, with an API key from a secret file, `publish_host` on the bridge gateway, `[egress] mode = "dns+nft"`, capabilities dropped, `no-new-privileges`, a pid limit; the sandbox image built from the executor image with user 1000, `/workspace` and `git`, published by CI and pinned by digest; runbook section beside the interpreter's. ADR for a second service holding the Docker socket and the `sandbox` capability, written when this step starts | Staging: server healthy; a sandbox created from the API container; a sandbox cannot reach the host or MemoryOS services |
| 2. Capability | `io.memoryos.sandbox` in `core`: `SandboxService` (`acquire` per conversation, `run`, `read`, `write`, `saveWorkspace`), the runtime interface, the `sandbox_workspaces` table with `revision`; `memoryos-integration-opensandbox` with the SDK; renew on every call, a new sandbox and the saved workspace on `SandboxNotFoundException`, 4-hour total life, the running-sandbox bound; Worker task every 5 minutes for sandboxes without a row and workspaces of deleted conversations | Integration tests against an OpenSandbox server in Testcontainers: acquire twice returns the same sandbox; an expired sandbox is recreated with its files; the bound refuses one more; a creation race leaves one sandbox |
| 3. Tools | `SandboxTools` as in the design's Tools section, one group under the agent key `code_interpreter`: `bash` (command file, `setsid timeout`, head and tail within 30,000 characters with the full output's path, exit 124 as a timeout, user 1000, renew, background up to 30 minutes), `bash_output` (with `kill`), `edit_file` (`view`, `create`, `str_replace`, `insert`), `publish_file` into the Library; tool guidance and the tool manifest in the prompt; the group's steps share an icon and a `bash` step is titled by its `description`; `/admin/code-interpreter` becomes the Sandbox page (label "Sandbox" in every language) with the sandbox health and the allowed domains | Executor and API tests for registration and output shaping; contract test of the command wrapper against a real server; browser check of the timeline and the admin page |
| 4. Files | Conversation attachments copied into `/workspace/attachments` before the first sandbox tool of each turn (done, 2026-10-16); the workspace archived to Object Storage at the end of each turn that used the sandbox and restored into a new sandbox, with Onyx Craft's bounds and a Worker sweep of unused archives (V183, 2026-10-17) | Staging acceptance below |
| 5. Documents | `docs/specs/chat.md`, a sandbox spec, `docs/tests/`, `ARCHITECTURE.md`, the CI/CD runbook, roadmap | Reviewed with each change |

## Acceptance

- [x] A workbook sent to Chat is cleaned and summed by month, and a follow-up "add a chart" works on the same file.
      2026-10-16 on `e12fd7d5`, once [MEM-287](../../completed/mem-287-tool-history/plan.md) replays earlier tool calls.
- [ ] After the sandbox has expired, the next question in the conversation finds its files.
- [x] `sleep 300` stops after 60 seconds with the output so far and is reported as a timeout. 2026-10-16 on `7879ebe6`:
      the call ended after 63.7 s with exit code 124, and the answer said the default 60-second timeout stopped it.
- [ ] A domain outside the allowlist, a direct IP and the host's ports are unreachable. 2026-10-16 on `7879ebe6`:
      `example.com`, `github.com`, `8.8.8.8`, example.com's IP and `172.17.0.1:5432` are unreachable, and Fastly refuses
      another Host through `pypi.org` or `files.pythonhosted.org` (421/403). The host's nameservers stay reachable on every
      port, so DNS-over-HTTPS to `1.1.1.1` resolves any name: [MEM-292](https://linear.app/memory-os/issue/MEM-292).
- [x] A command adds a Python package with `uv pip install` from PyPI. 2026-10-16 on `7879ebe6`: the `pip` scenario
      installed `wordcloud` into `/opt/sandbox-venv` and published the word cloud PNG (65 KB).
- [x] Past the running-sandbox bound, the tool answers that the computer is busy. 2026-10-16 on `7879ebe6`: with two
      sandboxes in use and staging's bound of 6, four of six parallel conversations ran and two got "Every sandbox is in use".
- [x] A file the agent publishes appears in the Library. 2026-10-16: `GET /api/chat/library` lists the published
      `wordcloud_doanh_thu.png`, `primes.py` and `doanh_thu_2026_da_lam_sach.xlsx` as `GENERATED` with their conversation.
