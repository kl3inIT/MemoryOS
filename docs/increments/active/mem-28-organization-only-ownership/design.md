# MEM-28 design: Organization-only ownership

## Outcome

MemoryOS removes the default-Workspace domain layer before source, knowledge, retrieval, and agent capabilities depend on it. `Organization` becomes both the hard customer tenant and the stable operational owner for the single Tasco AI Workspace product. The user-facing product may still be called an AI Workspace; no `Workspace` aggregate, membership, identifier, configuration, or API field remains.

Groups remain a future Organization-owned audience and capability mechanism. They do not own source or ingestion lifecycles. Current source ACL remains the future data-read ceiling and cannot be overridden by Organization roles or capabilities.

## Evidence and decision

The accepted Linear baseline originally assigned connector, credential, ingestion, knowledge, and ACL ownership to a nested Workspace. The clarified Tasco scope contains one AI Workspace for all employees, with specialized Agents separated by Groups, knowledge collections, action grants, and source ACL. No validated flow creates, selects, switches, independently retires, or delegates administration across multiple operational Workspaces.

Maintaining the default Workspace would therefore duplicate Organization membership and force every future aggregate, query, foreign key, cache key, invitation, and authorization check to carry a second identifier that never changes independently. MEM-28 supersedes that part of the baseline before those downstream capabilities land.

## Replacement invariants

1. One bootstrap-published Organization remains the installation tenant and operational owner.
2. Active membership in an active Organization is the sole application admission context. More than one active Organization remains an invariant failure.
3. Organization roles are `OWNER` and `MEMBER` in the current production flow. Capabilities, not role comparisons, gate frontend behavior.
4. Invitations scope to one Organization. Acceptance atomically creates or resolves the exact Actor binding, grants one Organization `MEMBER` row, and consumes the invitation.
5. Future source connections, credentials, ingestion jobs/checkpoints, knowledge resources/versions/chunks, ACL snapshots, collections, agents, actions, and conversations carry `organization_id` directly.
6. Groups, when introduced by a concrete flow, aggregate Organization capabilities and resource grants; they are neither tenant nor operational ownership boundaries.
7. Source ACL remains authoritative for resource reads. Organization ownership or administration never implies document clearance.
8. `GET /api/identity/me` projects only Actor, Organization presentation, Organization role, and backed capabilities.
9. No Workspace switcher, Workspace configuration, Workspace membership, dormant Workspace enum, compatibility alias, or deprecated API path remains.

## Migration and deployment

Applied V1–V3 Flyway migrations remain immutable historical schema. V4 performs a direct clean cutover: it adds the Organization foreign key required by Invitation, drops the historical Workspace invitation column, then drops Workspace memberships, the Organization default-Workspace reference, and Workspaces. No compatibility layer, dual-write mode, data translation, or rollback runtime is shipped.

V4 and all runtime SQL ship in one deployable. The current Tasco runtime contains the single generated default Workspace and lockstep memberships produced by the supported bootstrap/invitation flows; preserving divergent or speculative Workspace states is outside this cutover contract.

Deployment safety remains independent from migration complexity. Before the first V4 staging deploy, stop writers, capture and verify a PostgreSQL dump, restore it into an isolated rehearsal database, run the exact cutover image, compare Flyway state and Organization/membership/invitation counts, then smoke owner login and one member invitation. After V4, rollback requires restoring that dump and the prior image because old SQL cannot run against the collapsed schema.

## Product terminology

The product surface may say `Tasco AI Workspace` as a product name. Authority labels describe the durable model as `Owner` or `Member`; they do not claim a Workspace role. Living architecture/spec/runbook documents use `Organization` for the persisted boundary. Completed increment evidence remains unchanged as historical fact.

## Explicit exclusions

- Group tables, SCIM, IdP-group provisioning, or a generic policy engine;
- source, knowledge, retrieval, or Agent implementation;
- schema-per-Organization tenancy;
- multi-Organization switching;
- compatibility shims for removed Workspace APIs or configuration.
