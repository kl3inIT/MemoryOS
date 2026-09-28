# 17. The shared kernel also holds domain-free technical utilities

Date: 2026-09-25

## Status

Accepted; implemented 2026-09-25 in phase 3 ([`docs/increments/completed/phase3-dedup-hot-paths`](../increments/completed/phase3-dedup-hot-paths/design.md)). Supersedes the consequence in [ADR 0015](0015-capability-module-map.md) that `shared` "must not grow into a common utilities package"; the rest of ADR 0015's shared-kernel section stands.

## Context

ADR 0015 created the closed `shared` module for identifiers carried by several modules (`TenantId`, `ActorId`) and ruled out utilities. The 2026-09-24 audit then found the same technical code copied across modules that may not depend on each other: SHA-256 hex digests in about nine places, LIKE-pattern escaping in five, the PDF text engine (font loading, glyph-safe text, wrapping) in three renderers across `meeting` and `usage`, and the leased claim → run → fail loop in five background jobs across `meeting`, `chat`, `library` and `usage`. The copies had already drifted (the usage report never broke a word longer than a line). A home reachable from every module without a new dependency edge or a cycle is needed; the owner chose `shared`.

## Decision

`shared` holds two kinds of type:

1. Identifiers and values carried by several modules (unchanged from ADR 0015).
2. Technical utilities with no domain meaning, no Spring beans and no dependency on any module: currently `Sha256`, `LikePattern`, `PdfText` and `LeasedJob`.

A utility enters `shared` only when at least two modules already need it and it knows nothing about any capability's rules, tables or vocabulary. Anything with business meaning, persistence, configuration or beans stays in its owning module. `shared` stays a closed module with no allowed dependencies; library dependencies (such as PDFBox) are acceptable.

## Consequences

- The copies collapse into one implementation each; later call sites (the remaining SHA-256 copies in `document`, `retrieval`, `connector` and `api/source`) move to `Sha256` as they are touched.
- Review must keep the entry rule: a helper used by one module stays in that module, and nothing domain-shaped enters `shared`.
