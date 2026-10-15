# MEM-270: Chat sandbox

## Why

`run_python` runs one script in a fresh, network-less container and throws the container away
([MEM-110](../../completed/mem-110-memoryos-interpreter/design.md)). A member who sends a workbook, asks for it to be
cleaned and then asks for a chart gets two unrelated runs: the second starts without the first one's files. Every
reference product now gives the agent a machine that keeps its files across the steps and turns of a conversation:
a `bash` tool, file tools, files in and out. It is also what Agent Skills (MEM-120) run on.

This increment gives each Chat conversation its own sandbox through OpenSandbox. It copies what the references do
and adds nothing of its own.

## References

| Product | Revision read | What it does |
| --- | --- | --- |
| Dust | `3a1e97b` | A "Computer" per conversation (`front/lib/api/sandbox/`, provider `e2b.ts`). `sandbox` tool runs bash with a 60 s default and 120 s maximum, enforced in the container by `timeout`, returning the output captured so far (`front/lib/api/actions/servers/sandbox/metadata.ts`). States running and sleeping; a reaper sweeps every 5 minutes. Conversation files are mounted from GCS. Egress denied by default with a per-workspace allowlist; the agent may request a domain for an administrator to approve; secrets are placeholders replaced by the egress proxy; code in the sandbox calls Dust back with a scoped `sbt-` token |
| Onyx Craft | `40eb240df` | One sandbox per user, Docker or Kubernetes (`backend/onyx/server/features/build/sandbox/`). Unprivileged container (`cap_drop ALL`, user 1000, `no-new-privileges`) on its own bridge network that cannot reach Postgres, Redis or MinIO. Idle 1 hour (`SANDBOX_IDLE_TIMEOUT_SECONDS=3600`) then the workspace is tarred to the file store and the sandbox sleeps; it is restored on the next use |
| spring-agent | `473f691` | One container per user (Docker) or pod (Kubernetes) from a Debian-slim `shell-runner` image. An in-container watchdog exits after 30 minutes idle and 4 hours in total (`DockerShellProperties`). Tools `Bash`, `BashOutput`, `KillShell`, `RestartShellContainer`; per-user, group and tenant home directories mounted |
| agents-flex | `4047dd5` | OpenSandbox SDK runtime (`agents-flex-skills-open-sandbox`): one sandbox per conversation, recorded in a JDBC table keyed by conversation; reattach with `Sandbox.connector()` and `renew`; a lost creation race kills its own candidate. Tools `bash`, `read`, `write`, `edit`, `ls`, `glob`, `grep`, `publish_file` (`agents-flex-skills/.../tools/`). Weaknesses observed: renews only when attaching, never recovers an expired sandbox, never reports a timeout, keeps the head of long output |
| spring-ai-agent-utils | `fe1255d` | Claude Code style `Bash`, `BashOutput`, `KillShell`, `Read`, `Write`, `Edit`, `Grep`, `Glob`; shell output capped at 30,000 characters; execution behind an `ExecBackend` |
| agent-sandbox (BSL, read only) | `06f6477` | A `Sandbox` interface of `exec`, `files()`, `workDir()`, `close()`; one compatibility test kit every backend passes; the image is the user's, pinned by digest; Docker access is root-equivalent and a container is workload separation, not a hostile-code boundary |
| OpenAI Code Interpreter | Documentation | A container expires after 20 minutes without use and cannot be revived; its files are lost |
| OpenSandbox | `1.1.1` | Lifecycle server on Docker or Kubernetes; `execd` in each sandbox for commands and files; sandbox `timeout` with `renew`; `networkPolicy` with a default action and egress rules enforced by an egress sidecar; Java SDK `com.alibaba.opensandbox:sandbox` |

## Decisions

