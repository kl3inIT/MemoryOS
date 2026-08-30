# Document capability contract

Document owns normalized durable knowledge independently of provider implementation. A Document is Tenant-scoped and either ELIGIBLE or INELIGIBLE. DocumentVersion is immutable and records bounded normalized text, safe title, detected media type, source-content SHA-256, and metadata.

`DocumentCommandService.publish` creates a Document/version or appends a new content-hash version to the currently mapped Document. Pair serialization and database uniqueness make publication deterministic. Connector owns provenance mappings; Document never imports Connector or Ingestion types.

When Connector removes provenance, `DocumentCommandService.removeUnreferenced` hard-deletes Document/version content only after no live mapping remains. Current-version foreign keys are cleared before version deletion. MEM-35 exposes no retrieval HTTP endpoint, embeddings, chunks, or citations.
