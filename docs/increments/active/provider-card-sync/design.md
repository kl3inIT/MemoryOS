# Provider card sync

Linear: [MEM-108](https://linear.app/memory-os/issue/MEM-108)

## Context

The Models page and the Web search settings page both list connectable providers, but each drew its own card. Models used a two-column grouped grid of whole-card buttons with a bordered brand tile; Web search used a hand-built single-column `div` list with a bare logo, a bordered "Connect" button and raw Tailwind section headings. OpenRouter and Ollama showed Lucide glyphs instead of their brand marks. Voice (MEM-91) and image generation (MEM-96) will add more provider pickers.

Reference: Onyx `40eb240df` renders Web Search, Voice, Image Generation and Tracing with one `sections/admin/ProviderCard` (connect, set default, current default).

## Decisions

- One `ProviderCard` in `web/src/components/provider-logos/provider-card.tsx`, composed from the shadcn registry `Item` (`web/src/components/ui/item.tsx`, adapted to MemoryOS tokens: subtle border, `font-main-ui-*` type, shared focus ring). It owns the brand tile, name, one-line description, selected treatment and the trailing action slot. Pages keep their own behaviour.
- The card is not itself a button. Actions are `Button` controls, so every card stays keyboard reachable; Models' "Connect" keeps its `Connect {{name}}` accessible name.
- Web search keeps its single-column list, provider order and sections; only its cards change to `ProviderCard`, including the built-in crawler and provider-hosted search rows. The owner reviewed a two-column grouped variant on 2026-09-15 and kept the original layout.
- Web search keeps one active provider per capability ("In use", "Connected", "Set as Default", "Configure"). Models keeps "Available connections" with expandable model tables; only its logo tile is shared.
- Brand marks: OpenRouter and Ollama come from simple-icons (CC0) as monochrome marks. 9Router's mark already matches `public/favicon.svg` of `decolua/9router`. LiteLLM publishes no vector mark (its favicon is a platform emoji image), so it keeps a neutral glyph. OpenAI-compatible endpoints keep the plug glyph because they are not a brand.
- Vietnamese natural source keys on the Web search page are the existing translation contract (`vietnameseUi`) and stay unchanged.

## Out of scope

Backend, API, connection dialogs, provider editors and the Models "Available connections" rows.

## Verification

`provider-card.test.tsx` covers landmark, content, actions and the selected state. The existing Playwright `chat-ui-polish` Web settings scenario still locates each provider region and its "Connect"/"Configure" buttons. Both pages are checked in the browser at desktop and narrow widths in light and dark themes.
