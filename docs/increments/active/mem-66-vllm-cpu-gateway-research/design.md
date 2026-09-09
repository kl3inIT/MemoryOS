# MEM-66 design: AI gateway and CPU vLLM research with a small model

## Why this increment exists

MemoryOS stores documents and extracts their text, but no component generates
text. [MEM-11](https://linear.app/memory-os/issue/MEM-11) will answer from
authorised evidence and needs a serving path to call. The Tasco proposal
targets a Qwen model that needs hardware this deployment does not have.

MEM-66 verifies the serving path itself — client, authenticated gateway,
internal vLLM, small model — before any commitment to that hardware. It
answers questions that do not depend on model size: does the CPU runtime
start, does the chat API work, does streaming survive the gateway, does
authentication refuse unknown callers, does a client disconnect actually stop
generation, and what does the service cost on a shared host.

It does not evaluate answer quality. `HuggingFaceTB/SmolLM2-135M-Instruct` is
English-only by its model card, so it cannot support a Vietnamese or retrieval
quality claim. A conclusion of "not feasible within this budget", supported by
a reproducible error, is a valid outcome of this increment.

## Scope boundary

This increment adds infrastructure configuration, scripts, and documentation.
It adds no capability package, no HTTP endpoint, no application runtime mode,
and no dependency from `api` or `worker`, per
[ADR 0002](../../../decisions/0002-no-speculative-operational-surfaces.md).

| Concern | Owner |
| --- | --- |
| Generation runtime and gateway trial | MEM-66 |
| Embedding service research, reusing this gateway contract | [MEM-67](https://linear.app/memory-os/issue/MEM-67) |
| Chunking | MEM-62 |
| Stored-chunk embedding persistence | MEM-48 |
| Retrieval answering | MEM-11 |

MEM-67 must not introduce a second gateway; its contract is agreed against the
one chosen here.

## Target runtime shape

```text
verification script
  → loopback gateway            authenticates, forwards, streams
      → internal vLLM CPU       one pinned small model
          → cache volume        pinned revision, survives restart
```

Both services run in a separate Compose project that is started for an
experiment window and removed afterwards. Base, staging, and production
Compose files stay untouched, so no deployment path changes.

## Decisions

### Gateway is a minimal reverse proxy with a file-backed API key

There is one upstream and one model, and the caller is a script. That does not
justify an AI gateway with model routing or usage accounting. The repository's
existing OAuth2 Proxy pattern serves browser SSO for inspection UIs, not
machine callers, so it is not reused here. One file-backed key authenticates
both layers. vLLM 0.28.0 has no environment or file form of `--api-key`, so a
launcher injects the mounted value only into its in-process argument parser
and redacts that value from vLLM's non-default-argument log. Docker metadata,
the OS process command line, and container logs therefore contain no secret.
Revisit this when MEM-67 adds a second upstream.

### The CPU runtime is the prebuilt official image, pinned by digest

`vllm/vllm-openai-cpu` publishes prebuilt x86_64 images, so the experiment does
not compile vLLM on a host that also runs PostgreSQL, MinIO, Keycloak, Docling,
and the application. The image tag and the model revision are both pinned so a
later run reproduces this one. That image also sets the x86 `LD_PRELOAD` for
tcmalloc and Intel OpenMP and entrypoints on `vllm serve`, which a
self-assembled runtime would have to reproduce correctly to avoid unexplained
slowness.

Verified cost of this choice: upstream `docker/Dockerfile.cpu` declares only
the `vllm-openai`, `vllm-test`, and `vllm-dev` targets and installs no
`zentorch`, so the published image carries no AMD Zen kernels. Enabling them
does not require compiling from source — `setup.py` exposes the `zen` extra as
`zentorch==2.13.0.0` — so the documented fallback is a thin image that
installs `vllm[zen]` from the CPU wheel index and sets `LD_PRELOAD` itself.

That fallback is deliberately not the first step. The first question is whether
the runtime serves at all inside the memory budget; adding a self-owned image
layer first would make a failure ambiguous between runtime incompatibility and
local packaging error. Startup logs decide whether Zen kernels are engaged
(`AMD Zen CPU detected with zentorch installed`), and their absence is recorded
as remaining optimisation headroom rather than treated as a defect.

### `bfloat16` is mandatory

The host CPU is AMD EPYC 9354P (Zen 4) with AVX-512, where vLLM selects
`ZenCpuPlatform`. Upstream documents that this platform does not support
`float16`, and that `float32` is the only other accepted dtype — at double the
memory per parameter and per KV cache entry, which this budget cannot afford.

### The default seccomp profile is kept; only `SYS_NICE` is added

Upstream suggests `--security-opt seccomp=unconfined` for NUMA syscalls, and
also documents that functionality is unaffected when they are denied — only
memory placement optimisation is lost. Weakening the syscall filter of a
research container on a host that serves the application is not worth that.
The expected `get_mempolicy: Operation not permitted` line is recorded as a
known consequence. If measurements later point at NUMA placement as the actual
bottleneck, the escalation is a custom profile that allows `migrate_pages`,
not an unconfined container.

### Isolation comes from unpublished ports, not an internal-only network

The first run must reach Hugging Face for the pinned revision, so the project
network allows egress. vLLM publishes no port and gets no reverse-proxy route;
the gateway publishes only on loopback. Upstream metrics are not routed through
the gateway, and the browser never calls a model.

### Metrics are not permanently wired

The observability collector accepts OTLP only, and Prometheus sits on that
project's internal backend network, so it cannot scrape this service directly.
The measurement record captures a metrics snapshot through the container, and
any telemetry attachment for the window is reversible. No permanent scrape
target is added for a capability that does not exist yet.

## Resource budget

Trial budget: 2 vCPU and 4 GiB for vLLM, context 1024, output 128, concurrency
1, KV cache 1 GiB. Container memory must cover model weights **plus** the KV
reservation; upstream documents that exceeding available memory kills the
worker with exit code 9 rather than degrading. The unknown is Torch and kernel
compilation overhead, which the cold-start measurement resolves.

The host has 4 vCPU and 15 GiB with roughly 7.8 GiB available and about 2.1 GiB
of swap already in use as of 2026-09-06. Docling alone is allowed 4 CPUs and
8 GiB, so a run while it converts a document is not a valid measurement.
Growing swap is a stop condition, not a workaround.

## Verification approach

A single repeatable script produces the evidence: authenticated listing,
non-streaming and streaming generation, missing and invalid credentials,
unsupported model, oversized and malformed payloads, output limit, client
timeout, gateway routing, disconnect cancellation, prompt-free container logs,
a metrics snapshot, and upstream unavailability with restart on a warm cache.

Health status and a lone HTTP 200 are not accepted as evidence of generation.
Streaming evidence timestamps the first non-empty content event; HTTP time to
first byte is insufficient because vLLM first emits a role-only event with
empty content. Disconnect evidence requires a request observed as running,
generation ending below its forced output limit, no later token-counter
increase, and the running gauge returning to zero. Container CPU is supporting
evidence. vLLM 0.28.0 does not increment its advertised `abort` result counter
for this streaming disconnect path. A partially streamed request is never
retried whole.

Prompts, generated text, and credentials never enter logs or metric labels.

## Local conclusion

The checked-in path is locally feasible inside its 2-CPU and 4-GiB vLLM
limits: the pinned model lists, generates, streams through the authenticated
gateway, stops after disconnect, and restarts from an offline named cache.
The local vLLM container used 3.469 GiB at idle after verification, leaving
too little headroom to infer that the shared target host can safely carry the
same workload. The official image did not enable ZenTorch, so CPU
optimisation remains deliberately untested rather than silently assumed.

## Target-server questions deferred

- What are the target server's peak CPU, memory, swap, startup, and generation
  costs while the existing application is healthy and Docling is idle?
- Does target-host throughput justify a thin `vllm[zen]` image?
- Can the shared host meet an accepted latency and concurrency objective
  without degrading existing application requests?

These require access to and changes on the target server and are outside the
local completion recorded here.

## Follow-on proposal

After the target-server decision, create a separate production-model and GPU
benchmark increment. It should pin the candidate model, runtime, and hardware;
measure first-content latency, generation throughput, concurrency, GPU memory,
host impact, and warm recovery; and define an explicit acceptance threshold
before any staging rollout. It consumes the gateway contract proven here.
MEM-67 owns embedding-runtime evaluation and must extend this gateway contract
rather than add a second gateway.

Completing this research does not by itself authorise a staging rollout, prove
production capacity, or satisfy the Tasco requirement.
