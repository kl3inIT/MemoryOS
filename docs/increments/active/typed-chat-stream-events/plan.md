# Implementation plan

Nothing is implemented yet.

- [x] Owner go-ahead and naming decisions 2026-09-30; [MEM-202](https://linear.app/memory-os/issue/MEM-202).
- [x] List every type string `StreamBufferWriter` writes and `ChatEventStream` reads; fix the record list.
- [x] No fixtures of the earlier shape: the owner decided not to keep the names.
- [x] `ChatStreamEvent` and its records with `@JsonTypeInfo`/`@JsonSubTypes` and `UnknownEvent`.
- [x] `StreamBufferWriter` publishes and replays the sealed type; remove the wide `Event` and its constructors.
- [x] `ChatEventStream` maps with an exhaustive `switch`; the SSE event names follow the records.
- [x] Round-trip, legacy-fixture and unknown-type tests; existing streaming tests unchanged; `clean check`.
- [ ] Update the Chat spec and test matrix; after merge move to `completed/` and reconcile the roadmap.
