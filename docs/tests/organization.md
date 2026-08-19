# Organization verification matrix

| Requirement | Durable verification |
| --- | --- |
| First startup creates one exact actor, Organization, default Workspace, and owner/admin memberships | `JdbcInitialOrganizationBootstrapperTest.createsTheExactInitialAggregateAndReplaysTheSameConfiguration` |
| Identical startup configuration reuses the published aggregate | `JdbcInitialOrganizationBootstrapperTest.createsTheExactInitialAggregateAndReplaysTheSameConfiguration` |
| Concurrent startup creates one aggregate and one caller observes replay through the Spring transaction proxy | `JdbcInitialOrganizationBootstrapperTest.serializesConcurrentStartupAndCreatesOneAggregate` on H2 and `PostgresInitialOrganizationBootstrapperConcurrencyTest.concurrentBootstrapSerializesOnTheSingletonRowAndPublishesOneAggregate` on PostgreSQL |
| Configuration drift fails without mutation | `JdbcInitialOrganizationBootstrapperTest.rejectsConfigurationDriftWithoutChangingTheExistingAggregate` |
| Failed aggregate creation rolls back the new actor and binding | `JdbcInitialOrganizationBootstrapperTest.rollsBackTheIdentityBindingWhenAggregateCreationFails` |
| Browser admission requires active Organization authority | `JdbcInitialOrganizationBootstrapperTest.resolvesOnlyActiveOrganizationMemberships` and `BrowserAuthenticationIntegrationTest.rejectsABoundIdentityWithoutOrganizationMembershipAndInvalidatesItsSession` |
| Initial owner completes Authorization Code + PKCE login and receives an `ActorId`-only JDBC session | `BrowserAuthenticationIntegrationTest.authenticatesTheInitialOwnerWithPkceAndPersistsOnlyTheActorSession` |
| Bearer identity behavior remains compatible | `JwtAuthenticationIntegrationTest` |

The primary persistence suite executes Flyway SQL in isolated H2 PostgreSQL-compatibility databases. The concurrency contract also runs against a pinned PostgreSQL 17 Testcontainer through the real Spring `@Transactional` proxy, proving row-lock serialization independently of H2 JVM connection-lock behavior. The browser integration test starts the real API HTTP composition and a local standards-based OIDC issuer, verifies S256 challenge/verifier coupling, session ID rotation, membership gating, and provider-token absence. Shared PostgreSQL and Keycloak verification remains a separate runtime gate recorded in the active MEM-8 verification document.