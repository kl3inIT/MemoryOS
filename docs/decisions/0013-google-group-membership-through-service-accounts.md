# 0013 — Google Group membership comes from service-account Directory reads

Status: Accepted, implementation started 2026-09-21. Supersedes the Google Group consequence of [ADR 0011](0011-verified-email-source-permission-matching.md); the rest of ADR 0011 stands.

## Context

Auto Sync Sources enforce the Google Drive permissions of each file ([MEM-105](../increments/completed/mem-105-source-access-modes/design.md)). ADR 0011 matches `user` and `domain` permissions against the reader's verified login email and leaves `group` permissions matching nothing, because MemoryOS could not read Workspace group membership. Companies on Google Workspace share most files with groups, so their Auto Sync Sources hid most of their content. [MEM-90](../increments/active/google-drive-service-account/design.md) adds a domain-wide-delegated service-account credential that acts as a primary Workspace administrator and can read the Admin SDK Directory, as Onyx's `group_sync.py` does.

## Decision

A service-account credential reads its Workspace groups (`groups.list` over the primary admin's domain) and each group's members (`members.list` with `includeDerivedMembership=true`) as the primary admin. It advances one Directory page per Drive sync step of a Source on that credential. Membership is stored per credential as generations, and only a completed run becomes the active generation.

A `group` permission grants `google_group:<lower-case email>`. A reader holds that token while the active generation of an active service-account credential in their Tenant lists their verified login email. A group whose members include the whole customer admits every reader whose verified email is in the primary admin's domain. Reader tokens stay per request, so a membership change never rewrites the index.

## Consequences

- A failed or interrupted run keeps the previous active generation in force, matching the MEM-105 rule that the last successful observation stands until a newer one succeeds.
- Revoking a service account or replacing its key forgets every generation at once. A credential that loses its authority (`NEEDS_REAUTHORIZATION`) stops granting membership without deleting it.
- Nested groups count: a member of a group inside the granted group can read. Onyx reads direct members only.
- OAuth credentials read no groups, so their `group` permissions still match nothing.
- Membership is a Tenant-wide fact: once any active service account lists a reader in a group, that group's grants admit the reader on every Auto Sync Source of the Tenant.
- The whole-customer rule covers only the primary admin's domain. Secondary Workspace domains are not admitted until whole-domain user listing lands (MEM-90 step 3).
