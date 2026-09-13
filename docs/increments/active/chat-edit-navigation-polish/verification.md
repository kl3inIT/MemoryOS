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

## Dictation withdrawn and draft restore — 2026-09-13

- Dictation was not implemented: the Web Speech adapter sends audio to the browser vendor's recognition service. The owner deferred it; no existing Linear issue covered it, so MEM-91 was created in Backlog.
- Draft restore: typecheck, lint, format and i18n audit pass; chat unit tests 71/71. The new `chat-workspace.spec.ts` scenario types a question, reloads, finds it restored, sends it, reloads again and finds the composer empty.
- Playwright, one worker: `chat-workspace`, `chat` and `chat-history-search` passed 47/48. The failure was `chat.spec.ts` "grounds prose citations…" missing the transient "Đang tìm trong tài liệu…" status within 5 s; it passed 2/2 on an immediate repeat and does not touch the composer, so it is recorded as a timing flake, not fixed.

## Quote and image preview decision — 2026-09-13

- Typecheck, lint, format and i18n audit pass; chat unit tests 72/72. `chat-transport.test.ts` checks that a composer quote is sent as `> First line\n> Second\n\nQuestion`.
- The new `chat-workspace.spec.ts` scenario selects an answer paragraph, quotes it, sees the composer preview, sends a question, sees the quote block, reads the saved question text from the fixture history, reloads and still sees the quote block and question. Its first run failed only because the reloaded question text shared one element with the quote; the remainder is now its own span.
- Playwright, one worker: `chat-workspace`, `chat` and `chat-history-search` passed 49/49.
- Image thumbnails were not built by owner decision (see design); no code change.

## Owner review follow-up: duration and link styling — 2026-09-13

- The owner found the reply duration ("· 1 giây") noisy; the answer bar now shows only the time on hover, and `finishedAt` is no longer carried into UI metadata. External Markdown links in answers reuse the MarkdownText `aui-md-a` styling instead of a plain underline.
- Typecheck, lint, format and i18n audit pass; chat unit tests 72/72. Playwright re-ran the model regeneration/timing scenario (time now matches `HH:MM`), the grounded citation scenario and the Shiki/Mermaid renderer scenarios.
- Links then moved to the Sources chip shape at the owner's request (see design). `sources.test.tsx` checks the fallback-only icon; the regeneration scenario checks the fixture's `Reference` link is a `data-slot="source"` chip with letter fallback and no favicon image.

## Composer `+` menu — 2026-09-13

- Ready recent files no longer show "Sẵn sàng" (`chat-file-reader.test.tsx` asserts its absence), committed separately.
- `+` menu: typecheck, lint, format and i18n audit pass; chat and assistant-ui element unit tests 86/86. `chat-web.test.tsx` now drives `ChatWebToggle` (toggle and options entry) and `ChatWebModes` (disabled unsupported modes, native search without a connection, adapter-declared required mode).
- `chat-ui-polish.spec.ts` checks the toolbar order `+` → model picker → Send, the three root rows, choosing automatic Web from the options view and the resulting "Tắt Web" chip at 1440/390px.
- Playwright, one worker: `chat-ui-polish`, `chat-workspace` and `chat` passed 50/50, including the unchanged mobile model picker and compact question editor (which keeps its paperclip file picker).

## Recent-file status icons — 2026-09-13

- Owner asked for icons instead of status text. `chat-file-reader.test.tsx` checks labelled status images for processing, failed and not-searchable files, no failure text in the compact list, and failure text in the full dialog. The change applies everywhere `ChatFilePicker` is used (composer `+` menu, question editor, assistants, projects).
- Typecheck, lint, format, i18n audit and chat unit tests 73/73 pass; Playwright `chat-ui-polish` and `chat-workspace` passed 24/24 with one worker.
