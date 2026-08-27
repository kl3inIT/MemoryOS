# MEM-37 verification matrix

| Requirement | Required evidence |
| --- | --- |
| Revocation is an explicit durable command | Owner HTTP integration calls `POST /api/invitations/{invitationId}/revoke`, receives 204, and observes REVOKED state |
| Backend authority remains unchanged | Real member HTTP integration receives `403 INVITATION_NOT_OWNER` from the POST command |
| Clean transport cutover | Live mappings, OpenAPI, generated client, backend tests, and browser mocks contain no DELETE revoke operation |
| Stable generated client API | Operation ID and generated function remain `revokeInvitation`; generated transport is POST to the command path |
| Consumer-facing OpenAPI taxonomy | Current operations use `Identity` and `Invitations`; generated controller names are absent |
| Repository compatibility | Focused backend tests, frontend check/browser flow, and backend `clean check` pass |

## Evidence

- `InvitationController` exposes only `POST /api/invitations/{invitationId}/revoke`; `IdentityController` and `InvitationController` publish stable class-level OpenAPI tags.
- `OpenApiContractTest` asserts the exact live path set, POST operation ID, product tags, and absence of the old invitation-ID path. `SessionSecurityIntegrationTest` exercises owner `204` revocation and member `403 INVITATION_NOT_OWNER` through the command route.
- Regenerated `openapi.yml` and Hey API files contain `revokeInvitation` as POST to `/api/invitations/{invitationId}/revoke`; generated controller tags and the DELETE transport are absent.
- JetBrains warnings-enabled inspection found no errors in changed Java/OpenAPI files. The existing weak warning for the intentional custom `X-MemoryOS-CSRF` header remains; focused HTTP and browser scenarios exercise its required value.
- Focused `OpenApiContractTest` plus `SessionSecurityIntegrationTest`: passed.
- `pnpm check`: passed, including generated-client stability, lint, formatting, TypeScript, 25 unit tests, route stability, and production build.
- Targeted Chromium invitation create/rotate/revoke flow: 1/1 passed and asserted the revoke command's same-origin header.
- `gradlew.bat clean check --no-daemon`: passed; 17 tasks, 9 executed and 8 from cache.
