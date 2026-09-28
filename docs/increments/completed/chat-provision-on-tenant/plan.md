# Plan

- [x] Publish `TenantBootstrapped` from both branches of the initial-Tenant bootstrap, in its transaction.
- [x] Add `ChatTenantProvisioner`, an in-transaction `@EventListener` that seeds the catalog, flow rows and built-in agent insert-only, wired in the API catalog configuration.
- [x] Remove the per-request `initialize` and `provisionPersona` calls; read the default agent where a session or model list needs it.
- [x] Mark `ModelCatalogService` reads `readOnly` and authorize them without the shared Tenant lock.
- [x] Test creation, rollback on failed provisioning, restart provisioning of an unprovisioned Tenant, and read-only catalog reads (`ChatTenantProvisioningTest`); adjust fixtures that relied on lazy provisioning.
- [x] Update the Tenant, catalog and Chat contracts and the Chat verification matrix; mark the owner decision done.

## Verification — 2026-09-25

- `./gradlew :core:compileJava :core:compileTestJava :api:compileJava :worker:compileJava --no-daemon`: passed.
- `:core:test` with `ChatTenantProvisioningTest`, `DefaultInitialTenantBootstrapperTest`, `ModelCatalogSelectionTest`, `ModelCatalogConstraintsTest`, `ChatExportIntegrationTest`, `ChatLifecycleIntegrationTest`, `ChatPersistenceIntegrationTest`, `ChatTurnServiceTest`, `ChatTurnSetupTest`, the five IAM tests that build the bootstrapper, `io.memoryos.ModulithArchitectureTest` and `*CoreDependencyRulesTest*`: passed.
- `:api:test --tests '*OpenApiContractTest*' --tests '*ChatModelCatalogConfigurationTest*'`: passed. Four `ChatSessionApiIntegrationTest` cases (persona projection, concrete model IDs, send with usage, the editors/projects/personas round trip) passed against a Spring context whose bootstrap provisions Chat through the listener.
- `git diff dathip04/owner-decisions-group-1...HEAD -- openapi.yml web/`: empty. The two-dot form lists files that later merges changed on the integration branch; none of them are in this branch.
