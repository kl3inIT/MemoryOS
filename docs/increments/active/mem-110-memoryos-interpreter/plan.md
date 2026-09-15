# MEM-110 plan

Design: [design.md](design.md).

## Phase 0 — vendor and verify locally

- [x] Snapshot `onyx-dot-app/python-sandbox@a999ed9` into `interpreter/` (`service/`, `executor/`, `kubernetes/memoryos-interpreter/`, `HOW_IT_WORKS.md`, MIT `LICENSE`, `NOTICE.md`).
- [x] Rename package, distribution, script, image defaults, labels and chart; restart versions at `0.1.0`.
- [x] Regenerate `service/uv.lock` and `executor/uv.lock`. Only the root package name and version change.
- [x] `uv sync --frozen`, `ruff check`, `ruff format --check` and `mypy` pass for `service/`.
- [ ] Build the executor image and run the service integration tests against it.
- [ ] Build the service image, run it with the Docker socket, execute a matplotlib/python-pptx snippet and download the generated file.
- [ ] CI: an `interpreter` change area and a job running the same checks and tests, required by the CI gate.

## Phase 0b — executor image and limits (wave 1, owner-approved 2026-09-15)

- [x] Add fonts (Noto core, DejaVu, Liberation, Carlito, Caladea), `LANG=C.UTF-8`, `MPLBACKEND=Agg` and a prebuilt matplotlib cache.
- [x] Add statsmodels, pyarrow, xlrd, chardet, tabulate, jinja2, markdown, beautifulsoup4, markitdown, pdf2image, sympy; poppler-utils, qpdf, sqlite3, unzip/zip.
- [x] Upgrade the yanked matplotlib 3.9.1 to 3.10; remove `nvidia-nccl-cu12` through a uv override.
- [x] Raise service defaults and Helm values to 1024 MB memory and 30 s CPU.
- [x] Rebuild; verify Vietnamese PDF with a registered TTF in fpdf2 and reportlab, imports, CLI tools, image size, a 320 MB DataFrame, and the integration tests.
- [x] Render one Arabic chart to confirm the kept Onyx CJK/Arabic font sentence. Result on 2026-09-15: Vietnamese renders correctly; the Arabic glyphs render but run left to right, so the Onyx sentence stays accurate.
- [x] CI coverage: `tests/integration_tests/test_office_stack.py` checks the added packages and CLI tools, and round-trips a Vietnamese PDF through a registered TTF font and `/v1/files`.

## Phase 0c — tool guidance (wave 2)

- [ ] Keep Onyx `PYTHON_TOOL_GUIDANCE` and `FILE_REMINDER` wording; add lines only for capabilities added in wave 1, each backed by a reference or measurement. The Java `ChatPrompts` change lands with phase 3.

## Phase 1 — staging runtime

