# Tenant verification matrix

| Requirement | Durable verification |
| --- | --- |
| First startup creates the configured Tenant UUID, exact Actor, and owner membership | `DefaultInitialTenantBootstrapperTest.createsTheExactInitialAggregateAndReplaysTheSameConfiguration` |
| Identical startup configuration reuses the published aggregate | `DefaultInitialTenantBootstrapperTest.createsTheExactInitialAggregateAndReplaysTheSameConfiguration` |
| Configured Tenant UUID drift fails without mutation | `DefaultInitialTenantBootstrapperTest.rejectsAConfiguredTenantIdentifierThatDiffersFromThePublishedTenant` |
| Other bootstrap configuration drift fails without mutation | `DefaultInitialTenantBootstrapperTest.rejectsConfigurationDriftWithoutChangingTheExistingAggregate` |
| The database rejects a second Tenant row | `DefaultInitialTenantBootstrapperTest.databaseRejectsASecondTenant` |
| Failed aggregate creation rolls back the Actor and binding | `DefaultInitialTenantBootstrapperTest.rollsBackTheIdentityBindingWhenAggregateCreationFails` |
| Concurrent startup publishes one aggregate | `DefaultInitialTenantBootstrapperTest.serializesConcurrentStartupAndCreatesOneAggregate` and `PostgresInitialTenantBootstrapperConcurrencyTest.concurrentBootstrapSerializesOnTheSingletonRowAndPublishesOneAggregate` |
| Session admission requires active Tenant authority | `DefaultInitialTenantBootstrapperTest.resolvesOnlyActiveTenantMemberships` and `SessionSecurityIntegrationTest.rejectsABoundIdentityWithoutTenantMembershipAndInvalidatesItsSession` |
| Durable owner/member projection excludes inactive rows and resolves active Tenant identifiers | `JdbcTenantAccessResolverTest` |
| Fixed HTTP resolution ignores `X-TenantId` selection and retains the deployment Tenant | `BearerAuthenticationIntegrationTest.ignoresTenantHeadersAndUsesTheFixedDeploymentTenant` |
| Bound bearer Actors remain subject to durable membership | `BearerAuthenticationIntegrationTest.returnsEmptyTenantAuthorityForBoundActorWithoutMembership` and `returnsDurableTenantAuthorityForBoundOwner` |
| V6 migrates the historical Organization schema before Tenant repositories execute | Core H2 suites apply V1–V6; PostgreSQL concurrency suites apply the same migration sequence |
| OpenAPI exposes only `tenant` / `CurrentTenant` | `OpenApiContractTest.committedContractDescribesOnlyTheLiveBrowserApi` and generated-client drift checks |

H2 suites run in PostgreSQL compatibility mode. PostgreSQL concurrency suites use the pinned PostgreSQL Testcontainer and the real transactional proxy. API integration tests start the real HTTP composition, exercise Arconia Web filtering, authentication, authorization, and the migrated schema. Worker tests exercise explicit durable Tenant identity and the runtime composition root.
