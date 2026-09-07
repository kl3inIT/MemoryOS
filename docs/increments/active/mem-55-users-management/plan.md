# MEM-55 implementation plan

## Current ownership and remaining gate

The user subsequently authorized multiple scoped commits and pushing this existing branch to GitHub for Nhat's reference. This supersedes earlier commit/push restrictions only; no PR opening, merge or deployment is authorized. IAM follow-ups are assigned to Nhat (`nhudinhnhat2004`); MEM-65 authentication theme belongs to Duc Anh (`anhnd05122004`).

The receiving Codex session owns the combined Users/Groups work in this existing checkout after the 2026-09-06 full handoff. Historical Main/delegation assignments below describe completed work, not current dependencies. Preserve all existing files. Datasource/JPA IDE setup is deferred; no staging/Infisical mutation, PR opening, merge or deployment is authorized.

- [x] Complete post-warning-cleanup review and bounded repository/frontend/browser verification; see the post-cleanup section of verification.md for current evidence and skipped opt-in scenarios.
- [x] Compare the Spring I/O security talk, slides and visible demo code with current IAM; record [findings and coverage gaps](../mem-36-iam-jpa/security-patterns-review.md).

The user subsequently authorized container-backed verification on this memory-constrained host. Run isolated test containers and one build at a time, using `--no-daemon --no-parallel --max-workers=1` and bounded Gradle/test heaps. Shared staging and IDE datasource state remain out of scope.

## Design

- [x] Inspect current Identity/Tenant/Invitation and Onyx Users patterns.
- [x] Isolate work from the dirty MEM-61 checkout using an Orca-managed worktree.
- [x] Record ownership, API shape, profile provenance, directory semantics and frontend direction in the design.

## Implementation

- [x] Identity profile persistence and exact-binding write contract.
- [x] Global-USERS_MANAGE current Users directory with bounded search/status/role/Group/sort/page/count queries, real Group editing and STANDARD Account Type.
- [x] Tenant MEMBER activate/deactivate with protected OWNER and inactive-invitation precedence.
- [x] Users controller and generated API surface.
- [x] Admitted-login profile capture, USERS_MANAGE projection and per-request membership enforcement.
- [x] Decomposed Users UI, shared interaction primitives where actually reused, Invite/recovery modal and status-aware actions.
- [x] Remove the old invitation-history browser route and migrate callers/tests without breaking public invitation routes.
- [x] Reconcile admin-only Keycloak provisioning provenance and distinguish identity-account conflicts in the UI.

## Verification

- [x] Record combined-checkout JetBrains unavailability after repeated timeouts; use wrapper compilation and runtime verification without claiming IDE-clean status.
- [x] Verify focused core/API behaviors, PostgreSQL query/transaction edges and architecture boundaries.
- [x] Regenerate OpenAPI/Hey API contracts and pass the final combined Gradle clean check plus frontend gates.
- [x] Exercise the actual Users surface, invitation activation/recovery and next-request deactivation with real browser/runtime evidence.

Observed results and cleanup are recorded in [verification.md](verification.md). This increment remains active until merge.

The current checkout owner integrates application sources, generated contracts and verification. Delegated agents reconcile Linear documents and issues independently while the owner runs one bounded build at a time.
