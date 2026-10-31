# Implementation plan

- [x] `chatToolDefaults` and `turnModelOf` (`chat-tool-defaults.ts`) with unit tests.
- [x] Preferences return undefined without a choice and store off; MCP preference added.
- [x] Transport sends the choice or the conversation's defaults; the hook computes them.
- [x] Composer chips only for a tool turned on against its default; chips never wrap.
- [x] Chat spec, test matrix and roadmap.
- [ ] CI; owner approval of the screenshots; merge; staging check.

## Staging acceptance (2026-10-19)

A new conversation offers Web search, image generation and the person's MCP servers on, and its first send carries them (202, the answer searched and cited); turning Web off survives a reload; a documents-only conversation sends them off and is answered from its sources.
