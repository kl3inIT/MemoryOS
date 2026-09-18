# Verification

## Web gates (2026-09-16, branch head `b3e611ce`)

Run in `web`:

| Gate | Result |
|---|---|
| `pnpm exec oxfmt --write` on the changed files | clean |
| `pnpm -s typecheck` | pass |
| `pnpm exec oxlint --deny-warnings .` | pass |
| `pnpm -s check:i18n` | 0 direct UI literals, 0 missing or unused static keys |
| `pnpm exec vitest run src/features/sources` | 16 files, 69 tests, 0 failed |

`pnpm check` — the repository-wide web gate, which adds the contract drift check, the
route check and the production build — last ran green before the empty-state and
connector-setup commits.

## Sources end-to-end (2026-09-16, branch head `b3e611ce`)

`pnpm exec playwright test tests/e2e/google-drive-enterprise.spec.ts
tests/e2e/source-action-feedback.spec.ts tests/e2e/google-drive-source-setup.spec.ts
tests/e2e/file-source-setup.spec.ts tests/e2e/identity-shell.spec.ts --workers=1`:
**46 passed** in 5.5m.

These suites carry the redesign's behaviour, not only its markup:

- `google-drive-source-setup`: the credential step's actions, the OAuth JSON dialog,
  revoke and reconnect confirmations, and that a member cannot reach setup.
- `google-drive-enterprise`: the selection tree's paging and kept branches, hidden
  approvals across search, unavailable items, failed branches, and the credentials
  disclosure following `data-state` instead of `group-open`.
- `file-source-setup`: the single-step FILE flow and scoped creation requiring managed
  groups.
- `source-action-feedback`: reindex, removal and deletion feedback, including the
  notices that must not claim completion.
- `identity-shell`: the Sources shell, the empty state's `No sources yet` heading and
  the admin routes.

## Backend tests behind the two contract changes

- `PostgresSourceRunHistoryTest` (core): the run history filters on a set of statuses,
  including a combined `SUCCEEDED, INDEXING` query, and an empty set lists every run.
  The sorted status set is part of the cursor scope, so a cursor cannot page a
  different filter.
- `SourceApiIntegrationTest` (api): `GET /api/sources/{sourceId}/runs?status=FAILED&status=SUCCEEDED`
  returns both, and the Drive schedule defaults to 30 minutes.
- `OpenApiContractTest`: the committed `openapi.yml` matches springdoc after `status`
  became a repeatable query parameter.

## Screens and their references

Captures are QA artefacts, so they stay in ignored scratch storage and are attached to
[MEM-106](https://linear.app/memory-os/issue/MEM-106) beside their Mobbin references,
as [conventions](../../../conventions.md) requires. This table records which screen
follows which reference.

| Screen | Reference | Captured |
|---|---|---|
| Sources list, grouped by provider | WRITER Connectors, Twingate Connectors | pending |
| Sources list, empty | Fabric "Let's add our first connection" | pending |
| Source type picker | Customer.io Integrations | pending |
| FILE creation | Mistral AI Upload Documents, Retool Upload file | pending |
| Drive credential step | Vanta Connect AWS, Databricks Connect data source | pending |
| Drive connector step (Connection, Source settings, Content) | Lindy Connections, folk Accounts, Coda | pending |
| Drive selection tree | Databricks Catalog Explorer | pending |
| Source detail, Drive | Customer.io Google Sheets, Sana AI integration | pending |
| Summary card | Customer.io integration details, Render manual sync | pending |
| Sync history | Stripe filter chips | pending |
| Run details Sheet | StackAI Run Details | pending |
| Files tab | Sana AI document table | pending |
| Indexing history and attempt Sheet | GitBook GitHub Sync | pending |

Each screen is captured at desktop width and 390px through the browser inside Orca.
`orca screenshot` serves only the active tab, so each capture calls
`orca tab switch --page <id>` first; without it the command answers
`runtime_unavailable`. The local app is reached at `http://127.0.0.1:8080`, not
`localhost`, because the Keycloak client registers the `127.0.0.1` redirect URI.

## Open

- Captures for the table above.
- Migration numbers: this branch adds V61–V64 while main has since added its own V61 and
  V62; the Sources migrations need renumbering when main merges.
- Status tabs with counts on the Files tab need a status filter and per-status counts on
  `GET /api/sources/{sourceId}/items`, which pages by cursor only.
- Warning and error notices still use token-styled blocks; `ui/alert` has no warning
  variant and adding one changes how the existing Alerts look.
