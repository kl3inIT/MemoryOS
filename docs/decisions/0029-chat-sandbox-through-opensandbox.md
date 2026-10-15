# 29. The Chat sandbox runs on OpenSandbox behind a `sandbox` capability

Date: 2026-10-15

## Status

Accepted with the [MEM-270 design](../increments/active/mem-270-chat-sandbox/design.md) on 2026-10-15; implementation
started with the runtime (step 1).

## Context

Chat needs a computer per conversation that keeps its files across steps and turns: a `bash` tool, file tools,
files in and out. Every reference product has one (Dust's "Computer" on E2B, Onyx Craft's sandbox on Docker or
Kubernetes, Claude's and ChatGPT's code containers). `memoryos-interpreter` (MEM-110) runs one script in a fresh
container and keeps nothing; its session routes did not kill a timed-out command, could not move files, had no
bound and kept the workspace in memory. E2B needs KVM, which the staging host lacks, and has no Java SDK. Driving
Docker from the application, as Onyx's Docker mode and spring-agent do, would put the Docker socket in the API or
the Worker and leave egress control to be written.

## Decision

- **OpenSandbox on Docker.** A second service holds the Docker socket: `memoryos-sandbox`, the OpenSandbox lifecycle
  server pinned by digest with MemoryOS's configuration. It is reached only on `memoryos-internal` with an API key
  from a file and has no host port. Sandboxes start from `memoryos-sandbox-computer`, run commands as user 1000 and
  carry a network policy enforced in `dns+nft` mode. Java callers go through the server's proxy.
- **A `sandbox` capability in `core`.** One sandbox per Chat conversation, recorded in PostgreSQL; the runtime is an
  interface the OpenSandbox SDK implements in `memoryos-integration-opensandbox`, so no SDK type leaves that module.
  The workspace is archived to Object Storage, not to a runtime snapshot, so a conversation's files outlive a
  sandbox and a change of runtime.
- **Tools in Chat.** `bash`, file, search and publish tools as `@LlmTool` methods; `run_python` moves onto the same
  sandbox in MEM-271.

## Consequences

- Two services on a host hold the Docker socket until MEM-271 retires the interpreter's runtime.
- A second runtime (E2B, or the interpreter for `run_python` alone) is one more adapter behind the capability's
  interface, a provider family under [ADR 0019](0019-provider-families-use-adapters-behind-a-registry.md) once it
  exists.
- Isolation is a container, not a virtual machine: workload separation between conversations of one Tenant, not a
  boundary against hostile tenants. A multi-tenant cloud deployment needs Firecracker, which OpenSandbox supports on
  hosts with KVM.
