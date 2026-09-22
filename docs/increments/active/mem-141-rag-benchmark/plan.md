# MEM-141 — Plan

## Prerequisites on staging

1. **Actor matrix — met 2026-09-21.** The Savico Sources are `PRIVATE`, each attached to its department Group and to
   `Savico Executive`; the owner designated existing test accounts as `exec`, `finance`, `all-departments` and
   `outsider`. See [corpus findings](corpus-findings.md).
2. **Credentials.** Each actor signs in with `rag-benchmark login`; the judge key comes from Infisical. The OpenRouter
   key was pasted into a chat transcript and must be rotated before the first scored run.
3. **A stable corpus.** No re-index, upload or Group change between a baseline and its candidate. `check` reports a
   drifted corpus and any expectation the Group configuration no longer satisfies.

## Delivery order

1. **Runner skeleton** — done in PR #251: configuration, Keycloak token per actor, frozen corpus manifest, JSONL
   question loader.
2. **Retrieval run** — done in PR #251: `POST /api/search` per question, recall@k and nDCG@5, JSON result file.
3. **Chat run** — done in PR #251: session per question, poll the saved reply, collect content, citations and
   activity, delete the session.
4. **Judge and report** — done in PR #251: correctness, abstention, leakage, latency; Markdown and JSON report;
   baseline file and zero-regression gate.
5. **Question set** — 90 questions, 131 actor asks, each authored from a passage read on staging
   (2026-09-20, extended 2026-09-21): ten per category, `lookup` twenty.
6. **Authority matrix** — in the working tree (2026-09-21):
   - `login` with Authorization Code + PKCE and renewing access tokens, because tokens live five minutes;
   - per-actor expectations (`expect`) and the `cross_department` category with partial answers;
   - leak checks on the search page, the timeline documents, citations and forbidden facts in the answer;
   - per-actor frozen corpora, the expectation consistency check in `check`, and a per-actor report table;
   - the question set relabelled to the actor matrix, six `group` questions denied to `finance`, ten
     `cross_department` questions across the finance/legal/governance/ir/hr boundaries; every
     expectation checked against the actors' real read sets, and every forbidden fact checked to appear
     in the forbidden document only — three names in `cross_department-001` did not and were removed.
7. **Measurement fixes from the first smoke run** — in the working tree (2026-09-21). The smoke run reported
   13/13 failures as a pass and marked two correct refusals wrong, so no baseline may be saved before these land:
   - the gate fails a run that could not finish its asks, which it used to ignore;
   - a refusal is scored on what the reply reached, not on its wording, and the declining vocabulary of the real
     replies (`chưa tìm thấy`, `không thể xác định`, English equivalents) is recognised;
   - the judge runs `MEMORYOS_JUDGE_TRIALS` times (3) and records the majority with its split; the partial-answer
     prompt no longer requires the reply to announce what it could not read;
   - each result keeps the turn's timeline: queries, filters and documents per tool step;
   - the report carries `judgeDisagreement`, so a soft correctness figure is visible, as OrgMemory's
     RAGAS runner reports its spread rather than one number.
8. **First baseline** — sign in the four actors, `freeze`, `check`, then `run --label baseline --save-baseline`.
9. **First comparison** — the chunk-size question that started this increment: one candidate per chunk setting,
   reported together.

## Verification

- `uv run pytest`, `uv run ruff check .`, `uv run ruff format --check .` and `uv run mypy rag_benchmark tests` — 38
  tests covering metrics, question validation, the regression gate, leak detection, expectation consistency, token
  renewal, refusal scoring, judge trials and the reply timeline.
- The expectation check against staging read sets (SQL of the code's read rule): 131 actor asks, no gold
  document unreadable and no forbidden document readable; `rag-benchmark check` reports 90 valid questions.
- A staging run whose report is attached to MEM-141 — pending step 8.
- Documentation consolidated into [docs/tests](../../../tests) and the roadmap when the first comparison lands.

## Open questions

- Whether the judge model stays qwen3.8 27B through OpenRouter or moves to a provider already configured in the
  catalog; the runner keeps it configurable. Three trials and a recorded split are now in, following OrgMemory;
  whether to report the spread as its own metric is still open.
- Whether Tenant figures in `gold_answer` and the per-actor corpus titles belong in Git.
- Whether `cross_department` needs a case where three departments each hold one part; the ten questions today pair
  two departments at a time.
- Whether retrieval runs belong in CI later. They need staging credentials, so they stay manual for now.
