# Verification

## Automated

| Check | Result |
| --- | --- |
| `:core:test` for `OpenAiProviderAdapterTest`, `ResponsesRequestBuilderTest`, `ModelCallsTest`, `ModelSettingsTest`, `OpenAiReportedModelsTest`, `GroundingClassifierTest`, `ModelCatalogConstraintsTest`, the migration tests | pass |
| `OpenApiContractTest` with the regenerated `openapi.yml` (three schemas gain `structuredOutput`) | pass |
| `pnpm --dir web check` | every step passes except one run of `library-page.test.tsx`, which fails under the full parallel run and passes alone, with and without this change |
| `models-discovery.spec.ts` (Playwright, desktop, dark and phone) | pass; screenshots reviewed, the capability shows as an icon beside tool calling, vision and reasoning |
| `ChatSessionApiIntegrationTest` (94 cases, real Embabel calls against a stub provider) | pass. The minutes case asserts that the request reaching the provider carries the schema of the minutes with every field required |
| `clean check` | not run locally (memory); CI runs it |

## What Embabel 1.5.2 does with a schema

The first version of this increment went through Embabel's native structured output. A probe against
`shouldUseNativeStructuredOutput` in `ENABLED` mode, and its bytecode, showed why no schema left the process:

| Schema | Native path |
| --- | --- |
| object, every property required | yes |
| object, one property not required | no |
| array of numbers | yes |
| array of objects | no |
| nested object | yes |
| what Embabel generates for `Answers`, `TranscriptSummary`, `TranscriptCorrections`, a record with a `String` | no |

CodeRabbit raised the array case on the pull request. `ChatSessionApiIntegrationTest` also failed on CI in that
version: Embabel calls its configurer for every request and passes no schema request unless the path is taken.

## What the wire tests show

- Chat Completions: `response_format.type` is `json_schema`, `json_schema.strict` is true, `json_schema.schema` is
  the schema carried on the options and it has a name. The request is converted by a task binding that reasons and
  goes through the binding's policy options and final request, the two rewrites `ModelGuard` applies. Options
  without a schema send no `response_format`.
- Responses: `text.format` is `{type: json_schema, name: answer, strict: true, schema}`; without an output schema
  no `text` is sent.
- `OutputSchemas.strict` for the three answer types: every object lists all its properties as required and is
  closed; an optional field (`owner` of an action) is `["string","null"]`.

## Through 9Router, 2026-10-04

Free tier, one self-written prompt. The system message asks for the single word `hello` and forbids JSON, so only
an enforced schema returns the object. Schema: the question check's `Answers`.

| Model | Route | Schema sent | Answer |
| --- | --- | --- | --- |
| `cx/gpt-6-luna` | Chat Completions | no | `hello` (2 of 2) |
| `cx/gpt-6-luna` | Chat Completions | yes | `hello` (2 of 2): the gateway drops `response_format` |
| `cx/gpt-6-luna` | Responses | yes | `{"answers":[{"id":"1","probability":1}]}`: enforced |
| `ocg/deepseek-v4-flash`, `ocg/glm-5.3-flash`, `ocg/qwen3.8-flash`, `ocg/kimi-k3` | both | | `503`, usage limit exceeded: not measured |

The cause is in 9Router (0.5.91 installed, 0.5.95 upstream the same): its Chat Completions to Responses translator
copies a fixed list of fields and leaves `response_format` out (upstream issue decolua/9router#2896, fix in the
open pull request #4547).

The same day the host's 9Router was rebuilt from 0.5.95 with that pull request and the Devin patch applied, and
the measurement repeated:

| Model | Route | Schema sent | Answer |
| --- | --- | --- | --- |
| `cx/gpt-6-luna` | Chat Completions | no | `hello` (2 of 2) |
| `cx/gpt-6-luna` | Chat Completions | yes | `{"answers":[]}` (2 of 2): enforced |
| `cx/gpt-6-luna` | Responses | yes | enforced |

Staging's task model is `cx/gpt-6-luna` on Chat Completions, so the capability can now be declared on it as it
is. The fix is a local patch until upstream merges it; an update from npm drops it.

## Not verified

- A declared model on staging: one set of minutes and one correction after deployment.
- That OpenAI itself accepts the three schemas under `strict`; they are checked against its published rules, and
  9Router's Codex route is the only provider measured.
- Any gateway other than 9Router.
