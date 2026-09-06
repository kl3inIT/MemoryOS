# Spring I/O security patterns comparison

Reviewed on 2026-09-06 against the combined MEM-55/MEM-36 working tree. This review does not establish new runtime verification or accept an extension of IAM scope.

## Evidence

Reference: Cristian Schuszter's [Spring I/O talk](https://www.youtube.com/watch?v=QXQiGS9CL20) and [36-page slides](https://2026.springio.net/slides/architectural-patterns-for-spring-security-you-wish-your-tech-lead-knew-springio26.pdf). The English automatic transcript and actual video frames were inspected. Captions misrender JWT in places; identifiers below were checked visually.

The final-slide link resolves to [the CERN GitLab demo branch](https://gitlab.cern.ch/ischuszt/identity-crisis/-/commits/feature/architectural-patterns). Repository/API requests timed out. This is a review of visible demo code, not the entire repository. Local reference material is ignored under `.tmp/security-talk-review/`.

| Video position | Code observed |
| --- | --- |
| [16:00](https://www.youtube.com/watch?v=QXQiGS9CL20&t=960s) | `CustomOauth2AuthResolver` delegates registration selection for multiple OAuth clients. |
| [28:45](https://www.youtube.com/watch?v=QXQiGS9CL20&t=1725s) | Resource-server converters extract client roles from provider claims. |
| [33:10](https://www.youtube.com/watch?v=QXQiGS9CL20&t=1990s) | `HelloController` retrieves an authorized client's access token for downstream calls. |
| [34:55](https://www.youtube.com/watch?v=QXQiGS9CL20&t=2095s) | `GatewayRoutesConfig` separates JWT pass-through and opaque-token routes. |
| [37:15–37:45](https://www.youtube.com/watch?v=QXQiGS9CL20&t=2235s) | `OpaqueTokenRelayFilter` creates a UUID, stores JWT claims with a five-minute TTL, and replaces Authorization. |
| [40:00–42:18](https://www.youtube.com/watch?v=QXQiGS9CL20&t=2400s) | `IntrospectionController` looks up stored tokens, returns inactive for missing entries, and builds the response from stored attributes. |

## Fit with MemoryOS

`JwtToActorAuthenticationConverter` resolves exact issuer/subject to `ActorId` without converting provider roles into grants. `DefaultIamAuthorization` and `IamAuthorizationRepository` resolve current Group authority. Source services own resource scope and use the shared authorization-lock protocol. This already separates authentication and domain authorization without distributed authorization services.

Browser login has the security property illustrated by BFF: Spring performs OIDC login; the browser uses a protected session cookie. `ActorSessionLoginSuccessHandler` explicitly saves an Actor-only context and authorized-client credentials are discarded. API and browser composition share a deployable rather than adding a forwarding BFF. `BrowserMutationConfiguration` guards unsafe API requests with a non-simple header under the same-origin contract; disabling the default API CSRF filter alone is not evidence of missing protection.

Application revocation does not wait for JWT expiry: durable membership/Group checks change on the next request, while `authorizationVersion` invalidates private browser data. The demonstrated claim store does not establish fresh database authorization. Copying it would require additional invalidation and distributed-store semantics while duplicating existing authority checks.

## Remaining work

The subsequent Linear reconciliation reuses [MEM-59](https://linear.app/memory-os/issue/MEM-59) for upstream broker configuration, safe linking, future JIT/pending approval and simulator/real-provider acceptance. It adds [MEM-68](https://linear.app/memory-os/issue/MEM-68) for provider/broker revocation and [MEM-69](https://linear.app/memory-os/issue/MEM-69) for absolute session lifetime. [MEM-25](https://linear.app/memory-os/issue/MEM-25) remains the audit follow-up. These are separate from current MEM-55/MEM-36 delivery; current authentication still has no ordinary-login JIT.

1. **Current verification:** earlier successful backend/frontend/runtime gates predate late source/test warning cleanup. Review those edits and run affected compilation/tests when memory permits. Current checkout readiness remains pending.
2. **Focused regression coverage:** the expanded `SessionSecurityIntegrationTest.userDirectoryConvergesAfterAdmissionAndLiveMembershipRevocation` now covers inflated provider role/scope claims against an active Basic-only member, bearer requests without a session and alongside another Actor's browser session, invalid-bearer rejection without cookie fallback, and the same token after deactivation. All 26 focused session/bearer tests passed after the review; current full-gate status is recorded in the combined verification document.
3. **Provider-side revocation:** local JWT validation and Actor-only sessions do not implement provider introspection or OIDC back-channel logout. Keycloak-only logout/disable must not be described as immediate MemoryOS revocation. A follow-up needs logout-versus-disable semantics, a revocation deadline, multi-instance session correlation, replay protection and provider-outage behavior. Back-channel logout alone does not establish coverage of every disable event.
4. **Session policy:** configuration has a 30-minute default idle timeout; no explicit absolute lifetime/forced-reauthentication path was found. Continuous activity and idle expiry are different contracts. Select the required policy before implementation.
5. **Audit:** HTTP telemetry is not attributable administrator history. A new audit capability must satisfy ADR 0003's consumer, retention, access, redaction and transaction requirements; a generic event table is not justified by the talk alone.

Keep the current architecture. Do not copy a gateway, token issuer, multi-issuer acceptance, role-claim mapping or provider-token persistence into these increments. Brokering upstream IdPs through Keycloak remains the appropriate direction; actual upstream federation configuration was not verified here.

The initial video review changed no application code, shared environment, IDE datasource or PR and ran no Gradle/container gate while RAM was below 1 GiB. The user subsequently authorized bounded container-backed verification, regression additions, Linear reconciliation and scoped commits/push; see the combined verification record for later results. Temporary headless browsers used to read video frames were closed; video inspection is not application runtime verification.
