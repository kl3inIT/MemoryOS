# Implementation plan

Nothing is implemented yet.

- [ ] Owner review of [design](design.md); open a Linear issue.
- [ ] List every type string `StreamBufferWriter` writes and `ChatEventStream` reads; fix the record list.
- [ ] Record fixtures of today's serialized events before changing the type.
- [ ] `ChatStreamEvent` and its records with `@JsonTypeInfo`/`@JsonSubTypes` and `UnknownEvent`.
- [ ] `StreamBufferWriter` publishes and replays the sealed type; remove the wide `Event` and its constructors.
- [ ] `ChatEventStream` maps with an exhaustive `switch`; SSE contract unchanged.
- [ ] Round-trip, legacy-fixture and unknown-type tests; existing streaming tests unchanged; `clean check`.
- [ ] Update the Chat spec and test matrix; after merge move to `completed/` and reconcile the roadmap.
