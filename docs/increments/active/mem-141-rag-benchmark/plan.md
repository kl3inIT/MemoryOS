# MEM-141 — Plan

## Prerequisites the benchmark needs from an administrator

1. Two benchmark actors, provisioned through the admin surfaces, never by writing rows:
   - `rag-benchmark@memoryos.test` (exists on staging) added to the Groups that hold the granted Sources;
   - a second actor with no Source-granting Group, for the unauthorized side of every `group` question.
2. Their passwords and the judge API key in Infisical, read from the environment at run time.
3. A corpus that is stable while a comparison runs: no re-index and no upload between the baseline and the candidate.

## Delivery order

1. **Runner skeleton** — configuration, Keycloak token per actor, frozen corpus manifest, JSONL question loader.
   Verified by `uv run pytest` over the metric and parser units, and by `corpus freeze` against staging.
2. **Retrieval run** — `POST /api/search` per question, recall@k and nDCG@5, JSON result file.
3. **Chat run** — session per question, poll the saved reply, collect content, citations, activity and duration,
   delete the session.
4. **Judge and report** — correctness, abstention, leakage, latency; a Markdown and JSON report; the baseline file and
   the zero-regression gate.
5. **Question set** — ten questions per category, each authored from a read passage. Written after the runner so the
   questions are validated by running them, not by eye.
6. **First comparison** — the chunk-size question that started this increment: a baseline run, then one candidate per
   chunk setting, reported together.

## Verification

- Metric units: recall@k, nDCG@5, abstention and leakage counting, each with a fixture.
- A staging run whose report is attached to MEM-141.
- Documentation consolidated into [docs/tests](../../../tests) and the roadmap when the first comparison lands.

## Open questions

- Whether the judge model stays qwen3.8 27B through OpenRouter or moves to a provider already configured in the
  catalog; the runner keeps it configurable.
- Whether retrieval runs belong in CI later. They need staging credentials, so they stay manual for now.
