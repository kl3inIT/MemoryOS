# Identity verification matrix

| Requirement | Durable verification |
| --- | --- |
| Same subject at different issuers resolves independently | `JdbcExternalIdentityResolverTest.resolvesSameSubjectSeparatelyForEachIssuer` |
| Lookup is exact and case-sensitive | `JdbcExternalIdentityResolverTest.doesNotResolveUnknownOrDifferentlyCasedIdentity` |
| One exact identity cannot bind to two actors | `JdbcExternalIdentityResolverTest.exactIdentityCanOnlyBelongToOneActor` |
| Binding requires an existing actor | `JdbcExternalIdentityResolverTest.bindingRequiresExistingActor` |
| One actor may own multiple identities | `JdbcExternalIdentityResolverTest.oneActorCanOwnMultipleExternalIdentities` |
| Bound actor deletion is restricted | `JdbcExternalIdentityResolverTest.actorWithBindingCannotBeDeleted` |
| Missing, malformed, invalid-signature, wrong-issuer, wrong-audience, expired, future, or missing-subject bearer tokens fail | `JwtAuthenticationIntegrationTest` |
| Unknown exact bearer binding fails | `JwtAuthenticationIntegrationTest.rejectsUnknownExternalIdentity` |
| Bound bearer identity returns only `ActorId` | `JwtAuthenticationIntegrationTest.returnsOnlyActorIdForBoundIdentity` |
| Anonymous current-identity probes return `401` without creating a JDBC session or emitting a session cookie | `BrowserAuthenticationIntegrationTest.rejectsAnonymousIdentityWithoutCreatingASession` |
| Initial Organization transaction creates or reuses one stable actor binding | `JdbcInitialOrganizationBootstrapperTest.createsTheExactInitialAggregateAndReplaysTheSameConfiguration` |
| Browser callback replaces provider identity with `ActorId` and stores no provider token marker | `BrowserAuthenticationIntegrationTest.authenticatesTheInitialOwnerWithPkceAndPersistsOnlyTheActorSession` |
| Authenticated browser session reads the same `ActorId` from `/api/identity/me` | `BrowserAuthenticationIntegrationTest.authenticatesTheInitialOwnerWithPkceAndPersistsOnlyTheActorSession` |
| Bound browser identity without active Organization authority gains no session | `BrowserAuthenticationIntegrationTest.rejectsABoundIdentityWithoutOrganizationMembershipAndInvalidatesItsSession` |
| Signed-out browser state starts the backend-owned OAuth2 flow | `identity-shell.spec.ts` — `offers the backend OAuth2 flow when no session exists` |
| Authenticated shell renders the stable actor and survives reload | `identity-shell.spec.ts` — `renders the stable actor returned by an authenticated session` |
| Public not-provisioned state remains distinct from signed out | `identity-shell.spec.ts` — `keeps unprovisioned access separate from signed-out state` |
| Transient identity failure renders retryable unavailable state and recovers | `identity-shell.spec.ts` — `recovers from an unavailable identity endpoint without treating it as signed out` |

The browser test uses the real API composition with a local OIDC issuer and JDBC-backed Spring Session. It verifies Authorization Code + S256 PKCE, session fixation protection, exact identity resolution, active-membership admission, provider-state disposal, and rejection isolation. Shared Keycloak and PostgreSQL evidence is recorded separately in the active MEM-8 verification document.