# 28. SharePoint permissions admit readers by verified email, and by groups kept for each Source

Date: 2026-10-12

## Status

Accepted; implementation started 2026-10-12
([MEM-136](../increments/active/mem-136-sharepoint-auto-sync/design.md)). Extends
[ADR 0011](0011-verified-email-source-permission-matching.md) to SharePoint. ADR 0011 and
[ADR 0013](0013-google-group-membership-through-service-accounts.md) stand.

## Context

A SharePoint Source may admit readers by SharePoint's own permissions (Auto Sync), as Onyx EE does. SharePoint says
who may read an item as role assignments, which name three kinds of principal: a person, a SharePoint group of the
site, and a security group of the Entra directory. Groups contain groups. MemoryOS knows a reader by the verified
login email of their identity provider and has no Microsoft identity for them.

Three choices had to be made: what a person in SharePoint is matched with, how a group is named so that a document
and a membership list mean the same group, and where membership is kept.

## Decision

- **A person is matched by email.** A reader holds `ms_user:<lower(verified login email)>`. A document grants
  `ms_user:<email>` for each person named on it, where the email is the Entra sign-in name (`UserPrincipalName`)
  in lower case with `.onmicrosoft` removed, as Onyx's `normalize_email` does. A sign-in name that is not an address
  grants nothing. An unverified login email holds no token, as in ADR 0011.
- **A group is matched by a name both sides build the same way.** An Entra group is `<display name>_<object id>`
  and a SharePoint group is `<site address>::<title>`, both lower case, as Onyx names them. A document grants
  `ms_group:<source id>:<group name>` for every group reached from its role assignments, the groups inside those
  groups included, and a reader holds that token for each group that lists their email as a direct member.
- **Membership is kept for each Source, in generations.** A SharePoint group belongs to one site, and which
  groups matter is decided by the sites a Source holds items of, so membership is read for a Source rather than
  for its credential. As in ADR 0013, only a read that reached every site becomes the active generation, and a
  failed read keeps the one in force.
- **An Entra group is never found by its display name.** Onyx falls back to a search by display name when a login
  name carries no object identifier. Display names are not unique, and a wrong match would show a document to the
  members of another group, so such a group is left out. The owners claim of a group (`<id>_o`) is left out for
  the same reason: owners are not members.
- **Everyone is public.** A role assignment to "Everyone" or "Everyone except external users" makes the document
  readable by every active Tenant member, as in Onyx.

## Consequences

- Auto Sync on SharePoint is exactly as strong as the identity provider's email verification and as the equality
  of a person's Entra sign-in name and their MemoryOS login email. A guest, whose sign-in name is
  `name_domain#EXT#@tenant`, matches no reader; neither does a person whose sign-in name differs from their
  mailbox. Both can only withhold a document.
- A group renamed in Entra or in SharePoint has two names until the permissions of its documents and its
  membership were both read again; in between, nobody reads through it.
- Onyx reads group membership only from the role assignments of each site itself. A group granted on a single
  file and on nothing above it has no members unless the deployment asks the directory for every group
  (`memoryos.sharepoint.enumerate-directory-groups`). This is Onyx's behaviour, kept as it is.
- Two Sources over the same site read its groups twice. That costs requests, not correctness, and keeps a Source
  from admitting readers on the evidence of another Source's credential.
