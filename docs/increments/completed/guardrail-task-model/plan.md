# Implementation plan

- [x] `ModelFlow.CHAT_GUARDRAIL`; V135 extends the flow CHECK and seeds it with the Tenant's Chat model.
- [x] `ChatGuardrailCheck.check` takes the classifying binding; `ChatTurnService` resolves the task model, closes it
  and records usage against it.
- [x] OpenAPI enum and the Hey API client; the "Kiểm tra câu hỏi" row on Models › Task models.
- [x] Tests: `ChatGroundedTurnTest`, `ModelCatalogConstraintsTest`, `ChatTenantProvisioningTest`, models unit tests;
  a screenshot for owner review.
- [ ] Owner approval; CI; merge; on staging pick a model for the check and ask a documents-only question.

## Staging acceptance (2026-10-19)

The question check now sits on AI Providers › System One (moved there by MEM-198) and runs on the 9Router Jev connection; a documents-only question on the grounded assistant was answered with its source, and the check's usage count rose with each turn.
