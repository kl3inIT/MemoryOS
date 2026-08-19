# Organization verification matrix

| Requirement | Durable verification |
| --- | --- |
| First startup creates one exact actor, Organization, default Workspace, and owner/admin memberships | `JdbcInitialOrganizationBootstrapperTest.createsTheExactInitialAggregateAndReplaysTheSameConfiguration` |
| Identical startup configuration reuses the published aggregate | `JdbcInitialOrganizationBootstrapperTest.createsTheExactInitialAggregateAndReplaysTheSameConfiguration` |
| Concurrent startup creates one aggregate and one caller observes replay | `JdbcInitialOrganizationBootstrapperTest.serializesConcurrentStartupAndCreatesOneAggregate` |
| Configuration drift fails without mutation | `JdbcInitialOrganizationBootstrapperTest.rejectsConfigurationDriftWithoutChangingTheExistingAggregate` |
| Failed aggregate creation rolls back the new actor and binding | `JdbcInitialOrganizationBootstrapperTest.rollsBackTheIdentityBindingWhenAggregateCreationFails` |
| Browser admission requires active Organization authority | `JdbcInitialOrganizationBootstrapperTest.resolvesOnlyActiveOrganizationMemberships` and `BrowserAuthenticationIntegrationTest.rejectsABoundIdentityWithoutOrganizationMembershipAndInvalidatesItsSession` |
| Initial owner completes Authorization Code + PKCE login and receives an `ActorId`-only JDBC session | `BrowserAuthenticationIntegrationTest.authenticatesTheInitialOwnerWithPkceAndPersistsOnlyTheActorSession` |
| Bearer identity behavior remains compatible | `JwtAuthenticationIntegrationTest` |

Automated persistence tests execute Flyway SQL in isolated H2 PostgreSQL-compatibility databases. The browser integration test starts the real API HTTP composition and a local standards-based OIDC issuer, verifies S256 challenge/verifier coupling, session ID rotation, membership gating, and provider-token absence. Shared PostgreSQL and Keycloak verification remains a separate runtime gate recorded in the active MEM-8 verification document.