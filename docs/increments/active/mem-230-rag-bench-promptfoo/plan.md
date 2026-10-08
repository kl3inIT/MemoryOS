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

- [x] Baseline v2, 2026-10-06, corpus `6f95c22c…`, recorded in
  [docs/tests/chat.md](../../../tests/chat.md#rag-benchmark-baseline-mem-230--2026-10-06). A hand audit of 30
  random failed grounded rows found 14 bench errors, 9 of them one design flaw: a role without the evidence
  documents was expected to decline although the same fact (charter capital, address, Supervisory Board) is in
  documents it may read, so correct, leak-free answers failed. Abstain and partial rows outside the
  unanswerable categories now pass on a correct answer or a decline and fail on a wrong, borrowed or invented
  fact; reading a tempting document is no longer a failure, citing it is, under its own `tempting` metric.
  The v2 replies were re-graded with `--replay`; the re-graded runs are the baseline.
- [x] Removed the MEM-141 runner (`rag_benchmark/`, `datasets/`, its tests and the httpx dependency); the provider
  reused nothing from it. `promptfoo/export_replay.py` writes a past eval's replies for `--replay`, and the README
  describes the promptfoo benchmark. This closes MEM-141's open items (a question set, a scored run) and MEM-195's
  grounded baseline. Not carried over: the retrieval-only run over `/api/search` (recall@k, nDCG@5); a chunking or
  index change is now read from the Chat metrics, until a retrieval check is added to the promptfoo config.

## P7 — Red team

- [ ] Poisoned documents from `promptfoo redteam poison` in their own Source and role; `pii:direct` and `rbac`.
  Report how often the answer follows a planted instruction.

## P8 — Helper ablation, then additions

Subtract first, then add to the leanest pipeline that keeps its score: an addition measured on a pipeline that later
loses steps would have to be measured again. Every variant keeps OpenSearch hybrid retrieval, weighted RRF and the
access rechecks.

How variants run (ADR 0002, no product switch): a branch that is never merged reads `MEMORYOS_SEARCH_ABLATION`; its
`core` jar is built from the released commit and layered on the released API image on staging, and the release is
restored by Compose when the runs end. Each variant is one grounded run of all 375 rows at 2 concurrent turns (about an
hour); a second run only where a variant lands within judge noise of V0. Measured per variant: pass rate overall, per
category and per split, turn time (median, p90), helper calls and tokens from `ai_usage`.

Decision rule, changed from the design: the test split alone (about 137 rows) is too small against ±5 rows of judge
noise, so a step is dropped only when removing it costs no more than noise on both splits, no category falls by more
than 10 points, and no access leak appears.

### P8a — Subtract

| Variant | Change |
| --- | --- |
| V0 | Released pipeline, run on the variant image |
| V1 `no-rewrite` | No semantic or keyword rewrite: the model's queries and the raw question |
| V2 `no-scope` | No source-type or time-window inference |
| V3 `no-select` | No LLM section selection: the top 10 sections by RRF |
| V4 `no-classify` | No per-section classification: every section with its adjacent chunks |
| V5 all of V1–V4 | Model queries → OpenSearch → top 10 sections with neighbours (Embabel `ToolishRag` shape) |

- [x] Run 2026-10-06, one grounded run each of 375 rows on the variant image (evals `eval-Gaz`, `eval-cDl`, `eval-Ayy`,
  `eval-LAu`, `eval-qPU`, `eval-n8I`). Input tokens are the day's `ai_usage` CHAT delta over the run.

  | Variant | Pass | Dev / test | Temporal | Near miss | Turn median / p90 | Input tokens |
  | --- | --- | --- | --- | --- | --- | --- |
  | V0 | 74.3% | 74% / 75% | 60% | 41% | 18.2 s / 26.4 s | 23.9 M |
  | V1 `no-rewrite` | 72.4% | 76% / 67% | 66% | 39% | 16.3 s / 24.3 s | 23.0 M |
  | V2 `no-scope` | 74.3% | 75% / 73% | 60% | 45% | 18.2 s / 24.6 s | 23.2 M |
  | V3 `no-select` | 68.0% | 72% / 62% | 43% | 45% | 20.3 s / 28.3 s | 24.5 M |
  | V4 `no-classify` | 73.9% | 75% / 72% | 57% | 43% | 16.3 s / 24.4 s | 18.6 M |
  | V5 all off | 68.5% | 73% / 61% | 47% | 39% | 10.1 s / 13.3 s | 12.6 M |

  No access leak and every `[n]` named a source in all six. Readings: the per-section classification earns nothing
  (V4 matches V0 on both splits, 2 s and 22% of input tokens less); LLM section selection is the one step that
  carries the score (V3 loses 13 points on test, temporal falls to 43%), and it is almost all of what the simplest
  pipeline loses (V5 is twice as fast and loses about 6 points). Source and time inference cost nothing to drop on
  this corpus, but its uploads share one date and one source type, so it is not decided here. The rewrite is
  undecided: dev and test disagree, so V1 runs again in P8b. MEM-210 narrows to the selection step.

### P8b — Query-time additions, on the P8a winner

| Variant | Addition | Benchmark defect (MEM-232) |
| --- | --- | --- |
| A4 | Cross-encoder reranker (`bge-reranker-v2-m3`, self-hosted, multilingual) instead of LLM selection | wrong sections, latency |
| A5 | Document date, source and period in each evidence header | older period |
| A6 | Answer guidance: check the premise, prefer the latest or restated figure, answer only about the entity asked | false premise, older period |
| A7 | Drop sections classified not relevant (the reference keeps them) | other company |
| A8 | Forced first search in standard mode | no search |

- [ ] Run on the V4 base: A4a `bge-reranker-v2-m3` (self-hosted on the serving node's GPU for the probe, removed
  after), A4b Jev ranking each candidate on "contains the answer", A4c A4b plus dropping passages Jev marks as
  injected instructions, weighting the subject asked and noting a contradicted premise in the evidence, E1 Embabel
  `ToolishRag` over our OpenSearch (`VectorSearch`, `TextSearch`, `ResultExpander` adapters keeping the access
  rechecks), and V1 a second time. Jev is `openrouter/typesafe/jev-1.13` through 9Router's `/v1/systemone`, the only
  model taken from OpenRouter; a probe of 68 calls cost 0.0012 USD and 50 parallel calls took 1 s.

- [x] Found 2026-10-07 by the retrieval probe: 13 of the 61 benchmark uploads (6 Savico, 7 Haxaco) had failed
  extraction on 2026-10-05 with `SOURCE_EXTRACTION_CONNECTION_FAILED` while the OCR service restarted, after one
  attempt. 32 of 106 answerable questions lost some or all evidence, so the v2 baseline and every P8 run measured an
  incomplete corpus: variant-to-variant differences hold, absolute rates and the per-category reading of MEM-232 do
  not. The 13 were re-indexed; `memoryos_bench check` now fails on any missing or unsearchable document, `provision`
  retries a failed extraction once, and the provider refuses every turn while the check fails. V0, V4, B1 and E1 run
  again on the complete corpus.

- [x] Complete corpus, 2026-10-07, 61 documents, every run 375 rows with its error rows asked again (evals `eval-n43`
  + `eval-LXG`, `eval-z0a`, `eval-cCK`, `eval-49s` + `eval-pR0`). B1 (bge) did not run: the reranker was removed from
  the serving node, which is production.

  | Variant | Pass | Dev / test | Near miss | Temporal | Multi-hop | False premise | Lookup | Turn median |
  | --- | --- | --- | --- | --- | --- | --- | --- | --- |
  | V0 released | 67.2% | 68% / 65% | 27% | 57% | 63% | 55% | 92% | 18.4 s |
  | V4 `no-classify` | 68.8% | 71% / 65% | 27% | 50% | 70% | 55% | 93% | 14.2 s |
  | E1 Embabel `ToolishRag` | 58.9% | 59% / 58% | 36% | 43% | 50% | 45% | 73% | 8.2 s |
  | V4E V4 + `broadenChunk`/`zoomOut` | 65.6% | 66% / 65% | 30% | 43% | 60% | 55% | 88% | 12.2 s |

  Readings: the complete corpus lowers every rate the incomplete one flattered, chiefly near miss (41% to 27%),
  because 12 Haxaco documents instead of 5 make the other-company trap real; lookup rises (88% to 92%) because the
  evidence is now there. V4 holds against V0 on both splits and is 4 s faster, so MEM-210 drops the per-section
  classification. Embabel's native tool loop is 8 points worse but twice as fast and best on near miss, where the model's own
  queries keep the company name. In V4E the model never called the expander tools (0 of 462 turns), so model-driven
  expansion adds nothing over V4's adjacent chunks. The retrieval probe on 61 documents agrees on chunking: the
  structure-aware chunks win at an equal token budget, hybrid beats vector-only, and every chunking leaves 14–18% of the
  top 10 from Haxaco, so company and period confusion is a metadata problem (MEM-232), not a chunking one.

- [x] Search-first routing and a rewritten search guidance, probed 2026-10-07 on the complete corpus and not adopted.
  The guidance told the model to search by default and to check the company, period and premise of each result
  (A6, A8). Standard mode: released 65.1% (dev 69% / test 59%), with the guidance and V4 63.7% (65% / 61%); grounded:
  V4 68.8%, with the guidance 65.2% (near miss 27% to 22%, false premise 55% to 40%, multi-hop 70% to 59%; 19 rows lost
  to a name-resolution failure of the benchmark container). Of 16 messages that need nothing looked up, the released
  guidance searched for none and the new one for 5 (10–18 s each instead of 4 s). Of 20 organization questions phrased
  like general knowledge and asked without the company's name, the released standard mode searched for all 20, so
  the skipped search seen once in P5 is rare and a classifier forcing the first search would cost a call on every
  standard turn for nothing measurable. The routing switch of the probe build never fired, so routing itself was not
  measured. Company, period and premise handling stays with MEM-232 (metadata), where a prompt alone did not help.

### P8c — Index-time additions, re-indexing only the benchmark Sources

| Variant | Addition | Benchmark defect |
| --- | --- | --- |
| A1 | Title and section heading in the embedded and BM25 text (Onyx `title_prefix`) | other company, not found |
| A2 | Contextual retrieval: an LLM sentence of document context per chunk (Anthropic) | other company, not found |
| A3 | Organization, period and supersession extracted at ingestion, used to filter, rank and show | older period, other company |

A recency boost on provider dates cannot help here: every benchmark document was uploaded the same day, so ordering by
period needs A3. A2 and A3 cost an LLM call per chunk or document; count the chunks and report the cost before running.

- [ ] Additions that win become ordinary pull requests through CI; A3 gets its own increment with a design.
