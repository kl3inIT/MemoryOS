# MEM-130 — Model discovery and correct catalog specs

[MEM-130](https://linear.app/memory-os/issue/MEM-130) · related [MEM-77](../../active/mem-77-provider-backend/design.md)

## Problem

Staging models carried wrong limits: the deployment bootstrap wrote the execution bounds (32,000 + 4,096) as the
model's context window and output limit, so `gpt-5.6-luna` and `gpt-5-mini` stopped at 4,096 output tokens and Deep
research refused them as "turned off". Provider discovery returned names only, so every OpenRouter or local model
needed its limits, capabilities and prices typed by hand, and a 4,096-token cut-off surfaced as a generic
"interrupted" reply.

## Reference

Onyx `40eb240df` `backend/onyx/server/manage/llm/api.py` has one `available-models` fetcher per provider
(OpenRouter reads `context_length` and image input; OpenAI-compatible reads `context_length`; Ollama `/api/tags`;
LiteLLM `/v1/model/info`) and `backend/onyx/llm/model_capabilities.py` looks limits up in LiteLLM's map by name.

## Decisions

1. **Extend the existing endpoint.** `GET /api/chat/providers/{id}/reported-models` returns each reported model with
   the specs the endpoint publishes, then the installed catalog's by name (a `models/` or vendor prefix is ignored),
   plus `source`. The OpenAI-compatible adapter reads the vendor fields listed in the
   [catalog spec](../../../specs/chat-models.md); the body cap is 16 MiB and 1,000 models, because OpenRouter's list is
   about 2 MiB. No new endpoint and no per-vendor adapter: every vendor already speaks the OpenAI protocol here.
2. **Never guess, never block (Onyx).** An answer limit that is missing stays missing and no output cap is sent, as
   Onyx `llm_loop` sends no `max_tokens`; the deployment `max-output-tokens` reserves room when the input budget is
   computed. One at or above the context window is replaced by the catalog's. A model nobody describes takes Onyx's
   32,000-token fallback window and tool calling (Onyx sends tools to every model; the saved-connection check probes
   a tool request), reports `source` `none` and can be edited before it is added. Every reported model can therefore
   be added without typing. Prices are copied once when the model is added.
3. **Catalog breadth.** `known-models.json` adds Gemini, xAI, DeepSeek and Mistral from the same pinned LiteLLM
   commit. Models LiteLLM records with an output limit equal to their window (for example grok-4,
   mistral-large-latest) stay out: their answer limit is unknown.
4. **Bootstrap.** The deployment model takes its catalog limits, capabilities and prices; the execution bounds apply
   only to a model the catalog does not know.
5. **Honest failures.** An OpenAI Responses answer that stops at `max_output_tokens` before any text or completed call
   fails with `CHAT_MODEL_OUTPUT_LIMIT`; history and the outcome event carry `failureCode`, and the reply names the
   cause. Deep research on an unsuitable model returns `CHAT_RESEARCH_MODEL_UNSUPPORTED`, and the composer disables
   the toggle with the reason.
6. **Search.** The reported-models dialog filters by name; the default-model and composer pickers already search.

## Out of scope

Refreshing prices of existing models, Ollama `/api/show` and LM Studio native metadata, a per-vendor adapter.
