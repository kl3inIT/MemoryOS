# MEM-215 The remaining minor UI findings

Last fix batch of the 2026-10-02 UI critique; frontend and documentation only.

## Requirement and design

1. **M-1 Search headings and filter label.** The result heading ("11 kết quả cho “…”", `search-results.tsx`) is `font-main-ui-action`, 14px like body text, so the page has no visible hierarchy below the 14px shell title. It becomes `font-heading-h3`. The filter row opens with "Bộ lọc" and a sliders icon in a 32 px box that reads as a control but is static text (`search-box.tsx`); the label is removed and the row becomes a `role="group"` named *Filters*, whose menus already say what they filter.
2. **M-2 Duplicate page titles and an empty usage line.** In the application area the shell header shows a page's title at every width, so the four pages that also open with a `PageHeader` (Agents, Library, the Meetings list, a meeting's loading and error states) repeat it. Administration and settings already hide the shell header from `md` up. `AppShellHeader` takes `pageHeader`; such a page's shell header steps aside from `md` up the same way (`md:has-data-page-header:hidden`), and those pages drop their `md:pt-8` so they start where administration pages do. The Library's usage line ("0 B · Không giới hạn" and *Xem tệp lớn nhất*) shows only once the person has a file (`fileCount > 0`).
3. **M-6 The administration sidebar hides the open page.** At 1440×900 with every capability the menu is 1010 px in a 739 px area; on `/admin/audit` the open page's own link sits below the fold, and the resting scrollbar is invisible. On every navigation the sidebar scrolls the link of the open page into view (`scrollIntoView({ block: "nearest" })`), as editors' file trees do; nothing moves when it is already visible.
4. **M-7 Disabled by opacity.** `formField` (`lib/action-errors.ts`), used by the agent editor's text areas, draws disabled as `opacity-60`, against the [interaction contract](../../../conventions.md#frontend-interaction-contracts). It takes `Input`'s disabled tokens: `surface-sunken` fill, transparent border, `content-disabled` text, `not-allowed` cursor.
5. **M-8 Control heights in the conventions.** `docs/conventions.md` says controls share a 32/40/44 px scale; the tokens are 28/36/40 px (`--control-height-sm|md|lg`), and coarse pointers grow every control to 44 px (`base.css`). The sentence states the tokens.
6. **M-9 The CSV current row's side stripe.** The current cited row draws a 4 px `pdf-highlight` stripe on its pinned cell (`TableCell pinned`), a side stripe the critique flags. The row keeps its full-strength `evidence-highlight-surface` and gains the cited passage's own outline, `evidence-highlight-border` above and below, as text previews outline a cited passage; the stripe is removed.
7. **A-3 Two search behaviours.** The Users search applies only when *Tìm kiếm* is pressed, while the Sources and Groups searches apply as the person types. The Users search follows Groups: the draft is applied 250 ms after typing pauses (`useDebouncedValue`), replacing the history entry and resetting the page; Enter still applies at once; the *Tìm kiếm* button is removed.

## Reuse

`PageHeader`, `SettingsLayout`, the administration header's `md:hidden`; `useDebouncedValue` and the Groups page's settled-draft pattern; `Input`'s disabled tokens; the evidence highlight tokens.

## Out of scope

"Tenant" in UI copy (awaiting an owner decision); keyboard shortcuts and bulk selection (A-5, a roadmap candidate).
