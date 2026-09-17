# Plan

1. Simplify the existing staging workflow and clarify deployment-script messages; preserve release, backup, migration, health and locking guards.
2. Add a narrow configuration regression check to CI; run workflow/IDE checks and the repository gate. Update the delivery runbook and matrix in this change.
3. Publish directly to main under the user's updated authorization, including the existing OCR manifests and increment documents in a separate commit. PR #100 is superseded by this direct publication; do not require a PR review gate.
4. Verify main CI, then deploy the exact release. If the old rollback transaction is still pending, explicitly select that transaction and use guarded finalization, never delete its reservation directly. Verify only deployed SHA and health; the user performs business acceptance.

Excluded: applying the standalone OCR deployment to the cluster, identity provisioning, smoke credentials, Linear writes and data restoration. Publishing the existing OCR configuration does not constitute a new live OCR deployment or acceptance run.
