# MEM-145 — Personal settings

Linear: [MEM-145](https://linear.app/memory-os/issue/MEM-145). Prototype reviewed with the owner: `output/mem145-prototype/index.html` (not checked in).

## Problem

Personal settings are one `/settings/general` page stacking language, voice and prompt shortcuts. A member cannot see their own AI usage, the prices of the models they may use, or set how Chat behaves for them. The AI costs page (MEM-98) is only for model managers.

## Reference

**Onyx** `web/src/app/app/settings/layout.tsx` tabs: General (Profile with Full Name and Work Role, Appearance, Language, Danger Zone "Delete All Chats"), Chat Preferences (Default Model, Default App Mode, Personal Preferences, Chat Auto-scroll, Collapse Large Pastes, Smooth Streaming, temperature, reasoning, Memory, Prompt Shortcuts, Voice), Accounts &amp; Access, LLM Gateway, Connectors, Usage (`usage/UsageSettings.tsx`: "Usage this period", "Budget", "Model prices", tokens per model). Onyx appends a `# User Information` section to the default system prompt (`chat/prompt_utils.py`, `prompts/user_info.py`): `## Basic Information` (name, email, role) and `## User Preferences`; a custom agent prompt is sent after it, so the section applies to agents too. Deep research only gets the language line.

**Mobbin:** Claude settings (left tabs, Usage with "% used" and "Resets …"), Langdock Preferences ("Default model — Will be preselected whenever you start a new chat", theme cards), ChatGPT and Bolt settings rows, Cofounder and OpenAI Platform usage (spent / remaining / budget bar).

## Decisions

### Shell

`/settings` is a layout route with four tabs, **General · Chat · Connections · Usage** (vi: Chung · Chat · Kết nối · Mức dùng), a left list on desktop and horizontal tabs on phones. `/settings` redirects to General; the existing `/settings/general` URL is kept.

### Preferences storage

One table `chat_preferences (tenant_id, actor_id)`, keyed and foreign-keyed to the Tenant membership exactly as `chat_voice_settings` (V83): `work_role` (≤ 200), `personal_preferences` (≤ 2,000, it is sent in every system prompt), `default_model_configuration_id` (FK to `model_configuration`, `ON DELETE SET NULL`), `start_page` (`CHAT` or `SEARCH`) and `auto_scroll` (default true). Theme stays in the browser (existing `features/theme`); language stays on the IAM account.

### Personal default model

Precedence for a conversation without an explicit choice: Persona model → **personal default** → Tenant default, in `resolve`, `availableModels` (`isDefault` of the picker) and Web availability. Saving requires a visible model the member may use with the default Persona. A personal default that is later hidden, deleted or no longer accessible falls back to the Tenant default without error, as a revoked Persona model does (`SELECTION_UNAVAILABLE` only for an explicit choice).

### Profile in the prompt

As Onyx, the Chat system prompt gets `# User Information` with `## Basic Information` (name and email from the login profile, role when set) and `## User Preferences` when set, for the default and custom Personas alike, next to the existing account-language hint. Conversation naming and Deep research do not get it (Onyx gives research only the language line).

### Start page, auto-scroll, pasted text

- Start page: the app root opens Chat or Search as chosen; Search stays available from navigation either way.
- Auto-scroll: `ThreadPrimitive.Viewport autoScroll`, default on.
- Collapse large pastes is a follow-up (MEM-148): Onyx draws pasted text as tiles inside its own rich input (`paste_as_tile`, off by default), which the assistant-ui textarea cannot do without composer and send changes. No toggle ships before the behaviour.
- Smooth streaming is not offered: MemoryOS disables it because re-parsing long answers froze slower machines.

### Delete all chats

`DELETE /api/chat/sessions` deletes the caller's own sessions exactly as deleting each one does, which stops an active reply rather than refusing; a conversation removed meanwhile is skipped. Confirmation dialog as Onyx ("Delete All Chats").

### Connections

The tab lists the MCP servers the member may use with per-user authentication and reuses the existing MCP connection endpoints and components (connect with OAuth or API key, disconnect). No second connection flow.

### Usage

`GET /api/ai-costs/mine?from&to` returns the caller's own `AiCostDetail` from the MEM-98 ledger to any active member; nobody reads another member's usage through it. Model prices come from the available-models list (`pricing` with the cache-read rate, `isDefault`). The budget card shows Onyx's "No budget set" until MEM-123 adds limits; no placeholder API field is added (ADR 0002).

## Out of scope

Smooth streaming, default temperature, per-chat reasoning (MEM-147), chat background, Memory (MEM-116), LLM Gateway and personal access tokens (MEM-146), password changes (Keycloak), setting budgets (MEM-123).
