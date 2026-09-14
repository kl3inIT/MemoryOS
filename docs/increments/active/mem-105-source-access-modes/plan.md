# MEM-105 implementation plan

- [x] Create branch `nhuxuanviet/mem-105-source-access-modes` from the MEM-88 head and move MEM-105 to In Progress.
- [x] Agree the modes, identity matching, permission interpretation, freshness, mode changes and authority with the user; record them in the [design](design.md).
- [ ] Detailed task plan (writing-plans) after the user reviews the design.

## Phase 1 — Public and Private

- [ ] V56 migration, `SourceAccess` enum, request contracts, OpenAPI and generated client.
- [ ] Remove the Drive RESTRICTED hard-codes; add Drive creation `access`; open `updateSourceAccess` to Drive.

## Phase 2 — Auto Sync enforcement

- [ ] Shared token SQL fragment; index-time `documentAccess`; `READ_SCOPE` recheck; reader tokens from the verified email.
- [ ] `GoogleDriveAclChanged` → `enqueueDocumentAccess`.

## Phase 3 — Minimal web

- [ ] Mode choice at creation, Change visibility for Drive, list filter and badge, translations.

## Phase 4 — Decisions and documentation

- [ ] ADR 0011; identity and connector specs, test matrices, ARCHITECTURE, README, roadmap, MEM-93 note, AGENTS.

## Phase 5 — Verification

- [ ] PostgreSQL, OpenSearch and API suites; `pnpm check`; `gradlew clean check`.
- [ ] Live local runtime through Orca with the YOUNGXV AUTO fixture and two accounts; record evidence without email addresses.
