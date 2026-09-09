# MEM-66 verification

## Local CPU smoke-test boundary

This evidence is from Docker Desktop on an Intel Core i5-12500H host whose
Linux VM exposes AVX2 but not AVX-512. It verifies the serving path and failure
contracts; it is not target-server capacity evidence, a GPU benchmark, a
Vietnamese-quality result, or authority for a staging rollout.

The checked-in research Compose project uses:

- `vllm/vllm-openai-cpu:v0.28.0-x86_64@sha256:ea8b61f8ccab650e1c369105373863ac12a581b53f289b344cea72a09238e131`;
- `nginx:1.28.0-alpine@sha256:30f1c0d78e0ad60901648be663a710bdadf19e4c10ac6782c235200619158284`;
- `HuggingFaceTB/SmolLM2-135M-Instruct` revision
  `12fd25f77366fa6b3b4b768ec3050bf629380bac`;
- the model revision's extracted chat template, checked in with SHA-256
  `872be49dbb638044ad01b60388f48d469ff2980e5f0dccdc22ec907db54d0788`;
- `bfloat16`, context 1024, maximum concurrency 1, maximum batched tokens
  1024, a 1 GiB CPU KV-cache budget, 2 CPUs, 4 GiB memory, 4 GiB total
  memory-plus-swap, and 1 GiB shared memory.

`docker compose config` rendered this contract with no vLLM host port, a
loopback-only gateway port, both pinned image digests, the pinned model
revision and chat-template mount, the resource limits, and no credential
value. Both shell launchers pass `sh -n`; both Python entry points pass
`py_compile`. The checked-in chat template has the expected hash above.
JetBrains semantic inspection was unavailable, and no YAML or Python language
server is configured; Docker Compose rendering and Python compilation are the
configuration and syntax fallbacks.

`./gradlew.bat clean check --no-daemon` passed in 5 minutes 30 seconds:
23 actionable tasks, 11 executed, 11 restored from cache, and 1 up-to-date.

## Gateway and inference contracts

The checked-in Compose project started successfully from the warm named cache.
The vLLM container became healthy, while only the gateway published
`127.0.0.1:18088`. The repeatable verifier exercised these calls through that
gateway:

| Contract | Observed result |
| --- | --- |
| Model listing without a key | `401` |
| Model listing with a wrong key | `401` |
| Model listing with the file-backed key | `200`, pinned model present |
| Chat inference without a key | `401` |
| Non-streaming chat with the key | `200`, generated content and usage |
| Unsupported model | `404` with `NotFoundError` |
| Malformed JSON | `400` |
| 9,119-byte input exceeding context 1024 | `400`, context limit identified |
| Output limit of five tokens | five tokens, `finish_reason=length` |
| 20,119-byte body exceeding the gateway limit | `413` |
| Gateway `/metrics` | `404` |
| Gateway `/invocations` | `404` |

A repeatable run produced 24 non-empty streaming content events plus the
protocol events and `[DONE]`. First non-empty content arrived in 0.891 seconds
and the stream completed in 1.906 seconds. This measures content arrival, not
HTTP time to first byte; vLLM sends an initial empty-content role event.

The gateway uses `proxy_buffering off`, `proxy_request_buffering off`, and
`proxy_next_upstream off`. Prompt text used by both streaming and
non-streaming checks was absent from fresh gateway and vLLM logs.

`infrastructure/inference/verify-inference-research.py` returned `PASS` and
wrote bodies, stream events, metrics, and a redacted result summary under
`.tmp/mem66-local/evidence-final/`. It prints no response body or credential
to stdout. The ignored evidence directory is local-only because it contains
generated text.

A follow-up non-restart run also returned `PASS` after expanding the secret
check from PID 1 to every readable container process command line; its summary
is under `.tmp/mem66-local/evidence-process-scan/`.

The durable operating sequence is
[the inference research runbook](../../../runbooks/inference-research.md).

## Authentication and isolation

The same file-backed random 64-character hexadecimal key authenticates the
gateway and vLLM `/v1` surface. A container on the inference network received
`401` from vLLM with a missing or wrong key and received the pinned listing
with the correct mounted key.

vLLM 0.28.0 offers only the `--api-key` CLI form. The launcher reads the
Compose secret, validates it, injects it into vLLM's in-process argument parser,
and installs a logging filter before the CLI logs non-default arguments. Fresh
runtime checks found the credential in none of Docker inspect output, any
readable `/proc/*/cmdline`, gateway logs, or vLLM logs; the vLLM argument log
contains `<redacted>` instead.

The first launcher iteration exposed its local key in vLLM's non-default-
argument startup log. That credential was treated as compromised: the logging
filter was added, both services were recreated with a newly generated key, and
the old key then returned `401` at both gateway and internal vLLM boundaries.
The new key served listing and generated content, while fresh logs, Docker
metadata, and every readable process command line contained neither credential.

The vLLM container has no published port and belongs only to the research
network. Host access to the former port `18001` fails. The gateway alone
publishes on loopback. This network boundary is also necessary because vLLM's
own documentation states that `--api-key` protects selected path prefixes,
not every inference-capable endpoint; the gateway routes only the two approved
OpenAI-compatible endpoints.

The secret provisioner produced a 64-character hexadecimal key, retained it
unchanged across repeated execution, and never printed its value.

## Failure, restart, cache, and cancellation

Stopping vLLM made the gateway return `502`; the gateway stayed running and
returned to `200` without a restart when vLLM became ready. The repeatable
offline warm restart took 146.1 seconds and post-restart chat generation
returned `200`. A preceding checked-in-Compose recreation took 172.3 seconds.
Earlier local exploratory runs measured approximately 210 seconds for a 4 GiB
cold start, 114 seconds for one 4 GiB warm restart, and 188 seconds for a 3 GiB
warm restart. The variation means startup health allowance must be measured
again on the target host rather than inferred from one local run.

The measured local cache is the named `mem66-hf` volume; the checked-in
default is `memoryos-inference-cache`. vLLM reached ready with
`HF_HUB_OFFLINE=1`, proving that the warm restart used the local cache rather
than downloading the model. The Compose setting defaults to online mode so a
new cache can download the pinned revision; the runbook switches to offline
mode only after that first healthy start.

A warmed streaming request forced toward 128 output tokens was terminated by
the client after 3.0 seconds. vLLM was observed with one running request and
had generated 33 tokens. The running gauge returned to zero, and the
generation counter added no tokens during the eight-second follow-up. The
gateway remained healthy. This proves generation stopped rather than
continuing invisibly to the 128-token limit.

vLLM 0.28.0 did not increment
`vllm:request_success_total{finished_reason="abort"}` for that path, despite the
running and generation counters proving cancellation. The abort-labelled
counter is therefore not sufficient cancellation evidence for this pinned
runtime.

## Local resource snapshot and deferred work

After the final cancellation check, an idle snapshot reported:

- vLLM: 0.95% CPU and 3.469 GiB of the 4 GiB container limit;
- gateway: 0.00% CPU and 2.801 MiB of the 96 MiB container limit.

Docker's memory figure includes chargeable cache and is not a target-host peak
RSS measurement. The official image emitted no `AMD Zen CPU detected with
zentorch installed` startup line, consistent with the image containing no
ZenTorch dependency.

The local implementation, repeatable verifier, and operations runbook are
complete. Target-server baselines, peak CPU/RAM/swap, existing-application
impact, and the target-specific `vllm[zen]` decision are deferred because this
work does not touch the server. Branch creation, pull-request-head
verification, evidence attachment, merge, and increment archival remain
delivery actions after the unrelated working-tree changes are separated.
