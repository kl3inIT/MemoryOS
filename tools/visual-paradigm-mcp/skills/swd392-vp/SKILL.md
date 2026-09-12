---
name: swd392-vp
description: Create and verify native System Context, Use Case, Conceptual ERD, Class, Sequence, Activity, and State diagrams in Visual Paradigm through the local Spring AI MCP server.
---

# SWD392 Visual Paradigm workflow

Use the system prompt in `../../prompts/diagram-agent-system.md`.

## Required runtime order

1. Open Visual Paradigm and a project. The Java 11 bridge starts on port 2026.
2. Start `mcp-server`; it discovers bridge tools and serves Streamable HTTP at
   `http://127.0.0.1:2027/mcp`.
3. Connect the OpenAI-powered MCP client to that endpoint.

## Diagram order

- Class: diagram -> classes -> attributes/operations -> fit every classifier
  with `fitClassToContents` -> position classifiers -> relationships -> route
  connectors -> export/inspect. Never leave a fixed-height blank compartment;
  the class bottom must sit directly below its last visible member.
- Sequence: diagram -> lifelines -> activations -> messages -> return messages
  -> combined fragments -> layout.
- Activity: diagram -> initial/action/decision/merge/fork/join/final nodes ->
  control flows with guards -> layout.
- State: diagram -> initial/states/final -> transitions with
  `trigger [guard] / effect` -> layout.
- System Context: strict declarative spec -> `createContextDiagram` dry-run ->
  native central process/entities/data flows -> inspect -> validate -> targeted
  revision-safe layout/route repair -> inspect/validate again. Also apply
  `../../prompts/context-diagram.md`.
- Use Case: extract a requirement fact ledger -> strict declarative spec ->
  apply the mandatory-vs-conditional relationship gate ->
  `createUseCaseDiagram` dry-run -> native system/actors/use cases/relationships
  -> inspect -> validate -> targeted presentation repair -> inspect/validate
  again. Also apply `../../prompts/use-case-diagram.md`. Never create
  `include`, `extend`, or generalization only to demonstrate notation.
- Conceptual ERD: extract persistent business information into a fact ledger ->
  strict declarative spec -> `createConceptualErd` dry-run -> native Crow's
  Foot ER Diagram in Conceptual mode -> inspect -> validate -> targeted
  relayout/repair -> inspect at 100% -> remove internal metadata only after the
  final layout. Also apply `../../prompts/conceptual-erd.md`. Never add columns,
  PK/FK, data types, constraints, implementation join tables, or DBMS details.

Always call `getDiagramElements` after layout and `saveProject` only after the
verification result matches the problem statement.

## Submission gate

The finished deliverable is the retained Word template, not only the `.vpp`
project. After semantic and visual approval:

1. On Evaluation Copy, capture clean screenshots manually and save them under
   `build/submission-assets/manual-q1-class.png`, `manual-q2-sequence.png`, and
   `manual-q3-state.png`. Never submit native exports containing the evaluation
   watermark.
2. Run `submission.ps1` to copy the original template and insert the images and concise
   explanations into their matching questions.
3. Open the generated `.docx` in Microsoft Word and inspect every page for
   clipping, orphan labels, unreadable scaling, or accidental blank pages.
4. Keep the original template and `.vpp` project unchanged for recovery.
