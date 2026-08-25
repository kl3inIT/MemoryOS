# Invitation verification matrix

| Requirement | Durable verification |
| --- | --- |
| Only an active Organization owner can issue, list, rotate, or revoke | `DefaultInvitationServiceTest.requiresAnActiveOwnerAndValidEmail` and invitation API integration tests |
| Email is normalized and only one pending invitation exists per Organization/email | `DefaultInvitationServiceTest.issuesAndListsDigestOnlyInvitationForTheActiveOwner` and `rejectsDuplicatePendingEmailAndRotatesOrRevokesWithoutRecoveringOldSecrets` |
| Plaintext secret is returned once and only its digest persists | `DefaultInvitationServiceTest.issuesAndListsDigestOnlyInvitationForTheActiveOwner` |
| Rotation replaces the digest and invalidates the prior secret | `DefaultInvitationServiceTest.rejectsDuplicatePendingEmailAndRotatesOrRevokesWithoutRecoveringOldSecrets` |
| Revocation prevents intake | `DefaultInvitationServiceTest.rejectsDuplicatePendingEmailAndRotatesOrRevokesWithoutRecoveringOldSecrets` |
| Expiry is durable and permits a later replacement invitation | `DefaultInvitationServiceTest.expiresPendingInvitationAndAllowsAReplacementForTheSameEmail` |
| Invitation history filtering, sorting, totals, bounded pages, and deterministic ID tie-breakers share one server transaction view | `DefaultInvitationServiceTest.filtersSortsAndPaginatesInvitationHistory`, `usesInvitationIdAsTheStableTieBreakerForEqualSortValues`, and `SessionSecurityIntegrationTest.filtersSortsAndPaginatesInvitationHistoryOverHttp` |
| Unverified or mismatched email creates no identity or membership | `DefaultInvitationServiceTest.rejectsUnverifiedOrMismatchedEmailWithoutIdentityWrites` |
| Existing authority conflicts fail without mutation | `DefaultInvitationServiceTest.rejectsAnIdentityThatAlreadyHasOrganizationAuthority` |
| Binding, fixed memberships, and acceptance commit atomically | `DefaultInvitationServiceTest.acceptsVerifiedMatchingIdentityAndCreatesFixedMembershipsAtomically` |
| Concurrent replay of one invitation produces one member and one accepted invitation | `DefaultInvitationServiceTest.concurrentAcceptanceProducesOneMemberAndOneAcceptedInvitation` and `PostgresInvitationAcceptanceConcurrencyTest.concurrentAcceptanceSerializesOnInvitationAndCreatesOneMember` |
| Concurrent invitations for one bound identity serialize on its Actor row and grant one authority | `PostgresInvitationAcceptanceConcurrencyTest.concurrentAcceptanceSerializesOnInvitationAndCreatesOneMember` |
| Intake/current responses do not cache continuation metadata, and failed intake removes prior state | `SessionSecurityIntegrationTest.acceptsInvitationThroughPkceAndPersistsOnlyTheMemberActorSession` |
| Invitation callback uses Authorization Code + S256 PKCE and stores only `ActorId` | `SessionSecurityIntegrationTest.acceptsInvitationThroughPkceAndPersistsOnlyTheMemberActorSession` |
| Mismatched invitation email invalidates the partial session and writes no identity | `SessionSecurityIntegrationTest.rejectsMismatchedInvitationEmailAndInvalidatesThePartialSession` |
| Expected Invitation REST failures use `application/problem+json` with safe RFC 9457 fields, namespaced code, and derived URN type | `SessionSecurityIntegrationTest.returnsProblemDetailsForBusinessAndFrameworkFailures` |
| Built-in malformed-request failures use Boot-native Problem Details without a capability code | `SessionSecurityIntegrationTest.returnsProblemDetailsForBusinessAndFrameworkFailures` |
| The committed browser API snapshot is generated from the live Invitation operations, schemas, security metadata, and Problem Detail responses | `OpenApiContractTest.committedContractDescribesOnlyTheLiveBrowserApi` |
| Owner Invitations page supports create, copy/share, lifecycle presentation, typed URL filters/sort/page state, and server-driven TanStack Table pagination | `identity-shell.spec.ts` — `creates a production invitation from the Invitations administration page` and `restores and updates the server-driven invitation view from the URL` |
| Recipient landing and failure recovery are responsive and accessible | `identity-shell.spec.ts` — `shows the recipient invitation landing and recovery states` plus shared-runtime browser evidence |

The persistence suite runs the production Flyway SQL in H2 PostgreSQL compatibility mode through real Spring transaction proxies. Final concurrency evidence also runs against PostgreSQL. Browser integration uses the real API composition, OIDC authorization/token exchange, and JDBC-backed Spring Session; delivery evidence uses the shared Keycloak realm and PostgreSQL deployment.
