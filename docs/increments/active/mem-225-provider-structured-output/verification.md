# Verification

## Automated

| Check | Result |
| --- | --- |
| `:core:test` for `OpenAiProviderAdapterTest`, `ResponsesRequestBuilderTest`, `ModelCallsTest`, `ModelSettingsTest`, `OpenAiReportedModelsTest`, `GroundingClassifierTest`, `ModelCatalogConstraintsTest`, the migration tests | pass |
| `OpenApiContractTest` with the regenerated `openapi.yml` (three schemas gain `structuredOutput`) | pass |
| `pnpm --dir web check` | every step passes except one run of `library-page.test.tsx`, which fails under the full parallel run and passes alone, with and without this change |
| `models-discovery.spec.ts` (Playwright, desktop, dark and phone) | pass; screenshots reviewed, the capability shows as an icon beside tool calling, vision and reasoning |
| `clean check` | not run locally (memory); CI runs it |

## What the wire tests show

- Chat Completions: `response_format.type` is `json_schema`, `json_schema.strict` is true, `json_schema.schema` is
  the schema given and it has a name; `max_tokens` survives. The request goes through the binding's policy options
  and final request, the two rewrites `ModelGuard` applies, which keep the schema. An undeclared model sends no
  `response_format`.
- Responses: `text.format` is `{type: json_schema, name: answer, strict: true, schema}`; without a response format
  no `text` is sent.

## Through 9Router, 2026-10-04

Free tier, one self-written prompt. The system message asks for the single word `hello` and forbids JSON, so only
an enforced schema returns the object. Schema: the question check's `Answers`.

| Model | Route | Schema sent | Answer |
| --- | --- | --- | --- |
| `cx/gpt-6-luna` | Chat Completions | no | `hello` (2 of 2) |
| `cx/gpt-6-luna` | Chat Completions | yes | `hello` (2 of 2): the gateway drops `response_format` |
| `cx/gpt-6-luna` | Responses | yes | `{"answers":[{"id":"1","probability":1}]}`: enforced |
| `ocg/deepseek-v4-flash`, `ocg/glm-5.3-flash`, `ocg/qwen3.8-flash`, `ocg/kimi-k3` | both | | `503`, usage limit exceeded: not measured |

Staging's task model is `cx/gpt-6-luna` on Chat Completions (`maxCompletionTokens` only). Declaring the capability
there changes nothing until the model is routed through Responses (`reasoningSummary: auto`) or 9Router forwards
`response_format`.

## Not verified

- A real call through Embabel end to end with the capability declared: the wire tests call the configurer and the
  chat model of the binding directly, and `ModelCallsTest` shows the mode asked for. The first declared model on
  staging is the check.
- Whether Embabel's own request carries `strict` from the declared capability: the wire test builds the request
  by hand.
- Whether Embabel keeps its format instructions in the prompt on the native path (read from bytecode only).
- The schema Embabel generates for `TranscriptSummarizer` and `TranscriptCorrector` answers under OpenAI's `strict`
  rules.
- Any gateway other than 9Router.
