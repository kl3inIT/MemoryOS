# Audit quality fixes

## Requirement

Owner request 2026-09-24: audit the whole backend and frontend for code quality and best practice, fix what needs no owner decision, and list everything else for review. The audit itself is recorded in [audit.md](audit.md); items waiting for the owner are in [owner-decisions.md](owner-decisions.md).

## Scope

In scope: defects the audit exposed (audit section A) and small, local fixes that follow an existing repository rule or an existing helper, without changing the published HTTP contract (`openapi.yml` and `web/src/lib/hey-api` stay unchanged).

Out of scope, listed in owner-decisions.md: design choices that conflict with a deliberate rule, the iam-style package split, de-duplication inside active increments, repository-wide mechanical sweeps whose timing collides with open branches, contract changes, and deployment scripts.

## Design

Each fix is local to its capability and reuses the helper or pattern the audit named: the abandoned-lease sweep follows `UsageReportRepository.failAbandoned`; the Source summary reads the provider error from both provider tables; web fixes use the existing registry components, generated query-key helpers and TanStack Router `useBlocker`. No new endpoint, runtime mode or abstraction is introduced.
