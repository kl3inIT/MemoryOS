# Plan

- [x] Input budget from the model window with the Onyx reserve (1,024) and 5% margin; optional deployment cap.
- [x] Output bound for bounded work: model limit or Onyx's 32,000-token fallback within a quarter of the window.
- [x] Search: 50 candidates, 25,600 selection and evidence tokens, no call or helper count.
- [x] MCP call limit optional; image tools uncounted.
- [x] Unknown deployment model takes the discovery defaults.
- [x] Tests: `ChatModelBindingLimitsTest`, `ChatModelCatalogConfigurationTest`; `:core:test :api:test` green.
- [x] Docs: chat-models and chat specs, MCP runbook, chat verification matrix.
- [ ] Staging: a long luna conversation keeps its history beyond 32,000 tokens; search returns more evidence.
