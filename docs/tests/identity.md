# Identity verification matrix

| Requirement | Durable verification |
| --- | --- |
| Same subject at different issuers resolves independently | `JdbcExternalIdentityResolverTest.resolvesSameSubjectSeparatelyForEachIssuer` |
| Lookup is exact and case-sensitive | `JdbcExternalIdentityResolverTest.doesNotResolveUnknownOrDifferentlyCasedIdentity` |
| One exact identity cannot bind to two actors | `JdbcExternalIdentityResolverTest.exactIdentityCanOnlyBelongToOneActor` |
| Binding requires an existing actor | `JdbcExternalIdentityResolverTest.bindingRequiresExistingActor` |
| One actor may own multiple identities | `JdbcExternalIdentityResolverTest.oneActorCanOwnMultipleExternalIdentities` |
| Bound actor deletion is restricted | `JdbcExternalIdentityResolverTest.actorWithBindingCannotBeDeleted` |
| Locked registration serializes concurrent membership grants for one bound identity | `PostgresInvitationAcceptanceConcurrencyTest.concurrentAcceptanceSerializesOnInvitationAndCreatesOneMember` |
| Missing, malformed, invalid-signature, wrong-issuer, wrong-audience, expired, future, or missing-subject bearer tokens fail | `BearerAuthenticationIntegrationTest` |
| Unknown exact bearer binding fails | `BearerAuthenticationIntegrationTest.rejectsValidTokenWithoutIdentityBinding` |
| Bound bearer actor without membership receives stable `ActorId`, null Organization, and no capabilities | `BearerAuthenticationIntegrationTest.returnsEmptyOrganizationAuthorityForBoundActorWithoutMembership` |
| Bound bearer owner receives Organization presentation, `OWNER`, and `INVITATIONS_MANAGE` | `BearerAuthenticationIntegrationTest.returnsDurableOrganizationAuthorityForBoundOwner` |
| Active owner/member projection, inactive rows, and active-Organization ambiguity use durable Organization data | `JdbcOrganizationAccessResolverTest` |
| Anonymous current-identity probes return `401` without creating a JDBC session or emitting a session cookie | `SessionSecurityIntegrationTest.rejectsAnonymousIdentityWithoutCreatingASession` |
| Initial Organization transaction creates or reuses one stable actor binding | `DefaultInitialOrganizationBootstrapperTest.createsTheExactInitialAggregateAndReplaysTheSameConfiguration` |
| OAuth2 login replaces provider identity with `ActorId` and stores no provider token marker | `SessionSecurityIntegrationTest.authenticatesAndSignsOutTheInitialOwnerWithoutProviderState` |
| Authenticated owner/member application sessions read the durable authority projection from `/api/identity/me` | `SessionSecurityIntegrationTest.authenticatesAndSignsOutTheInitialOwnerWithoutProviderState` and `acceptsInvitationThroughPkceAndPersistsOnlyTheMemberActorSession` |
| Guarded sign-out invalidates the JDBC session and returns the Keycloak RP-initiated logout location | `SessionSecurityIntegrationTest.authenticatesAndSignsOutTheInitialOwnerWithoutProviderState` and `identity-shell.spec.ts` — `signs out from the account menu with the same-origin guard` |
| The committed browser API snapshot describes nullable Organization context and excludes non-API routes | `OpenApiContractTest.committedContractDescribesOnlyTheLiveBrowserApi` |
| Bound identity without active Organization authority gains no application session | `SessionSecurityIntegrationTest.rejectsABoundIdentityWithoutOrganizationMembershipAndInvalidatesItsSession` |
| Signed-out browser state starts the backend-owned OAuth2 flow | `identity-shell.spec.ts` — `offers the backend OAuth2 flow when no session exists` |
| Authenticated shell renders server-derived Organization role/initials and hides owner administration UI for members | `NewSessionPage` unit tests and `identity-shell.spec.ts` — `renders the authenticated application shell` and `hides owner UI and blocks member administration deep links without requests` |
| Public not-provisioned state remains distinct from signed out, including null projected membership | `ApplicationSessionBoundary` unit tests and `identity-shell.spec.ts` — `keeps unprovisioned access separate from signed-out state` |
| Transient identity failure renders retryable unavailable state and recovers | `identity-shell.spec.ts` — `recovers from an unavailable identity endpoint without treating it as signed out` |
| A changed actor clears prior query and mutation caches before replacement authority UI renders | `ApplicationSessionBoundary` unit tests |

The browser test uses the real API composition with a local OIDC issuer and JDBC-backed Spring Session. It verifies Authorization Code + S256 PKCE, session fixation protection, exact identity resolution, active-membership admission, provider-state disposal, and rejection isolation. Shared Keycloak and PostgreSQL evidence is recorded separately in the active MEM-8 verification document.