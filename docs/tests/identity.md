# Identity verification matrix

| Requirement | Durable verification |
| --- | --- |
| Same subject at different issuers resolves independently | `JdbcExternalIdentityResolverTest.resolvesSameSubjectSeparatelyForEachIssuer` |
| Lookup is exact and case-sensitive | `JdbcExternalIdentityResolverTest.doesNotResolveUnknownOrDifferentlyCasedIdentity` |
| One exact identity cannot bind to two actors | `JdbcExternalIdentityResolverTest.exactIdentityCanOnlyBelongToOneActor` |
| Binding requires an existing actor | `JdbcExternalIdentityResolverTest.bindingRequiresExistingActor` |
| One actor may own multiple identities | `JdbcExternalIdentityResolverTest.oneActorCanOwnMultipleExternalIdentities` |
| Actor deletion is restricted while bindings exist | `JdbcExternalIdentityResolverTest.actorWithBindingCannotBeDeleted` |
| Missing/malformed token and invalid signature fail | `JwtAuthenticationIntegrationTest` rejection cases |
| Wrong issuer or audience fails | `JwtAuthenticationIntegrationTest.rejectsWrongIssuer` and `rejectsWrongAudience` |
| Expired or not-yet-valid token fails | `JwtAuthenticationIntegrationTest` timestamp rejection cases |
| Missing or blank subject fails | `JwtAuthenticationIntegrationTest.rejectsMissingSubject` and `rejectsBlankSubject` |
| Valid but unbound identity fails with `401` | `JwtAuthenticationIntegrationTest.rejectsValidTokenWithoutIdentityBinding` |
| Email claim cannot substitute for binding | `JwtAuthenticationIntegrationTest.rejectsMatchingEmailWithoutExplicitIdentityBinding` |
| Bound identity returns only actor ID | `JwtAuthenticationIntegrationTest.returnsOnlyActorIdForBoundIdentity` |
| Insecure remote JWKS URL is rejected | `SecurityConfigurationTest` JWKS URI cases |
| API datasource and Flyway composition starts | `ApiApplicationSmokeTest` and `MemoryOsApiApplicationTest` |
| Capability and deployable boundaries remain closed | `MemoryOsModulesTest`, `CoreDependencyRulesTest`, and worker smoke test |
| Shared Keycloak Authorization Code + PKCE resolves a stored exact binding | Real OIDC scenario recorded in the active MEM-7 verification record |

## Environment boundaries

Automated database tests use isolated H2 PostgreSQL-compatibility mode to execute the Flyway migration grammar and constraints. They do not replace the shared PostgreSQL check. MEM-7 verification also applies Flyway V1 to PostgreSQL 18.4 and exercises the API through the shared Keycloak realm.

Real OIDC verification uses a normal temporary user, never an administrator token as application identity. Temporary user, binding, actor, callback data, and local secret files are removed after the scenario.
