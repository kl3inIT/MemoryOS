# Plan

1. Simplify the existing staging workflow and clarify deployment-script messages; preserve release, backup, migration, health and locking guards.
2. Add a narrow configuration regression check to CI; run workflow/IDE checks and the repository gate. Update the delivery runbook and matrix in this change.
3. Publish one follow-up PR, perform the bounded review pass, wait for latest-head CI and guarded merge under the existing user authorization.
4. Verify main CI, then deploy the exact release. If the old rollback transaction is still pending, explicitly select that transaction and use guarded finalization, never delete its reservation directly. Verify only deployed SHA and health; the user performs business acceptance.

Excluded: OCR dirty files, identity provisioning, smoke credentials, Linear writes and data restoration.
