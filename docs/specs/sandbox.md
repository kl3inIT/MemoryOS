# Sandbox

The Chat sandbox ([MEM-270](../increments/active/mem-270-chat-sandbox/design.md),
[ADR 0029](../decisions/0029-chat-sandbox-through-opensandbox.md)): one runtime sandbox a Chat conversation, a
computer that keeps its files while the conversation works in it. The [Sandbox tools](chat.md#sandbox-tools) of Chat
work in it.

## Contract

- **One row a conversation.** `sandbox_workspaces` (V179) holds the conversation, its owner, the runtime and the
  sandbox alive in it, if any. Deleting the conversation or the owner's membership deletes the row.
- **Acquire.** `SandboxService.acquire(tenant, actor, conversation)` locks the row, renews the recorded sandbox by the
  idle timeout and answers it. Otherwise it starts one in three steps: a short transaction takes a place in the running
  bound and marks the row as starting with a token; the runtime creates the sandbox outside any transaction; a second
  short transaction records it if the token still holds the row. A failed start gives its place back; a start older
  than ten minutes no longer counts. Another call of the same conversation waits for a start in progress instead of
  making a second sandbox. A sandbox the runtime no longer has, or older than the total life (killed), is replaced.
  The caller has already established that the actor may work in the conversation.
- **Workspace.** A conversation's `/workspace` outlives its sandbox, as Onyx Craft tars a session's files to its file
  store and extracts them into the next sandbox (`sandbox_daemon/snapshot.py`). A turn that used a Sandbox tool ends,
  after its reply and before the run gives its place back, with `SandboxService.save`: tar, run in the sandbox as user
  1000, writes `/workspace` without `attachments` (copied in again every turn), `.memoryos` (run files),
  `node_modules`, `.next`, `__pycache__` and `.venv`; the archive, with the size and SHA-256 tar's command prints, is streamed to Object Storage under a new key
  `raw/{tenant}/{uuid}` (the API writes and reads `raw/`, the Worker deletes there), recorded in `sandbox_workspace_archives` (V184) before its upload,
  and becomes `sandbox_workspaces.archive_key` once written. Onyx's bounds apply: a workspace over 500 MiB of files or an
  archive over 100 MiB is not saved, and the previous archive stays. A new sandbox of the conversation is not recorded
  until the archive is extracted into it (as user 1000, within three minutes); a restore that fails leaves an empty
  workspace and the turn goes on. Packages installed with `uv pip install` live in the image's venv, outside
  `/workspace`, and are not kept.
- **Operations.** `run`, `write`, `read`, `open` and `list` acquire first and, when the sandbox went away between the
  renewal and the call, acquire once more and repeat the call.
- **Commands.** A command runs with `bash -c` as the computer image's user 1000 in `/workspace`, stopped at its
  timeout; a command stopped at its timeout is reported as `timedOut`.
- **Files.** `write`, `read`, `open` and `list` run as the same user through OpenSandbox's identity-bound file routes
  (`filesWithIdentity`, execd 1.1.1), so a file call has exactly a command's rights. execd itself runs as root, and its
  default routes follow a link a command planted: on 2026-10-16 they read `/etc/shadow` and overwrote `/etc/passwd`.
  A path the user may not touch, a missing path or a directory is refused in a fixed sentence (never execd's raw message) as
  `SandboxFileException`; only a sandbox that does not answer is `SANDBOX_UNAVAILABLE`.
- **Network.** Every sandbox is created with a policy that denies egress except to the configured domains. The
  default is PyPI (`pypi.org`, `files.pythonhosted.org`), and the image's venv `/opt/sandbox-venv` belongs to user 1000,
  so a command adds a package with `uv pip install`, as Onyx Craft's sandbox user owns its venv and installs what its
  image leaves out and ChatGPT installs from a package index while other egress is blocked; a package lasts as long as
  its sandbox. Like Dust's workspace allowlist, the list is the deployment's: empty denies all egress. An allowed index
  also accepts uploads; `open_url` already reaches any public URL, so it opens no new path out of a conversation.
  OpenSandbox allows the nameservers of the sandbox's `/etc/resolv.conf` (the host's, `1.1.1.1` among them on staging)
  on every port, so DNS-over-HTTPS resolves any name; a command still cannot connect to what it resolves. Its egress
  sidecar can block DoH, but the 1.1.1 server passes neither setting to it
  ([MEM-292](https://linear.app/memory-os/issue/MEM-292)).
- **Bound.** At most `memoryos.sandbox.max-running` sandboxes used within the idle timeout run at once; past it,
  acquiring a new one fails with `SANDBOX_BUSY`. Taking a place is serialised across conversations by a transaction-scoped advisory lock, so two conversations cannot both take the last place; the lock is never held while the runtime creates a sandbox. A deployment without a computer image or a runtime answers
  `SANDBOX_NOT_CONFIGURED`; a runtime failure is `SANDBOX_UNAVAILABLE`.
- **Scratch sandboxes.** `SandboxService.scratch(tenant, work)` runs short work of no conversation, a presentation
  preview's conversion (MEM-271), in a sandbox labelled with the Tenant, without egress, living at most five minutes
  and killed when the work returns. It is not counted in the bound: at most two run at once per process, and a third
  is `SANDBOX_BUSY`.
- **Health.** `SandboxService.healthy()` is whether the deployment has a sandbox and a listing of MemoryOS's sandboxes
  succeeds.
- **Sandbox page.** `/admin/sandbox`, labelled "Sandbox" in every language, is for model managers (`MODELS_MANAGE`): the
  Tenant switch (`chat_interpreter_setting`), the health above with a check again, and the deployment's allowed domains
  read-only, or "none" when egress is denied. It replaced `/admin/code-interpreter` after MEM-271, without a redirect.
- **Reconciliation.** Every five minutes the Worker clears rows whose sandbox the runtime no longer holds and kills
  sandboxes labelled `memoryos.io/managed` that no row records and that are older than ten minutes (a start with its
  restore). It also deletes, 100 a run, the archives older than an hour that no row starts from: replaced by a later
  one, never finished, or left by a deleted conversation.

## Configuration

| Property | Environment | Default |
| --- | --- | --- |
| `memoryos.sandbox.computer-image` | `MEMORYOS_SANDBOX_COMPUTER_IMAGE` (the release's `memoryos-sandbox-computer`) | empty: no sandbox |
| `memoryos.sandbox.idle-timeout` | | 30 minutes |
| `memoryos.sandbox.max-lifetime` | | 4 hours |
| `memoryos.sandbox.max-running` | `MEMORYOS_SANDBOX_MAX_RUNNING` | 3; Compose sets 12, staging 6 |
| `memoryos.sandbox.cpu`, `memory` | | `1`, `1Gi` |
| `memoryos.sandbox.allowed-domains` | `MEMORYOS_SANDBOX_ALLOWED_DOMAINS` | `pypi.org,files.pythonhosted.org`; empty denies all egress |
| `memoryos.sandbox.opensandbox.endpoint` | `MEMORYOS_SANDBOX_ENDPOINT` | empty: no OpenSandbox runtime |
| `memoryos.sandbox.opensandbox.api-key` | `MEMORYOS_SANDBOX_API_KEY_FILE` through the launcher | |
| `memoryos.sandbox.opensandbox.ready-timeout` | | 60 s, at most 90 s (under the reconciliation's ten-minute grace) |

The runtime is `memoryos-integration-opensandbox`; operating notes are in the
[CI/CD runbook](../runbooks/ci-cd.md#sandbox-runtime).
