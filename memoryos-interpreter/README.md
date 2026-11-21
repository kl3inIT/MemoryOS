# MemoryOS interpreter

Runs `run_python` again since [ADR 0033](../docs/decisions/0033-code-execution-through-memoryos-interpreter-again.md), after the OpenSandbox sandbox of [MEM-271](../docs/increments/superseded/mem-271-run-python-sandbox/design.md) was removed. CI builds and tests both images, each release publishes and deploys them, and Dependabot watches them.

Runs model-written Python for Chat in disposable executor containers or pods. It started as a snapshot of the Onyx Code Interpreter service; see [NOTICE.md](NOTICE.md) for the source and the changes. [HOW_IT_WORKS.md](HOW_IT_WORKS.md) describes the executor architecture. The increment is [MEM-110](../docs/increments/completed/mem-110-memoryos-interpreter/design.md).

| Path | Content |
| --- | --- |
| `service/` | FastAPI service `memoryos-interpreter` (package `memoryos_interpreter`). It never runs user code itself. |
| `executor/` | Executor image `memoryos-interpreter-executor`: Python 3.14 with numpy 2.5, pandas 3.0, headless OpenCV 5 and the data, chart and office-document packages. Its `sitecustomize.py` turns two silent pandas mistakes in the model's script into errors the model can fix: a chained assignment, and `dayfirst=True` on ISO dates. [`tools/interpreter-eval`](../tools/interpreter-eval/README.md) measures a change to it or to the `run_python` guidance with a real model. |
| `kubernetes/memoryos-interpreter/` | Helm chart for the Kubernetes executor backend. |

## Local verification

Requires Docker and [uv](https://docs.astral.sh/uv/).

```bash
docker build -t ghcr.io/kl3init/memoryos-interpreter-executor:latest memoryos-interpreter/executor

(
  cd memoryos-interpreter/service
  uv sync --frozen
  uv run ruff check . && uv run ruff format --check . && uv run mypy .
  uv run pytest tests/integration_tests
)
```

From the repository root, generate a local API key with OpenSSL and run the service against the local Docker daemon
(Docker-out-of-Docker).

```bash
docker build -t memoryos-interpreter:local memoryos-interpreter/service
interpreter_api_key=$(openssl rand -hex 32)
docker run --rm -p 127.0.0.1:8000:8000 --user root \
  -e "API_KEY=$interpreter_api_key" \
  -v /var/run/docker.sock:/var/run/docker.sock \
  memoryos-interpreter:local
```

Check readiness from another terminal with `curl -fsS http://127.0.0.1:8000/health`. The health endpoint stays open
for probes. Send the generated key in the `X-Api-Key` header on every `/v1` request. Keep the port on loopback or a
private network.
