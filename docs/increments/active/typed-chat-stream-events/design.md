# Typed Chat stream events

Status: **proposed 2026-09-30; not started.** No Linear issue yet.

## Problem

A Chat turn streams events through a Redis Stream (`StreamBufferWriter`) to the browser (`ChatEventStream` in
`api`). Every event is one record, `StreamBufferWriter.Event`, with twelve components, most `@Nullable`, and a
`String type`:

- Callers pass nulls by position: `new Event(id, seq, type, null, null, null, null, null, false, event, null)`.
  Five telescoping constructors exist only to supply those nulls.
- `ChatEventStream.payload` switches on the strings `"text-delta"`, `"reasoning"`, `"research-plan"`, `"tool"`,
  `"image"`, `"code"`, `"outcome"` and others, and `StreamBufferWriter` switches on some of them again.
- A misspelled type, a new event not mapped in `api`, or a component filled in the wrong position compiles and
  fails only at run time.

## Reference

Spring AI `v2.1.0-M1`, `spring-ai-model/src/main/java/org/springframework/ai/chat/messages/part/`
([#6997](https://github.com/spring-projects/spring-ai/pull/6997)): `MessagePart` is a
`sealed interface … permits TextPart, ReasoningPart, ToolCallPart, ToolResultPart, MediaPart, UnknownPart`,
serialized with `@JsonTypeInfo(use = NAME, property = "type", defaultImpl = UnknownPart.class)` and
`@JsonSubTypes` naming each part. `StreamingParts` stamps a streamed part with its index and whether it is a delta,
and `MessageAggregator` merges deltas per index.

| | |
| --- | --- |
| Requirement | Compile-time exhaustiveness for the event protocol; no positional nulls |
| Reference strengths | One record per variant; the wire name is declared once; an unknown name decodes to a known fallback instead of failing |
| Proposed difference | MemoryOS's events are broader than message parts (research, tool progress, image, code, outcome), so MemoryOS keeps its own hierarchy and follows the reference's conventions rather than reusing `MessagePart` |
| Costs | One record per event type; `ChatEventStream` rewritten as a pattern-matching `switch` |
| Simpler baseline | An `enum` for `type` with the same wide record. Rejected: it keeps the nulls and the positional constructors |

## Design

- `sealed interface ChatStreamEvent` in `chat.streaming` with `assistantMessageId()` and `sequence()`, permitting one
  record per event: `TextDelta`, `Reasoning`, `ResearchPlan`, `TopLevelBranching`, `ResearchAgentStart`,
  `IntermediateReport`, `IntermediateReportCitations`, `ToolProgress`, `ImageProduced`, `CodeRun`, `Outcome`, and
  `UnknownEvent`. The exact list comes from today's type strings; each record has only its own non-null
  components.
- `@JsonTypeInfo(property = "type")` with `@JsonSubTypes` names **equal to today's strings**, so an event written by
  the previous version and still in Redis (within `ttl`/`doneTtl`) decodes after a deploy.
- `defaultImpl = UnknownEvent.class`: an event from a newer version read by an older one during a rolling deploy is
  skipped, not a failed replay.
- `ChatResearchEvent` (a `Kind` enum with nullable components) disappears: its kinds are already the
  `ResearchPlan`, `TopLevelBranching`, `ResearchAgentStart`, `IntermediateReport` and `IntermediateReportCitations`
  records. `ChatToolEvent` stays one record, built with the named factories from
  [record construction](../record-construction/design.md), which runs first.
- `StreamBufferWriter` publishes `ChatStreamEvent`; the telescoping constructors and the `String type` go.
- `ChatEventStream.payload` becomes an exhaustive `switch (event) { case TextDelta e -> … }` mapping to the existing
  `contract/` records, so the SSE JSON and `openapi.yml` do not change.

## Verification

- JSON round-trip test per event type, including an event serialized by the current `Event` shape (fixture) decoding
  to the new record, and an unknown `type` decoding to `UnknownEvent`.
- Existing streaming, replay and `ChatEventStream` tests unchanged; `OpenApiContractTest` shows no change.
- `clean check`.
