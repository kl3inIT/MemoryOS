# MEM-19 verification: frontend authority projection

## Contract matrix

| Contract | Boundary | Required evidence |
| --- | --- | --- |
| Owner projection | Organization resolver + HTTP integration | Active owner receives Organization summary, `OWNER`, and `INVITATIONS_MANAGE` |
| Member projection | Organization resolver + HTTP integration | Active member receives summary, `MEMBER`, and no invitation capability |
| No active membership | HTTP integration | Bound actor receives `200`, null Organization, empty capabilities |
| Anonymous identity | Security integration | `/api/identity/me` remains `401` without session creation |
| Browser/bearer parity | Browser and bearer integration tests | Same actor authority produces the same response shape |
| Backend remains authoritative | Real member browser session | List/create/rotate/revoke return `403 application/problem+json` with `INVITATION_NOT_OWNER` |
| Account presentation | Frontend unit/browser | Owner/member label and derived initials use server context; no hardcoded owner assumptions |
| Navigation gating | Frontend unit/browser | Member sees no Admin Panel or invitation navigation; owner remains unchanged |
| Deep-link gating | Browser | Member URL is preserved, access denied renders, invitation request count is zero |
| Capability boundary | Source/unit test | Behavior gates use `useCan`; role is presentation-only |
| Cross-actor cache isolation | Frontend unit | Changed `actorId` clears cached query state before gated render |
| Clean contract | Repository/OpenAPI tests | Dead session controller removed; spec, OpenAPI, generated client, architecture align |

## Browser scenarios

1. Owner projection renders owner account context, Admin Panel, invitation navigation, and working invitation table/actions.
2. Member projection renders member context without admin entry/navigation.
3. Member deep-links to `/admin` and `/admin/invitations`; URL stays, access denied renders, and the request recorder observes zero invitation API calls.
4. Anonymous navigation renders Sign In; authenticated actor with no active membership retains the access-not-provisioned flow.
5. Session projection changes actor in one mounted app; cached owner data is removed before member UI renders.
6. Desktop and narrow-width shells retain keyboard focus, menu semantics, and readable denied states.

## Evidence log

Evidence is appended only after the corresponding test, command, or runtime scenario is observed.

### 2026-08-26

- `JdbcOrganizationAccessResolverTest`: 4/4 passed for owner/member projection, inactive rows, and active-Organization ambiguity.
- `BearerAuthenticationIntegrationTest` and `SessionSecurityIntegrationTest`: passed owner/member/no-membership projection, unchanged anonymous behavior, and real accepted-member `403 INVITATION_NOT_OWNER` coverage for list/create/rotate/revoke.
- `OpenApiContractTest`: passed; committed `openapi.yml` models required nullable Organization context with `oneOf`, and the Hey API client regenerated without drift.
- JetBrains inspections ran with warnings enabled for every changed Java file and `openapi.yml`. No errors or unresolved warnings remain. The existing `SessionSecurityIntegrationTest` weak warning for the intentional custom `X-MemoryOS-CSRF` header remains covered by its `HttpHeaderInspection` suppression.
- `gradlew.bat clean check --no-daemon`: passed.
- `pnpm check`: passed, including generated-client stability, zero-warning lint, formatting, TypeScript, 18 unit tests, route generation, and production build.
- `pnpm test:e2e`: 12/12 Chromium scenarios passed. Owner administration remained functional at desktop width. The member scenario ran at `390 × 844`, showed server-derived member context without Admin Panel, preserved the `/admin/invitations?status=PENDING` deep link (with canonical default search fields), rendered access denied, and recorded zero invitation requests.
- Cross-actor unit verification changed the identity projection in one mounted boundary and observed prior query and mutation caches removed before member UI rendered.
