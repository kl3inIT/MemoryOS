# Implementation plan

- [x] `ModelFlow.CHAT_GUARDRAIL`; V136 extends the flow CHECK and seeds it with the Tenant's Chat model.
- [x] `ChatGuardrailCheck.check` takes the classifying binding; `ChatTurnService` resolves the task model, closes it
  and records usage against it.
- [x] OpenAPI enum and the Hey API client; the "Kiểm tra câu hỏi" row on Models › Task models.
- [x] Tests: `ChatGroundedTurnTest`, `ModelCatalogConstraintsTest`, `ChatTenantProvisioningTest`, models unit tests;
  a screenshot for owner review.
- [ ] Owner approval; CI; merge; on staging pick a model for the check and ask a documents-only question.
