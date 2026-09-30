# Implementation plan

Nothing is implemented yet.

- [ ] Owner review of [design](design.md); open a Linear issue.
- [ ] Extract `ResponsesRequestBuilder` and `ResponsesInputMapper`; fixture tests green.
- [ ] Extract `ResponsesStreamAssembler`, `ResponsesMetadata`, `OpenAiResponsesException`; fixture tests green.
- [ ] Unit tests per class; `clean check`.
- [ ] Update the Chat Web and model specs; after merge move to `completed/` and reconcile the roadmap.
- [ ] Later, with Spring AI 2.1: replace with Spring AI's model and delete these classes.
