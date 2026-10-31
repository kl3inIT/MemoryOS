# One AI Providers page

Frontend only. The administration menu listed five pages that each configure a connection to an external AI service: Models, System One, Web search, Voice and Image generation. With them the menu held about 20 links and scrolled on a laptop ([MEM-221 L2-3a](../../active/mem-221-list-page-patterns/design.md)); folding its sections was tried and removed on the owner's review (2026-10-10). The owner chose to merge the five into one page instead (2026-10-10).

## Requirement and design

- **One menu row.** *AI Providers* (*AI Provider* in Vietnamese, the English term kept on the owner's review, 2026-10-11) in the Configuration section replaces the five rows. The section keeps Code Interpreter and Chat, which configure behaviour and hold no provider. Search settings stays under Documents & Knowledge: its embedding provider is bound to the index generations on the same page.
- **One page, five tabs.** `/admin/ai-providers` shows one `PageHeader` and a line `Tabs` row: Models, System One (not *Classification (System One)*, owner's review 2026-10-11), Web search, Voice, Image generation. The tab is the `tab` search parameter, validated by the route and left out for Models, as the MemoryOS MCP page does ([shell, routes and runtime](../../../conventions.md#frontend-shell-routes-and-runtime)).
- **Tab content.** Each former page keeps its component, queries and sections and loses its own layout and header; the page loads a tab's code when the tab opens. Every tab, Models included, shares one frame at the standard width, centred, so the header and tabs never move when the tab changes, as BeyondPilot keeps one frame. A wide page over standard-width tabs left the content stuck to the left; a width per tab moved the header between tabs (owner's review, 2026-10-11). The model catalog fits: its lists open in dialogs.
- **Models action.** *Refresh catalog* moves from the page header to the Connections heading of the Models tab, beside what it reloads, where it does not take a row of its own; when no connection loaded, it sits in the catalog failure.
- **Old addresses.** `/admin/models`, `/admin/system-one`, `/admin/web-search`, `/admin/voice` and `/admin/image-generation` redirect to their tab, so saved links and the deployed product's bookmarks keep working. Links inside the app point at the new address.
- **Access.** Unchanged: the row and every tab need `MODELS_MANAGE`, as the five pages did. The administration entry lands on AI Providers where it landed on Models.
- **Phone.** The tab row scrolls sideways and brings the open tab into view.

## Reference

BeyondPilot's Admin › AI › Providers page: one menu row, a tab per purpose (Chat, Embedding, OCR), each tab an address of its own.

## Reuse

`Tabs` (line variant), `PageHeader`, `SettingsLayout`, `validateSearch` with `stripSearchParams`, `mayWarmAdminPage`. No new composite. The page lives in `components/app-shell` because it composes several capabilities' parts ([feature folders](../../../conventions.md#frontend-feature-folders)).

## Left out

- The per-page descriptions under the old titles: the tab names say what each tab holds.
- An Anthropic adapter, an OCR tab and an external OCR provider, which BeyondPilot's page has. They are backend capabilities, not part of merging the menu.
