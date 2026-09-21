# Plan

## Change

1. `SearchFilters`
   - `ASSUMED_DOCUMENT_AGE` (90 days, Onyx `ASSUMED_DOCUMENT_AGE_DAYS`) and one shared rule
     `keepsUndated(interval, now)`: true for an open-ended lower bound older than that age.
   - `matches` keeps an origin whose `createdAt` is null, and one whose `updatedAt` is null when the rule
     allows it.
2. `OpenSearchIndexService`
   - date clauses become null-tolerant through the same rule: `created_at` always, `updated_at` by the rule.
     The clause is `bool.should[range, must_not exists]`, as Onyx builds it and as `accessFilter` already does.
3. `SearchTool`
   - remember whether this cycle's time window came from the `TIME_FILTER` helper;
   - when ranked retrieval returns no hit and that window was inferred, run the same queries once more
     without it and note the dropped window in the tool result.

## Verification

- `SearchFiltersUndatedTest`: undated created kept; undated updated kept for an old open lower bound, dropped for a
  bounded recent window; an explicit window still excludes a dated origin outside it.
- `UndatedDateClauseTest`: the emitted clause carries `must_not exists` exactly when the rule says so.
- `SearchToolTest`: an inferred window that returns nothing is retried without it and then answers; a
  window the user asked for is kept and asked once. The Persona cutoff is covered in
  `SearchFiltersUndatedTest`, where the floor is built.
- `./gradlew :core:test`. The repository-wide `clean check` cannot pass in this working tree: the
  in-flight meeting-notes work added `ModelFlow.MEETING_MINUTES` without updating
  `ModelCatalogConstraintsTest`, which is the only other failure and is unrelated to this change.
- Staging after release: re-run the reproduction; `exec` reads and cites both documents. Then
  `rag-benchmark run --only temporal` and `--only cross_department`.

## Out of scope

- Google Drive ingestion capturing source dates, and the re-index it needs.
- Onyx's overlap mapping for "updated in [S,E]".
- Any change to the direct Search `updatedSince` contract, which filters an always-present field.
