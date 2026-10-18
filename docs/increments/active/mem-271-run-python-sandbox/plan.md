# MEM-271 plan

Design: [design.md](design.md). Status 2026-10-18: step 1 in PR #749; step 2 on `dathip04/mem-271-run-python-switch` (the chat and sandbox tests pass on zm; the evaluation runs before it merges).

| Step | Change | Verification |
| --- | --- | --- |
| 1. Evaluation | `tools/interpreter-eval` takes the computer image as an arm's runtime and reads that arm's `sitecustomize.py` from `memoryos-sandbox/computer/` | The tool's unit tests; a run on staging with the executor image and today's guidance against the computer image and the sandbox guidance |
| 2. Switch | `run_python` in the conversation's sandbox as the design's "How a call runs"; the guidance changes; offered with the sandbox tools; the Tenant switch and the health check ask the sandbox; previews in a short-lived sandbox; the Java client and its tests removed; `ARCHITECTURE.md`, `docs/specs/chat.md`, `docs/tests/chat.md` | `RunPythonToolTest` against a recording sandbox (staging, last-line printing, files out, charts, timeout, caps, a full library); `PresentationPreviewServiceTest`; the evaluation of step 1 at an equal or better score before merging; staging acceptance below |
| 3. Removal | After step 2 has run on staging and production: Compose service, executor image, `images.env`, CI publication, `deploy.sh`, `prune-release-images.sh`, the Dependabot entries and their infrastructure tests; `interpreter/` renamed `memoryos-interpreter/` with a note that it is neither built nor deployed; CI/CD runbook | CI; a staging deployment without the interpreter |

## Acceptance

- [ ] On staging, `run_python` cleans a workbook a member attached, and a follow-up `bash` call finds the file it wrote.
- [ ] A chart left open by `run_python` appears below the answer as before.
- [ ] A generated deck opens as a PDF preview.
- [x] The evaluation scores the sandbox at least as well as the interpreter. 2026-10-18 on staging, `cx/gpt-6-luna`,
      5 repeats of 10 tasks: interpreter 35 first runs / 48 after one fix / 2 wrong without error; sandbox 38 / 49 / 0.
- [ ] Staging and production run without `memoryos-interpreter`.