| Topic | References | MemoryOS |
| --- | --- | --- |
| Runtime | Dust: E2B. Onyx, spring-agent: Docker or Kubernetes of their own. agents-flex: OpenSandbox | OpenSandbox on Docker, through its Java SDK. E2B needs KVM, which staging lacks; OpenSandbox runs on both hosts and moves to Firecracker without a code change |
| Scope of a sandbox | Dust, agents-flex: a conversation. Onyx, spring-agent: a user | A conversation, as Dust and agents-flex |
| Idle and total lifetime | spring-agent: 30 min idle, 4 h total. Onyx: 1 h idle. OpenAI: 20 min | 30 minutes idle through the OpenSandbox `timeout`, renewed on every tool call; 4 hours in total, then a new sandbox from the saved workspace |
| Files after the sandbox ends | Onyx: tar to the file store, restore on next use. Dust: files mounted from storage. OpenAI: lost | The workspace is saved to Object Storage at the end of every turn that used the sandbox and restored when a new sandbox starts, as Onyx |
| Command timeout | Dust: 60 s default, 120 s maximum, killed in the container with partial output | The same |
| Output | spring-ai-agent-utils and agents-flex: 30,000 characters | 30,000 characters, keeping the head and the tail (agents-flex keeps only the head and loses the error) |
| Tools | agents-flex, spring-ai-agent-utils, Claude Code | `bash`, `read_file`, `write_file`, `edit_file`, `list_files`, and `publish_file` to put a file in the Library; descriptions taken from agents-flex and spring-ai-agent-utils |
| Network | Dust: deny by default, per-workspace allowlist. Onyx: own bridge, internal services unreachable | Deny by default with an administrator allowlist through `networkPolicy`; the sandbox cannot reach the host's or MemoryOS's services |
| Credentials | Dust: never in the sandbox, placeholders replaced at egress | None in the sandbox. Placeholders through OpenSandbox's credential proxy come later |
| Isolation | Onyx: unprivileged containers. agent-sandbox: a container is workload separation | Unprivileged containers (capabilities dropped, `no-new-privileges`, pid limit, CPU and memory limits). The OpenSandbox server holds the Docker socket, as `memoryos-interpreter` does today |
| Concurrency | agents-flex: database insert decides a creation race, but updates overwrite each other | One row per conversation with a `revision` compare-and-set, and the creation race decided by the insert |
| Cleanup | Dust: reaper every 5 min. spring-agent: in-container watchdog | Expiry is OpenSandbox's own; a Worker task every 5 minutes kills sandboxes without a row and deletes workspaces of deleted conversations |
| Limits | Glean, Dust: per workspace | A maximum number of running sandboxes per deployment (3 on staging, 10 on production); past it the tool answers that the computer is busy |
| Image | agent-sandbox, spring-agent: the deployment owns the image | The MEM-110 executor image (Python libraries, LibreOffice, Vietnamese fonts) with `bash`, coreutils and `git`, pinned by digest |
| Image contents | Dust `registry.ts`, Claude code execution, Onyx Craft `sandbox/image/Dockerfile` | The executor image plus what at least one of them ships for office work (owner, 2026-10-15): git, jq, ripgrep, fd, file, 7zip, bc; pandoc; LibreOffice Writer and Impress beside Calc; tesseract with Vietnamese data and pytesseract; imagemagick; ffmpeg; xlsxwriter, pypdfium2, plotly, duckdb; Node with pptxgenjs. Not taken: Codex's language toolchains, Dust's dbt and Snowflake CLIs, chromium, Dust's document inspection scripts (with Skills). A tool manifest (name and one line each) is given to the model, as Dust does |
| Where it lives in code | Dust: a sandbox module with a provider. agents-flex: `SkillRuntime` | A `sandbox` capability in `core`; the SDK in `memoryos-integration-opensandbox` behind the capability's runtime interface ([ADR 0027](../../../decisions/0027-integrations-by-platform-and-memoryos-module-names.md)) |

## Tools

The sandbox is one group, as Dust's "Computer" is one server and Claude's code execution is one tool with bash and
a file editor inside (owner, 2026-10-16).

