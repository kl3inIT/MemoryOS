# MEM-134 — plan

One pull request.

- [ ] 1. Migration: `connector_credential_pairs.model_boundary` (`ANY` | `INTERNAL_ONLY`, default `ANY`),
      `chat_session.internal_data_used_at`, and `chat_external_consent(session_id, provider_id, actor_id,
      consented_at)`.
- [ ] 2. Source: read and set the flag through `SourceManagementService`, with the Source-manager authority that
      `updateSourceAccess` uses; audited as `source.update` with change `MODEL_BOUNDARY`; the set of `INTERNAL_ONLY`
      Sources of a Tenant for the chat turn.
- [ ] 3. Turn: `ChatTurnSetup` carries a data gate (external or not, the withheld Sources, what to do when internal
      data is used). `SearchTool` removes withheld Sources from the scope, counts the relevant ones it withheld, and
      reports internal use.
- [ ] 4. `ChatTurnService`: refuse an external provider for a conversation that used internal data
      (`CHAT_INTERNAL_DATA_LOCKED`) and one without consent (`CHAT_EXTERNAL_CONSENT_REQUIRED`); naming keeps the short
      title for a locked conversation.
- [ ] 5. HTTP: set the Source flag; record consent (`POST /api/chat/sessions/{sessionId}/external-consent`), audited
      as `chat.external_consent`.
- [ ] 6. Web: the Source setting; the consent dialog with two equal choices, sending the turn again on consent; the
      locked message.
- [ ] 7. Tests: the scope without withheld Sources on an external turn and with them on an internal one; the lock; the
      consent per provider; the audit records; the dialog.
- [ ] 8. Docs: [chat-models](../../../specs/chat-models.md) (the boundary is now enforced for retrieval),
      [connector](../../../specs/connector.md) (the Source flag), [chat](../../../specs/chat.md), the matrices, the
      roadmap.
