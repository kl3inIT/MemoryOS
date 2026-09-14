# MEM-95 — Plan

Backend and admin UI delivered together.

- [x] Increment docs (this directory).
- [x] `KeycloakAdminConfiguration` exposes shared `Keycloak` bean; provisioner injects it; `configure-memoryos-realm.sh` grants `manage-identity-providers` to `memoryos-user-provisioner` (role assertion updated).
- [x] `core.iam.keycloak`: `KeycloakIdentityProviderGateway`, `OidcDiscoveryClient`, `DiscoveredOidcProvider`.
- [x] `core.iam.identityprovider`: service + view/command/exception + `JitAdmissionPolicy`/`DefaultJitAdmissionPolicy` + `persistence/JitAllowlistRepository`; migration `V53__jit_allowed_provider.sql`.
- [x] `api.identityprovider`: `IdentityProviderController` + `contract/` records; `ActorSessionLoginSuccessHandler` and `SessionSecurityConfiguration` switched to `JitAdmissionPolicy`; startup seeder inserts env aliases idempotently.
- [x] Regenerate `openapi.yml` (`MEMORYOS_OPENAPI_WRITE=true` + `OpenApiContractTest`) and the Hey API client.
- [x] Tests: `DefaultJitAdmissionPolicyTest` (DB allowlist, strict String, seed idempotency), `DefaultIdentityProviderAdministrationTest` (authorization, mapping, failure mapping with a stubbed gateway), updated `ActorSessionLoginSuccessHandlerTest`; `SessionSecurityIntegrationTest` stays green via env seed.
- [x] Targeted `:core:test :api:test`, then `clean check` — passed: 783 tests, 0 failures (8 opt-in live-provider skips).
- [x] Admin UI: `/admin/identity-providers` route + `features/identity-providers/` (card list, create/edit dialog with issuer discovery and alias suggestion, delete confirm), sidebar tab gated by `SYSTEM_ADMIN`, i18n en/vi. Verified live against dev Keycloak: `tasco` provider lists with JIT badge, edit dialog shows stored state, discovery resolves the Tasco issuer, duplicate alias surfaces `IDP_ALIAS_CONFLICT`.

## Remaining acceptance

- Live realm reconciliation replay granting `manage-identity-providers` to `memoryos-user-provisioner` (role assigned manually on the console; script assertion updated).
- Live end-to-end: brokered login through a managed provider and JIT admission.
- Test-connection endpoint (phase 2).
