# Testing and verification

## Contract first

Tests defend observable behavior, boundaries, invariants, transitions, and failure modes. Avoid tests for framework wiring, source text, or implementation details unless they enforce a repository boundary.

Use the narrowest boundary that owns the contract:

- Value and capability behavior: focused unit test.
- Database constraints and repository semantics: repository test against the migration grammar used by production.
- Module ownership: Spring Modulith and ArchUnit.
- HTTP authentication: application integration test with signed JWT and database-backed actor resolution.
- Identity-provider integration: real Authorization Code + PKCE smoke test with a normal temporary user.

## Required gates

1. Run focused tests while changing a contract.
2. Inspect every changed IDE-supported file with JetBrains static analysis; include warnings.
3. Run `gradlew.bat clean check --no-daemon` on Windows or `./gradlew clean check --no-daemon` elsewhere.
4. Exercise the actual changed runtime surface. For authentication or provider changes, use a normal temporary OIDC user, verify both rejection and success paths, and remove temporary records afterward.

A green compile does not prove runtime configuration, database behavior, or view/HTTP behavior.

## Evidence

Record concise evidence in the active increment's `verification.md`: exact command or scenario, observed result, environment boundary, cleanup, and remaining risk. Never store tokens, passwords, authorization codes, or raw secret-bearing logs.

Capability matrices under `docs/tests/` map stable requirements to their durable checks. Update the matrix whenever a contract is added, removed, or materially changed.
