# Implementation plan

One pull request, three commits.

- [x] **Bypass.** `ChatTurnSetup` hides blocked exchanges (text and files) from the materialized history;
  `GroundingClassifier.conversation` marks `[blocked]` and strips a typed marker; the check's instruction covers
  follow-ups to a blocked message; `ChatTurnService` gives the answer model the topic rules whenever a topic is
  enabled; the rules refuse instructions inside messages. Tests: `ChatTurnSetupTest`, `GroundingClassifierTest`,
  `ChatGroundedTurnTest`, `ChatGuardrailRulesTest`.
- [x] **Topics.** `ChatGuardrails.Topic` becomes a record of the Tenant's topic; seed with fixed ids; V137; entity, service,
  contracts and validation; classifier labels `TOPIC_n`; block audit names the topic; `openapi.yml` and the Hey API
  client. Tests: `ChatGuardrailsTest`, the migration seed check, `ChatSessionApiIntegrationTest`, `OpenApiContractTest`.
- [x] **Page.** Topic cards with immediate switches, add and edit dialog, delete confirmation; phrases card with its
  own save; history radios; nested Web option; check model link; Deep Research title; mobile wrapping. Tests:
  `admin-chat-settings.test.tsx`, e2e for adding, editing and deleting a topic; screenshots light, dark and phone.
- [x] Chat spec, audit spec, test matrices, roadmap.
- [ ] CI; owner approval of the screenshots; check the latest migration number on main; merge.
- [ ] Staging: the four bypass pairs from 2026-10-01 and one ordinary follow-up; a custom topic end to end.
