# MEM-106 — shadcn adoption and Sources redesign

**Goal:** every browser surface is built from shadcn/ui primitives, and every Sources screen — list, creation, detail, indexing, index and run detail, settings and credentials — is redesigned against a chosen enterprise reference. API contracts, authorization and sync behaviour stay as they are.

**Linear:** [MEM-106](https://linear.app/memory-os/issue/MEM-106). Builds on [MEM-105](../mem-105-source-access-modes/design.md), whose access modes the new screens must express.

## Why

The Sources area grew screen by screen. It now carries four hand-written tables, nine `<details>` disclosures, bare Radix `Dialog`/`Tabs`, a hand-rolled progress bar, native radio, checkbox and textarea controls, `title=` attributes in place of tooltips, and cards nested inside cards. The repository already declares shadcn (`web/components.json`, style `radix-nova`, Tailwind v4, lucide) but only a dozen primitives exist, so each screen re-invents the rest. The result reads as unfinished next to the products this tool competes with, and it is the part of the product the owner sees most.

## Current state (2026-09-14 survey)

- `web/src/components/ui` has: badge, button, card, command, confirm-dialog, empty, grid-pattern, help-popover, icon-button, input, menu-item, popover, select, separator, skeleton, status-badge, table-pagination, text-button, plus layout helpers.
- Missing: dialog, alert-dialog, sheet, tabs, table, dropdown-menu, tooltip, alert, progress, scroll-area, breadcrumb, radio-group, checkbox, switch, textarea, label, field, collapsible, toggle-group, pagination, and a Radix-backed select.
- `ui/select` wraps the native `<select>`; `ui/button` exposes its own `tone × prominence` API instead of shadcn variants.
- The Sources feature is about 10,600 lines across `web/src/features/sources`.

## Decisions

1. **Add the missing primitives through the shadcn CLI** and bind them to the existing tokens in `tokens.css`/`theme.css` rather than to shadcn's default palette. The design language of Chat and Search does not change.
2. **Keep the `Button` public API (`tone × prominence`) and rebuild its internals on the shadcn recipe.** Renaming the props would touch every feature for no user-visible gain; the exception and its reason belong in this document, as the acceptance criteria require.
3. **Replace `ui/select`** with the Radix-backed shadcn `Select`, keeping the current props so call sites move in one mechanical pass.
4. **One row-detail pattern.** A row that has more to say — an index attempt, a sync run — opens a right-hand `Sheet`, never a new page. The list stays in view.
5. **Processing state is filtered by tabs with counts**, not by expanding a disclosure. `<details>` disappears from the feature.
6. **Every error shows its translated code and the next action** (reindex, reconnect), instead of a bare message.

## Screen map and references

Each screen has one primary reference chosen after comparing several enterprise products on Mobbin; secondary references contribute details only.

| # | Screen | Primary reference | Secondary | shadcn |
|---|---|---|---|---|
| 1 | Sources list (`sources-page`) | Sana AI — Connected integrations | WRITER — Connectors (Status, Access columns) | DataTable, Input, DropdownMenu, Badge |
| 2 | Source type picker (`source-catalog-page`) | Steep — connect flow | Customer.io — Directory | Card, Button |
| 3 | Create FILE Source | Mistral AI — Upload Documents | Retool — Upload file | Field, Input, RadioGroup cards, Progress |
| 4a | Create Drive — credential step | n8n — Credentials | Tines — Credentials | RadioGroup cards, Badge, Dialog |
| 4b | Create Drive — scope step (`google-drive-selection-*`) | Relevance AI — Google Drive picker (tree left, selection right) | StackAI — checkbox tree; Air — selected count in the footer | Checkbox, Collapsible, ScrollArea, Input |
| 4c | Create Drive — access step | WRITER — Manage access | Klaviyo — Sync settings | RadioGroup cards, Alert |
| 4d | Create Drive — groups step (`source-group-picker`) | Jira — group chips | — | Command, Badge |
| 5 | Source detail header and overview | Customer.io — integration detail | Sana AI — right panel (Connected by, Status, Access) | Breadcrumb, Tabs, Card, DropdownMenu |
| 6 | Files tab | Mistral AI — Library URLs (status tabs with counts) | Braintrust — backfill progress line | Tabs, Table, Progress |
| 7 | Index attempts (`source-item-history`) | Relevance AI — Queues | HubSpot — import summary | Tabs, Table, Pagination |
| 8 | Index attempt and error detail (new) | OpenAI Platform — Batch detail | Hume AI — job timeline | Sheet, Badge, Alert |
| 9 | Sync run history (`source-run-history`) | n8n — Executions | Snowflake Copy History; Clay run history | Table, DropdownMenu, Badge |
| 10 | Sync run detail (new) | Databricks — Job run details | StackAI — Run details | Sheet, Tooltip |
| 11 | Repeated-failure and first-index notices | Teachable — alert | Contractbook — import banner | Alert, Progress |
| 12 | Settings tab (`google-drive-panel`) | Airtable — settings rows with Change | Klaviyo — Sync settings | Card, Switch, RadioGroup, AlertDialog |
| 13 | Credentials on the detail page | Coda — connected accounts | Supabase — authorized apps | Card, AlertDialog |
| 14 | Upload content on a FILE Source | Mistral AI — upload dialog | Retool — Upload file | Dialog, Progress |

## Scope

- `web/src/components/ui`: add the missing primitives, retire the native select, rebuild the button internals.
- `web/src/features/sources`: redesign the fourteen screens above.
- The rest of `web/src`: replace bare Radix usage, `<details>`, hand-written tables, native form controls and `title=` tooltips with the new primitives.
- Tests: unit tests per redesigned surface, and the Playwright specs that name the changed structures.

## Out of scope

- API, OpenAPI, authorization, sync and ingestion behaviour.
- New product capability. A screen that does not exist today (index attempt detail, sync run detail) presents data the API already returns.

## Quality bar

Every redesigned screen must hold at 390px, be operable by keyboard and screen reader, respect dark mode, and carry Vietnamese and English copy. Each screen is captured through Orca at desktop and mobile width and placed beside its reference in `verification.md`.

## Risks

- **Breadth.** The sweep touches every feature. It is staged: primitives first, then Sources screen by screen, then the rest of `web/src`, so each commit stays reviewable.
- **Token drift.** shadcn defaults would introduce a second palette. Every added component is re-pointed at the existing tokens in the same commit that adds it.
- **Test churn.** Structural changes break selectors. Tests are updated with the screen they cover, never in a separate sweep.
