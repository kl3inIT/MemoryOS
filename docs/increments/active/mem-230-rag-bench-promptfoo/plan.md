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

- [x] Collected 2026-10-05 from savico.com.vn, haxaco.com.vn and two CafeF mirrors; every URL answered 200 with a real
  PDF, DOC or DOCX. [`tools/rag-benchmark/corpus/manifest.json`](../../../../tools/rag-benchmark/corpus/manifest.json)
  lists 61 documents with id, department, period, kind, text layer, URL, size and SHA-256:
  - **Savico (SVC), questions: 49.** Finance 14 (six quarters Q1 2025 to Q2 2026, reviewed H1 2025 and H1 2026,
    audited 2024 and 2025, separate Q1 2026 and 2025, variance explanation, profit proposal), governance 13 (AGM
    2023 to 2026, the October 2024 written opinion, five governance reports, the Supervisory Board report, the 2026
    charter proposal), HR 13 (appointment, dismissal and resignation notices 2024 to July 2026, nominees, a nomination
    form), investor relations 3 (annual reports 2024 and 2025, the board's 2025 report), legal 6 (charter amendments 14
    and 15, internal governance rules 2021, disclosure rules 2024, draft election rules, related-party framework 2026).
  - **Haxaco (HAX), noise: 12.** Same sector and exchange: statements, AGM 2025 and 2026, annual reports, charter,
    governance reports, personnel resolutions. Never questioned.
  - Left out: a candidate's CV (personal data) and five Haxaco files beyond twelve.
- [x] Text layer: 25 text, 10 mixed, 26 scanned. Scans go through the deployment's OCR
  ([PaddleOCR-VL on the serving node](../../../runbooks/ci-cd.md)), so their extraction is part of what the benchmark
  measures.
- [x] Real conflicts kept on purpose: the current charter (amendment 15 published; the AGM 2026 resolution cites a
  "19th amendment of 25/11/2025" never published), the AGM 2026 resolution dated 2025 in its header, superseding
  personnel notices, and three governance reports over overlapping periods.
- [x] Originals are not copied anywhere: the provisioning script downloads each URL and refuses a file whose SHA-256
  differs. A source that changes or disappears is a manifest change, reviewed like any other.

## P3 — Provisioning

- [x] [`memoryos_bench`](../../../../tools/rag-benchmark/memoryos_bench/), standard library only so it runs in the
  promptfoo container as well as on a workstation:
  - `login --role <role>`: Authorization Code with PKCE and `offline_access` through `memoryos-integration`; the
    offline refresh token goes to `$MEMORYOS_BENCH_HOME/tokens.json` (0600). A role is whichever account signed in
    for it, so no account is named in the repository; each role's actor comes from its own `/api/identity/me`.
  - `provision`: as the `owner` role, creates or reconciles the Groups and Sources of
    [`corpus/layout.json`](../../../../tools/rag-benchmark/corpus/layout.json) (six Groups; five Savico Sources by
    department, read by their Group and the executive Group; one Haxaco Source read by every Group; all `PRIVATE`),
    downloads each document from its publisher, refuses a digest that differs from the manifest, uploads what a
    Source lacks, places each role in exactly its Groups and waits until every file is `READY`. A file or membership
    the layout does not list fails the run unless `--prune` removes it.
  - `fingerprint`: SHA-256 over each document's key, digest and Source, each Source's Groups and each role's Groups.
    Descriptive fields do not change it.
- [x] `tests/test_memoryos_bench_corpus.py`: unique keys and digests, every Source and role names known Groups, the
  fingerprint moves with a file or an access change and not with a description.
- [x] Upload deduplication is per Source (`uq_items_connector_sha`), so the Savico files already on staging under the
  old `Tài liệu Savico - …` Sources do not block the benchmark copies. Those Sources stay as the demo data they are;
  the benchmark roles never reach them because each role is placed in exactly its benchmark Groups.
- [x] 2026-10-05, staging. The eight roles were signed in without a browser: the realm's bootstrap administrator
  impersonated each test account and the normal PKCE flow of `memoryos-integration` then issued an offline token;
  no password changed. `provision --wait 0` created the six Groups and six Sources and uploaded all 61 files; after a
  Windows console encoding failure and a dropped connection, both fixed in #549, the next run placed each role and
  listed five memberships outside the benchmark (`finance`, `legal`, `governance`, `ir`, `hr` in their old
  `Savico - …` Groups; `hr` also read the 46 Tasco documents through `Savico - HROD`). On the owner's go-ahead
  `--prune` removed them; the old Groups, Sources and other members are unchanged. The following run reported
  `corpus unchanged`, fingerprint `6f95c22c5f147a927312b04de4546dca187b2687032162e97efe4b0305ca91ee`.
- [x] Each role's readable Sources (`/api/chat/library/documents/sources`): `exec` the six benchmark Sources; each
  department role its own Savico Source and Haxaco; `outsider` none.
- [ ] Every file `READY` (indexing in progress: 20 of 61 at 10:43 UTC, none failed).

## P4 — Questions