- **One switch.** The agent tool key `code_interpreter` turns on the whole group: `run_python` and the sandbox tools.
  No new key, no change to `persona_tool`'s CHECK; agents that have Code Interpreter get the computer. The Tenant
  switch `chat_interpreter_setting` and the page `/admin/code-interpreter` govern the group; the page becomes the
  Sandbox page (the switch, the health of the interpreter and of the sandbox server, the allowed domains). The
  group's label is "Sandbox" in the menu, the agent editor and the page, untranslated in Vietnamese too (owner,
  2026-10-16).
- **Few tools for the model.** Five, after Claude's bash and text editor, instead of ten: fewer tool schemas in every
  request and fewer wrong choices for a small model. Search goes through `rg` and `fd` in `bash`, as in Claude's
  container. The file editor's name avoids `read_file`, which the attachment reader already uses.
- **One look in the timeline.** The group's steps share an icon; a `bash` step is titled by its `description`.
- **Not now.** A composer switch (the agent decides, as Dust attaches the Computer to an agent) and a per-tool
  always/ask/off policy for built-in tools (approvals exist only for MCP).

Written as `@LlmTool` methods of one per-turn `SandboxTools` instance in `chat.tools`, registered like
`RunPythonTool`. spring-ai-agent-utils `ShellTools` is not used: its timeout is fixed at a 120 s default and 600 s
maximum, it keeps only the head of long output and returns no output on a timeout. Its descriptions are borrowed
with attribution.

| Tool | Parameters | Behaviour | From |
| --- | --- | --- | --- |
| `bash` | `command`, `description` (5–10 words, shown as the step title), `timeout_seconds` (default 60, maximum 120), `run_in_background` | Runs in `/workspace` as user 1000; returns stdout, stderr and exit code; a timeout is reported as such, with the output so far. In the background it returns a `bash_id` at once and the command runs up to 30 minutes | Dust (timeouts, partial output), spring-agent and Claude Code (`description`, background) |
| `bash_output` | `bash_id`, `filter`, `kill` | New output of a background command since the last read and whether it still runs; with `kill` it first terminates the command's process group (TERM, then KILL after 1 s) | spring-agent `BashOutput` and `KillShell`, Claude Code |
| `edit_file` | `command` (`view`, `create`, `str_replace`, `insert`), `path`, and per command `view_range`, `file_text`, `old_str`, `new_str`, `insert_line` | `view`: numbered lines (2,000 by default, at most 4 MB) or a directory listing two levels deep; `create`: writes the file with its parent directories; `str_replace`: the old text must match exactly once; `insert`: after a line | Claude's text editor tool, agents-flex |
| `publish_file` | `path`, `filename` | Puts the file in the Library; description after agents-flex: the final delivery step, never return an internal path, on error fix and retry and never claim delivery | agents-flex |
| `run_python` | `code` | Unchanged contract, through `PythonRunner` | MEM-110, Onyx |

Background commands avoid spring-agent's faults: ids are UUIDs, not milliseconds; output is read from an offset with
`tail -c +N`, not `dd bs=1`; each command runs under `setsid timeout` with a 30-minute ceiling and a capped output
file; starting one renews the sandbox to outlive it, so the idle expiry never kills a running command. Not taken now:
Dust's `add_egress_domain` and `request_egress_domain`, which need an administrator approval flow, and Dust's
`describe_toolset`, replaced by the tool manifest in the prompt.

A `bash` call:

1. Writes the command to `/workspace/.memoryos/cmd/<uuid>.sh` through the file API, so nothing is shell-quoted
   (spring-agent).
2. Runs `setsid timeout -k 5 <seconds> bash <file>` with stdout and stderr redirected to files in the sandbox, so
   the timeout kills the whole process group (Dust; spring-agent left processes running).
