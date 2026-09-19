# Chat limits as Onyx

## Problem

Chat execution carried limits Onyx does not have, chosen at first as safe placeholders and never measured. The
largest: `context-token-limit` 32,000 capped every ordinary turn's input, so `gpt-5.6-luna` (922,000 tokens) used
about 3% of its window. Search selection read 6,000 tokens of candidates where Onyx reads 25,600, returned evidence
was cut at 8,000 tokens, and search, MCP and image calls had per-turn counts where Onyx has only the cycle limit.

## Reference

Onyx `40eb240df`: `backend/onyx/chat/llm_loop.py` (input budget `max_input_tokens * (1 -
GEN_AI_INPUT_TOKEN_SAFETY_MARGIN)`, `MAX_LLM_CYCLES`), `backend/onyx/configs/model_configs.py`
(`GEN_AI_NUM_RESERVED_OUTPUT_TOKENS` 1024, `GEN_AI_MODEL_FALLBACK_MAX_TOKENS` 32000, margin 0.05),
`backend/onyx/configs/chat_configs.py` (`NUM_RETURNED_HITS` 50, `MAX_CHUNKS_FED_TO_CHAT` 25, `MAX_LLM_CYCLES` 6,
`LLM_SOCKET_READ_TIMEOUT` 60), `backend/onyx/tools/tool_implementations/search/` (selection budget 25 x 512 x 2, 10
sections, `limit=max_llm_chunks`), `backend/onyx/deep_research/dr_loop.py` (50,000-token minimum, no margin).

## Decisions

1. **Input follows the model.** Input = (context window - answer reserve) x 0.95. The reserve is
   `max-output-tokens`, now 1,024, or the model's smaller output limit. `context-token-limit` becomes an optional
   deployment cap, unset by default; a Persona `contextTokenLimit` still lowers the budget. Deep research keeps its
   own input math, which already reads the model window and applies no margin, as `dr_loop.py`.
2. **Bounded work names Onyx's fallback.** Cost reservation, research inferences and search helpers need an output
   number; a model without one uses Onyx's 32,000-token fallback kept within a quarter of the window.
3. **Search sizes as Onyx.** 50 candidates, 25,600 selection tokens, 10 sections. Evidence is bounded by chunk
   count as Onyx, not tokens: 10 sections, each widened by at most five chunks, then by the context the model has
   left. Onyx's `limit=max_llm_chunks` (25) applies to sections and never binds after the 10-section selection. No
   per-turn search-call or helper count.
4. **Tool calls bounded by cycles.** MCP `mcp-call-limit` becomes optional and unset; image generation and editing
   lose their 4-call count.
5. **Unknown deployment model.** A persona model the catalog does not know takes the discovery defaults (32,000-token
   window, no output cap) instead of values derived from execution limits.
6. **Kept.** Operational bounds Onyx does not need in the same form stay: concurrency, lease, Redis stream buffer
   and `max-answer-characters` (it bounds the replay buffer), file sizes, optional token/cost budgets.

## Consequence

Turns may send many more tokens and cost more on long conversations. The optional `cost-budget-usd`/`token-budget`
and Persona `contextTokenLimit` remain the controls.
