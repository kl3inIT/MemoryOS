# PR #28 — Direct IntelliJ development launch

## Approved scope

The user requested review and merge of PR #28 and an equivalent worker example. They subsequently authorized shared environment reconciliation, then clarified that the PR and worker configuration must merge before datasource/JPA work resumes. Environment provisioning is separate from this PR's code scope. Work is isolated from the uncommitted MEM-55/MEM-36 implementation.

PR #28 restores direct Spring Boot Run/Debug in IntelliJ with a before-launch Infisical dev export to an ignored YAML cache. Preserve byte-faithful values and stop launch on failed synchronization. API and worker may synchronize concurrently; each export needs an independent temporary file and atomic final publication.

## Current runtime contract

Align both configurations with the current local development profile: API owns PostgreSQL Dev Services, worker owns Redis Dev Services and consumes the API-migrated database. Worker has durable processing loops and the FILE provider runtime, not the historical exit-only scaffold. Neither example may silently connect a local worker to staging queues or database. Keycloak, object-storage and structured-extraction prerequisites remain explicit.

Keep the normal confidential OIDC/Infisical paths. Do not introduce a temporary application profile, fake credential mode, copied secrets, or alternative processing loop. Document the local cache as sensitive ignored state rather than committing values.

The authenticated audit found legacy Organization/Workspace keys in both environments. Separate staging reconciliation preserved the deployed Tenant, owner, issuer, credentials, and database target. Post-merge Infisical-only work reconciled known development keys and isolated MinIO configuration, with the remaining identity prerequisite recorded in the plan. Development must not reuse staging database, queue, or object-storage authority. Operator credentials and local dependency state remain outside Git.

## Delivery boundaries

Update the existing PR history by merge commits, preserve its reviewed head ancestry, and merge only with latest-head CI and review evidence. No application release deployment or destructive data migration is included. The separate IAM datasource/inspection work must not enter this PR or resume before its merge. RAM exhaustion stopped local dependency provisioning and direct IntelliJ launch verification; record that limit rather than claiming a healthy API/worker launch.
