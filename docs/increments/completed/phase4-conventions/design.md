# Phase 4 — conventions and API contract hygiene

## Requirement

Owner decision 2026-09-25 (group 4 of [owner-decisions.md](../../active/audit-quality-fixes/owner-decisions.md) plus the listed follow-ups); best practice even where contracts change; one pull request.

## Decisions

- **Imports and logging.** No inline fully qualified class names in handwritten Java (conventions §Java). Every log line uses SLF4J fluent key/values with a stable `event` and, for failures, `error_type`/`error_code`, never payloads (observability guideline).
- **HTTP responses.** Spring Security's default header writer already sends `Cache-Control: no-cache, no-store…` and `X-Content-Type-Options: nosniff`; hand-written copies are removed, keeping only deliberate overrides (verified against real responses). Transport records live one per file under the owning `contract/` package.
- **Problem contract.** `ApiProblem` declares the extension members handlers actually send (`scope`, `group`, `resetsAt`, `retryAfterSeconds`, `usedBy`…) instead of `additionalProperties: false`; the web reads them typed. Common OpenAPI boilerplate (problem responses, the hidden authenticated principal) comes from one springdoc customizer and one meta-annotation.
- **Typed failures.** Chat failure codes travel as typed exceptions matched by type, not as `IllegalStateException` message strings matched against an allowlist.
- **Ownership follow-ups.** An identity-owned people-and-Group search replaces `/api/chat/persona-share-options` for the web principal picker; the Google Drive account OAuth client and sign-in admission decisions move from `api` into their capabilities; branch and meeting-publish endpoints get API tests. Owner decision 2026-09-25: the people-and-Group search is open to every active member of the Tenant, independent of `CHAT_WRITE`, because meetings share through the same picker.
- **Operations and docs.** `deploy.sh` reports the failing line and command through an ERR trap; delivered increments move to `completed/` and the roadmap is reconciled.

## Verification

Contract changes regenerate `openapi.yml` and the web client through the repository mechanism; behaviour-preserving sweeps rely on existing tests plus the architecture tests; the full gate runs in CI.
