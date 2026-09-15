# Plan

## 1. Shared card

- [x] Install the shadcn `Item` and adapt it to MemoryOS tokens.
- [x] `ProviderCard` and the shared brand tile.
- [x] OpenRouter and Ollama brand marks.
- [x] Unit test `provider-card.test.tsx`.

## 2. Pages

- [x] Models: "Add Provider" grid uses `ProviderCard`; connection rows reuse the tile and infer OpenRouter, 9Router and Ollama marks from the base URL.
- [x] Web search: original single-column layout and sections; search, crawler and provider-hosted search rows on `ProviderCard` (two-column grouped variant reviewed and rejected by the owner on 2026-09-15).

## 3. Verification and documents

- [x] `pnpm check` in `web/` (51 test files, 279 tests, build).
- [x] Playwright `chat-ui-polish` Web settings and `models-administration` scenarios.
- [x] Browser review of both pages at 1280, 1024 and 390 px, light and dark. It found and fixed an OpenAI mark on OpenRouter/Claude connections, taller connection rows, and names squeezed by actions at narrow widths.
- [x] Merged in PR #190 (reviewed head `3fdb59c`, merge `62e9585`); increment moved to `completed/` and the roadmap reconciled on 2026-09-15.
