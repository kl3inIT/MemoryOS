# Use Case Diagram semantic and presentation gate

Apply this prompt together with `diagram-agent-system.md` whenever the requested
artifact is a UML Use Case Diagram.

## 1. Build a verified requirement-fact ledger first

Read the supplied paper before selecting any actors, use cases, associations, or
use-case relationships. Create stable fact IDs and concise fact statements that
preserve the paper's meaning. Facts may be paraphrased, but must not add a
business rule that the paper does not state or necessarily imply.

Every actor, use case, association, and use-case relationship must reference at
least one fact ID. A missing or unknown fact ID is a hard failure. A fact ID is
traceability evidence, not decoration.

## 2. Model actor goals, not screen operations

- Actors are external roles or external systems, not departments hidden inside
  the system and not implementation components.
- Decide the target-system boundary before naming actors. A device or subsystem
  is an actor only when it is outside that boundary and exchanges behavior with
  the target system; never move it inside or outside merely to improve layout.
- Name a use case with a concise verb phrase that expresses an actor goal or a
  service the system provides.
- Keep actors outside one named system boundary and use cases inside it.
- Add an actor-to-use-case association only when the paper supports that role's
  participation.
- An association means direct participation in that use case, not that the role
  benefits from, owns data used by, or appears elsewhere in the workflow. When
  one role initiates a goal through another role or channel, associate the
  initiating/directly interacting role supported by the paper; add both only
  when both really participate in the system interaction.

## 3. Prove every include, extend, or generalization

Do not create a relationship merely because two use cases are related, occur in
sequence, share an actor, or make the diagram look more complete.

### `<<include>>`

Use `INCLUDE` only when the included use case is an unconditional, mandatory
subflow of the base use case or an explicitly shared mandatory sequence. The
base use case is `from`; the included use case is `to`. Its `condition` must be
blank. The relationship rationale must explain why the base cannot complete
without the included behavior and cite the supporting fact IDs.

Words such as "may", "can", "optional", "when needed", "after approval", or a
separate lifecycle state do not prove `include`.

### `<<extend>>`

Use `EXTEND` only when extra behavior is inserted into an otherwise meaningful
base use case under a specific condition. The extension use case is `from`; the
base use case is `to`. Supply a short explicit condition and a rationale tied
to the fact ledger.

A normal next step in a workflow is not automatically an extension. Use
`extend` only when the base remains valid without the extension in the
condition where it does not run.

### Generalization

Use `GENERALIZATION` only for a real substitutable is-a specialization between
two actors or two use cases. The specialized element is `from`; the general
element is `to`. A shared association or similar name is not proof.

If the requirement facts do not prove one of these semantics, omit the
relationship and retain separate actor associations. Ambiguity defaults to no
use-case relationship.

## 4. Mandatory execution order

1. Extract and show the fact ledger.
2. List candidate actors and actor goals with their fact IDs.
3. For each proposed use-case relationship, state:
   - type;
   - direction;
   - condition;
   - concise business rationale;
   - supporting fact IDs.
4. Reject unsupported candidates.
5. Call `createUseCaseDiagram` with `dryRun=true`.
6. Fix every schema or semantic-gate failure before a real mutation.
7. Create the native diagram with the same validated specification.
8. Inspect and validate the native result.
9. Repair presentation without changing semantics, then inspect and validate
   again.

Never bypass the dry-run by assembling a fresh Use Case Diagram from low-level
shape and connector tools.

## 5. Visual acceptance gate

- The system title is readable and the boundary is no larger than necessary.
- Actors are aligned outside the boundary; use cases are aligned inside it.
- Use presentation-only `group` values to cluster use cases by their primary
  actor or cohesive business area. Leave visibly larger whitespace between
  groups than between use cases in one group.
- Place a use case shared by two actors between their two clusters when this
  shortens both association paths.
- Order business groups so the connected use cases of actors on the same side
  form mostly disjoint vertical bands. An actor that primarily participates in
  upper groups must appear above one that primarily participates in lower
  groups.
- Align both ends of an `include` or `extend` relationship on the same visual
  row whenever possible. Prefer one short horizontal dependency over a long
  diagonal connector.
- Association lines terminate at the correct actor and use case.
- Dashed `include` and `extend` arrows point in the UML direction.
- Each stereotype caption is centered on its own connector with enough
  clearance from both use-case ellipses.
- No orphan label, clipped caption, crossing through a use case, or accidental
  overlap remains at 100% inspection scale.
- Treat avoidable connector crossings and star-shaped fan-out as failed
  presentation. First remove unsupported associations, then reorder groups and
  actors, and only then adjust connector routing.

Primary notation reference:
https://online.visual-paradigm.com/diagrams/tutorials/use-case-diagram-tutorial/
