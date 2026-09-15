# Notice

`interpreter/` started as a snapshot of [onyx-dot-app/python-sandbox](https://github.com/onyx-dot-app/python-sandbox), the service behind the Onyx Code Interpreter, copied on 2026-09-15.

| Item | Value |
| --- | --- |
| Upstream commit | `a999ed96c4ec50bbc6d0dff0f3b90eacd682e0f4` |
| Upstream version | service, executor and Helm chart `0.4.7` |
| License | MIT, Copyright (c) 2025-present DanswerAI, Inc. — kept verbatim in [LICENSE](LICENSE) |

## Changes from upstream

- Directory layout: `code-interpreter/` is `service/`, `kubernetes/code-interpreter/` is `kubernetes/memoryos-interpreter/`; `executor/` and `HOW_IT_WORKS.md` keep their names.
- Python package `app` is `memoryos_interpreter`; distribution `code-interpreter` is `memoryos-interpreter`, script `memoryos-interpreter-api`, executor bundle `memoryos-interpreter-executor`.
- Default executor image `onyxdotapp/python-executor-sci` is `ghcr.io/kl3init/memoryos-interpreter-executor`, built from `executor/`.
- Execution defaults are 1024 MB memory and 30 s CPU time (upstream 256 MB and 5 s), in `service/memoryos_interpreter/app_configs.py` and the Helm values.
- The executor image adds fonts (Noto core, DejaVu, Liberation, Carlito, Caladea), `LANG=C.UTF-8`, `MPLBACKEND=Agg`, a prebuilt matplotlib cache seeded into `/tmp` by `executor/sitecustomize.py`, `XDG_CACHE_HOME=/tmp/.cache` for fontconfig, poppler-utils, qpdf, sqlite3, unzip/zip, and the Python packages statsmodels, pyarrow, xlrd, chardet, tabulate, jinja2, markdown, beautifulsoup4, markitdown, pdf2image and sympy. matplotlib moves from the yanked 3.9.1 to 3.10, and a uv override drops `nvidia-nccl-cu12`.
- Versions restart at `0.1.0`. Upstream version gates (for example sessions and `bash` from `0.4.0`) do not apply to MemoryOS clients.
- Fixes to upstream defects found in review of the first MemoryOS pull request (#195):
  - `file_id` accepts only the canonical UUIDs the service issues, so an absolute or `..` path cannot read files outside file storage.
  - Uploads are read in chunks and rejected as soon as they exceed `MAX_FILE_SIZE_MB`.
  - Downloads send an RFC 6266 `filename*` so non-Latin-1 file names (for example Vietnamese) do not fail.
  - `/health` returns HTTP 503 when the executor backend is not healthy.
  - The last-line-interactive wrapper runs user code in its own namespace.
  - Workspace snapshots keep leading dots in file names (`removeprefix` instead of `lstrip`).
  - Execution containers sleep for the timeout in seconds, not milliseconds.
  - Docker CLI calls that start containers or stage files have a timeout; a failed snapshot is logged.
- Upstream repository files that do not apply here were not copied: `.github/`, `.pre-commit-config.yaml`, `AGENTS.md`, `CLAUDE.md`, `CONTRIBUTING.md`, `README.md` and the README screenshot.

## Porting upstream fixes

Upstream fixes are ported by hand. Compare the upstream diff from the commit above, apply it under the renamed paths, and update the commit in this file.
