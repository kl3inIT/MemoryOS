# Diagram-aligned group manager view

## Decision

The diagram’s actor labels map to the existing capability model; they do not add provider roles or a second role system.

- **Employee** is an active Basic member: questions, searches, citations and eligible documents only. It has no Groups or Sources administration entry merely because it belongs to the Basic Group.
- **Group Manager** is an ordinary-Group manager. Its scoped `GROUPS_READ`/`GROUPS_MANAGE` and Source authority expose only Groups it manages, their members, associated Source summaries, source-group assignment and synchronization status. It never gains the Tenant-wide catalogue or another Group’s data.
- **HR Officer** is assigned the existing global `USERS_MANAGE` and `GROUPS_MANAGE` bundle by a System Administrator for invitations, revocations, memberships, Group membership/manager assignment and Group administration.
- **System Administrator** retains `SYSTEM_ADMIN` for identity-provider settings, model configuration, usage limits, role-grant configuration, audit, chat-history and cost reporting.

Direct Group membership is a document/search eligibility relation, not Group-administration authority. It must not reveal the Group directory, member list or Source association summaries.

## Consequences

Group projections admit global Group readers and scoped ordinary-Group managers only. `listGroupSources` admits global Source readers or the manager of that exact Group; it does not admit a plain Group member. The browser exposes the Groups administration entry only when the identity carries global or scoped `GROUPS_READ`.

No provider claim grants any of these capabilities. Keycloak authenticates the person; Tenant Group grants and manager edges remain the sole authorization source.
