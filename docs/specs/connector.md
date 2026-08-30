# Connector capability contract

## Ownership

Connector owns Tenant-scoped Connector, NO_AUTH Credential, ConnectorCredentialPair, ConnectorItem/version, index/cleanup operation association, and Pair-to-Document provenance. The public product read model is Source; `sourceId` is the operational Pair identifier and never exposes persistence association names.

FILE is the only implemented provider. One active Tenant OWNER creates and manages it. Creation serializes on the Tenant row, creates or reuses exactly one NO_AUTH Credential, and creates one PUBLIC Pair. Members cannot manage Sources.

## Application and persistence boundary

`DefaultSourceManagementService` owns authorization, validation, orchestration, transaction boundaries, transition decisions, and typed failures. It injects concrete `JdbcSourceRepository`, `JdbcSourceItemRepository`, `JdbcIndexAttemptRepository`, `JdbcSourceDocumentRepository`, and `JdbcSourceQueryRepository`. Application code contains no SQL, row mapping, locks, claims, or bulk updates; single internal JDBC implementations have no repository interfaces.

## File and operation lifecycle

One upload contains 1 byte–10 MiB, normalizes the display filename, computes lowercase SHA-256 before persistence, stores immutable bytes in PostgreSQL, and creates or resolves one Pair-specific NOT_STARTED index attempt. Duplicate bytes in one FILE Pair converge on one item/version/live attempt. Detection and extraction never run in the API transaction.

Reindex is idempotent while work is nonterminal. Item removal and source deletion are explicit POST commands that immediately invalidate retrieval mappings and return durable cleanup operations. Cleanup results remain queryable after target deletion; `SUCCEEDED` and `SUPERSEDED` are terminal success states.

## Access

`SourceDocumentAccessResolver` grants PUBLIC read clearance only when the actor has current active Tenant membership and a live retrieval-eligible mapping whose Pair, Connector, and Document are active/eligible. Source-management authority does not imply access, and inactive/deleting state fails closed.
