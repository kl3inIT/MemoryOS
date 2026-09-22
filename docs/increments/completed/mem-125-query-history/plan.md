# MEM-125 — plan

One pull request.

- [x] 1. `V107__chat_history_visibility.sql`: `chat_settings.chat_history_visibility` with its CHECK and default,
      `CHAT_HISTORY_READ` added to the capability CHECK constraint, and the
      `(tenant_id, updated_at DESC, id)` index on `chat_session`.
- [x] 2. `IamCapability.CHAT_HISTORY_READ`: the enum value, `isOrdinaryGrant()`, the administrator implication, the
      Group capability metadata, and the SQL capability lists that name every grantable capability.
- [x] 3. `io.memoryos.chat.history`: `ChatHistoryService` (`page`, `transcript`, `export`, each resolving the
      reader's capability and the Tenant's visibility) and `JdbcChatHistoryRepository` (cursor paging on
      `(updated_at, id)`, the first question and answer, the message count, the feedback roll-up, and the transcript
      read). Temporary conversations are excluded; deleted ones are marked, not hidden.
- [x] 4. `ChatSettingsService`: read and write `chatHistoryVisibility`, recorded as `chat_settings.change`.
- [x] 5. `ChatHistoryController`: the three endpoints, the CSV with its byte-order mark and formula guard, and the
      403 when visibility is `DISABLED`.
- [x] 6. Audit: `CHAT_HISTORY_READ_ACTION` (`chat_history.read`) and `CHAT_HISTORY_EXPORT` (`chat_history.export`),
      written outside the read as the audit export is, with SUCCESS and FAILURE.
- [x] 7. Web: `/admin/chat-history` with the counts, filters, shared table and pager, the transcript Sheet, the
      export link, and the visibility control in Chat settings. Vietnamese copy keeps `token`, `model` and
      `provider` in English.
- [x] 8. Tests: the capability gate; each visibility mode; a deleted conversation listed and marked; a temporary
      conversation absent; feedback filters; the cursor; the export's bound and guard; the audit records; the screen.
- [x] 9. Docs: [chat](../../../specs/chat.md) and its [verification matrix](../../../tests/chat.md); the audit
      catalog in [audit](../../../specs/audit.md); the roadmap.

## Open

- Whether a Group manager should see only their Group's conversations is deliberately not in this increment: the
  capability is Tenant-wide, as Onyx's is.

## Verification notes

- Local gates run: `pnpm check` (571 tests); `ChatHistoryServiceTest` (9 tests on real PostgreSQL);
  `ChatSessionApiIntegrationTest.conversationsAreReadOnlyWithHistoryAccessAndEveryTranscriptReadIsRecorded`; the
  OpenAPI contract, regenerated.
- The screen was reviewed at 1280 in light and dark with realistic Vietnamese fixtures, and the transcript opens in a
  centred dialog.
- Not covered by a test: a conversation of another Tenant is never read. The schema allows one Tenant per deployment
  slot (`uq_tenants_deployment_slot`), so a second Tenant cannot be seeded at this level; the Tenant filter is in
  every query.
- The full local `./gradlew clean check` was not run to completion on this machine (memory pressure); CI is the gate.
