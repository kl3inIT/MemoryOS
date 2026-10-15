# Plan

## Steps

1. **Query vectors** — the Caffeine cache in `OpenSearchIndexService` for every query embedding path; `caffeine`
   in `memoryos-core`. Done.
2. **One Source scope** — `DocumentSearchService.search` resolves the scope once; `SourceSearchService.accessTokens`
   removed. Done.
3. **Documentation** — this increment; [Search contract](../../../specs/search.md) and its verification matrix. Done.

## Verification

| What | Where |
| --- | --- |
| Another page or filter of the same question calls the embedder once, and the index request is the same with a kept vector | `QueryVectorReuseTest` |
| Another generation, another text or another reader calls the embedder again | `QueryVectorReuseTest` |
| Chat's batch embeds only texts without a kept vector and shares them with direct Search | `QueryVectorReuseTest` |
| Direct Search resolves the Source scope once, filters the index with its tokens and reads origins with it; a scope of another Tenant stops before the index | `DocumentSearchServiceTest` |
| Existing retrieval, Chat Search and boundary tests | `DocumentSearchServiceTest`, `SearchToolTest`, `CoreDependencyRulesTest`, `ModulithArchitectureTest` |

Not run here: the OpenSearch integration tests (Docker) and the full `clean check`; CI runs them.

## Open

- Measure the saved embedding latency on staging after deployment.
