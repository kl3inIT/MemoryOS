# MEM-16 implementation plan: RFC 9457 API errors

## Contract

- [x] Add root-package `FailureCategory` and abstract `BusinessException` without changing the eight-module graph.
- [x] Give `InvitationFailureReason` capability-prefixed stable codes, semantic categories, and safe fallback messages.
- [x] Make `InvitationException` copy its reason descriptor into `BusinessException`, retain its typed reason accessor, and support a standard cause constructor.
- [x] Enable Spring Boot MVC Problem Details for built-in framework exceptions.

## API

- [x] Replace the Invitation-scoped advice with one REST-only `ApiExceptionHandler` for `BusinessException`.
- [x] Map failure category to HTTP status/title once and render RFC 9457 `ProblemDetail` with derived URN type and `code` extension.
- [x] Preserve browser redirect handlers, Spring Security filter responses, and `ACCESS_NOT_PROVISIONED` browser response unchanged.
- [x] Remove generic Invitation exception-construction helpers while retaining semantic helpers.

## Published contract

- [x] Replace `InvitationError` responses with `application/problem+json` in OpenAPI.
- [x] Regenerate the Hey API client and remove obsolete generated error types.
- [x] Update architecture, API conventions, invitation specification, and verification matrix.

## Verification

- [x] Verify one capability failure body, status, media type, stable code, URN type, safe detail, and request instance through the real API.
- [x] Verify one built-in MVC failure uses Boot-native Problem Details.
- [x] Verify browser redirect and Spring Security failure contracts remain unchanged.
- [x] Run JetBrains inspections with warnings for every changed Java/YAML file, then compile.
- [x] Run focused API/browser tests, `gradlew.bat clean check --no-daemon`, `pnpm check`, and browser contracts.
