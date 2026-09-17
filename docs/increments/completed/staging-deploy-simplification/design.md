# Staging delivery: deployment, not business acceptance

## Accepted scope — 2026-09-12

The user owns post-deploy login, upload, indexing, Search and reader acceptance. CD must not require those tests or a smoke account. A successful CD means the verified main images were deployed, migrations/startup succeeded and API/worker/web health and revision checks passed; it does not mean business acceptance passed.

Reuse the existing immutable release bundle, checksum/provenance checks, dedicated SSH identity, server lock, database backup, normal Flyway startup and `verify_runtime`/`finish` machinery. Remove Node/pnpm/Playwright setup, smoke secrets and smoke commands from CD. Keep optional local acceptance tooling unchanged. Recovery is an explicit operator operation; workflow failure/cancellation reports the transaction and leaves any reservation intact, with no automatic rollback or database restore. Existing rollback compares schema history before restoring images. The manual exact `recovery_release` input can finish an already-healthy pending transaction after its existing ownership/image/health checks, then promote the selected release.

Do not modify OCR, identity/account permissions, CI test coverage or Linear. No new runtime profiles, endpoints, worktrees or recovery framework. This substantive CI/CD follow-up supersedes the mandatory-smoke design introduced in PR #99; its history remains intact.

### Publication scope update — 2026-09-12

The user subsequently authorized direct publication to main without a PR and explicitly included the existing OCR files. Preserve those changes and publish them as a separate commit; this supersedes the earlier OCR publication exclusion, not the boundary against applying standalone OCR to the cluster. Identity provisioning, Linear writes and business acceptance remain outside this change.
