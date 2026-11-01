# Search reuses a question's vector and resolves the Source scope once

## Problem

- **The same question is embedded again.** Changing page or toggling a filter in Search, or a Chat turn that
  searches the same text again, calls the embedding model for a vector it computed moments before: a model call's
  latency and cost each time.
- **Direct Search resolves the reader's Source scope twice.** `DocumentSearchService.search` read the access tokens
  before the index query (`SourceSearchService.accessTokens`) and then the whole scope (`SourceSearchService.scope`:
  active Tenant, searchable Sources and the same access tokens again) for the readable origins.

## Decision

Only redundant work is removed. Ranking and every search parameter are unchanged: the `multi_match` fields and
boosts, `minimum_should_match`, `ef_search`, candidate limit, `pagination_depth`, the keyword and semantic weights,
`min_score` and the hybrid pipeline are the same, and the index receives the same request as before (the test
compares the serialized request of a kept and an embedded vector).

### Query vectors are kept for ten minutes

`OpenSearchIndexService` keeps the vectors it embedded for a question in a Caffeine cache, used by direct Search,
Search in a document set, Chat's batched Search and Search in attached files.

- **Key:** the generation's ID and index identity, the Tenant, the actor and the exact text. A new generation (a
  model switch or a rebuild) never reuses a vector. The actor is in the key so no one can learn from a faster
  answer that another person asked the same question.
- **Bounds:** ten minutes after embedding, at most 16 MiB of vectors and text per process (weighed by bytes, since
  a vector is 4 bytes a dimension and dimensions go up to 16,000).
- **No authority is kept.** A vector carries no access data; Tenant, `SEARCH_READ`, Source eligibility and current
  generation are checked on every request as before.
- **The model is called outside the cache** (`getIfPresent`, then `put`), so a slow provider never blocks another
  key. Two concurrent first searches of one text may both embed it, as every search did before.
- A kept vector is not a provider call, so it adds nothing to the AI usage ledger.
- Chat's batch embeds only the texts without a kept vector, in the same batches as before.

Caffeine is already in the version catalog (Spring Boot manages its version) and used by `api`; `core` now
declares it. `ValidatedEmbeddingService` is left alone: its contract is that `query` calls the provider, which the
provider tests rely on.

### Direct Search resolves one Source scope

`DocumentSearchService.search` resolves `SourceSearchService.scope(actor)` once, in the PREFETCH stage, checks that
its Tenant is the request's, passes its access tokens to the index query and its Sources to `readableMetadata`.
The index query itself is unchanged: direct Search still filters by access tokens only, not by the scope's Source
IDs. `SourceSearchService.accessTokens`, used only here, is removed.

Effect: one access-token query and one active-Tenant lookup fewer for a search with results. A search with no
candidates now reads the searchable Sources it did not need before; almost every hybrid search returns candidates.
If the actor loses the Tenant between the first check and the scope, the request fails with `SOURCE_NOT_FOUND`
(not found) before the index instead of `SEARCH_DOCUMENT_UNAVAILABLE` after it, as the later scope lookup already
could.

## Out of scope

- Any change to ranking, fusion or search parameters (shared with Chat; a team decision).
- Caching across processes (Redis) or caching index results.
