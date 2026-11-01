# memoryos-sandbox

The Chat sandbox runtime ([MEM-270](../docs/increments/completed/mem-270-chat-sandbox/design.md)): a computer per Chat
conversation, run by [OpenSandbox](https://github.com/alibaba/OpenSandbox) on the host's Docker daemon.

- `server/` builds `memoryos-sandbox`, the OpenSandbox lifecycle server with MemoryOS's configuration. It holds the
  Docker socket and is reached only on `memoryos-internal` with an API key; Java callers go through its proxy.
- `computer/` builds `memoryos-sandbox-computer`, the image every conversation's sandbox starts from.

Operating notes are in the [CI/CD runbook](../docs/runbooks/ci-cd.md#sandbox-runtime).
