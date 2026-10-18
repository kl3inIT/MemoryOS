# MEM-271 plan

Design: [design.md](design.md). Status 2026-10-18: steps 1 and 2 merged (PRs #749, #751) and accepted on staging; production runs the switch since 2026-10-18 (release `6b4f11f1`, after the channels image fix in #764), and a sandbox from the computer image ran Python there through the server's proxy; step 3 is #761. The computer image's stack and office tests moved from the executor's to `memoryos-sandbox/computer/tests/test_image.py`, run by the CI `sandbox` job; the first release without the interpreter removes its container after the sandbox server is healthy, and a rollback to a runtime that ran it starts it again.

| Step | Change | Verification |
| --- | --- | --- |
| 1. Evaluation | `tools/interpreter-eval` takes the computer image as an arm's runtime and reads that arm's `sitecustomize.py` from `memoryos-sandbox/computer/` | The tool's unit tests; a run on staging with the executor image and today's guidance against the computer image and the sandbox guidance |
| 2. Switch | `run_python` in the conversation's sandbox as the design's "How a call runs"; the guidance changes; offered with the sandbox tools; the Tenant switch and the health check ask the sandbox; previews in a short-lived sandbox; the Java client and its tests removed; `ARCHITECTURE.md`, `docs/specs/chat.md`, `docs/tests/chat.md` | `RunPythonToolTest` against a recording sandbox (staging, last-line printing, files out, charts, timeout, caps, a full library); `PresentationPreviewServiceTest`; the evaluation of step 1 at an equal or better score before merging; staging acceptance below |
| 3. Removal | After step 2 has run on staging and production: Compose service, executor image, `images.env`, CI publication, `deploy.sh`, `prune-release-images.sh`, the Dependabot entries and their infrastructure tests; `interpreter/` removed (the design kept it renamed; removed instead on 2026-10-18, see the design); CI/CD runbook | CI, including `test_the_interpreter_is_gone_from_a_release_but_not_from_a_rollback`; a staging deployment without the interpreter |

## Acceptance

- [x] On staging, `run_python` cleans a workbook a member attached, and a follow-up `bash` call finds the file it wrote.
      2026-10-18 on `89da3ca6`: three `run_python` calls handed over one `doanh_thu_sach.xlsx`, and the next turn's `bash`
      listed it and counted its 240 rows with `python3`; the conversation used one sandbox.
- [x] A chart left open by `run_python` appears below the answer as before. 2026-10-18: `Doanh thu theo tháng.png`
      came back with chart data (`chart: true`).
- [x] A generated deck opens as a PDF preview. 2026-10-18: `bao_cao.pptx` from `run_python`; `pdf-preview` answered
      200 `application/pdf` (131 KB) from a scratch sandbox.
- [x] The evaluation scores the sandbox at least as well as the interpreter. 2026-10-18 on staging, `cx/gpt-6-luna`,
      5 repeats of 10 tasks: interpreter 35 first runs / 48 after one fix / 2 wrong without error; sandbox 38 / 49 / 0.
- [ ] Staging and production run without `memoryos-interpreter`.
