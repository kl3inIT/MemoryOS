# MEM-110: MemoryOS interpreter

Linear: [MEM-110](https://linear.app/memory-os/issue/MEM-110). Owner decisions from 2026-09-15:

- The target is the Onyx Code Interpreter, not the Onyx Craft/Build sandbox.
- The first tool is `run_python`.
- Strong isolation is not required; output quality is.
- The service lives in this monorepo as `memoryos-interpreter`, starting from a recorded upstream snapshot.

## Reference

The Onyx baseline is checkout `40eb240df` together with the service repository [onyx-dot-app/python-sandbox](https://github.com/onyx-dot-app/python-sandbox) at `a999ed96c4ec50bbc6d0dff0f3b90eacd682e0f4` (MIT).

### Observed behaviour

- **Separate service.** A FastAPI service does not run user code itself. It starts a disposable executor per run, either a Docker container or a Kubernetes pod, from a separate executor image.
- **Executor limits.** Docker executors run with `--network none`, all capabilities dropped, `no-new-privileges`, 64 pids, a non-root user, a read-only root with a tmpfs workspace, 256 MiB memory and 5 s CPU time. Every limit is configurable.
- **REST contract.**
  - `GET /health` returns `status`, `message` and `version`.
  - `POST /v1/execute` and `POST /v1/execute/stream` (SSE events `output`, `result`, `error`).
  - `POST/GET/DELETE /v1/files` with a TTL.
  - `POST/DELETE /v1/sessions` and `POST /v1/sessions/{id}/bash`.
- **Executor packages.** pandas, numpy, scipy, scikit-learn, xgboost, matplotlib, seaborn, python-pptx, python-docx, openpyxl, fpdf2, reportlab, pypdf, pdfplumber and opencv.
- **Onyx client tool.** `run_python`, never `python`, because OpenAI rejects that function name (`backend/onyx/tools/tool_implementations/python/python_tool.py`).
  - Stages chat files: files named in the code first, then the newest, capped at 25 files / 100 MiB, with a hash-keyed upload cache.
  - Tells the model which files were not staged.
  - The tool is available only when the URL is configured, the admin enabled it and `/health` is healthy (`server/manage/code_interpreter/api.py`).
- **No authentication.** The service has none; Onyx relies on network placement.

## Decisions for this increment

| Topic | Reference | Difference | Benefit | Cost | Baseline option |
| --- | --- | --- | --- | --- | --- |
| Source ownership | Consume published `onyxdotapp/*` images | Snapshot in `interpreter/`, renamed | Extend the executor packages, templates and API in the same pull request as the Java tool; no dependency on Onyx's release cadence | Upstream fixes are ported by hand ([NOTICE](../../../../interpreter/NOTICE.md)) | Pin Onyx images by digest |
| Names | `code-interpreter`, package `app`, image `python-executor-sci` | `memoryos-interpreter`, package `memoryos_interpreter`, image `memoryos-interpreter-executor` | Clear ownership in images, logs and labels | None at runtime | Keep upstream names |
| Versions | `0.4.7` with client version gates | Restart at `0.1.0` | Version numbers describe MemoryOS releases | The Java client must not copy Onyx version gates | Keep `0.4.7` |
| Executor image | Pulled from Docker Hub | Built from `interpreter/executor` and published to GHCR | MemoryOS ships and scans the binary it runs | One more image in CI and publication | Pin the Onyx image |
| Fonts | Only matplotlib's bundled DejaVu | `fonts-noto-core`, DejaVu, Liberation, Carlito, Caladea, `fontconfig`, `LANG=C.UTF-8`; matplotlib cache built into the image | Measured 2026-09-15: fpdf2 raises `FPDFUnicodeEncodingException` on "ă" and reportlab Helvetica prints "t■ng tr■■ng", so Vietnamese PDFs fail without a registered TTF | Fonts, libraries and CLI tools together add 310 MB (measured: 2.48 GB after the NCCL removal, 2.79 GB after the additions; markitdown's onnxruntime is the largest single part) | Keep DejaVu and forbid PDF output |
| Libraries and CLI tools | Onyx executor list; no CLI tools | Add statsmodels, pyarrow, xlrd, chardet, tabulate, jinja2, markdown, beautifulsoup4, markitdown, pdf2image, sympy; poppler-utils, qpdf, sqlite3, unzip/zip | Legacy `.xls`, CSV encodings, statistics, markdown tables and PDF rendering; the same items ship in Anthropic, AWS AgentCore, LibreChat or E2B images | Larger image and lockfile | Onyx list only |
| Image size | Includes `nvidia-nccl-cu12` pulled by xgboost | uv override removes the CUDA NCCL wheel | About −380 MB; executors have no GPU | xgboost GPU training unavailable (never was without a GPU) | Keep the wheel |
| Execution limits | 256 MB memory, 5 s CPU, 60 s wall | 1024 MB, 30 s CPU, 60 s wall (service defaults and Helm values) | Measured 2026-09-15: a 320 MB DataFrame is killed with exit 137 at 256 MB; peers default to 512 MiB–5 GiB | More host memory per concurrent run | Upstream limits |

Behaviour of the service, executor and Helm chart is otherwise unchanged in phase 0.

## Deployment constraints

- **Staging.** Staging is one Docker Compose host where every service drops capabilities, and nothing mounts `docker.sock` or runs privileged (`infrastructure/deployment/compose.base.yaml`).
  - The Docker executor needs the host socket (`--user root`) or Docker-in-Docker (`--privileged`).
  - The owner accepted weaker isolation. This is still the first socket mount on the host, so phase 1 must keep the service on `memoryos-internal`, without a host port, and document that the socket grants host-level Docker control.
  - On 2026-09-15 the owner asked to deploy phase 1 to staging ("ok đưa lên staging đi") with this socket mount.
- **Release pipeline.** Phase 1 adds the service and executor images to CI publication, `images.env` (six lines) and `deploy-staging.sh`. The executor image is pulled on the host by the deployment, not by the service. Operating constraints are in the [CI/CD runbook](../../../runbooks/ci-cd.md#interpreter-runtime).
- **Rancher.** The Kubernetes executor could run in the Rancher `jmix-ocr` project. That cluster has no NetworkPolicy, and MemoryOS cannot change Pod Security labels there, so it is not the first target.
- **Authentication.** Before the Java tool calls the service outside a private network, the service needs its own credential (as Docling has in MEM-79).

## Tool guidance

The tool description (`Execute Python code in an isolated sandbox environment.`), the `code` parameter and `FILE_REMINDER` keep the Onyx wording. `PYTHON_TOOL_GUIDANCE` keeps every Onyx sentence and appends lines only for capabilities added in phase 0b. Phase 3 ports this text into `ChatPrompts`, and it is sent only when `run_python` is registered.

The Onyx CJK/Arabic font sentence stays: `fonts-noto-core` has Arabic faces but no CJK, and neither matplotlib nor fpdf2 shapes Arabic without extra libraries. Verify with one Arabic render before phase 3 ships.

Appended lines:

```text
Also preinstalled: statsmodels, sympy, pyarrow, xlrd (legacy .xls), xlsxwriter, python-docx, python-pptx, reportlab, fpdf2, pypdf, pdfplumber, pdf2image, markitdown, beautifulsoup4, jinja2, markdown, tabulate, chardet and charset-normalizer. Packages cannot be installed; use only what is available.
If a text file's encoding is unknown, detect it with charset-normalizer before decoding.
Command-line tools are available via subprocess: pdftotext and pdftoppm, qpdf, sqlite3, zip and unzip.
Vietnamese and other Latin, Greek and Cyrillic text renders in matplotlib's default font, but the built-in PDF fonts (Helvetica, Times) cannot render it. Register a TTF font first, e.g. `/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf` with fpdf2 `add_font` or reportlab `TTFont`.
Memory is limited to about 1 GiB; process large files in chunks.
CPU time is limited to 30 seconds per run. A run killed by the memory or CPU limit exits with code 137 and no error message.
```

Where each line comes from:

| Line | Source |
| --- | --- |
| Preinstalled list and no installs | Anthropic code execution docs; Open WebUI "Do not install packages" |
| Encoding detection | Only our own new capability (chardet, charset-normalizer); no product wording found |
| Command-line tools | Anthropic pdf skill |
| PDF fonts | fpdf2 Unicode docs, Anthropic pdf skill, and our measurement on 2026-09-15 |
| Memory limit | Anthropic states its limits in the same way |

The memory line must match the deployed `MEMORY_LIMIT_MB`.

## Integration with MemoryOS

The runtime path this increment targets. Names below are proposals until the phase that implements them.

1. **Chat request.** The browser sends a turn with attachments. `ChatTurnService` checks the caller's capability, following the `IMAGE_GENERATE` check at `ChatTurnService.java:98`.
2. **Tool registration.** `ChatModelExecutor` registers `RunPythonTool` next to `GenerateImageTool` when three conditions hold:
   - the selected model supports tool calling;
   - the interpreter is enabled for the Tenant;
   - a cached `/health` check reports healthy.

   `ChatPrompts` adds the Onyx Python tool guidance only when the tool is actually registered.
3. **Running code.** When the model calls `run_python(code)`, the tool:
   - selects authorized chat files through `ChatFileContentService`, using the Onyx order and limits;
   - uploads them with `POST /v1/files`, reusing uploads within the turn by content hash;
   - calls `POST /v1/execute/stream` with a timeout capped by the turn deadline.

   Stop and deadline cancel the HTTP call; the executor's own timeout kills the container.
4. **Progress.** `output` events become tool activity on the existing SSE `tool` stream. Code, output and file references are persisted with the message activity, so history replays them.
5. **Generated files.** Each workspace file is downloaded with `GET /v1/files/{id}` and stored in object storage under Tenant and message, in a table like `chat_image_artifact` (V57). It is served through an authorized API, then deleted from the interpreter.
6. **Model result.** The model receives stdout/stderr truncated to the output token budget, the exit code, the timeout flag, the stored file names and a staging notice. It never receives interpreter file IDs or URLs.
7. **Browser.** The activity timeline renders the step with collapsible code, output, inline image previews and download chips for pptx/docx/xlsx/csv/pdf.
8. **Administration and runtime.**
   - An admin page enables the interpreter per Tenant and shows health.
   - The API (the deployable that runs Chat turns) reads the service URL and API key from configuration and Infisical.
   - The interpreter runs as a Compose service reachable only on `memoryos-internal`.

## Scope

In scope, by phase:

0. Vendor, rename, lockfiles, CI verification and local run.
1. Staging runtime: Compose service, executor image publication and pull, release/deploy contract, and a runbook.
2. Service hardening: API key authentication, structured logs and metrics suitable for MemoryOS observability.
3. Java integration: client, `run_python` tool in the existing Chat tool loop, file staging with Onyx limits, generated files stored like image artifacts, admin enable/health.
4. Browser: tool step in the activity timeline, generated file download, and a capability decision (`CODE_EXECUTE` or Basic grant).
5. Office output quality and self-checks: LibreOffice rendering of docx/pptx/pdf to images the model inspects, xlsx formula recalculation, templates and on-demand instructions (Anthropic Agent Skills pattern).
6. Structured outputs and state: captured charts and DataFrames (E2B pattern), session-scoped stateful execution per Chat, a small warm pool.

Authorization follows Onyx: no new capability. Any user who can chat can use `run_python` once an administrator has enabled a configured, healthy interpreter.

Out of scope:
- **`bash`.** Onyx `40eb240df` runs `bash` in interpreter sessions only inside `CodingAgentTool`, not as a Chat tool of its own (`BUILT_IN_TOOL_MAP` has no `BashTool`). MEM-110 does not add a coding agent, so it does not call the session or `bash` routes; they remain in the snapshot.
- **Other products.** Onyx Craft and provider-hosted code execution.
- **Executor access.** Internet access and runtime package installation.
- **Generated-file artifacts.** Preview, listing and versioning belong to [MEM-111](https://linear.app/memory-os/issue/MEM-111).

## Verification

Each phase records evidence in [plan.md](plan.md). Phase 0 is verified by `uv sync --frozen`, ruff, format, mypy and the integration tests against a locally built executor image, plus a real `/v1/execute` call that writes and downloads a generated file.
