# 11. Source Group attachment follows a recorded manager

Date: 2026-09-16

## Status

Accepted

## Context

Scoped Source authority was derived from associations: an actor could operate a non-public Source only while they managed **every** ordinary Group attached to it. Sharing a Source between two Groups therefore removed it from both managers at once, and a Group manager could not even detach it from their own Group. The owner reported exactly that, and rejected keeping the rule.

Two alternatives were considered and declined:

- **Relax the association rule to "any managed Group".** Attaching a Source to one's own Group would then hand over rename, upload, reindex and Drive configuration for a Source belonging to someone else.
- **Fix only the browser.** The operation the owner wanted would stay impossible.

## Decision

A Source records one `manager_actor_id`. That Actor, while they remain an active standard member managing at least one ordinary Group, holds the scoped operational authority the association set used to confer, and attaches or detaches the Source within the Groups they manage. Groups they do not manage are untouched by their changes.

Scoped creation records its creator and no longer requires a Group. Global creation records no manager. `SYSTEM_ADMIN` appoints or clears the manager; a candidate who manages no ordinary Group is rejected.

Independently of the manager, the manager of an associated Group may detach the Source from **their own** Group, because what their Group carries is their decision.

Catalog visibility includes the recorded manager. Reading a restricted Source's documents is unchanged and still requires membership in an associated Group: neither the manager role, global administration nor creator identity is a content ACL bypass ([Connector contract](../specs/connector.md)).

## Consequences

- A restricted Source with no Group is legitimate and reaches nobody until it is attached; the browser says so at creation and on Source detail.
- Sources created before V47 have no recorded creator, so they have no manager and stay administrator-only until one is appointed.
- Losing the Group-manager role withdraws Source authority without changing any association, which is the intended handover path: an administrator appoints someone else.
- Google Drive credentials remain bound to the Actor who authorized them, so appointing a new manager does not transfer the Drive grant. That gap is recorded in the [increment](../increments/active/source-manager-group-authority/design.md) and remains open.
- Supersedes the association-derived scoped write rule described in earlier Connector revisions; item removal and Source deletion keep their global-only rule with the existing groupless-creator carve-out.
