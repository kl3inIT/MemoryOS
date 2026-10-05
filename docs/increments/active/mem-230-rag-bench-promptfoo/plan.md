# MEM-230 — Plan

Design: [design.md](design.md). Four pull requests: P1–P3 (viewer, corpus, provisioning), P4–P6 (questions,
promptfoo, baselines, removal of the old runner), P7, and P8.

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
  1. Prove the read-only root filesystem with a throwaway `docker run --read-only` of the pinned image: the server
     answers `/health`, `promptfoo --version` runs, and `python3` imports `ssl`, `sqlite3`, `json` and
     `urllib.request`.
  2. Run `provision-staging-secrets.sh`; add `MEMORYOS_PROMPTFOO_PUBLIC_URL` to `/apps/memoryos/.env.staging`;
     create `/apps/memoryos/promptfoo`.
  3. Run the realm script with the complete operator environment of its last staging run plus the promptfoo pair
     (the secret value read from the file of step 2). A partial environment is not harmless: without the SMTP pair
     the script clears the realm's mail server.
  4. Nginx Proxy Manager host `memoryos-bench.72-62-193-33.nip.io` to `memoryos-promptfoo-oauth2-proxy:4180`.
- [ ] Leave draft and merge only after steps 1–4: `STAGING_AUTO_DEPLOY` is on and `MEMORYOS_PROMPTFOO_PUBLIC_URL` is
  required, so an early merge fails the deployment. Set MEM-230 back to In Progress after the merge. The staging
  deployment starts the service. Sign in with the inspector role; a user without it is refused.
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

- [ ] Python provider, standard library only (the root filesystem is read-only): offline token per role, ask, read the event stream (time to first text), return the answer,
  sources and the text of each read passage. It reaches MemoryOS through the public staging origin and the judge
  through `https://9router.zeromail.vn/v1`: its egress network has no route to the internal `api` service.
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
