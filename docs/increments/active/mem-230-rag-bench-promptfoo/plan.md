# MEM-230 — Plan

Design: [design.md](design.md). Three pull requests: P1–P3 (viewer, corpus, provisioning), P4–P6 (questions,
promptfoo, baselines, removal of the old runner), then P7 and P8 each on their own.

## P0 — promptfoo on staging (done 2026-10-03)

- [x] `ghcr.io/promptfoo/promptfoo:0.123.1` pinned by digest, loopback only, 1 GiB, telemetry, update checks,
  remote generation and sharing off. Hand-run from `/apps/promptfoo`; P1 replaces it.

## P1 — Preparation and viewer

- [x] Checks, 2026-10-05:
  - the image ships Python 3.14 (`/usr/bin/python3`), so the provider runs in the stock image;
  - staging's `memoryos-integration` accepts `scope=openid offline_access` (an unknown scope is refused with
    `invalid_scope`); the realm script's MCP part, which staging runs, sets offline sessions to 30 days idle and
    180 days at most;
  - the public API can create a file Source, upload, set access, attach Groups and add members;
  - Chat has no rate limit or AI quota that a run would hit;
  - `grounded-partial-evidence` merged as #332, so a baseline on `main` includes it.
- [x] `promptfoo` and `promptfoo-oauth2-proxy` in `compose.staging.yaml`: the viewer only on an internal access
  network, egress on its own network, `memoryos-inspector` required; client `memoryos-promptfoo` in the realm script;
  secrets in `provision-staging-secrets.sh`; `MEMORYOS_PROMPTFOO_PUBLIC_URL` in `staging.env.example` and the runbook.
- [ ] Staging, before the merge (one step at a time, each approved):
  1. Prove the read-only root filesystem with a throwaway `docker run --read-only` of the pinned image.
  2. Run `provision-staging-secrets.sh`; add `MEMORYOS_PROMPTFOO_PUBLIC_URL` to `/apps/memoryos/.env.staging`;
     create `/apps/memoryos/promptfoo`.
  3. Run the realm script with the promptfoo pair.
  4. Nginx Proxy Manager host `memoryos-bench.72-62-193-33.nip.io` to `memoryos-promptfoo-oauth2-proxy:4180`.
- [ ] Merge; the staging deployment starts the service. Sign in with the inspector role; a user without it is refused.
  Remove the hand-run `/apps/promptfoo` container and its volume.
- [ ] 9Router key `memoryos-benchmark`.

## P2 — Corpus

- [ ] Collect the public disclosures by the criteria in the design; noise from a second listed company.
- [ ] `tools/rag-benchmark/corpus/manifest.yaml`: key, department, period, format, source URL, SHA-256.
- [ ] Originals in a private bucket outside Git.

## P3 — Provisioning

- [ ] Idempotent script: Sources and Groups `Benchmark - …`, uploads, indexing wait, members from the role mapping,
  fingerprint printed. A second run changes nothing.

## P4 — Questions

- [ ] Move the 90 questions to roles, document keys and evidence passages; keep the ones the new corpus still answers.
- [ ] New questions per department, across periods, across departments and unanswerable; drafts from promptfoo or
  Ragas, each kept only after reading its passage. About 150 in all.
- [ ] `check`: every evidence passage is in the corpus and every role reads what its question expects.

## P5 — promptfoo

- [ ] Python provider: offline token per role, ask, read the event stream (time to first text), return the answer,
  sources and the text of each read passage.
- [ ] Tests generated from the question set; built-in assertions; the two MemoryOS assertions.
- [ ] Judge calibration: `factuality` on the 87 answers judged on 2026-10-03; on a large disagreement replace its
  `rubricPrompt` with a Vietnamese one, still the built-in assertion.

## P6 — Baselines and removal

- [ ] Ungrounded and grounded baselines on `main`, with the fingerprint.
- [ ] Remove the MEM-141 runner except what the provider reuses; update `tools/rag-benchmark/README.md`,
  `docs/tests/` and the roadmap, and record the MEM-141 and MEM-195 items this closes.

## P7 — Red team

- [ ] Poisoned documents from `promptfoo redteam poison` in their own Source and role; `pii:direct` and `rbac`.
  Report how often the answer follows a planted instruction.

## P8 — Helper ablation

- [ ] Variants per the design, two runs each; estimate the cost and check the 9Router balance before running.
  Decide each helper step and MEM-210.