- [x] Publish `memoryos-interpreter` and `memoryos-interpreter-executor` images with the verified release (OCI labels, `candidate-interpreter`, publish loop).
- [x] Extend `images.env` (six lines), `deploy-staging.sh` and its tests for the new images; pre-pull the executor image on the host; handle a runtime accepted before the interpreter.
- [x] Compose service on `memoryos-internal` only, no host port, `PYTHON_EXECUTOR_DOCKER_NETWORK=none`, socket mount documented in the [CI/CD runbook](../../../runbooks/ci-cd.md#interpreter-runtime).
- [ ] Health verified on staging: the `Deploy staging` run after merge passes `verify_runtime` with `memoryos-interpreter` healthy.

## Phase 2 — service hardening

- [ ] API key authentication for every route except `/health`.
- [ ] Structured JSON logs and metrics aligned with the [observability conventions](../../../guidelines/observability.md).
- [ ] BuildKit layer cache for the `interpreter` CI job, so releases stop adding about 3 GB of unshared layers to the staging host.
- [ ] A concurrency limit on executor runs sized to the staging host memory.

## Phase 3 — Java integration

- [ ] HTTP client with explicit timeouts, following existing JDK `HttpClient`/`RestClient` usage.
- [ ] `run_python` tool registered in `ChatModelExecutor` like `GenerateImageTool`: deadline, cancellation, per-turn call limit and output token budget.
- [ ] Stage authorized chat files with the Onyx limits and staging notice.
- [ ] Store generated files per Tenant/message in object storage and serve them through an authorized API.
- [ ] Admin enable/disable and health; the tool is offered only when configured, enabled and healthy.

## Phase 4 — browser and authorization

- [ ] Tool step with code, output and generated files in the activity timeline (vi/en).
- [ ] Capability decision and enforcement.
- [ ] Chat spec and verification matrix updated.

## Phase 5 — office output quality and self-checks (wave 3)

- [ ] LibreOffice headless in the executor with a temporary user profile per run (Anthropic skills `soffice.py` pattern).
- [ ] Render docx/pptx/pdf pages to images the model can inspect, capped by page count; shared with MEM-111 previews.
- [ ] xlsx formula recalculation returning error counts and cells (Anthropic skills `recalc.py` pattern).
- [ ] Office templates and on-demand instructions for pptx, docx, xlsx and charts, verified on realistic Vietnamese prompts.

## Phase 6 — structured outputs and state (wave 4)

- [ ] Capture matplotlib figures and DataFrames as structured results (E2B `chart`/`data` pattern).
- [ ] Session-scoped stateful execution per Chat with idle TTL and Tenant-checked session ids; update the stateless wording in tool guidance.
- [ ] Small warm pool of executor containers.

## Evidence

### Phase 0 — local, 2026-09-15

- `uv sync --frozen`, `ruff check`, `ruff format --check`, `mypy` (34 files): pass.
- Integration tests on the Windows host: 161 passed, 6 failed. The failures are all `test_streaming.py`, and the unmodified upstream copy fails the same 6 there. The same file inside a Linux container: 7 passed.
- Service image with the Docker socket: `/health` returned `{"status":"ok","version":"0.1.0"}`, and e2e ran 5 passed.
- A matplotlib + python-pptx run returned `report.pptx` and `revenue.png`. The downloaded pptx opened with 1 slide and a Vietnamese title.
- actionlint 1.7.12 with shellcheck on `ci.yml`: pass.

### Phase 0b — executor image, 2026-09-15

- **Before:** image 2.86 GB, of which `nvidia` is 380 MB. fpdf2 raises on "ă"; reportlab Helvetica prints "t■ng". A 320 MB DataFrame is killed with exit 137 at 256 MB.
- **After, as an unprivileged read-only run:**
  - Every added import succeeds.
  - pdftoppm, pdftotext, qpdf, sqlite3, unzip, zip and fc-list are present.
  - Noto Sans/Serif, DejaVu, Liberation, Carlito and Caladea are installed.
  - `nvidia` is absent; matplotlib is 3.10.9 on the Agg backend with no glyph warnings for Vietnamese.
  - fpdf2 and reportlab with a registered Noto Sans TTF both round-trip the Vietnamese sentence exactly.
  - Image size is 2.79 GB.
- **Through the service at the new defaults:**
  - A 320 MB DataFrame succeeds in 6.0 s.
  - A CPU-bound sort loop is killed at 29.4 s by the 30 s CPU limit (exit 137).
- **Rendering:** CJK glyphs are missing from DejaVu Sans (warning), so the Onyx CJK sentence stays.
- **Fontconfig and matplotlib:** on a read-only root, fontconfig printed "No writable cache directories" and matplotlib warned that `/opt/matplotlib` was not writable. Fixed with `XDG_CACHE_HOME=/tmp/.cache` and `sitecustomize.py`, which seeds the prebuilt cache into `/tmp/matplotlib`. After the rebuild, the probe runs with no warnings and the image is still 2.79 GB.
- **Full integration suite in a Linux container,** with the whole `interpreter/` mounted so the Helm chart version test can read `Chart.yaml`: 167 passed.
- **Vietnamese report through the service:**
  - A pandas bar chart plus an fpdf2 PDF with DejaVu Sans returned `bao-cao.pdf` and `bieu-do.png`.
  - Exit 0 in 5.8 s, with empty stderr.
  - `to_markdown` output was correct.
  - The PDF text read back as "Báo cáo doanh thu quý 3 — Hà Nội".

### Phase 1 — release and deployment, local, 2026-09-15

- `python -m unittest discover -s infrastructure/deployment`: 8 passed, including the interpreter release-contract and internal-network tests.
- ShellCheck on `deploy-staging.sh` and actionlint 1.7.12 on `ci.yml` and `deploy-staging.yml`: pass.
- `docker compose config --quiet` with `compose.base.yaml`, `compose.staging.yaml` and `compose.search.staging.yaml` and a placeholder environment: pass. The rendered service has no ports, only `memoryos-internal`, and the socket bind.
- The service image built with `VCS_REF` carries the revision and source labels.
- PR #195 review fixes to upstream defects (listed in `interpreter/NOTICE.md`), each covered by `test_request_hardening.py`:
  - rejects file IDs that are not issued UUIDs, which closes the absolute-path read;
  - reads uploads in chunks;
  - adds an RFC 6266 `filename*` for Vietnamese names;
  - `/health` returns 503 when unhealthy;
  - runs user code in its own wrapper namespace;
  - keeps hidden file names;
  - container sleep is in seconds;
  - Docker start and staging calls have timeouts.

  Integration suite in a Linux container: 181 passed.
- On GitHub's Hyper-V runners, `import markitdown` loads onnxruntime, which writes a device-discovery warning to stderr. No setting silences it ([microsoft/onnxruntime#27092](https://github.com/microsoft/onnxruntime/issues/27092)). The office-stack test ignores only that line. Staging runs on a Hyper-V host will show the same line in stderr.
- Rehearsal of the staging settings: `--network none`, read-only root, `/tmp` tmpfs of 512 MB, all capabilities dropped, `no-new-privileges`, root with the socket, watchdog off. The container became healthy, `/health` returned `ok` 0.1.0, and a pandas plus fpdf2 Vietnamese PDF run exited 0 with empty stderr and returned `r.pdf`.
