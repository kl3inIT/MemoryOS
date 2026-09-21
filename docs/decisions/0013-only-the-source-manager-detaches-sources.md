# 0013 — Only the Source manager detaches a Source from a Group

Status: Accepted, implementation started 2026-09-21. Supersedes only the paragraph "Independently of the manager, the manager of an associated Group may detach the Source from **their own** Group, because what their Group carries is their decision." in [ADR 0011](0011-source-manager-group-attachment-authority.md).

## Context

ADR 0011 gave attachment to the Source's recorded manager but let any manager of an associated Group detach the Source from that Group. With two managers on one Group, the one who did not create a Source could remove it and then could not put it back, because only the recorded manager attaches. The owner reported this on 2026-09-21 and decided that the other manager should have no authority over that Source at all.

## Decision

Detaching a Source from a Group (`POST /api/groups/{groupId}/sources/{sourceId}/remove`) needs the same authority as attaching it: global `SOURCES_MANAGE`, or the Source's recorded manager under the scoped write rule (active Group manager, non-public Source) acting on a Group they manage. Managing a Group the Source is attached to confers nothing on that Source. `removableSourceIds` on `GET /api/groups/{groupId}/sources` projects the same rule.

The rest of ADR 0011 is unchanged.

## Consequences

- Another manager of the Group sees the Source there but cannot remove or add it; Group detail shows it locked and names the responsible manager.
- A Source with no recorded manager, and every PUBLIC Source, can be detached only with global `SOURCES_MANAGE`.
- A recorded manager still detaches only from Groups they manage; an association with a Group they do not manage stays for an administrator.
