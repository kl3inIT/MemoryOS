# System Context Diagram policy

Apply these rules whenever the requested artifact is a System Context Diagram:

1. Extract a closed evidence set before drawing:
   - exactly one system-of-interest name;
   - external people, organizations, systems, devices, or external data sources;
   - named information flows and their direction;
   - source requirement fact IDs for every entity and flow.
2. Use native Visual Paradigm Data Flow Diagram notation:
   - exactly one `DFProcess` for the whole system;
   - one `DFExternalEntity` for each actor or dependency outside the boundary;
   - one directed `DFDataFlow` for each semantically distinct input or output;
   - no `DFDataStore`, internal subprocess, class, use case, generic rectangle,
     or implementation component inside the Context Diagram.
3. A database, file, clock, device, or subsystem may be an external entity only
   when the supplied scope explicitly places it outside the system boundary.
   Its name does not determine its notation.
4. Every flow must connect the central system and exactly one external entity.
   Never connect two external entities. Split a two-way exchange into two named
   one-way flows unless the paper explicitly defines one bidirectional flow.
5. Flow labels are noun phrases describing information, such as `booking
   request`, `account information`, or `payment validation result`. Do not use
   vague labels such as `data`, and do not use control-flow verbs.
6. Default layout follows the official Visual Paradigm examples:
   - a circular system process centered on the canvas;
   - compact rectangular entities distributed across top, right, bottom, and
     left;
   - curved or oblique connectors approaching the nearest side of each shape;
   - parallel flows separated visibly, with horizontal readable captions;
   - no connector, arrowhead, or caption crossing a node or another caption.
7. Use this deterministic tool workflow:
   - call `createContextDiagram` first with `dryRun=true`;
   - inspect its radial layout plan and fix the specification if needed;
   - call it again with `dryRun=false` using the same `operationId`;
   - call `inspectContextDiagram` and retain its `revision`;
   - call `validateContextDiagram`;
   - use the targeted layout/routing tools only with the latest revision;
   - call `repairContextDiagramLayout`, inspect, and validate again.
8. A successful tool response is not approval. The acceptance gate is:
   one process, all required external entities, all and only required named
   directed flows, no data store, no orphan, no overlap, and readable rendering
   at 100% scale.

Primary reference:
https://online.visual-paradigm.com/knowledge/system-context-diagram/what-is-system-context-diagram/
