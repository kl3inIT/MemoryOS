# ADR 0002: No speculative operational surfaces

- Status: Accepted
- Date: 2026-08-18
- Decision owner: MemoryOS

## Context

MEM-7 needs persistent actor and external-identity resolution, but MemoryOS does not yet have account linking, administrative authorization, or audit behavior for ownership changes. A temporary Spring profile, one-shot main class, Gradle task, and provisioning contract were initially added to seed bindings.

That surface made an incomplete product flow appear operable while adding production configuration, lifecycle, and maintenance obligations. It would also let later work depend on a mechanism that cannot prove identity ownership or authorize the change.

## Decision

Do not ship application runtime modes, one-shot profiles, convenience endpoints, or speculative abstractions whose only purpose is to bridge a product flow that has not been designed.

A production write path is added only with a concrete recurring use case and its complete security and operational contract. External account linking must:

1. authenticate the current actor;
2. prove control of the new provider identity;
3. authorize the ownership change;
4. preserve exact `(issuer, subject)` semantics;
5. write transactionally with explicit conflict behavior; and
6. emit audit evidence.

Until that flow exists, explicitly approved bootstrap or recovery may use a reviewed database transaction from a runbook. Disposable verification helpers stay in test or temporary workspace files and are never packaged into the application.

An exception requires a recurring operator-owned capability, documented authorization and audit boundaries, and a separate accepted ADR. “Needed once” is not a recurring capability.

## Consequences

- MEM-7 keeps the Flyway schema, database constraints, datasource/Flyway runtime, and read-only JDBC resolver.
- MEM-7 removes the provisioning main class, Spring profile, Gradle task, write contract, conflict exception, and production insert logic.
- Tests seed isolated databases directly and continue to verify exact lookup, uniqueness, foreign keys, deletion restriction, and authentication behavior.
- Operators may bootstrap an approved binding through the [development runtime runbook](../runbooks/development-runtime.md), but that SQL is not a product API.
- Account linking becomes its own vertical slice rather than hidden follow-up debt inside identity persistence.
