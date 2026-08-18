# MEM-7 implementation plan

## Acceptance criteria

- [x] Versioned migration creates actors and exact external-identity bindings.
- [x] Database constraints enforce uniqueness, actor existence, and restricted deletion.
- [x] API resolves valid JWT `(iss, sub)` through the database.
- [x] Missing binding returns `401`; email cannot substitute for a binding.
- [x] Datasource and Flyway configuration fail fast when absent.
- [x] Worker remains independent of datasource and persistence implementation.
- [x] Shared PostgreSQL is provisioned with a dedicated database/user and loopback-only access.
- [x] Shared Keycloak + PKCE smoke flow proves a stored binding resolves to `ActorId`.
- [x] Temporary users, rows, callbacks, tunnels, and local secret files are cleaned up.
- [x] No speculative provisioning profile, command, task, or write abstraction remains.
- [x] Latest branch passes repository-wide verification after the operating-model and provisioning-removal changes.
- [ ] Pull-request review findings are resolved or dispositioned.
- [ ] Pull request is merged.
- [ ] After merge, the increment record is moved to `completed/` and the roadmap is reconciled.

## Change sequence

1. Add migration and capability-owned JDBC resolver.
2. Compose datasource, Flyway, and resolver in the API.
3. Replace static identity mapping in JWT integration tests with database-backed mapping.
4. Provision and verify shared PostgreSQL without exposing its port.
5. Exercise a normal-user Authorization Code + PKCE flow against shared Keycloak.
6. Remove temporary provisioning runtime code; retain only the real read path and safe bootstrap runbook.
7. Establish repository-as-system-of-record documents and consolidate stable identity contracts.
8. Run static analysis, clean repository gate, and changed runtime smoke verification.
9. Update PR/Linear evidence and complete the single review pass.
10. After merge, move this directory to `docs/increments/completed/` and update the roadmap.
