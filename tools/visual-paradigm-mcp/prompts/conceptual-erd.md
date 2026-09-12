# Conceptual ERD prompt contract

Use this contract only when the paper requests a Conceptual ERD.

## Semantic gate

1. Build a fact ledger from the paper before choosing entities.
2. Model persistent business information, not screens, services, repositories,
   reports, analytics calculations, or implementation components.
3. Use singular business nouns for entity names.
4. Keep the model conceptual:
   - no columns or attributes in the diagram;
   - no PK, FK, data type, table constraint, join-table implementation, or DBMS detail;
   - put the concise entity meaning in `description` for the written answer.
5. Give every relationship a short business verb that reads naturally from
   `from` to `to`.
6. Give both ends explicit cardinality:
   `ONE`, `ZERO_OR_ONE`, `ONE_OR_MANY`, or `ZERO_OR_MANY`.
7. Every entity and relationship must cite at least one fact ID. Reject
   invented entities added only to make the diagram look complete.
8. Prefer the smallest model that preserves the information named by the
   paper. Details without an independent business identity may remain in the
   owning entity description.

## Visual gate

1. Use native Visual Paradigm `Entity Relationship Diagram` in Conceptual
   data-model mode with Crow's Foot notation.
2. Use compact entity boxes sized to the name only and match Visual Paradigm
   desktop's native Conceptual entity appearance: light green fill
   (`RGB 230,245,122`), native font, rounded corners, compact height, and the
   empty compartment divider. Do not hide the compartment merely because the
   Conceptual entity has no columns.
3. Arrange entities on a deliberate grid. Put the main transaction entity near
   the center, upstream owners/resources above it, and dependent records around
   it.
4. Keep relationship names near the middle of their own connector. Never leave
   a relationship label or cardinality detached from the line.
5. Prefer direct oblique lines for a sparse model. Use rectilinear routing only
   where it materially reduces crossings.
6. No connector may pass through an entity. Entity shapes may not overlap.
7. Inspect at 100% scale. Repair clipped names, oversized boxes, crossings,
   ambiguous cardinalities, and labels that cover endpoint markers.

## Required execution order

1. `createConceptualErd(spec, true)`
2. Review fact coverage, entity descriptions, relationship verbs, both-end
   cardinalities, and the planned grid.
3. `createConceptualErd(spec, false)`
4. `inspectConceptualErd`
5. `validateConceptualErd`
6. `relayoutConceptualErd` or `repairConceptualErdPresentation` if necessary
7. Visually inspect the open diagram.
8. After the final layout only, call `prepareConceptualErdForSubmission` to
   remove internal metadata icons, then save and reopen the project.

Primary references:

- https://www.visual-paradigm.com/support/documents/vpuserguide/3563/3564/85378_conceptual,l.html
- https://www.visual-paradigm.com/tutorials/how-to-model-relational-database-with-erd.jsp
