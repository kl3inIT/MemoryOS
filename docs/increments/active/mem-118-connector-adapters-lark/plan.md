# Implementation plan

Depends on step 1 of [provider adapter registries](../provider-adapter-registries/plan.md) (the registry base shape
and naming). Nothing is implemented yet.

## 0. Scope

- [x] MEM-118 absorbs MEM-128; Todo, assigned to the owner.
- [ ] Owner review of [design](design.md).

## 1. Inventory

- [ ] List every place that chooses a provider (`switch`, `==`, per-provider operation kind, table name) in
  `connector`, `ingestion`, `retrieval`, `api` and `worker`, and map each to one adapter function.
- [ ] Decide per table whether it stays per provider or merges into a neutral table; record the decision and the
  migration in the design.
- [ ] Confirm the module dependency edges the adapters need; record an ADR if one changes.

## 2. Connector adapters (Google Drive and SharePoint, behavior unchanged)

- [ ] Function interfaces and registries in `connector`'s root API.
- [ ] `GoogleDriveSourceAdapter` and `SharePointSourceAdapter` in `core`'s `connector.googledrive` and
  `connector.sharepoint`, wrapping today's traversal, selection, authority, content and ACL code; the gateway
  implementations in `sources` are renamed only.
- [ ] Replace every dispatch site from the inventory with a registry lookup; remove per-provider operation-kind
  branching from `JdbcOperationDispatchRepository`.
- [ ] Rename `GoogleDriveProvider`/`SharePointProvider` to `…Gateway`; apply the naming convention across both stacks.
- [ ] Regenerate `openapi.yml` and the Hey API client if any contract name changed; `pnpm --dir web check`.
- [ ] Existing connector tests green unchanged; registry tests; `clean check`.

## 3. Hierarchy for existing Sources

- [ ] `SourceHierarchyAdapter` for Google Drive and SharePoint, and the folder tree on the Sources page.

## 4. Lark Suite

- [ ] Close the open questions (edition, app type, demo spaces, customer approval).
- [ ] `LarkGateway` and `LarkSourceAdapter`: credential, selection, sync, content (Drive, Docs, Sheets, Wiki),
  permissions, hierarchy.
- [ ] Credential and scope forms, controllers and web screens following the Google Drive pattern.
- [ ] Adapter tests on recorded responses; owner-run acceptance on the customer's tenant.

## 5. Consolidate

- [ ] `ARCHITECTURE.md`, `docs/specs/` and `docs/tests/` for connectors describe the adapters and Lark.
- [ ] After merge, move this increment to `completed/` and reconcile the roadmap.
