# Chat tools on by default

Status: implemented 2026-10-03 at the owner's request.

## Problem

On staging every one of 467 sends in the week to 2026-10-01 had `web_search = off`: Web search, image generation and
MCP servers were off in every new conversation since 2026-09-13 (`64cd108d`), so a person who asked to search the Web
got an answer from the model's memory. Onyx keeps an agent's tools on until the person turns one off
(`assistant__user_specific_config.disabled_tool_ids`), and ChatGPT searches when the question needs it.

## Decision

- **Default on where the tool can run.** While the person has chosen nothing for a conversation, a turn sends Web
  `auto`, image `auto` and the usable MCP servers. The model still decides whether to call a tool.
- **Never a refused turn.** The API refuses a turn that asks for a tool it cannot offer (`CHAT_WEB_UNAVAILABLE`, image
  provider unavailable), so the browser turns a default on only when it knows the tool can run: the turn's model,
  resolved as the server does (chosen, agent, personal default, Tenant default), calls tools; the agent allows the
  tool; Web has hosted search or a search connection; image has a connection and the person may generate; MCP servers
  are connected with enabled tools. Until all of that is read, every default is off.
- **Documents-only conversations keep tools off** until the person turns one on; Web there stays subject to the
  Tenant's `groundedAllowWeb`.
- **The person's choice wins and is remembered.** Off is stored as a choice too, per actor and conversation, so the
  default never turns a tool back on. MCP choices are now remembered like Web and image.
- **No new chips.** A tool on by default shows its check in the menu, as ChatGPT shows nothing for search; a chip
  marks only a tool turned on against its default.

The decision is entirely in the browser; the API contract and its validation are unchanged.

## Open

- Edit and Regenerate still send only the Web choice, as before this change.
- Live check on staging: ask a question that needs the Web in a new conversation and confirm `web_search = auto`.
