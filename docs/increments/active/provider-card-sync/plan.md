# Plan

## 1. Shared card

- [x] Install the shadcn `Item` and adapt it to MemoryOS tokens.
- [x] `ProviderCard` and the shared brand tile.
- [x] OpenRouter and Ollama brand marks.
- [x] Unit test `provider-card.test.tsx`.

## 2. Pages

- [x] Models: "Add Provider" grid uses `ProviderCard`; connection rows reuse the tile and infer OpenRouter, 9Router and Ollama marks from the base URL.
- [x] Web search: wide layout, grouped two-column grid, crawler and provider-hosted search on `ProviderCard`, token headings.

## 3. Verification and documents

- [x] `pnpm check` in `web/` (51 test files, 279 tests, build).
- [x] Playwright `chat-ui-polish` Web settings and `models-administration` scenarios.
- [x] Browser review of both pages at 1280, 1024 and 390 px, light and dark. It found and fixed an OpenAI mark on OpenRouter/Claude connections, taller connection rows, and names squeezed by actions at narrow widths.
- [ ] Consolidate durable facts, move the increment to `completed/` after merge.
