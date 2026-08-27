# MEM-37 implementation plan

## Foundation

- [x] Create and align Linear issue MEM-37.
- [x] Record the command-style route, product tags, clean cutover, and exclusions.
- [x] Add MEM-37 to the active increment maps.

## Contract cutover

- [x] Replace invitation revoke DELETE mapping with `POST /api/invitations/{invitationId}/revoke` while preserving operation ID and response semantics.
- [x] Add stable `Invitations` and `Identity` OpenAPI tags at the controller boundary.
- [x] Update backend HTTP and OpenAPI contract tests, including absence of the old route.
- [x] Regenerate `openapi.yml` and the Hey API client from the live Spring MVC contract.
- [x] Update the browser route harness and invitation living contract.

## Verification

- [x] Inspect every changed Java file and `openapi.yml` with JetBrains warnings enabled.
- [x] Run focused API security and OpenAPI contract tests.
- [x] Run `pnpm check` and the invitation browser flow.
- [x] Run `gradlew.bat clean check --no-daemon`.
- [x] Record exact evidence in `verification.md`.
