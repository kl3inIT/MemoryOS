# Verification — 2026-09-13

## Edit and navigation

- 9 focused unit tests passed across EditMessage and chat attachments: keyboard/IME, focus, pending/busy, safe errors, attachment-only messages and native attachment readiness/preview lifecycle.
- Chromium compact editing passed at 1440 and 390 pixels: one-line height, bounded autosizing, no resize handle or horizontal overflow, aligned attachment/Cancel/Save footer, Escape and successful edit preserving the old branch.
- Mobile navigation passed using New conversation then the existing localized Chat/Search selector; the sidebar contains no duplicate document Search link. Leaving a running conversation still only closes its reader, not server execution.
- Screenshots inspected: output/playwright/edit-question-1440.png and edit-question-390.png.

## Sources follow-up

- 4 sources.test.tsx tests passed: at most three distinct source icons, one generic localized label, accessible citation count, document sources do not request favicons, and upstream fallback resets for a new domain.
- Final chat-sources-toolbar.spec.ts run: 2/2 Chromium tests passed, desktop 1440 and mobile 390, in 20.6s. Mixed document/Web icons stay beside Copy; the correct Web evidence opens, the original URL is retained, panel closes and focus returns; no horizontal overflow. Favicon requests are deliberately aborted to exercise the letter fallback without external network dependence.
- Existing grounds-prose-citations browser test passed: document passage selection, reload, panel toggle and source-denial behavior remain unchanged.
- Earlier new-test failures came from matching text including decorative fallback letters, the mobile dialog's changing accessible title and an unscoped desktop aside locator. Final locators match the actual accessible surfaces; no runtime behavior was weakened for these tests.
- Screenshots inspected: output/playwright/sources-toolbar-1440.png and sources-toolbar-390.png.
- Typecheck, lint, i18n audit, production build and scoped diff whitespace checks passed. Build retains existing large-chunk notices.

## Actions, provider sections, conversation finder and source references

- Inspected Onyx snapshot 40eb240df (2026-09-10), specifically ToolsPopover/ToolLineItem, WebSearchPage, ChatSearchCommandMenu/sidebar hooks and MessageToolbar. This is a source/supplied-image comparison, not acceptance of a running Onyx instance. Exact reuse and deliberate differences are recorded in design.md.
- Final focused unit run: **21 passed**, 5 files: chat-web (5), chat-conversation-matches (3), sources (4), edit-message (2), chat-attachments (7). Includes collapsed Web results with real links, capability-aware action selection, Unicode/literal matching without tool payloads, 500-hit bound and local-calendar title grouping.
- Final Chromium run: **12 passed in 40.2s**. New Actions/search/settings surfaces and mixed Sources panel at 1440/390px; loaded-title sidebar filter; existing compact edit desktop/mobile, server-ID promotion/switch, authorized citation range/history/denial, mobile Chat/Search navigation and reader lifecycle.
- Settings tests verify Exa entered once in Search then reused in Crawler: REPLACE followed by KEEP, stored key absent from the input, six search-provider rows plus three external readers and the built-in reader. All requests use fixture data and do not contact paid providers.
- Finder tests verify real current-branch messages, next match via Enter, empty-result disabled controls, selected-message marker and Escape/focus restoration. Opening its header surface shares the single existing AssistantRuntimeProvider. An intermediate duplicate-provider implementation broke send visibility; it was removed, and the send/promotion/stream regressions above passed on the corrected implementation. An ambiguous action/back button accessible name was also corrected.
- Screenshots inspected: output/playwright/actions-390.png, conversation-find-390.png, web-settings-search-1440.png, web-settings-reader-390.png, source-reference-list-1440.png and source-web-reader-390.png. The tests also emit the opposite-width variants. Internal-source unavailability in the mixed-source screenshot is an explicit fixture denial; the separate authorized document test passes.
- Final typecheck, lint, i18n audit (0 findings), production build/font verification and scoped tracked-file diff whitespace checks passed. The build still emits the existing >500 kB chunk-size notice.
- No full-history server search, full AUI-connected thread-list migration, Image element or native hosted Web adapter is claimed. No backend/OCR changes, commit, PR, push, deployment or Linear mutation were performed for this follow-up.

### Shared verification boundary

Browser checks use the real local frontend with fixture backend/model responses, not staging acceptance. Favicon failure is tested; live favicon availability is not guaranteed. No paid provider probes, backend/OCR changes, commit, PR, push or deployment in this slice. The Sources change does not claim Image-element integration, all-history conversation search or native Web adapters; remaining audit findings are in design.md and the Web increment.

## Regenerate with another model and answer timing — 2026-09-13

- Typecheck, lint, format and i18n audit pass; unit tests 40 files / 216 tests pass. `chat-transport.test.ts` asserts the live `createdAt` on the start chunk, terminal `finishedAt`, and `toUiMessages` timestamps.
- Playwright (synthetic fixture, one worker): the new `chat-workspace.spec.ts` scenario opens the menu, regenerates with Qwen3.5 9B, sees version 2 / 2, confirms the fixture recorded that model, and checks the composer still shows GPT-5 mini. Timing is transparent until the answer is hovered, then reads `HH:MM · N giây`. A desktop screenshot of the open menu was reviewed. Full suites afterwards: `chat-workspace.spec.ts` 16/16 and `chat.spec.ts` 28/28 passed.
- No live provider run. On touch screens the timing is not revealed (no hover); the full date remains in the tooltip for pointer users.
