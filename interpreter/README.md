# MemoryOS interpreter

Runs model-written Python for Chat in disposable executor containers or pods. It started as a snapshot of the Onyx Code Interpreter service; see [NOTICE.md](NOTICE.md) for the source and the changes. [HOW_IT_WORKS.md](HOW_IT_WORKS.md) describes the executor architecture. The increment is [MEM-110](../docs/increments/active/mem-110-memoryos-interpreter/design.md).

| Path | Content |
| --- | --- |
| `service/` | FastAPI service `memoryos-interpreter` (package `memoryos_interpreter`). It never runs user code itself. |
| `executor/` | Executor image `memoryos-interpreter-executor`: Python 3.11 with the data, chart and office-document packages. |
| `kubernetes/memoryos-interpreter/` | Helm chart for the Kubernetes executor backend. |

## Local verification

Requires Docker and [uv](https://docs.astral.sh/uv/).

```bash
docker build -t ghcr.io/kl3init/memoryos-interpreter-executor:latest interpreter/executor

cd interpreter/service
uv sync --frozen
uv run ruff check . && uv run ruff format --check . && uv run mypy .
uv run pytest tests/integration_tests
```

Run the service against the local Docker daemon (Docker-out-of-Docker):

```bash
docker build -t memoryos-interpreter:local interpreter/service
docker run --rm -p 127.0.0.1:8000:8000 --user root \
  -v /var/run/docker.sock:/var/run/docker.sock \
  memoryos-interpreter:local
curl -s http://127.0.0.1:8000/health
```

The service has no authentication yet. Never publish its port beyond loopback or a private network.
