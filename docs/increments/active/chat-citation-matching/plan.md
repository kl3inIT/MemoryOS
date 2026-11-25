# Plan: Chat citations draw the passage that matched

Design: [design.md](design.md). Issue [#954](https://github.com/kl3inIT/MemoryOS/issues/954). One pull request.

## Steps

1. **Core.** `ChatSource.matchingOrdinal` (nullable, Document only, inside the range); `SearchTool` records the
   anchor of the section each window holds. Tests: `SearchToolTest` (window cites its own section's match),
   `ChatSourceLegacyJsonTest` (old rows read null; bounds).
2. **API.** `ChatSourceResponse.matchingOrdinal`; refresh `openapi.yml` with
   `MEMORYOS_OPENAPI_WRITE=true :memoryos-api:test --tests '*OpenApiContractTest*'`, then
   `pnpm --dir web generate:api`.
3. **Web.** Schema, `citationSelection`, `EvidenceViewSwitch` sections. Test:
   `chat-citation-selection.test.ts` (window 3–7 matching 5 draws one box; a saved source draws the range).
4. **Regression test.** Fixtures under `web/src/features/chat/sources/citation-fixtures/`, chunk texts from
   `CitationFixtureChunksTest`, `citation-locating.test.tsx` with per-fixture floors; check it fails on main's
   selection and with #953 reverted.
5. **Docs.** `docs/specs/chat.md`, `docs/specs/search.md`, `docs/tests/chat.md`, `docs/tests/document.md`,
   roadmap.
6. **Gates.** Touched modules' tests locally (Docker-only tests run in CI), `pnpm --dir web check`; CI runs
   `./gradlew clean check`.

## Acceptance

CI green; on staging the 2-page text PDF of #954 cited in Chat tints only the matched passage's blocks. Review
before merge; the owner merges.
