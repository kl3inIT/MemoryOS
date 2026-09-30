# Record construction without telescoping constructors

Status: **proposed 2026-09-30; not started.** No Linear issue yet. Applies the
[construction rule](../../../conventions.md#java-and-gradle).

## Problem

Several `chat` records grew one overload per feature so that existing callers did not change. Most overloads are
now called only by tests, which the [testing convention](../../../conventions.md#testing) forbids.

| Record | Extra constructors | `new` in main / test | Shape |
| --- | --- | --- | --- |
| `ChatMessage` | 7 | 1 / 10 | Many optional components |
| `ChatCommand` | 6 | 6 / 18 | Optional files, Web mode, image mode, model |
| `ChatTurnSetup` | 5 | 6 / 2 | Optional research, files, tools |
| `ChatTurnPersistence.TurnContext` | 4 | 1 / 5 | Optional components |
| `ChatSource` | 4 | 6 / 9 | A document, Web or file citation |
| `ChatToolEvent` | 5 | 16 / 30 | One per tool stage |
| `StreamBufferWriter.Event` | 5 | — | One per event type; handled by [typed Chat stream events](../typed-chat-stream-events/design.md) |
| `ChatResearchEvent` | static factories | — | One per research kind; folded into `ChatStreamEvent` there |

## Design

Per the rule, the shape decides the tool:

- **Builder** for a record with many optional components that are not variants: `ChatMessage`, `ChatCommand`,
  `ChatTurnSetup`, `TurnContext`. A `static Builder builder(required…)` nested in the record, setters for the
  optional parts, `build()` calling the canonical constructor, whose compact form keeps the validation. No
  Lombok. Every extra constructor is removed and every caller, main and test, migrates in the same change.
- **Named factory methods** where each variant has a meaning: `ChatSource.document(…)`, `ChatSource.web(…)`,
  `ChatSource.file(…)`; `ChatToolEvent.started(…)`, `searching(…)`, `source(…)`, `reading(…)`, `finished(…)`
  (the last two exist). The JSON of both stays the same because the canonical components do not change.
- **Test data builders** only in `src/test` where a fixture needs many fields; no production constructor exists
  for tests.

## Verification

Behavior-preserving: existing tests pass after migrating their construction; `OpenApiContractTest` unchanged;
`clean check`.
