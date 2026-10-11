# Implementation plan

[Design](design.md). Each step ends green on its narrowest tests; the backend gate is `clean check`, the web gate
`pnpm --dir web check`, both run in CI.

## 1. Fixtures and the gateway port

- [ ] Recorded responses of the test tenant under `sources/src/test/resources/lark/` (spaces, nodes, docx blocks,
  folder listing, collaborators, link settings, contact users, failure bodies), app and user identifiers kept,
  no token or secret.
- [ ] `LarkGateway` and its records, `LarkProviderException` in `core` root.

## 2. Transport and gateway (`sources`)

- [ ] `oapi-sdk` in the version catalog; `LarkTransport` with the four transport rules.
- [ ] `RestLarkGateway`: tenant token, bot info, scope probe, spaces, nodes, folder files, docx blocks, sheet values,
  file download, collaborators, link setting, contact users, chat/department/space members; failure mapping.
- [ ] `LarkProviderProperties`, metrics, auto-configuration; `RestLarkGatewayTest` on a local HTTP server serving
  the fixtures.

## 3. Credential

- [ ] Migration: `LARK_APP` kind and statuses, `lark_credentials`.
- [ ] `LarkCredentialService` (create, replace secret, rename, delete, list, test) with audit.
- [ ] API controller, contract, OpenAPI and web client regenerated.
- [ ] Web credential section with the scope guide.

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
