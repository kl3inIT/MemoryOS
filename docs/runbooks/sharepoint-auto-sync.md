# SharePoint Auto Sync setup

How an organization lets a SharePoint Source admit readers by SharePoint's own permissions. The contract is in the [Connector specification](../specs/connector.md#sharepoint-permissions-and-group-membership); this page is what an administrator and an operator do.

## In the Entra admin centre

1. Use an app registration with a **certificate**. SharePoint REST, which is where permissions are read, refuses the token of a client secret; MemoryOS refuses Auto Sync for such a credential with `SOURCE_SHAREPOINT_SYNC_REQUIRES_CERTIFICATE`.
2. Under API permissions add these **application** permissions and grant admin consent:

   | API | Permission | Used for |
   | --- | --- | --- |
   | Microsoft Graph | `Sites.Read.All` | Sites, libraries, files and pages |
   | Microsoft Graph | `GroupMember.Read.All` | Members of an Entra group a document is shared with |
   | SharePoint | `Sites.FullControl.All` | Role assignments of items and sites, and members of SharePoint groups |

   With `Sites.Selected` instead, grant the application `fullcontrol` on each site (`POST /sites/{id}/permissions`); `read` indexes the site but cannot read its permissions.

## In MemoryOS

1. Store the certificate as a SharePoint credential (Sources, SharePoint setup).
2. Create the Source with **Auto Sync**, or change an existing Source's visibility to it. Nobody reads its documents until the next run has read their permissions; the change asks for that run at once.
3. Optional, in the Source's settings: **Treat sharing links as public**. A file with a link for the organization or for anyone is then readable by every member. It is off by default.

A reader is matched by the verified email they sign in to MemoryOS with, which must equal their Entra sign-in name without `.onmicrosoft`. Guests are not matched.

## On the server

| Setting | Default | Meaning |
| --- | --- | --- |
| `MEMORYOS_SHAREPOINT_GROUP_SYNC_INTERVAL` | `PT5M` | The shortest time between two reads of group membership. A read happens inside a synchronization run, so membership is as fresh as the Source's synchronization interval |
| `MEMORYOS_SHAREPOINT_ENUMERATE_DIRECTORY_GROUPS` | `false` | Also ask the directory for every group, up to 100,000. Needed only when Entra groups are granted on single files and on nothing above them |

## When it does not work

| What the Source shows | What to do |
| --- | --- |
| `SOURCE_SHAREPOINT_SYNC_PERMISSIONS_DENIED` | Microsoft refused to list role assignments or group members. Grant the two permissions above with admin consent, then synchronize |
| `SOURCE_SHAREPOINT_SYNC_REQUIRES_CERTIFICATE` | Replace the credential's client secret with a certificate, or choose Public or Private |
| A member cannot find a document they can open in SharePoint | Check that their MemoryOS login email is verified and equals their Entra sign-in name; then wait for the next run. Permissions of every item are read again every thirty minutes, group membership at each run |
