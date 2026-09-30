# Implementation plan

Nothing is implemented yet.

- [x] Record the construction rule in [conventions](../../../conventions.md#java-and-gradle).
- [x] Owner go-ahead 2026-09-30; [MEM-202](https://linear.app/memory-os/issue/MEM-202).
- [x] `ChatCommand` and `ChatMessage` builders; remove their extra constructors; migrate callers.
- [x] `ChatTurnSetup` and `TurnContext` builders; same.
- [x] `ChatSource` and `ChatToolEvent` named factories; remove their extra constructors.
- [x] Sweep main code for other records with telescoping constructors or three or more positional nulls and apply
  the rule.
- [ ] `clean check`; after merge move to `completed/` and reconcile the roadmap.
