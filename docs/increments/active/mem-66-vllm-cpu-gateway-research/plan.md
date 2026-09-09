# MEM-66 implementation plan: AI gateway and CPU vLLM research

## 1. Decisions and increment record

- [x] Create the active increment record with the research scope boundary and
      the MEM-67, MEM-62, MEM-48, and MEM-11 ownership split.
- [x] Record the gateway choice, `bfloat16` requirement, prebuilt-image
      position, seccomp position, isolation model, and metrics position with
      their reasons, before deploying anything.
- [x] Register the increment in the roadmap and the repository guide.
- [x] Confirm the three open choices with the increment owner: minimal reverse
      proxy with a file-backed key, official prebuilt CPU image first with the
      thin `vllm[zen]` image as a measured second step, and default seccomp
      plus `SYS_NICE`.
- [x] Create the Linear branch from the current `origin/main`.

## 2. Server baseline

Deferred: these steps require target-server access and are excluded from this
local completion.

- [ ] Record CPU, AVX-512 support, available memory, swap in use, and free
      disk on the shared host.
- [ ] Record per-container CPU and memory for the running stack.
- [ ] Record application health and request latency as the comparison point.
- [ ] Record whether Docling is idle or converting.
- [ ] Apply the stop conditions: available memory below roughly 5 GiB, growing
      swap, an active Docling conversion, or insufficient disk.

## 3. vLLM runtime

- [x] Pin the prebuilt CPU image by digest and the model by revision.
- [x] Add the separate research Compose project with the trial CPU, memory,
      context, output, concurrency, and KV cache budget, no published upstream
      port, a persistent cache volume, and a cold-start-tolerant health check.
- [x] Load the API key from a mounted file so it never appears in container
      environment metadata or process arguments.
- [x] Prove generated content with the pinned local CPU runtime.
- [x] Confirm from startup logs that the official image does not engage Zen
      kernels.

## 4. Gateway and authentication

- [x] Authenticate both model listing and inference; route nothing else,
      including upstream metrics.
- [x] Provision the key with a repeatable script using the existing
      file-backed secret convention.
- [x] Disable proxy buffering and verify streaming reaches the client
      incrementally through the gateway.
- [x] Keep the upstream unpublished, unrouted, and unreachable from a browser.

## 5. Repeatable verification

- [x] Cover authenticated listing, non-streaming and streaming generation,
      missing and invalid credentials, unsupported model, oversized and
      malformed payloads, output limit, and client timeout.
- [x] Measure container CPU after client disconnect and prove generation
      stopped from the running and generation-token counters.
- [x] Assert no prompt text reaches container logs.
- [x] Cover upstream unavailability and restart on a warm cache.
- [x] Write response bodies to an evidence directory instead of stdout.

## 6. Measurement and operations

Target-host measurements remain deferred with the server baseline.

- [x] Measure local cold and warm start with the cache state stated explicitly.
- [ ] Measure target-host peak CPU, peak memory, and swap change.
- [x] Measure local first-content latency, end-to-end latency, and low-load
      throughput.
- [ ] Re-measure application health and latency while the model serves.
- [x] Prove the cache is authoritative across restart using offline mode.
- [x] Capture the local metrics snapshot; no temporary telemetry attachment
      remains.
- [x] Write the runbook: baseline, stop conditions, start, verify, measure,
      stop, remove, and roll back.

## 7. Delivery

- [x] Write `verification.md` with configuration, evidence, and measurements,
      distinguishing a CPU smoke test from a GPU benchmark and from model
      quality evaluation.
- [x] Propose the next increment for the production model and GPU benchmark,
      and state the embedding coordination boundary with MEM-67.
- [x] Run the repository gate.
- [ ] Publish the pull request and attach evidence to MEM-66.
- [ ] Move the increment to completed and reconcile the roadmap after merge.
