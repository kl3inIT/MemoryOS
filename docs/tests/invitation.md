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
| Concurrent callback acceptance produces one member and one accepted invitation | `DefaultInvitationServiceTest.concurrentAcceptanceProducesOneMemberAndOneAcceptedInvitation` and `PostgresInvitationAcceptanceConcurrencyTest.concurrentAcceptanceSerializesOnInvitationAndCreatesOneMember` |
| Intake persists no plaintext secret and uses no-store/no-referrer headers | `BrowserAuthenticationIntegrationTest.acceptsInvitationThroughPkceAndPersistsOnlyTheMemberActorSession` |
| Invitation callback uses Authorization Code + S256 PKCE and stores only `ActorId` | `BrowserAuthenticationIntegrationTest.acceptsInvitationThroughPkceAndPersistsOnlyTheMemberActorSession` |
| Mismatched invitation email invalidates the partial session and writes no identity | `BrowserAuthenticationIntegrationTest.rejectsMismatchedInvitationEmailAndInvalidatesThePartialSession` |
| Owner People page supports create, copy/share, and lifecycle presentation | `identity-shell.spec.ts` — `creates a production invitation from the People administration page` |
| Recipient landing and failure recovery are responsive and accessible | `identity-shell.spec.ts` — `shows the recipient invitation landing and recovery states` plus shared-runtime browser evidence |

The persistence suite runs the production Flyway SQL in H2 PostgreSQL compatibility mode through real Spring transaction proxies. Final concurrency evidence also runs against PostgreSQL. Browser integration uses the real API composition, OIDC authorization/token exchange, and JDBC-backed Spring Session; delivery evidence uses the shared Keycloak realm and PostgreSQL deployment.
