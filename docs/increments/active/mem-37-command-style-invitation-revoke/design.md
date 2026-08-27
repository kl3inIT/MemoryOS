# MEM-37 — Command-style invitation revocation and product API tags

## Problem

`DELETE /api/invitations/{invitationId}` transitions a durable invitation to `REVOKED`. The invitation remains addressable and history-bearing, so DELETE describes the implementation poorly and conflicts with the repository API convention that revoke/disable transitions use explicit commands.

The generated OpenAPI snapshot also exposes `invitation-controller` and `identity-controller`. Those names describe Spring implementation classes rather than stable consumer-facing product areas.

## Decision

Make one clean contract cutover:

```text
POST /api/invitations/{invitationId}/revoke
```

The command preserves operation ID `revokeInvitation`, owner authorization, same-origin protection, lifecycle semantics, RFC 9457 failures, and the `204 No Content` success response. The old DELETE route is removed without an alias.

Add controller-level OpenAPI tags:

```text
Invitations
Identity
```

Method-level operation IDs remain stable, so the generated `revokeInvitation` SDK and React Query mutation names remain stable while their transport becomes POST to the command path.

## Ownership

This is an API transport correction only. Invitation owns revocation behavior and persistence exactly as before. API owns HTTP routing, OpenAPI annotations, security composition, and response translation. The generated client remains derived from the live Spring MVC contract.

## Verification

- Owner integration revokes through the POST command and observes the durable REVOKED lifecycle.
- Member integration receives the existing `403 INVITATION_NOT_OWNER` through the POST command.
- OpenAPI contains the new path, no old revoke operation, stable operation ID, and only consumer-facing Identity/Invitations tags for current operations.
- Generated Hey API code uses POST and the new path; the browser flow uses that generated mutation.

## Exclusions

- No change to invitation lifecycle or persistence.
- No compatibility DELETE route.
- No operation ID rename.
- No generic command framework or API versioning layer.
