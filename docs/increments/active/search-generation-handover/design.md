# Search generation handover

## Problem

Publishing a Document cleared `searchable_generation`, and claiming its INDEX work cleared it again. A reindexed Document therefore disappeared from Search, Chat and citation passages until the new generation was embedded and verified (47 seconds for a 20 MiB Drive report on staging), and stayed hidden if indexing failed.

## Accepted scope

- `searchable_generation` is the served generation. Publication keeps it; only `markReady` of the current content generation replaces it.
- Pending and failed state of a new generation records `search_error_code` without withdrawing a different served generation. Projection repair of the served generation itself still withdraws readiness.
- Served-generation checks (`isCurrent`, `currentGenerations` with an identity) compare against `searchable_generation`, so every Search/Chat reader follows the switch without API changes.
- Source `ACCESS` refreshes target the served generation, and index metadata for that generation is still readable while a replacement is pending.
- Cleanup retains the served and current content generations. After readiness commits, the worker deletes the Document's other chunks best effort; the bounded stale sweep remains the fallback.

## Excluded

No migration, no API or UI change, no model-space migration. Source item `searchStatus` still reports the current content generation (INDEXING/FAILED) while the previous generation is served. PostgreSQL chunk rows remain current-generation only; no request path reads them, since passages and expansion come from OpenSearch. While a rewrite is pending, reconciliation verifies only the content generation, so a physical index loss during that window leaves the served generation without hits until the replacement becomes ready.

## Verification boundary

Real PostgreSQL proves the state transitions through claim, failure, retry, access enqueue and switch; real OpenSearch proves both cleanup paths retain and then remove the right generations. Staging verification is limited to reindexing an already ready Document and observing Search during indexing.
