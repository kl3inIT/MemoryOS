# Organization verification matrix

| Requirement | Durable verification |
| --- | --- |
| First startup creates one exact actor, Organization, default Workspace, and owner/admin memberships | `DefaultInitialOrganizationBootstrapperTest.createsTheExactInitialAggregateAndReplaysTheSameConfiguration` |
| Identical startup configuration reuses the published aggregate | `DefaultInitialOrganizationBootstrapperTest.createsTheExactInitialAggregateAndReplaysTheSameConfiguration` |
| Identical startup configuration replays after Invitation adds valid member authority | `DefaultInvitationServiceTest.replaysBootstrapAfterInvitationAddsAMember` |
| Concurrent startup creates one aggregate and one caller observes replay through the Spring transaction proxy | `DefaultInitialOrganizationBootstrapperTest.serializesConcurrentStartupAndCreatesOneAggregate` on H2 and `PostgresInitialOrganizationBootstrapperConcurrencyTest.concurrentBootstrapSerializesOnTheSingletonRowAndPublishesOneAggregate` on PostgreSQL |
| Configuration drift fails without mutation | `DefaultInitialOrganizationBootstrapperTest.rejectsConfigurationDriftWithoutChangingTheExistingAggregate` |
| Failed aggregate creation rolls back the new actor and binding | `DefaultInitialOrganizationBootstrapperTest.rollsBackTheIdentityBindingWhenAggregateCreationFails` |
| Session admission requires active Organization authority | `DefaultInitialOrganizationBootstrapperTest.resolvesOnlyActiveOrganizationMemberships` and `SessionSecurityIntegrationTest.rejectsABoundIdentityWithoutOrganizationMembershipAndInvalidatesItsSession` |
| Initial owner completes Authorization Code + PKCE login and receives an `ActorId`-only JDBC session | `SessionSecurityIntegrationTest.authenticatesAndSignsOutTheInitialOwnerWithoutProviderState` |
| Bearer identity behavior remains compatible | `BearerAuthenticationIntegrationTest` |

The primary persistence suite executes Flyway SQL in isolated H2 PostgreSQL-compatibility databases. The concurrency contract also runs against a pinned PostgreSQL 17 Testcontainer through the real Spring `@Transactional` proxy, proving row-lock serialization independently of H2 JVM connection-lock behavior. The browser integration test starts the real API HTTP composition and a local standards-based OIDC issuer, verifies S256 challenge/verifier coupling, session ID rotation, membership gating, and provider-token absence. Shared PostgreSQL and Keycloak verification remains a separate runtime gate recorded in the active MEM-8 verification document.