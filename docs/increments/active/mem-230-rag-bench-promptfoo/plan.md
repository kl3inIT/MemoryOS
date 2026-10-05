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
- [x] Staging, before the merge, 2026-10-05:
  1. A throwaway `docker run --read-only --cap-drop ALL` of the pinned image became healthy, answered `/health`, ran
     `promptfoo --version`, and its `python3` imported `ssl` (OpenSSL 3.5.8), `sqlite3`, `json` and
     `urllib.request`. No write error in its log.
  2. `provision-staging-secrets.sh` created only the two promptfoo secrets (mode 600); `/apps/memoryos/promptfoo`
     exists (750); `.env.staging` carries `MEMORYOS_PROMPTFOO_PUBLIC_URL`.
  3. The realm script's environment of its last staging run is not on the server, and a partial run would clear the
     realm's mail server. Only the promptfoo part was applied, through the admin REST API in the script's order:
     confidential client with the exact callback, no full scope, secret equal to the file, realm scope mapping
     exactly `memoryos-inspector`. A later full run of the script updates the same client.
  4. Nginx Proxy Manager, through its API after a database dump (`npm-pre-mem230-20261005T0931Z.sql`):
     Let's Encrypt certificate 36 for the exact domain and proxy host 32 to `memoryos-promptfoo-oauth2-proxy:4180`,
     with the Redis Insight host's settings. HTTP redirects to HTTPS; HTTPS validates and answers 502 until the
     service is deployed. The other hosts still answer.
- [x] Merged as #544 (`b46c6fff`); the staging deployment succeeded. `deploy.sh` recreates only Keycloak, the API,
  worker, web and interpreter, so the staging-only tools are started by an operator from the accepted configuration:

  ```sh
  files=(); while IFS= read -r f; do files+=(-f "$f"); done < /apps/memoryos/deployments/current.compose
  docker compose --project-name memoryos --env-file /apps/memoryos/deployments/current.base.env     --env-file /apps/memoryos/deployments/current.env "${files[@]}"     up -d --no-deps --wait promptfoo promptfoo-oauth2-proxy
  ```

  Both became healthy; the public origin redirects to the realm with client `memoryos-promptfoo`, PKCE and the exact
  callback, and the API redirects too without a session.
- [ ] Browser check: the owner (inspector role) reaches the viewer; a user without the role is refused.
- [x] Removed the hand-run `/apps/promptfoo` container, its volume (one smoke eval of 2026-09-30) and its directory.
- [x] 9Router key `memoryos-benchmark`, only in `/apps/memoryos/secrets/inspection/promptfoo-judge-api-key.txt`
  (mode 600). `/v1/models` answers 200 with it and 401 without; `cx/gpt-6-luna` answered through
  `https://9router.zeromail.vn/v1`.

- [x] Browser check, 2026-10-05: the owner signed in through Keycloak and reached the viewer.
- [ ] The viewer creates and runs evals: `OPENAI_BASE_URL` is 9Router and `OPENAI_API_KEY` is read from the
  `promptfoo_judge_api_key` secret by the entrypoint. The key file is owned by the image's `promptfoo` user
  (100:101, 0400), since a Compose file secret keeps the host file's owner. A throwaway container of this shape stayed
  healthy with a read-only root filesystem and passed a one-test eval against `cx/gpt-6-luna`. After the merge and
  deployment, recreate `promptfoo` with the command above. Remote generation stays off: it sends prompts to
  promptfoo's cloud.

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
