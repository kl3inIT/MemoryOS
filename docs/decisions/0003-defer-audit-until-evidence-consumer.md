# ADR 0003: Defer audit until an evidence consumer exists

- Status: Accepted
- Date: 2026-08-19
- Decision owner: MemoryOS

## Context

The first MEM-8 implementation introduced an `audit` capability, an `audit_events` table, and event names for bootstrap, invitations, membership administration, and context switching. Only initial-owner bootstrap had a complete runtime path. The other mutations had no authorized browser surface, and no audit reader, retention policy, export contract, incident workflow, or compliance consumer existed.

Keeping that model would freeze an event taxonomy before its evidence requirements were known. A write-only event table is not an audit system and would add a second incomplete lifecycle beside the authority transaction.

## Decision

Do not ship a generic audit capability or append-only event schema until a concrete security, operational, or compliance consumer defines:

1. the authority changes that require evidence;
2. actor and operator attribution semantics;
3. success and rejection recording boundaries;
4. transaction and rollback coupling;
5. retention, access, redaction, and export behavior; and
6. the query or incident workflow that consumes the evidence.

For the initial-owner flow, the production authority transaction is the evidence boundary currently required. `organization_bootstrap_state.initial_organization_id`, the exact organization aggregate, the configured owner binding, and `organizations.bootstrap_reference` prove the published bootstrap result and deployment change reference. Configuration drift fails startup.

Invitation onboarding is tracked separately by MEM-12. Its design must establish any required evidence contract from the real invitation and administration consumers before implementation.

## Consequences

- `core` has seven capabilities; there is no `audit` capability or `audit_events` table.
- `organization` depends only on the public `identity` contract.
- Initial bootstrap remains atomic, singleton, replay-verifiable, and fail-closed without a synthetic success event.
- Database and infrastructure logs remain operational diagnostics, not a product audit contract.
- No membership, invitation, context-switch, or account-linking mutation may be exposed without the authorization and evidence design required by its own increment and [ADR 0002](0002-no-speculative-operational-surfaces.md).