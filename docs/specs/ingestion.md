# Ingestion capability contract

Ingestion owns asynchronous indexing and cleanup orchestration. It depends only on public Connector, Document, and Tenant APIs. Provider parsing enters through `SourceContentExtractor`; no Tika type crosses the core boundary.

The worker claims NOT_STARTED or expired IN_PROGRESS work in bounded batches with a fresh random claim token and two-minute lease. Every durable index and cleanup record carries an explicit `TenantId`, and coordinator/repository operations retain that identifier in tenant-owned predicates without ambient context. Extraction occurs outside database transactions. Final publication runs in one transaction and requires the current token, active Tenant, non-deleting Pair/item, and current item version. Stale or obsolete completion cannot publish.

PostgreSQL attempt/cleanup rows remain both execution authority and current dispatch source. The worker now maintains ingestion/cleanup Redis Stream consumer-group topology through one cluster-safe db-scheduler recurring task, but no business operation is published to or consumed from Redis yet; the direct bounded PostgreSQL claim loop remains the only business executor. Relay (MEM-43), consumers (MEM-44), and epoch-fenced drain/removal (MEM-51) form one non-separable production cutover so no merged state accumulates unused delivery, introduces dual execution, or retains a disabled compatibility mode.

The FILE adapter uses Apache Tika 4.0.0 minimal PDF, Microsoft, text, and core modules. It detects actual content, supports PDF/DOCX/UTF-8 TXT/Markdown, disables OCR and embedded recursion, limits output to 2,000,000 characters, and returns typed unsupported/encrypted/malformed/timeout/write-limit/internal failures. Each extraction runs in a bounded child JVM; timeout or shutdown forcibly terminates that process so malformed parser work cannot survive a terminal result.

Cleanup is allowed after Tenant deactivation but cannot create content or access. REMOVE_ITEM and DELETE_SOURCE remove provenance first, then attempts/bytes/items/Pair/Connector in dependency order, and delete only unreferenced Documents.
