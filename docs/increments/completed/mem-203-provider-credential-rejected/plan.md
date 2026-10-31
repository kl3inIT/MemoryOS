# Implementation plan

- [x] `TurnFailure.PROVIDER_CREDENTIAL_REJECTED`, reported.
- [x] `ModelBinding.credentialRejection` with the OpenAI rule (`OpenAiFailures`); the Responses model applies it.
- [x] `ChatTurnService` stores the code from the guardrail check and from any failed call of the turn.
- [x] Web: the failure notice by code and role; "interrupted" only with kept text; no unconfirmed notice after a
  committed failure.
- [x] Tests: `OpenAiFailuresTest`, `OpenAiResponsesChatModelTest`, `ChatGroundedTurnTest`, `chat.spec.ts` for a
  member and a model manager, with screenshots for owner review.
- [x] Chat spec and test matrix.
- [ ] Owner approval of the screenshots; CI; merge; staging check with the refused key.

## Staging acceptance (2026-10-19)

A provider whose key is refused after it was saved fails the turn in 1.6 s with `CHAT_PROVIDER_CREDENTIAL_REJECTED`: a model manager sees "Provider từ chối API key" with "Cập nhật API key", a member "Model không dùng được", the same after a reload. The failed answer's footer still says "Xong trong 0,8 giây".
