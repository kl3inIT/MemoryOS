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
- Upstream repository files that do not apply here were not copied: `.github/`, `.pre-commit-config.yaml`, `AGENTS.md`, `CLAUDE.md`, `CONTRIBUTING.md`, `README.md` and the README screenshot.

## Porting upstream fixes

Upstream fixes are ported by hand. Compare the upstream diff from the commit above, apply it under the renamed paths, and update the commit in this file.
