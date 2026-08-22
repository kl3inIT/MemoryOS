# Invitation verification matrix

| Requirement | Durable verification |
| --- | --- |
| Only an active Organization owner can issue, list, rotate, or revoke | `DefaultInvitationServiceTest.requiresAnActiveOwnerAndValidEmail` and invitation API integration tests |
| Email is normalized and only one pending invitation exists per Organization/email | `DefaultInvitationServiceTest.issuesAndListsDigestOnlyInvitationForTheActiveOwner` and `rejectsDuplicatePendingEmailAndRotatesOrRevokesWithoutRecoveringOldSecrets` |
| Plaintext secret is returned once and only its digest persists | `DefaultInvitationServiceTest.issuesAndListsDigestOnlyInvitationForTheActiveOwner` |
| Rotation replaces the digest and invalidates the prior secret | `DefaultInvitationServiceTest.rejectsDuplicatePendingEmailAndRotatesOrRevokesWithoutRecoveringOldSecrets` |
| Revocation prevents intake | `DefaultInvitationServiceTest.rejectsDuplicatePendingEmailAndRotatesOrRevokesWithoutRecoveringOldSecrets` |
| Expiry is durable and permits a later replacement invitation | `DefaultInvitationServiceTest.expiresPendingInvitationAndAllowsAReplacementForTheSameEmail` |
| Unverified or mismatched email creates no identity or membership | `DefaultInvitationServiceTest.rejectsUnverifiedOrMismatchedEmailWithoutIdentityWrites` |
| Existing authority conflicts fail without mutation | `DefaultInvitationServiceTest.rejectsAnIdentityThatAlreadyHasOrganizationAuthority` |
| Binding, fixed memberships, and acceptance commit atomically | `DefaultInvitationServiceTest.acceptsVerifiedMatchingIdentityAndCreatesFixedMembershipsAtomically` |
| Concurrent replay of one invitation produces one member and one accepted invitation | `DefaultInvitationServiceTest.concurrentAcceptanceProducesOneMemberAndOneAcceptedInvitation` and `PostgresInvitationAcceptanceConcurrencyTest.concurrentAcceptanceSerializesOnInvitationAndCreatesOneMember` |
| Concurrent invitations for one bound identity serialize on its Actor row and grant one authority | `PostgresInvitationAcceptanceConcurrencyTest.concurrentAcceptanceSerializesOnInvitationAndCreatesOneMember` |
| Intake/current responses do not cache continuation metadata, and failed intake removes prior state | `BrowserAuthenticationIntegrationTest.acceptsInvitationThroughPkceAndPersistsOnlyTheMemberActorSession` |
| Invitation callback uses Authorization Code + S256 PKCE and stores only `ActorId` | `BrowserAuthenticationIntegrationTest.acceptsInvitationThroughPkceAndPersistsOnlyTheMemberActorSession` |
| Mismatched invitation email invalidates the partial session and writes no identity | `BrowserAuthenticationIntegrationTest.rejectsMismatchedInvitationEmailAndInvalidatesThePartialSession` |
| Expected Invitation REST failures use `application/problem+json` with safe RFC 9457 fields, namespaced code, and derived URN type | `BrowserAuthenticationIntegrationTest.returnsProblemDetailsForBusinessAndFrameworkFailures` |
| Built-in malformed-request failures use Boot-native Problem Details without a capability code | `BrowserAuthenticationIntegrationTest.returnsProblemDetailsForBusinessAndFrameworkFailures` |
| Owner People page supports create, copy/share, and lifecycle presentation | `identity-shell.spec.ts` — `creates a production invitation from the People administration page` |
| Recipient landing and failure recovery are responsive and accessible | `identity-shell.spec.ts` — `shows the recipient invitation landing and recovery states` plus shared-runtime browser evidence |

The persistence suite runs the production Flyway SQL in H2 PostgreSQL compatibility mode through real Spring transaction proxies. Final concurrency evidence also runs against PostgreSQL. Browser integration uses the real API composition, OIDC authorization/token exchange, and JDBC-backed Spring Session; delivery evidence uses the shared Keycloak realm and PostgreSQL deployment.
