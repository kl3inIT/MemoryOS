# Sandbox

The Chat sandbox ([MEM-270](../increments/active/mem-270-chat-sandbox/design.md),
[ADR 0029](../decisions/0029-chat-sandbox-through-opensandbox.md)): one runtime sandbox a Chat conversation, a
computer that keeps its files while the conversation works in it. No Chat tool calls it yet (MEM-270 step 3).

## Contract

- **One row a conversation.** `sandbox_workspaces` (V176) holds the conversation, its owner, the runtime and the
  sandbox alive in it, if any. Deleting the conversation or the owner's membership deletes the row.
- **Acquire.** `SandboxService.acquire(tenant, actor, conversation)` locks the row, renews the recorded sandbox by the
  idle timeout and answers it; it starts a new one when none is recorded, the runtime no longer has it, or it is older
  than the total life (the old one is killed). The caller has already established that the actor may work in the
  conversation.
- **Operations.** `run`, `write`, `read`, `open` and `list` acquire first and, when the sandbox went away between the
  renewal and the call, acquire once more and repeat the call.
- **Commands.** A command runs with `bash -c` as the computer image's user 1000 in `/workspace`, stopped at its
  timeout; a command stopped at its timeout is reported as `timedOut`.
- **Network.** Every sandbox is created with a policy that denies egress except to the configured domains.
- **Bound.** At most `memoryos.sandbox.max-running` sandboxes used within the idle timeout run at once; past it,
  acquiring a new one fails with `SANDBOX_BUSY`. Starting a sandbox is serialised across conversations by a transaction-scoped advisory lock, so two conversations cannot both take the last place. A deployment without a computer image or a runtime answers
  `SANDBOX_NOT_CONFIGURED`; a runtime failure is `SANDBOX_UNAVAILABLE`.
- **Reconciliation.** Every five minutes the Worker clears rows whose sandbox the runtime no longer holds and kills
  sandboxes labelled `memoryos.io/managed` that no row records and that are older than two minutes.

## Configuration

| Property | Environment | Default |
| --- | --- | --- |
| `memoryos.sandbox.computer-image` | `MEMORYOS_SANDBOX_COMPUTER_IMAGE` (the release's `memoryos-sandbox-computer`) | empty: no sandbox |
| `memoryos.sandbox.idle-timeout` | | 30 minutes |
| `memoryos.sandbox.max-lifetime` | | 4 hours |
| `memoryos.sandbox.max-running` | `MEMORYOS_SANDBOX_MAX_RUNNING` | 3 |
| `memoryos.sandbox.cpu`, `memory` | | `1`, `1Gi` |
| `memoryos.sandbox.allowed-domains` | | none |
| `memoryos.sandbox.opensandbox.endpoint` | `MEMORYOS_SANDBOX_ENDPOINT` | empty: no OpenSandbox runtime |
| `memoryos.sandbox.opensandbox.api-key` | `MEMORYOS_SANDBOX_API_KEY_FILE` through the launcher | |

The runtime is `memoryos-integration-opensandbox`; operating notes are in the
[CI/CD runbook](../runbooks/ci-cd.md#sandbox-runtime).
