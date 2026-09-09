# Inference research runbook

This runbook starts and verifies the isolated MEM-66 CPU-serving experiment.
It does not modify the base, staging, or production Compose projects and does
not create a public reverse-proxy route or a MemoryOS application endpoint.

## Preconditions and stop conditions

Required locally:

- Docker Engine with Compose v2;
- at least 2 CPUs available to Docker;
- at least 5 GiB memory available before startup;
- enough disk for the pinned vLLM image, model revision, and compile cache;
- Python 3.11 or newer for the verifier;
- `sh` and OpenSSL for secret provisioning.

Stop instead of increasing limits when available memory falls below roughly
5 GiB, swap grows continuously, Docker reports an OOM, or another heavy local
workload is active. The checked-in trial limit is 2 CPUs and 4 GiB for vLLM;
the gateway limit is 0.25 CPU and 96 MiB.

## Provision the file-backed credential

From the repository root:

```sh
sh infrastructure/inference/provision-secrets.sh .tmp/mem66-local
```

The command creates or preserves `.tmp/mem66-local/api-key.txt`, enforces a
64-character hexadecimal value, sets mode `0600` where the host supports it,
and never prints the value. `.tmp/` is ignored by Git.

In PowerShell, configure the experiment without printing the key:

```powershell
$env:MEMORYOS_INFERENCE_API_KEY_FILE = (
    Resolve-Path '.tmp/mem66-local/api-key.txt'
).Path
$env:MEMORYOS_INFERENCE_GATEWAY_HOST_PORT = '18088'
$env:MEMORYOS_INFERENCE_NETWORK = 'memoryos-inference-research'
$env:MEMORYOS_INFERENCE_CACHE_VOLUME = 'memoryos-inference-cache'
```

Use a deployment-owned absolute secret path instead of `.tmp` outside local
research.

## First start with model-download egress

A new cache must initially allow the pinned model revision to download:

```powershell
$env:MEMORYOS_INFERENCE_HF_HUB_OFFLINE = '0'
docker compose `
  -p memoryos-inference-research `
  -f infrastructure/deployment/compose.inference-research.yaml `
  up -d
```

Observe startup without printing any credential:

```powershell
docker compose `
  -p memoryos-inference-research `
  -f infrastructure/deployment/compose.inference-research.yaml `
  ps

docker compose `
  -p memoryos-inference-research `
  -f infrastructure/deployment/compose.inference-research.yaml `
  logs -f vllm
```

The vLLM health check allows five minutes for CPU compilation and warmup. The
service is ready when vLLM is healthy and authenticated
`http://127.0.0.1:18088/v1/models` returns the pinned model. Port `18001` must
not accept a connection; vLLM publishes no host port.

## Prove the cache in offline mode

After the first healthy start, force a vLLM recreation with network downloads
disabled:

```powershell
$env:MEMORYOS_INFERENCE_HF_HUB_OFFLINE = '1'
docker compose `
  -p memoryos-inference-research `
  -f infrastructure/deployment/compose.inference-research.yaml `
  up -d --force-recreate --no-deps vllm
```

Reaching healthy in this mode proves the named cache contains the pinned model
revision. Keep offline mode for warm restart verification. A fresh empty cache
with offline mode enabled is expected to fail closed.

## Run repeatable verification

The full verifier is intentionally disruptive: `--confirm-restart` stops and
starts vLLM to exercise upstream unavailability and warm recovery. It writes
response bodies, streaming events, a metrics snapshot, and a redacted summary
to an ignored evidence directory instead of stdout.

```powershell
python infrastructure/inference/verify-inference-research.py `
  --api-key-file .tmp/mem66-local/api-key.txt `
  --evidence-directory .tmp/mem66-local/evidence `
  --confirm-restart
```

Success prints only the verdict and evidence directory. `summary.json` covers:

- authenticated model listing and inference;
- missing and wrong credentials at the gateway and internal vLLM `/v1` layer;
- non-streaming and incrementally streamed generation;
- unsupported model, malformed JSON, context overflow, output limit, and the
  gateway body limit;
- blocked gateway `/metrics` and `/invocations` routes;
- client disconnect with running/generation metrics proving cancellation;
- no host vLLM port and no secret in Docker metadata or process command lines;
- prompt- and secret-free container logs;
- upstream unavailability, warm restart, and post-restart generation.

Do not publish the evidence directory: it contains generated response bodies.
It contains no API key.

## Measure the local window

Capture an idle or active snapshot as stated in the evidence record:

```powershell
docker stats --no-stream `
  memoryos-inference-research-vllm-1 `
  memoryos-inference-research-gateway-1
```

Read vLLM metrics from inside the unpublished container:

```powershell
docker compose `
  -p memoryos-inference-research `
  -f infrastructure/deployment/compose.inference-research.yaml `
  exec -T vllm python3 -c "import urllib.request; print(urllib.request.urlopen('http://127.0.0.1:8000/metrics').read().decode())"
```

Do not add `/metrics` to the gateway. Do not infer target-host capacity from
Docker Desktop measurements.

## Stop and resume

Stop both services while retaining containers and the model cache:

```powershell
docker compose `
  -p memoryos-inference-research `
  -f infrastructure/deployment/compose.inference-research.yaml `
  stop
```

Resume with the same cache and secret:

```powershell
docker compose `
  -p memoryos-inference-research `
  -f infrastructure/deployment/compose.inference-research.yaml `
  start
```

## Remove and roll back

Remove the research containers and network while preserving the named model
cache:

```powershell
docker compose `
  -p memoryos-inference-research `
  -f infrastructure/deployment/compose.inference-research.yaml `
  down --remove-orphans
```

This is the rollback: the independent project has no dependency from the
MemoryOS API, worker, web application, or deployment Compose files. Confirm
that no `memoryos-inference-research-*` container remains and that ports
`18088` and `18001` accept no connection.

Delete the model cache only when a new download is acceptable:

```powershell
docker volume rm memoryos-inference-cache
```

Never add `-v` to routine `docker compose down`; that would silently discard
the authoritative warm cache.

## Rotate the credential

Treat any value printed to a terminal, log, process command line, or committed
file as compromised. Replace the secret file, rerun the provisioner, and force
both services to recreate so neither process retains the old value:

```powershell
Remove-Item .tmp/mem66-local/api-key.txt -Force
sh infrastructure/inference/provision-secrets.sh .tmp/mem66-local
docker compose `
  -p memoryos-inference-research `
  -f infrastructure/deployment/compose.inference-research.yaml `
  up -d --force-recreate
```

Reload the client key from the file. The old key must return `401` at both the
gateway and internal vLLM `/v1` boundary before the rotation is accepted.
