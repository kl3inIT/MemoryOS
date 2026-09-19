# MEM-102 — Model flows and provider data boundary

Linear: [MEM-102](https://linear.app/memory-os/issue/MEM-102). Related: [MEM-129 intent router](https://linear.app/memory-os/issue/MEM-129) (a candidate next flow), [MEM-134 external data gate](https://linear.app/memory-os/issue/MEM-134) (enforces the boundary this increment records), [MEM-135 embedding model switch](https://linear.app/memory-os/issue/MEM-135).

## Problem

`ChatTurnService.generateTitle` resolves the conversation's own model (`models.resolve(actor, session, null)`), so every new conversation pays for its title on the chat model, reasoning models included, and a single-stream local model is blocked by naming calls while it answers. Further tasks will want their own model (image understanding, contextual retrieval, intent classification), so the catalog needs one extensible place for "the model used for task X".

The catalog also has no notion of where a provider sits relative to the organization's data. MEM-134 needs that to decide which calls must pass its gate, and administrators need to see it when they pick a model.

## Reference

- Onyx (snapshot 2026-09-10) has no generic fast model. `llm_model_flow` stores a default model per flow (`CHAT`, `VISION`, `CONTEXTUAL_RAG`, `REASONING`, `CHAT_NAMING`, `CRAFT`). Chat Preferences offers a "Chat Naming Model": "Defaults to each session's own model — pin a small, fast model here if your main model can't serve concurrent requests." Search helper flows (query expansion, time and source filters, section relevance) use the session's model. The older per-provider `fast_default_model_name` was removed.
- Mobbin: Twenty workspace AI settings (a "Fast Model — used for lightweight tasks like title generation" row beside the default), Langdock models (default-model setting rows; a per-model "Region — displayed to users so they know where their data is processed"; provider and region shown together as "Anthropic · EU"), Sana AI (confirming what happens to dependants when a model is disabled).

## Decisions

### Model flows

- A flow is a named task that may use its own model. `ModelFlow` starts with `CHAT_NAMING`; adding a flow is an enum value, a migration that widens the `flow` check and backfills its rows.
- `model_flow_default(tenant_id, flow, model_configuration_id, revision)`, one row per Tenant and flow. A null model means "use the conversation model". Rows are seeded when the catalog is initialized and backfilled by migration for existing Tenants, so reads and writes use the same revision contract as the Chat default.
- The Chat default stays in `chat_model_default`. It is mandatory, blocks deleting or hiding its model and marks catalog initialization; flow defaults are optional and degrade. Unifying them would change a working contract for no product benefit in this increment.
- Eligibility to set a flow model is the Chat default rule: a visible model whose provider is enabled, usable, public and has no Group or agent restriction. Flow output is not tied to one actor's model access, so a restricted provider could otherwise be reached indirectly.
- Resolution: `ModelCatalogService.resolveFlow(actor, session, flow)` takes the same locks as `resolve`, returns the flow model when it is set and still eligible, and otherwise returns exactly what `resolve(actor, session, null)` returns. An unset or ineligible flow model never fails the task.
- Deleting the model (or its provider) clears the flow through `ON DELETE SET NULL`. Hiding the model or disabling or restricting the provider does not block and does not clear; resolution falls back and the administration row reports the model as unavailable.
- Consumer in this increment: conversation titles (`CHAT_NAMING`).

### Provider data boundary

- `llm_provider.data_boundary`: `INTERNAL | EXTERNAL`, default `EXTERNAL` for existing and new providers. An administrator must state that a provider may receive internal documents (self-hosted, or an enterprise agreement without retention or training).
- The boundary belongs to the provider, not the model: data commitments follow the account and contract, and the same model family can sit behind an internal and an external provider.
- This increment records and shows the boundary only. It does not block any call; MEM-134 adds enforcement. The administration UI says so.

## HTTP

| Method and path | Contract |
| --- | --- |
| `GET /api/chat/model-flows` | every flow with `flow`, `modelConfigurationId`, `available`, `revision`; `MODELS_MANAGE` |
| `PUT /api/chat/model-flows/{flow}?revision&modelConfigurationId` | set, or omit the model to clear; same eligibility as the Chat default; unknown flow is `400` |
| Provider create, update and read | add `dataBoundary` |

`/api/chat/model-default` is unchanged.

## UI

- `/admin/models` section "Model theo tác vụ": setting rows with the label and description on the left and the picker on the right. Rows: "Chat" (the existing Chat default, renamed so every row names a task), then one row per flow. The naming row is "Đặt tên cuộc chat", first choice "Dùng model của cuộc chat" (clears). Only eligible models are listed.
- Every admin model picker group header shows its provider and boundary tag ("OpenAI · Bên ngoài"), since the boundary belongs to the provider. No extra notice for External flow models: the tag says it, and the conversation model already receives the same text.
- A flow row whose model is set but unavailable says titles currently use the conversation model.
- Provider editor: a "Ranh giới dữ liệu" choice of two cards, External (default) and Internal, each with its description, and a note that the label does not block calls yet. Choosing Internal asks for confirmation, because it states that internal documents may be sent to the provider. The provider list shows an Internal or External badge beside the name and a status badge only when the provider is disabled or has no credential.
- Deleting a model that a flow uses confirms that the flow will fall back to the conversation model.

## Out of scope

Moving the Chat default into flows, flows other than chat naming, per-flow capability requirements, enforcement of the boundary (MEM-134), embedding model selection (MEM-135), usage and cost of flow calls (MEM-98).