Method: [question authoring and bias controls](design.md#question-authoring-and-bias-controls).

- [x] [`authoring/pages.py`](../../../../tools/rag-benchmark/authoring/pages.py) extracts 1,750 pages from the 61
  originals (1,075 read from images, 462 prose, 213 tables). [`corpus/sample.json`](../../../../tools/rag-benchmark/corpus/sample.json),
  seed 230: 24 pages per department, one page of every question document first (49 of 49 covered) and the rest at
  random; finance has 12 table pages. A first draw that was uniform over pages left the short quarterly statements
  out and was replaced before any question was written.
- [x] Drafted 2026-10-05 by six Claude agents from [`authoring/BRIEF.md`](../../../../tools/rag-benchmark/authoring/BRIEF.md):
  17 per department and 40 across departments, ambiguous, general knowledge and sensitive; 125 in all. Pages with
  identity numbers and relatives were left out, and one question about a board member's spouse was replaced. The 90
  MEM-141 candidates were reused only where their fact sat on a drawn page and was re-authored from it.
- [x] Blind validation on the staging host (`cx/gpt-6-luna` and `cx/gpt-6-sol`, both must pass): 111 of 125 at first.
  The rejections taught three rules now in the brief and the tool: quotes stand alone, `as_of` reaches the validator,
  the whole-document pass is skipped for scanned evidence. 12 questions were sent back once; 5 still rejected
  (finance-014, cross-009, cross-012, cross-020, cross-021) were dropped. The shared Codex accounts rate-limit
  above two concurrent requests; the run moved to the server after the workstation ran out of memory.
- [x] [`corpus/questions.jsonl`](../../../../tools/rag-benchmark/corpus/questions.jsonl): 120 questions, every quote on
  its page of the original (pieces joined by `|` each checked, Unicode-normalised), trigram overlap at most 0.41,
  per-role expectations derived from `layout.json`, split 75 dev / 45 test. lookup 30, cross-department 13, near-miss
  12, temporal 10, aggregate 10, multi-hop 10, absent 9, general knowledge 8, ambiguous 6, sensitive 6, false
  premise 6.
- [x] Review, 2026-10-06. The owner delegated it ("bạn thấy ổn là được"), so the 24-question seeded sample
  (viewer eval `eval-JrW-2026-10-05T19:05:12`) was reviewed by Claude, the drafting family; the validators remain the
  independent check. No gold answer was wrong. Two defects of the derived expectations were found and fixed for the
  whole set: general-knowledge and sensitive questions are `grounded_only` (14), since only grounded mode must
  decline them; a near-miss or false-premise question read only in part is `partial`, not `follow_gold`.
  The set is frozen at 120.

## P5 — promptfoo

- [x] [`memoryos_bench/chat.py`](../../../../tools/rag-benchmark/memoryos_bench/chat.py) asks in a fresh session as a
  role, waits for the saved reply, returns the answer, `refusalReason`, the cited sources with the text of each cited
  span (read through the citation reader), the documents the timeline read, the steps and the time. Document titles
  are the manifest file names, so sources map back to manifest ids. History is polled rather than streamed, so time
  to first text is not measured yet.
- [x] Grounded runs ask through the agent `Benchmark - grounded` (the built-in assistant's settings with grounded
  on, public in the Tenant), created by `provision`; the Tenant's grounded setting is left as staging users have it.
- [x] [`promptfoo/memoryos_provider.py`](../../../../tools/rag-benchmark/promptfoo/memoryos_provider.py) runs inside
  the viewer container with the role tokens in its data volume; a turn that does not complete is an error, never
  graded as a refusal. [`promptfoo/build_config.py`](../../../../tools/rag-benchmark/promptfoo/build_config.py)
  turns the frozen set into 361 standard and 375 grounded tests: who asks follows the design, `answer` is scored by
  built-in `factuality` and `context-faithfulness`, `partial`, `abstain` and `follow_gold` by `llm-rubric` (the
  abstain rubric differs by mode), and [`promptfoo/assertions.py`](../../../../tools/rag-benchmark/promptfoo/assertions.py)
  checks forbidden or tempting documents and, in grounded mode, that every `[n]` names a source. The judge is
  `cx/gpt-6-luna`, its key read from the viewer's secret by the run command.
- [x] Judge calibration, changed from the plan: the 87 answers of 2026-10-03 belong to the old question set, so the
  judge was checked instead on two smoke runs of 15 tests, every verdict read by hand. That found and fixed: a
  missing `query` variable, rubrics that failed true facts the gold did not mention and judged two near-identical
  replies differently, an abstain rubric that did not distinguish modes, failed turns graded as refusals, and an
  incomplete gold (cross-039, corrected: the March 2025 change is the chief accountant's replacement, dismissal and
  appointment). `factuality` agreed with every hand verdict on answerable rows.
- [x] First findings from the smoke runs, to be confirmed by the baseline: department roles asked the cross-company
  near-miss answer with Haxaco's dismissal as Savico's; in standard mode a question whose evidence is a scanned
  regulation was answered from general knowledge with no search at all; some turns end `CHAT_EXECUTION_FAILED`.

- [x] First baseline, 2026-10-05, corpus `6f95c22c…`, chat model `cx/gpt-6-luna`, 2 concurrent turns (3 and 4
  locked both Codex accounts and failed 15 of 24 turns; MEM-231). Standard 48% of 359 graded rows, grounded 60% of
  373; no forbidden document reached in either; every grounded `[n]` named a source; correctness on answerable rows
  78% and 80%. It exposed three measurement defects, fixed before it is used as the reference:
  - a list-valued var (`forbidden`) made promptfoo expand a test per item, asking 86 id-role pairs twice; the lists
    are JSON strings now;
  - cited spans were cut at 6,000 characters, losing figures the answer took from further in (now 60,000);
  - promptfoo's `context-faithfulness` failed 42 correct, cited answers, 24 with every figure in context, on spaced
    figures from extraction ("46.621.079. 163") and on correct inferences. It is replaced by an `llm-rubric` over the
    question, the answer and the cited text that accepts formatting differences, figures given by the question and
    correct calculation or date reasoning. A live re-run of the 30 affected questions with full spans, re-graded:
    46 of 47 correct answers judged faithful; the remaining one is a real defect (a Supervisory Board election
    question called political by the guardrail).
  - `--replay` re-grades stored replies with changed checks, without new turns.

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
