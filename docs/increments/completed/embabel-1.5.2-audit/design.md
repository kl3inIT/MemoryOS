# Embabel 1.5.2 and the Embabel reuse audit

## Requirement

Owner request 2026-09-26:
- raise Embabel from 1.5.1 to 1.5.2;
- audit whether MemoryOS re-implements anything Embabel already provides;
- fix what the audit found before the pull request.

Owner decision the same day: every agent is told the current date, and the per-agent switch is removed.

## Audit result

The audit read the Embabel v1.5.2 source (tag `5e51b5763`) module by module against MemoryOS. Nothing needs undoing: each overlap has a reason that still holds in 1.5.2. The reasons Embabel does not fit are:

- **OpenAI provider, request policy, client lease.** Embabel's factory has a fixed 600 s timeout and SDK retries, and no way to close clients. Its Responses model does not stream and has no reasoning effort or `tool_choice`.
- **ModelGuard budget, cycles, finish reason, pricing.** The streaming path records no usage and never checks the finish reason. The tool loop is capped at 20 with no tools-off last cycle. Pricing has no cache-read rate.
- **Retrieval.** There is no OpenSearch backend, no reciprocal-rank fusion and no Tenant or Group access checks, and chunking is by characters.
- **MCP client.** Embabel's client is built from application properties, with tools loaded once per JVM.
- **Interpreter.** Embabel's skill script engines run `docker`/`podman` on the JVM host.
- **Guardrails.** In 1.5.2, `AssistantMessageGuardRail` does not run on the streaming path, and `UserInputGuardRail` runs on every inference.

The audit found three defects:

1. **The current date was in the Chat system prompt twice.** The turn added Embabel's `CurrentDate` on top of the filled `{{CURRENT_DATETIME}}` placeholder. An agent with `datetimeAware=false` still received a date.
2. **Meeting minutes and transcript correction failed on the first reply that did not bind.** Embabel's data-binding retry (`max-attempts: 2`) could not run, because `ModelCalls` admitted only one model call.
3. **`ModelCalls` sent its instructions and the untrusted transcript as one user message.**

## Design

- **Date.**
  - `ChatPrompts.resolve` is the only source of the date, and it is always present. It fills the placeholder, or appends Onyx's `ADDITIONAL_INFO` line to instructions that have none.
  - `CurrentDate` is no longer added to the turn's prompt contribution.
  - The per-agent switch is removed end to end: `PersonaInput`, `PersonaView`, `PersonaEntity`, the Chat repository, `openapi.yml`, the web client and the agent editor.
  - V131 drops `persona.datetime_aware`. This departs from Onyx `datetime_aware`, by owner decision.
- **ModelCalls.**
  - The guard admits Embabel's data-binding `max-attempts` calls, wired from the property. The call deadline is shared, so the worst-case time does not grow.
  - The instructions travel as the system message and the input as the user message, as `SearchTool` already does.
- **Documentation.**
  - `StreamingLlmService.supportsStreaming` documents why it does not probe.
  - The Chat spec records why `search_knowledge` is not Embabel RAG.

## Verification

- **Focused tests.** `ChatWebPromptsTest`, `ChatLanguagePromptTest` and `ChatPersistenceIntegrationTest` cover the date appearing once, custom instructions receiving the date line, and the persona round trip without the switch. The core `ai`, `chat` and `meeting` tests also run, as do the API persona and OpenAPI contract tests.
- **Web.** `pnpm typecheck` and `pnpm build`.
- **CI.** CI runs `clean check`.
- **Staging after deployment.** Regenerate the minutes of the "Demo AI IN OFFICE" meeting and compare them with the stored ones.
