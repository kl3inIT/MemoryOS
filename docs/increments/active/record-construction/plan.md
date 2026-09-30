# Implementation plan

Nothing is implemented yet.

- [x] Record the construction rule in [conventions](../../../conventions.md#java-and-gradle).
- [ ] Owner review of [design](design.md); open a Linear issue.
- [ ] `ChatCommand` and `ChatMessage` builders; remove their extra constructors; migrate callers.
- [ ] `ChatTurnSetup` and `TurnContext` builders; same.
- [ ] `ChatSource` and `ChatToolEvent` named factories; remove their extra constructors.
- [ ] Sweep main code for other records with telescoping constructors or three or more positional nulls and apply
  the rule.
- [ ] `clean check`; after merge move to `completed/` and reconcile the roadmap.
