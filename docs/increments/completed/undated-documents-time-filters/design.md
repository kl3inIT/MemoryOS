# Time filters must not erase documents without dates

## The failure

A Chat question that names a period returns nothing, while the Search page ranks the right document
first. Reproduced on staging on 2026-09-21 with the MEM-141 benchmark actor `exec`, which may read
both gold documents:

```
/api/search        hạng 1 = thông báo nhân sự (HROD), hạng 2 = Điều lệ (Pháp chế)
search_knowledge   filters {"updated": {"from": "2025-09-01T…", "to": "2025-09-30T…"}}
                   read: (none) · sources: [] · "chưa tìm thấy tài liệu…"
```

The `TIME_FILTER` helper read "tháng 9/2025" out of the question and turned it into a document-update
window. Every Google Drive item on staging carries a null `source_created_at` and `source_updated_at`
(58 of 58), as the [Search contract](../../../specs/search.md#source-metadata-and-chat-filters) records:
remote provider ingestion does not capture source dates yet. A range clause does not match a document
whose field is absent, so the inferred window removed the whole corpus, silently.

Two independently correct behaviours combine into a wrong one: a helper that infers a window, and a
corpus that has no dates to compare it against.

## Blast radius

| Path | Today |
| --- | --- |
| Chat question naming a period | Intermittent empty evidence; the helper is not deterministic |
| Persona `knowledge_cutoff` (Onyx `search_start_date`) | **Every** search returns nothing while a cutoff is set: `SearchTool.knowledgeCutoff` becomes `updated >= cutoff` over the same null field. No Persona sets one on staging today, and MEM-119 has just opened agent creation to members |
| Persona `knowledge_cutoff` newer than 90 days | **Still blacks out.** Such a floor is neither inferred nor old, so the undated rule does not admit undated documents and the retry does not apply. It stays wrong until Drive ingestion records dates; the rule below is Onyx's, and Onyx's corpus is dated |
| Direct Search `updatedSince` | Unaffected: it filters the top-level `updated_at` of the indexed chunk, which is always written |

Both the index clause (`OpenSearchIndexService.searchPrepared`) and the post-query origin filter
(`SearchFilters.matches`, used by `DocumentSearchService.ranked`) drop undated origins, so the
document disappears twice over.

## Reference

Onyx solved this and MemoryOS's port dropped the rule. `backend/onyx/document_index/FILTER_SEMANTICS.md`
has a section *Undated documents*:

> We prefer to over- than under-extend, so a missing timestamp does not remove a document — with one
> exception to avoid flooding recent-window queries.

- `created_at_range`: undated documents are **always** kept.
- `updated_at_range`: undated documents are kept only for an **old, open-ended lower bound** — a start
  older than `ASSUMED_DOCUMENT_AGE_DAYS` (90) with no upper bound; a recent or bounded range excludes them.

`opensearch/search.py` implements it by adding `{"bool": {"must_not": {"exists": {"field": …}}}}` to the
clause's `should`. Onyx's Google Drive connector also records `doc_updated_at`, so its corpus is dated in
the first place. MemoryOS has neither half.

The same file in MemoryOS already applies exactly this pattern for authorization — `accessFilter` keeps
chunks whose access fields predate the V52 backfill. The principle was present; it was not applied to dates.

## Decisions

1. **Adopt the Onyx policy for both layers.** `created` keeps undated origins always; `updated` keeps them
   for an old, open-ended lower bound. The index clause and `SearchFilters.matches` share one rule, as the
   access rule is already shared between index time and the recheck, so they cannot drift.
2. **An inference that empties the result is discarded, not obeyed.** When retrieval returns nothing and the
   turn applied an inferred time filter, `SearchTool` runs the same queries once more without it. This is a
   deliberate departure from Onyx, whose corpus is dated: a guess that removes every document is wrong by
   construction, and the retry costs one query only in the case that already failed. The tool result records
   that the window was dropped so the model does not present unfiltered evidence as filtered.
3. **Explicit restrictions stay strict.** A window the user or the tool call supplies is not an inference;
   only the helper's own inference is retried away.
4. **A recent Persona cutoff is knowingly left wrong.** Admitting undated documents to a recent window
   would flood every "what changed this week" question with the whole corpus, which is why Onyx draws the
   line at 90 days. The honest fix is dates, not a wider rule.
5. **Capturing Drive dates is out of scope here.** It is the root fix, it changes ingestion and needs a
   re-index, and it is recorded as follow-up work. Decisions 1–3 make the system correct while dates are
   missing, and remain correct once they exist.

## Verification

- Unit: `created` keeps an undated origin; `updated` keeps one for a 180-day-old open lower bound and drops
  one for a bounded September window; the index clause carries `must_not exists` in exactly those cases.
- Unit: a Persona cutoff older than 90 days retrieves an undated document.
- Unit: an inferred window that returns nothing is retried without the window; a window the user asked for
  is asked once and kept, and the reply says nothing about dropping it.
- Staging: re-run the reproduction above; `exec` must read and cite both documents.
- Benchmark: the MEM-141 `temporal` category is the regression test for this defect and must not be edited
  to avoid it.

## Follow-up

- Record `createdTime`/`modifiedTime` for Google Drive items during ingestion, then re-index; until then
  every inferred window is a guess over absent data.
- Onyx maps "updated in [S,E]" to the overlap `updated >= S AND created <= E`; MemoryOS uses a strict
  bounded range. Worth adopting once dates exist.
