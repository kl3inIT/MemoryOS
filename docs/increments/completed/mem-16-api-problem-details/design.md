# MEM-16 design: RFC 9457 API error contract

## Outcome

MemoryOS REST APIs expose expected capability failures and Spring MVC failures through one stable RFC 9457 Problem Details contract on Spring Boot 4.1 and Spring Framework 7. Business error codes remain transport-independent, capability-prefixed, and safe for generated clients.

## Current problem

Invitation errors currently use a controller-scoped advice and a bespoke `{code,message}` body. Every new capability would otherwise require another exception handler and another response type. Generic service helpers also wrap `InvitationException` constructors without adding policy, and the cause overload mutates the exception through `initCause`.

## Runtime boundary

Spring Boot owns framework-error translation when `spring.mvc.problemdetails.enabled=true`. Its auto-configured `ProblemDetailsExceptionHandler` remains active; MemoryOS does not extend `ResponseEntityExceptionHandler` and does not catch `Exception` globally.

`ApiExceptionHandler` applies only to `@RestController` types and handles the abstract `BusinessException`. Browser intake and OAuth handlers continue to catch typed capability exceptions for redirects. Spring Security filter-chain failures remain outside MVC advice. `BrowserPageController`'s `ACCESS_NOT_PROVISIONED` response is a browser destination contract and is intentionally not migrated.

## Shared failure vocabulary

The shared types live directly in `io.memoryos`, not a new direct subpackage, because every direct subpackage is a Spring Modulith module and the repository intentionally has eight capability modules.

- `FailureCategory`: only `VALIDATION`, `NOT_PERMITTED`, `CONFLICT`, and `UNAVAILABLE` until another category has a real producer.
- `BusinessException`: abstract expected-failure base that snapshots a stable code, semantic category, safe English fallback message, diagnostic-only message, and optional cause.

Typed capability enums retain their codes/categories/messages without implementing a shared interface. Typed final exceptions copy those values into `BusinessException` and retain their concrete reason for exhaustive browser-flow switches.

## Public response

A capability failure produces `application/problem+json`:

```json
{
  "type": "urn:memoryos:failure:invitation-not-available",
  "title": "Unavailable",
  "status": 410,
  "detail": "This invitation is no longer available.",
  "instance": "/api/invitations/current",
  "code": "INVITATION_NOT_AVAILABLE"
}
```

The API maps `FailureCategory` to status and title once. `type` is derived mechanically from the stable code. The API never exposes `BusinessException.getMessage()`; diagnostic messages remain server-side. Clients branch on `code` or status, never on `title` or `detail`.

## Codes

Invitation codes are capability-prefixed to prevent collisions:

- `INVITATION_NOT_OWNER`
- `INVITATION_INVALID_EMAIL`
- `INVITATION_CONFLICT`
- `INVITATION_NOT_AVAILABLE`
- `INVITATION_EMAIL_NOT_VERIFIED`
- `INVITATION_EMAIL_MISMATCH`
- `INVITATION_IDENTITY_CONFLICT`

## OpenAPI cutover

The checked-in OpenAPI document changes every Invitation 400/403/409/410 response to `application/problem+json`, replaces `InvitationError` with one RFC 9457 schema, and regenerates the Hey API client in the same change. `type` and `code` are optional in the generic schema because Boot-native framework problems may omit both when the effective type is `about:blank`; every expected capability failure is contract-tested to include both. No compatibility alias remains.

## Internationalization

I18n is deferred. The product has no locale selection, frontend translation system, translation ownership, or `Accept-Language` test matrix. `BusinessException.safeMessage()` is the English fallback. A future global handler may resolve `problem.<code>` through `MessageSource` without changing codes, exceptions, or clients.

## Exclusions

No HTTP types in core, `ErrorResponseException`, `@ResponseStatus` capability exceptions, catch-all handler, Zalando Problem, external error starter, error registry, code-uniqueness rule, field-validation extension, worker mapping, or speculative message bundles are introduced.
