# MEM-88 implementation plan

- [x] Create isolated Orca worktree from origin/main; move MEM-88 to In Progress.
- [x] Verify existing OAuth grant can read permissions on already-selected Google Drive files without writes or scope expansion.
- [x] Record provider, persistence and Worker contracts before implementation.
- [x] Implement bounded, fully paginated permissions collection and migrate Session implementations.
- [x] Add atomic snapshot persistence and explicit freshness/context/failure read contract.
- [x] Integrate fenced source synchronization, including unchanged-content and lost-access paths, without introducing reader authorization.
- [x] Verify provider edge cases, PostgreSQL lifecycle isolation and Worker permission-only behavior.
- [x] Run compilation and clean check; exercise the changed runtime surface with controlled fixtures.
- [x] Consolidate connector capability/test documentation and a handoff contract; report live-versus-fixture evidence and limitations.
- [x] Compare the local Onyx implementation and record adopted patterns and intentionally rejected authorization fallbacks.
- [x] Exercise the real synchronization runtime with controlled HTTP fixtures; remove throwaway program and build scaffolding.

## Verification limitations

The two real Google files returned a single owner permission each. This is not evidence for live pagination, sharing mutation, domain/group membership resolution or multi-user authorization. These semantics require controlled fixtures or separately authorized owned Google fixtures. No merge or deployment is included.

## Approved interface work

- [x] Add bounded, authorized ACL summaries and selected-file snapshot reads, with security/state coverage.
- [x] Build the read-only ACL inspector using generated API types and translated copy.
- [x] Replace run-table detail sprawl with outcome summaries and a selected-run error panel.
- [x] Remove the requested disclaimer and regroup Source overview, content, history and configuration.
- [x] Generate contracts, run applicable gates and exercise the actual local browser; record its URL and evidence in [verification](verification.md#approved-interface-verification).

## Real-runtime feedback

- [x] Make retained run errors distinguish historical failure from current file status, with safe diagnostic identifiers.
- [x] Classify extraction connection failures separately from processing timeouts.
- [x] Move the Source error below the synchronization overview.
- [x] Restore reachable Docling processing and verify the actual PDF/DOCX files and history UI through Orca.
- [x] Remove the separate Search-readiness line and provider subtitle; render file statuses as icon badges.
- [x] Read the pinned local Onyx error-summary/trace persistence and actual trace modal.
- [x] Read Onyx Pause/Resume through cancellation, worker stopping and checkpoint reuse; record the missing MemoryOS runtime contract without shipping a cosmetic Pause button.

## Acceptance gap closure

See [design](design.md#acceptance-gap-closure--approved-2026-09-14).

- [x] Classify 403 `SCOPE_INSUFFICIENT` (all Drive calls) and `ACCESS_DENIED` (`permissions.list` only); handle scope like authentication in SOURCE_SYNC; add UI copy.
- [x] Cover reader→writer, added permission and identical re-observation in the SOURCE_SYNC runtime test.
- [x] Add the handoff snapshot example and state interpretation to the connector spec.
- [x] Document the consumer read API and change event with a payload example; answer the enforcement owner's questions on MEM-88.
- [ ] Record MEM-93 confirmation of the handoff contract.
- [x] Run the live owned-fixture sharing sequence with the user and record evidence.
- [x] Observe a file shared to the connected account by another owner, as Editor and then as Viewer.
- [x] Run the repository gate and web checks; update verification and connector test matrix.

## ACL inspector removal

See [design](design.md#acl-inspector-removal--approved-2026-09-14).

- [x] Remove the Permissions tab, panel, translations, ACL HTTP endpoints, contract/OpenAPI/client and the inspector-only service and repository listing; keep collection, `readByDocument`, the change event and per-file `read`.
- [x] Update the connector spec, architecture, README, roadmap, test matrix and dependent increments.
- [x] Run the affected backend suites and web checks; record the result in [verification](verification.md#acl-inspector-removal--2026-09-14).

## Redundant content version removal (MEM-104)

- [x] Drop `google_drive_membership.content_provider_version` from V75 (then V69, V61) before merge, with the `unchanged()` fallback, the five-argument `observe` and the `releaseConfirmed` COALESCE.
- [x] Run the connector suites and record the result in [verification](verification.md#redundant-content-version-removal--2026-09-14).
