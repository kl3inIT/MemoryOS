# Backend implementation plan

- [x] Add the provider adapter contract and verify native binding/options/resource ownership with tests.
- [x] Add JDBC catalog migration, model management capability, secret handling and Tenant/Group/Persona access tests.
- [x] Add backend administration, allowed-model/default/Persona APIs and safe validation responses.
- [x] Resolve send selections once in RAM; preserve idempotency and acquire/release clients through all terminal paths.
- [x] Exercise multiple model configurations, updates during a turn, denied access, fallback, Stop and provider failures through the runtime.
- [x] Inspect changed files, regenerate OpenAPI/client, run focused tests and `clean check`; document verification and adapter handoff.

The accepted design is in [design.md](design.md). No frontend UI or web/image tools are scheduled here. Implementation starts from main `287ca9c4bb79878bf49855934bddd4781f1c931a` on `feat/mem-77-provider-backend`. Completion boxes require implementation and verification evidence, not just an interface or plan.

## PR review follow-through

Preserve the accepted internal HTTP/provider-manager trust policy. Fix OpenAPI schema identity and mutation headers, validate null options, make deployment capability overrides explicit and restore pricing fail-fast, remove client construction/cleanup from the shared monitor, batch provider associations, and verify the resulting contracts before merging. Review resolutions and exact-head CI evidence are tracked on PR #88.
