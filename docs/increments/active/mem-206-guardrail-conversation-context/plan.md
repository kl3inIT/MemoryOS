# Implementation plan

- [x] `GroundingClassifier`: Llama Guard layout, conversation input, last message judged, bounded context, markers
  removed, temperature 0.
- [x] `ModelCalls.generateObject` with a temperature; `OpenAiRequestPolicy.takesTemperature` shared by the converter and
  the turn sampling.
- [x] `ChatTurnService`: earlier messages to the check; one attempt, no second model.
- [x] Tests: `GroundingClassifierTest`, `ChatGroundedTurnTest`, `ModelCallsTest`, `OpenAiProviderAdapterTest`.
- [x] Chat spec and test matrix.
- [ ] CI; merge; staging follow-up check.
