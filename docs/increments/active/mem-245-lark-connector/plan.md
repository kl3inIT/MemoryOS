# Implementation plan

[Design](design.md). Each step ends green on its narrowest tests; the backend gate is `clean check`, the web gate
`pnpm --dir web check`, both run in CI.

## 1. Fixtures and the gateway port

- [x] Recorded answers of the test tenant under `sources/src/test/resources/lark/` (spaces, nodes, Docx blocks,
  folder listing, sheet, collaborators, link setting, contact users, refusals); names and emails replaced, no token
  or secret.
- [x] `LarkGateway` and its records, `LarkProviderException` in `core` root.

## 2. Transport and gateway (`sources`)

- [x] `oapi-sdk` 2.8.5 in the version catalog; `LarkTransport` with the four transport rules, its budget passed
  through a `ScopedValue` because the SDK's request carries no options.
- [x] `RestLarkGateway`: its own tenant token per session (the SDK's token cache is process-wide and keyed by App ID
  alone, so it is switched off), bot info, scope probe, spaces, nodes, folder and files, Docx and Sheet snapshots,
  file download, collaborators with the link setting, users by `open_id`, chat, department and space members;
  failure mapping.
- [x] `LarkProviderProperties`, metrics, auto-configuration; `RestLarkGatewayTest` (22 tests) on a local HTTP server
  serving the fixtures. Run with `javac` and the JUnit console on 2026-10-12 because the workstation had no memory
  for a Gradle daemon; the Gradle gate is left to CI. The gateway was also run against the test tenant itself.

## 3. Credential

- [x] Migration `V152`: `LARK_APP` kind and statuses, `lark_credentials` (V149 to V151 are OCR work merged first
  on another branch; whichever merges second renumbers).
- [x] `LarkCredentialService` (create, replace secret, rename, delete, list, test) with audit; the read scopes
  Lark refused at the last verification are kept on the credential, so the list names them without a new test.
- [x] API controller and contract; `openapi.yml` extended by hand from the SharePoint entries and the web client
  generated from it (`OpenApiContractTest` runs in CI).
- [ ] Web credential section with the scope guide (with the setup page of step 4).

## 4. Selection and Source

- [ ] Migration: `SourceType.LARK` in every CHECK and SQL switch, `lark_sources`, `lark_roots`,
  `lark_selection_details`, `lark_selection_entries`, `VALIDATE_LARK_SELECTION`.
- [ ] `LarkSourceService`, `LarkSelectionAdapter`; API and web setup page and panel.

## 5. Synchronization

- [ ] Migration: `lark_items`, `lark_sync_runs`, input formats `LARK_DOCX` and `LARK_SHEET`.
- [ ] `LarkSyncAdapter` (walk, checkpoint, acquire, prune, classify), registered for `SourceType.LARK`.
- [ ] Docx and Sheet extractors in the extractor router.
- [ ] Merge point: Sources sync with PUBLIC and PRIVATE access (`permissionSync=false`).

## 6. Permissions

- [ ] Migration: `lark_acl_snapshots`, directory generations and members.
- [ ] Snapshots on every run, `SourceAclChanged`; directory synchronizer.
- [ ] Lark parts of `SYNC_GRANTS` and `READER_TOKENS`; `permissionSync=true`; SYNC offered in the web.
- [ ] Tests with two readers: named collaborator, chat, department, space member, `tenant_readable` refused,
  `anyone_readable`, revoked share, unverified email.

## 7. Verification and consolidation

- [ ] Staging run on the test tenant: setup, sync, search and chat as two readers with different access; browser
  screenshots reviewed.
- [ ] `ARCHITECTURE.md`, `docs/specs/connector.md`, `docs/tests/connector.md`, setup runbook (scopes for the tenant
  token type); roadmap reconciled; MEM-118's Lark section points here.
