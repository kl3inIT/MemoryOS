# MEM-16 verification

Date: 2026-08-22

## Implemented contract

- Added root-package `FailureCategory` and abstract `BusinessException` without changing the eight closed Spring Modulith modules. Construction rejects blank or nonconforming codes before they can violate OpenAPI or break URN creation.
- `InvitationFailureReason` owns capability-prefixed codes, semantic categories, and safe English fallback messages; `InvitationException` retains its typed reason while copying public response fields into the base exception.
- Enabled Spring Boot 4 MVC Problem Details for built-in framework exceptions.
- Replaced the Invitation-scoped advice with one REST-only `ApiExceptionHandler` that handles `BusinessException`, maps category once, and renders RFC 9457 responses with derived `urn:memoryos:failure:*` type and `code` extension.
- Removed generic Invitation exception-construction helpers and the `initCause` mutation path. Semantic `notAvailable` and `identityConflict` helpers remain.
- Preserved browser redirect handlers, Spring Security filter failures, and the `ACCESS_NOT_PROVISIONED` browser response.

## Published contract

- Replaced `InvitationError` with the generic `ApiProblem` schema in every Invitation 400/403/409/410 response.
- Error responses use `application/problem+json`.
- Generic `type` and `code` fields are optional because Boot-native framework problems may omit both when the effective type is `about:blank`; expected capability failures always provide both.
- Regenerated the Hey API client. `InvitationError` is absent and `ApiProblem` is the generated error type.

## Behavioral evidence

`ApiExceptionHandlerTest.mapsEveryInvitationFailureToOneSafeProblemContract` verifies every Invitation reason maps to the expected status, category title, safe detail, namespaced code, and derived URN type while the diagnostic exception message remains private.

`BrowserAuthenticationIntegrationTest.returnsProblemDetailsForBusinessAndFrameworkFailures` exercises the real Spring API and proves:

- invalid Invitation email returns `400 application/problem+json`;
- body contains `INVITATION_INVALID_EMAIL`, `urn:memoryos:failure:invitation-invalid-email`, safe detail, and request instance;
- malformed JSON returns Boot-native `400 application/problem+json` with safe framework detail and no capability code;
- missing same-origin mutation header returns Boot-native `403 application/problem+json` with the published generic fields and no capability code;
- existing browser, redirect, session, and security scenarios remain green.

## Static and repository gates

- JetBrains inspections with warnings enabled report no new Java or YAML errors. The existing weak warning for the intentional `X-MemoryOS-CSRF` header remains covered by browser integration tests.
- Focused API error, browser-authentication, and Modulith architecture tests pass.
- `gradlew.bat clean check --no-daemon` passes.
- `pnpm check` passes generated-client stability, lint, formatting, TypeScript, unit tests, route generation, and production build.
- `pnpm test:e2e` passes 9/9 Chromium contracts.

## Deferred

MessageSource i18n remains absent until MemoryOS owns a locale contract, frontend translation system, translation catalog, and `Accept-Language` verification matrix. Stable codes and safe fallback messages allow that addition without changing exception or client contracts.
