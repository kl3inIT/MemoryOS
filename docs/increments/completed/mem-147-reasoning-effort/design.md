# MEM-147 — Reasoning level per conversation, personal sampling defaults

## Problem

Reasoning effort and temperature are administration-only: a model manager may pin them on a model
configuration, and nobody else can choose. A member cannot ask for a quick answer on a lookup and a
careful one on a reconciliation, and cannot set the tone their own conversations start with.

## Reference

Onyx, read on 2026-09-20 in `.tmp/onyx`:

- `chat_session` carries `temperature_override` and `reasoning_effort_override`
  (`backend/onyx/db/models.py:3236-3238`), written by `PUT /api/chat/update-chat-session-temperature`
  and `/update-chat-session-reasoning` from the model settings pane inside the composer's model picker.
- Settings › Chat Preferences holds `Default Temperature` and `Default Reasoning Level`; both reach
  inference as `UserChatDefaults` (`backend/onyx/llm/factory.py:166-169`) and are starting values only.
- `resolve_reasoning_effort` (`backend/onyx/llm/models.py:93-122`) settles one ordered chain:
  the request (the conversation's pinned level) → the model configuration's default → the member's
  default → a cap. `factory.py:377-379` settles temperature the same way.
- Whether a model reasons is a stored capability of the model configuration, seeded from the LiteLLM
  model map and, for models it does not know, one cached `litellm.supports_reasoning` probe.
- `supported_reasoning_efforts` narrows the levels to those the provider's parameter style tells apart;
  `xhigh` survives only for OpenAI and Anthropic adaptive models.

## Decision

1. **Settings › Chat holds both defaults**: creativity (temperature) and reasoning level. They are
   starting values, not per-turn controls.
2. **The composer holds the reasoning level only**, pinned per conversation. Temperature stays out of
   the composer, unlike Onyx: it is a voice-of-the-assistant setting, not a per-question one.
3. **Precedence follows Onyx**, unchanged: the conversation's pinned level → the model configuration's
   value → the member's default → the provider's own default. A manager who pinned a value on a model
   keeps it; in practice no model ships with one pinned.
4. **Levels are Off, Low, Medium and High.** `xhigh` is left out: only OpenAI and Anthropic adaptive
   models distinguish it, and MemoryOS has no per-provider level filter yet. A model whose
   `capabilities.reasoning` is false shows no control and receives no parameter — the existing rule in
   `OpenAiChatRequestPolicy`, which already strips `reasoningEffort` from such a request.

## Scope

- `chat_preferences` gains `temperature_default` and `reasoning_effort_default`, both nullable.
- The conversation gains `reasoning_effort_override`, nullable.
- `PUT /api/chat/preferences` carries both defaults; one endpoint pins a conversation's level.
- Settling happens where a turn's model settings are built, before the provider client is used, so a
  cached client binding is not specialized per member.
- Conversation naming and other helper flows keep the model's own configuration.
- The composer's model picker shows the level for a reasoning model, and hides it otherwise.
- Settings › Chat shows both sliders, each with its current value.

## Out of scope

- Temperature per conversation.
- Top-p, penalties and other sampling parameters.
- Per-provider filtering of which levels a model tells apart.
- A per-model maximum level (Onyx's cap).

## Verification

- A model without reasoning: no control, and no parameter in the provider request.
- An unsupported level is rejected by the API.
- The conversation's level beats the member's default; the model configuration beats both; the request
  the provider receives carries the settled value.
- Screenshots at 1280 light/dark and 390 of Settings › Chat and the composer picker.
