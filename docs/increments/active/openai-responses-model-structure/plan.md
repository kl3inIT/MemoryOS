# Implementation plan

Nothing is implemented yet.

- [x] Owner go-ahead 2026-09-30; [MEM-202](https://linear.app/memory-os/issue/MEM-202).
- [x] Extract `ResponsesRequestBuilder` and `ResponsesInputMapper`; fixture tests green.
- [x] Extract `ResponsesStreamAssembler`; fixture tests green. `ResponsesMetadata` and
  `OpenAiResponsesException` are not built ([as delivered](design.md#as-delivered)).
- [x] Unit tests per class; `clean check`.
- [x] No spec names the class; the Chat test matrix is updated.
- [ ] After merge move to `completed/` and reconcile the roadmap.
- [ ] Later, with Spring AI 2.1: replace with Spring AI's model and delete these classes.
