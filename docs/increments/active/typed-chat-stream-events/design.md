# Typed Chat stream events

Status: **implemented 2026-09-30.** Linear: [MEM-202](https://linear.app/memory-os/issue/MEM-202).

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

## Owner decisions (2026-09-30)

1. The earlier event names are not kept. Names follow Spring AI's convention (lower case with underscores, and its
   own name where the meaning is the same), in Redis and in the SSE sent to the browser alike.
2. `tool`, `image` and `code` stay one event each. They are progress shown to the person, by stage, and not the
   `tool_call`, `tool_result` or `media` a model exchanges.
3. One record per file, as Spring AI; records are not nested to keep the change small.

## As delivered

| Record | Wire `type` | Was |
| --- | --- | --- |
| `TextDelta` | `text` | `text-delta` |
| `Reasoning` | `reasoning` | `reasoning` |
| `ResearchPlan` | `research_plan` | `research-plan` |
| `TopLevelBranching` | `top_level_branching` | `top-level-branching` |
| `ResearchAgentStart` | `research_agent_start` | `research-agent-start` |
| `IntermediateReport` | `intermediate_report` | `intermediate-report` |
| `IntermediateReportCitations` | `intermediate_report_citations` | `intermediate-report-citations` |
| `ToolProgress` | `tool` | `tool` |
| `ImageProgress` | `image` | `image` |
| `CodeRun` | `code` | `code` |
| `Outcome` | `outcome` | `outcome` |
| `UnknownEvent` | `unknown` | none |

- Each record declares its name as `TYPE`; `@JsonSubTypes` and `type()` read that constant, and the API uses
  `type()` as the SSE event name, so a name exists once.
- The API contract record `TextDeltaEvent` is `TextEvent`; `openapi.yml` and the web client are regenerated and the
  web transport, its test and the e2e chat fixture use the new names.
- `ChatResearchEvent` does not disappear as the design first said: it stays the in-process activity event, and the
  writer turns each of its kinds into the matching stream record.
- An event written under an earlier name decodes to `UnknownEvent`, so a reply that was streaming across the deploy
  is read from history, and a browser tab still running the earlier web build ignores the new names until reloaded.

## Verification

- JSON round-trip test per event type, an unknown `type` decoding to `UnknownEvent`, and a known event ignoring
  components it does not know (`ChatStreamEventTest`). No fixture of the earlier shape: the names are not kept.
- Existing streaming, replay and `ChatEventStream` tests with the new names; `openapi.yml` changes only the
  `TextEvent` schema name and the event list; `chat-transport.test.ts`.
- `clean check`.
