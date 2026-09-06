# MEM-36 — Implementation plan

## Current ownership and remaining gate

The user subsequently authorized multiple scoped commits and pushing this existing branch to GitHub for Nhat's reference. This supersedes earlier commit/push restrictions only; no PR opening, merge or deployment is authorized. Related active Linear work is assigned to Nhat (`nhudinhnhat2004`).

The receiving Codex session owns this combined increment with MEM-55 in the existing checkout after the 2026-09-06 full handoff. Historical Main/delegation assignments below are no longer dependencies. IDE datasource setup is deferred; preserve existing work and do not open/merge a PR or deploy.

- [x] Complete post-warning-cleanup review and bounded repository/frontend/browser verification; see the combined verification record for current evidence and skipped opt-in scenarios.
- [x] Review the security talk's actual demo code and slides; record [architecture fit and remaining gaps](security-patterns-review.md).

The user subsequently authorized isolated container-backed tests despite host RAM limits. Run one build at a time with `--no-daemon --no-parallel --max-workers=1` and bounded Gradle/test heaps; this does not authorize shared-environment changes.

## Preparation

- [x] Fetch main and fast-forward local main to c0397a96ee1a3d5ec759bdae0e4915e276905a87.
- [x] Consolidate MEM-55 and MEM-36 on the existing kl3inIT/mem-55-users-management branch and IntelliJ checkout; stop using the temporary IAM branch.
- [x] Preserve unrelated uncommitted roadmap/Google Drive/chunking planning and notify the concurrent checkout agent.
- [x] Record approved IAM consolidation, JPA target, account classification, scope matrix and concurrency protocol in design.md.
- [x] Fast-forward the existing Users branch to main, restore all MEM-55 changes and resolve overlap with main V10–V12; retain the original changes in a recovery stash.
- [x] Inspect relevant Onyx Users/Groups components and carry their interaction hierarchy into the real implementation.

## Implementation ownership

The current checkout owner integrates backend, frontend, Source authorization and generated contracts and serializes verification. Delegated Linear document/issue reconciliation runs independently and does not edit application sources or launch competing builds.

- [x] Consolidate identity, tenant and invitation into iam with semantic caller migration, no aliases, and updated Modulith/ArchUnit rules.
- [x] Implement JPA entities/repositories and migrate IAM lifecycle writers; preserve exact bindings, provenance, bootstrap, invitations and membership behavior.
- [x] Implement Groups, capability registry, global/scoped resolution, system groups, protected administrator survival and delegation limits.
- [x] Apply authority locking to all IAM permission mutations and Source writes; ensure revoke cannot be bypassed by a concurrent write.
- [x] Add Tenant-qualified Group/Source associations and classify every existing Source endpoint according to design.md.
- [x] Simplify Users read contracts and integrate real group membership/account classification.
- [x] Build Onyx-aligned Groups list/detail, membership/manager/grant editing and Source association UI with actual generated API consumers.
- [x] Update Users row group editor and sidebar/route gates; preserve invitation recovery and private-cache convergence.
- [x] Regenerate OpenAPI/browser client after endpoint contracts converge.

## Verification

- [x] Attempt JetBrains warning-enabled inspection on the correct checkout and record unavailability: repeated project/module/per-file timeouts prevented an IDE-clean result. Use the documented Gradle/runtime fallback.
- [x] Compile affected Gradle modules and pass final clean check --no-daemon --no-parallel --max-workers=1.
- [x] Run frontend format/lint/typecheck, all 52 unit tests, production build, API/route drift and CI checks; exercise the actual browser.
- [x] Verify mixed JPA/JDBC rollback, permission union/implication, scoped-manager bounds, cross-Tenant constraints and concurrent revocation on PostgreSQL.
- [x] Exercise real owner/admin, manager, ordinary member and out-of-scope sessions through Users, Groups and FILE Source flows.
- [x] Capture desktop/mobile UI evidence and verify keyboard/confirmation/error/empty states.
- [x] Consolidate architecture, persistence/IAM/Source contracts, verification matrices and [exact combined evidence](../mem-55-users-management/verification.md). Keep increment active until merge.

## Boundaries

The user authorized scoped commits and pushing this branch for reference. No database reset, shared-environment change, PR opening, merge or deployment is authorized. No fake Groups screens or unsupported Account Type creation flows. Implementation remains in C:/Users/admin/orca/workspaces/MemoryOS/mem-55-users-management; IDE datasource setup is deferred. Deliver both issues on this one branch.
