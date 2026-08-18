# MEM-7 verification

Date: 2026-08-18

## Latest branch evidence

| Check | Scenario | Observed result |
| --- | --- | --- |
| Focused persistence and HTTP contracts | `gradlew.bat :core:test --tests io.memoryos.identity.persistence.JdbcExternalIdentityResolverTest :api:test --tests io.memoryos.api.security.JwtAuthenticationIntegrationTest --no-daemon` | `BUILD SUCCESSFUL`; production lookup and all test seed/update/delete operations use Spring `JdbcClient` |
| JetBrains static analysis | Resolver, repository test, API security composition, JWT integration test, core Gradle file, and version catalog; warnings included | Zero findings across all six changed IDE-supported files |
| Repository gate | `gradlew.bat clean check --no-daemon` | `BUILD SUCCESSFUL`; 17 actionable tasks, 13 executed and 4 from cache |
| Runtime surface removal | `gradlew.bat :api:tasks --all --no-daemon` | Only standard Spring Boot application tasks remain; no identity provisioning task is exposed |
| Documentation links | Parsed every repository Markdown relative link | 18 Markdown files and 43 relative links checked; zero broken links; `CLAUDE.md` imports `@AGENTS.md` |

## Shared PostgreSQL and OIDC evidence

Earlier in the same MEM-7 increment, before final cleanup:

- Flyway V1 applied to shared PostgreSQL 18.4 in database `memoryos`; a second startup reported no migration required.
- Shared database/user password authentication succeeded through a loopback-only port managed at `/apps/postgres`.
- A normal temporary Keycloak user completed Authorization Code + PKCE S256 against the `memoryos` realm.
- `/actuator/health` returned `200`.
- `/api/identity/me` without a token returned `401`.
- `/api/identity/me` with the valid token and exact stored `(issuer, subject)` returned `200` with the expected internal actor UUID.
- The temporary Keycloak user, actor row, binding row, callback data, password file, SSH tunnels, browser session, and local helper files were removed. A final database count for the temporary subject was zero.

The production read path exercised by that scenario is unchanged. The temporary application provisioning surface used to seed the row was subsequently removed; approved future bootstrap uses the reviewed SQL transaction in `docs/runbooks/development-runtime.md`.

## Remaining delivery work

- Collect the already-requested single CodeRabbit review against the latest available head and disposition findings.
- Push the operating-model and provisioning-removal commit to PR #3.
- Merge only after required CI is green and the reviewed head is proven.
- After merge, move the increment directory to `docs/increments/completed/` and reconcile the roadmap.
