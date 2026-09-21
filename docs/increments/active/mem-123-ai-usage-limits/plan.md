# MEM-123 — plan

One pull request for enforcement and its screens. Threshold warnings are a second increment.

- [x] 1. `V96__ai_usage_limit.sql`: the table, its checks (at least one budget, `group_id` only for `GROUP`,
      cascade with the Group), a unique rule so one Tenant keeps one limit per scope and Group, and the
      `(tenant_id, actor_id, day)` index on `ai_usage`.
- [x] 2. `io.memoryos.usage`: `AiUsageLimit`, `AiUsageLimitScope`, `AiUsageLimitRepository` (CRUD, and the SUMs the
      check needs), and `AiUsageLimitService` with `check(ActorId, TenantId)` returning the binding limit and when it
      frees, plus a cached "any limit exists" short circuit as Onyx has.
- [x] 3. Refusal in `ChatTurnService.sendLocked` and in session naming (swallowed there), the new failure code in
      `FAILURE_CODES`, and `ChatException.limitExceeded` carrying the scope and reset instant.
- [x] 4. HTTP: 429 with `Retry-After` and a problem body naming the scope and reset; admin CRUD under
      `/api/ai-costs/limits` on `MODELS_MANAGE`; the person's own binding limit on the existing `/api/ai-costs/mine`.
- [x] 5. Audit: new `AuditAction` values for a limit created, changed and deleted, recorded in the change's
      transaction, with the before and after budgets as details.
- [x] 6. Web: the Limits section on `/admin/ai-costs` with limit beside usage, the create and edit dialog, and the
      banner on Settings › Usage. Vietnamese copy keeps `token`, `model` and `provider` in English.
- [x] 7. Tests: the repository's window arithmetic, a turn refused at the Tenant, Group and person scope, a turn
      admitted when a second Group is under budget, naming that survives a refusal, system usage that never counts,
      the 429 contract and the capability gate, and the two screens.
- [x] 8. Docs: extend [AI usage](../../../specs/ai-usage.md) and its
      [verification matrix](../../../tests/ai-usage.md) with limits; reconcile the roadmap.

## Open

- The default when a Tenant has no limit is no limit, as in Onyx. If Tasco wants a starting cap, it is configuration,
  not code.
- Whether a Group manager may set their Group's limit, as in Onyx, is deliberately left to a later increment.

## Verification notes

- Local gates run: `pnpm check` (522 tests); `core` `AiUsageLimitServiceTest` (9 tests); the API test
  `spendingLimitsAreSetByModelManagersAndRefuseATurnWithTheBudgetSpent`; the OpenAPI contract, regenerated.
- The admin section and the member banner were reviewed at 1280 in light and dark with realistic fixtures.
- The full local `./gradlew clean check` was not run to completion on this machine (memory pressure); CI is the gate.