3. Returns the head and tail within 30,000 characters and, when cut, the path of the full output for the model to
   search (spring-agent's large-result pointer; the probe measured 9.7 s to stream 1 M characters).
4. Reads exit code 124 as a timeout.
5. Renews the sandbox (agents-flex renewed only on attach and lost active sandboxes).

The tool step shows the `description` as its title, then the command and its output, through the existing
`ChatToolActivity` and `ChatCodeEvent`.

## A second runtime later

E2B (or another runtime) can be added as a second adapter, as agent-sandbox has Docker and E2B backends behind one
`Sandbox` interface and Dust has a provider interface behind its E2B provider. What keeps that possible now:

- The capability's runtime interface speaks only of creating with a time to live, renewing, running a command with
  a timeout, reading and writing files, killing, and "not found"; no OpenSandbox type leaves
  `memoryos-integration-opensandbox`.
- The workspace is saved in MemoryOS's Object Storage, not as a runtime snapshot, so a conversation's files survive a
  change of runtime.
- `sandbox_workspaces` records which runtime holds each sandbox.
- One compatibility test suite every adapter passes, as agent-sandbox's `AbstractSandboxTCK`.

When a second runtime arrives it becomes a provider family with a registry ([ADR 0019](../../../decisions/0019-provider-families-use-adapters-behind-a-registry.md)),
chosen by deployment configuration, and lives in its own `memoryos-integration-e2b`. E2B has no Java SDK, so that
adapter would call its REST API and the in-sandbox `envd` API itself, and it needs a host with KVM.

## `run_python` and the interpreter

Claude and ChatGPT run Python, bash and file edits in one container per conversation; Onyx keeps its Code
Interpreter and the Craft sandbox as two systems; no reference falls back from one runtime to another. MemoryOS
follows Claude and ChatGPT in two steps (owner decision 2026-10-15):

1. This increment adds the sandbox tools beside `run_python`, which keeps running on `memoryos-interpreter` until the
   sandbox has passed its staging acceptance. There is no fallback between them.
2. A follow-up runs `run_python` (same name and guidance) as `python3` in the conversation's sandbox, from the same
   image, so its files stay in the workspace; `memoryos-interpreter` leaves Compose, CI and the Java client. Its
   source stays in the repository, renamed from `interpreter/` to `memoryos-interpreter/`, with a note that it is
   neither built nor deployed (owner, 2026-10-15). The interpreter stays switchable for `run_python` only:
   `RunPythonTool` keeps all its logic (staging, artifacts, charts, output shaping) and runs code through a narrow
   `PythonRunner` (`run(code, stagedFiles, timeout)` returning output, exit code and generated files) with two
   implementations, the sandbox one and `InterpreterPythonRunner` (today's client, moved), chosen by deployment
   configuration. The interpreter adapter keeps one contract test against recorded HTTP responses inside the normal
   test run; it has no image build, CI job or Compose service. Switching back means the configuration line and the
   service from its last image. With the interpreter, only `run_python` is offered; it receives no new features.

What the interpreter does today and where each part goes in the follow-up:

| Today | After |
| --- | --- |
| `RunPythonTool` stages chat files and the source files behind search hits (`SandboxDocuments`, as Onyx), runs the code, returns generated files as `chat_file_artifact` rows and Library artifacts | The same contract on the sandbox: files staged into `/workspace`, `python3` with the sandbox command timeout, new or changed files collected into the same artifact rows |
| `PresentationPreviewService` converts a generated deck to PDF with `pptx-to-pdf` in the executor (MEM-111) | The same command in a short-lived sandbox without a conversation, as Onyx converts decks in its sandbox |
| `chat_interpreter_setting` (V71), the Tenant switch on `/admin/code-interpreter`, the agent tool key `code_interpreter` | Kept as they are: the switch and the tool key now govern `run_python` on the sandbox |
| `tools/interpreter-eval` scores output quality | Pointed at the sandbox and run on both before the switch; the switch waits for an equal or better score |
| Compose service, executor image, `images.env`, CI publication, `deploy.sh`, `prune-release-images.sh` and their infrastructure tests | Removed in a separate change after the switch has run on staging and production |
| Past conversations replay `run_python` steps from `chat_message.activity`; artifacts live in Object Storage | Unchanged: the tool name is the same and no stored data moves |

## Probe on staging

Run on 2026-10-15 against OpenSandbox server `release-1.1.1` (execd `v1.1.0`, egress `v1.1.7`, Docker runtime,
bridge network) on the staging host, from a Java 25 program on the SDK 1.1.1 with memoryos-core's platforms
(Spring Boot 4.1.1, Spring AI 2.0.1, Embabel 1.5.2). The sandbox image was the MEM-110 executor image.

| Check | Result | Consequence |
| --- | --- | --- |
| Dependencies | OkHttp resolves to 4.12.0, the version memoryos-core already uses through the OpenAI and Anthropic SDKs; Kotlin stdlib to 2.3.21 (the SDK asks for 2.2.21). No conflict | The SDK goes in as is |
| Server footprint | 110–155 MiB | |
| Sandbox footprint | 4–6 MiB idle; an egress sidecar container beside each sandbox with a policy | The bound is the number of running sandboxes, not their idle cost |
| Create time | 28.6 s the first time (images not yet started), then 2.9–3.9 s with a network policy and 0.5–0.9 s without | The first tool call of a conversation waits about 3 s |
| Command timeout | `sleep 30` with a 5 s timeout returns at 6 s with exit code −1 and `CommandExecError`; the process is gone | Report a timeout as such: elapsed time reached and exit −1 |
| Output | 1.09 M characters of output took 9.7 s to stream to the client; each output line arrives as its own message without its newline | Capture to a file in the sandbox and return head and tail, as Dust does; join messages with newlines |
| Binary files | 2 MiB written in 83 ms and read back byte-identical (SHA-256) | Attachments and workspace archives go through the SDK file API |
| Network, `dns` mode (default) | Allowed domain 200, other domain blocked, but a direct IP (1.1.1.1) and the host's ports 22, 80, 443 and the OpenSandbox server reachable | Not usable |
| Network, `dns+nft` mode | Allowed domain 200; other domains, direct IPs and every host port blocked; only the host's DNS resolvers remain reachable | Required: `[egress] mode = "dns+nft"` |
| User | Commands run as root by default; `uid(1000)` runs them unprivileged; the image has no user 1000, no `/workspace` and no `git` | Run as 1000; the image adds the user, `/workspace` and `git` |
| Renew | `renew(3 min)` moves `expiresAt` | Renew on every tool call |
| File calls (2026-10-16) | execd runs as root. With execd `v1.1.0`, a link `/workspace/leak -> /etc/shadow` made by uid 1000 was read through `files().readStream`, and `files().write` through `/workspace/pw -> /etc/passwd` overwrote it. OpenSandbox 1.1.1 adds `/v1/filesystem/{uid}/{gid}` routes (`filesWithIdentity`), which run each file call as the given user | execd and egress move to `release-1.1.1`; every file call uses `filesWithIdentity(1000, 1000)` |
| Topology | With the server on an internal network plus a bridge of its own, a client on the internal network alone created a sandbox, ran commands, wrote and read files and saw a timeout through `useServerProxy(true)`; the API key came from a secret file through a start script | `memoryos-internal` stays internal; Java uses the server's proxy |
| Expired or killed sandbox | `connector().connect()` throws `SandboxNotFoundException` (HTTP 404 `DOCKER::SANDBOX_NOT_FOUND`) | That exception means "create a new one and restore the workspace" |

## Out of scope

Skills (MEM-120), code in the sandbox calling MemoryOS back, credentials at egress, the agent requesting a domain,
moving `run_python` into the sandbox, a browser in the sandbox, a persistent machine per member, microVMs.
