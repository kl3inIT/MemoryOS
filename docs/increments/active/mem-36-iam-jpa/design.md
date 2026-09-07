# MEM-36 — JPA IAM and Onyx-aligned Groups

## Approved direction

The user approved implementation, explicitly prioritizing the clean early-project target over migration effort. Identity, Tenant membership, invitations, Users and Groups become one closed `iam` capability. Its public identifiers and operation contracts are the boundary for other capabilities; entities/repositories remain internal. This supersedes the earlier MEM-36 constraint to preserve the separate Identity/Tenant/Invitation modules. Do not retain aliases or parallel authorization paths. Deliver MEM-55 and MEM-36 together on the existing `kl3inIT/mem-55-users-management` branch in `C:/Users/admin/orca/workspaces/MemoryOS/mem-55-users-management`, now fast-forwarded to main c0397a96ee1a3d5ec759bdae0e4915e276905a87. Do not create another branch/worktree or discard the existing Users work.

The existing MEM-55 work is retained and extended on the same branch. Preserve exact profile provenance, ActorId-only sessions, invitation recovery, active/inactive membership, owner protection, bounded Users queries and private-cache convergence. Its profile migration is V13 after main V10–V12. A recovery stash f799972ff956a0311334befff1e5ed85815cd591 retains the pre-update changes; it is not a runtime compatibility path.

## Scope

The post-handoff security review verifies bearer/browser identity isolation and fresh IAM authority without adding a token gateway or provider-role authorization. Enterprise broker/JIT, provider-side revocation and absolute browser-session lifetime are tracked separately on Linear and are not implementation claims for this combined increment.

Deliver working IAM persistence, Users, Groups, group grants, scoped managers and FILE Source associations. Follow Onyx interaction hierarchy and row actions using MemoryOS components. No speculative Requests, SCIM, bot, anonymous, service-account credential management, Agents, LLM, token-limit or tool controls. Account classification is persisted on Actor; the current admitted account type is STANDARD. Unsupported account creation types are not selectable or accepted. Account type is not an administrator role or permission grant.

## Model and ownership

JPA entities under `io.memoryos.iam.persistence`: ActorEntity, ExternalIdentityBindingEntity, ActorProfileEntity, TenantEntity, TenantMembershipEntity, InvitationEntity and TenantBootstrapStateEntity, plus GroupEntity, GroupMembershipEntity and GroupCapabilityGrantEntity. Map existing identity/Tenant/invitation tables without UUID changes. Composite binding and Tenant membership keys retain current constraints. GroupMembership is an explicit edge with isManager, not a bare ManyToMany. ORM relationships may connect these entities within IAM; there is no cross-capability entity navigation.

Groups use `iam_groups`, `iam_group_memberships`, `iam_group_capability_grants`. Group identity is UUID. Membership key is (tenant_id, group_id, actor_id), with composite foreign keys to the Group and Tenant membership. Grant key is (tenant_id, group_id, capability). Group names are trimmed and unique case-insensitively within the Tenant. Groups carry an optional system key ADMIN or BASIC; system keys are unique per Tenant. Ordinary group removal deletes its edges/grants and Source associations but never Actor, membership, Source or document data. Admin/Basic cannot be removed or renamed.

Connector owns `source_group_grants` linking existing Sources (connector_credential_pairs) to IAM Groups with Tenant-qualified foreign keys. IAM does not import Connector. Existing Sources migrate to the system Admin association without broadening access; newly created Sources receive an explicit association atomically.

Use JPA for IAM lifecycle. Keep native SQL for bounded union projections, locking and bulk operations when clearer. Repositories own persistence mechanics; application services own transactions, validation and authorization. No duplicate entity/domain/DTO layers and no interface/Default pair for a simple query. Keep Source, Document, Object Storage and Ingestion persistence JDBC-first.

## Public integration contract

All existing Identity/Tenant/Invitation public types move to `io.memoryos.iam` with clean caller migration; keycloak adapters remain an internal IAM subpackage. Existing public ActorId, TenantId and invitation/onboarding semantics are preserved. Existing Users read types are simplified to UserListItem, UserPage, UserQuery and UserQueryService, rather than UserDirectory and its wrapper hierarchy.

New public contracts:

- IamCapability: IAM_ADMIN, USERS_MANAGE, GROUPS_READ, GROUPS_MANAGE, SOURCES_READ, SOURCES_MANAGE, SOURCES_DELETE. Persist only explicit grants. IAM_ADMIN implies every implemented capability; GROUPS_MANAGE implies GROUPS_READ; SOURCES_MANAGE implies SOURCES_READ. IAM_ADMIN is reserved to the protected Admin Group and is not editable on ordinary groups.
- Authority: GLOBAL, SCOPED, NONE.
- IamAccess: TenantId tenantId and Authority authority.
- IamAuthorization: current access resolution plus methods to require global access, require global-or-scoped access, and lock/require authority inside a resource write transaction. It must query current active Tenant/member state. No session authority cache, second-level permission cache or effective_permissions JSONB.
- GroupProvisioner: bootstrap(TenantId, ActorId configuredOwner) and addToBasicGroup(TenantId, ActorId). Bootstrap is idempotent; invitation Basic assignment participates in the enclosing transaction and cannot create/reactivate Tenant membership or grant manager/Admin.
- GroupService: paged list/detail, create/rename/delete, paged members/candidates, add/remove membership, assign/remove manager, replace ordinary capability grants and replace a user's ordinary group memberships. API uses public bounded projection records, never entities.

Group and capability grant projections include allowed actions for UI affordances; server operations always reauthorize. Capability registry metadata is server-owned and contains only working consumers.

## Permission and scope matrix

| Operation | Global authority | Scoped manager |
| --- | --- | --- |
| Users list, invitation issue/rotate/revoke, activate/deactivate | USERS_MANAGE | Denied |
| Users row group editor | IAM_ADMIN | Denied |
| Group list/detail/members | GROUPS_READ | Own managed ordinary Groups only |
| Create/rename/delete ordinary Group | GROUPS_MANAGE | Denied |
| Add/remove ordinary Group member | GROUPS_MANAGE | Own managed Group, subject to delegation guard |
| Assign/remove manager | GROUPS_MANAGE | Denied |
| Edit capability grants | IAM_ADMIN | Denied |
| Change Admin membership or any system-group configuration | IAM_ADMIN | Denied |
| Source list/detail and operation polling/history | SOURCES_READ | Associated Sources in own managed Groups only |
| Source create | SOURCES_MANAGE | Denied |
| Upload initiate/finalize and reindex | SOURCES_MANAGE | Associated Sources in own managed Groups only |
| Source item removal and Source deletion | SOURCES_DELETE | Denied |
| Edit Source–Group associations | SOURCES_MANAGE | Denied |

A scoped manager may not add a member when the target Group's expanded global grants exceed the manager's own expanded global grants. Managers cannot change manager flags, manipulate system Groups, or promote themselves. Global grants remain global; they are not silently restricted to a same-named Group's Sources. Conversely manager status never becomes a global permission.

Basic has no administration or Source grant; active Tenant membership supplies the existing application admission baseline. Existing inactive members retain their status even when assigned Basic. The configured owner retains OWNER presentation/bootstrap semantics, protected active membership and Admin membership. Protect the final active STANDARD system administrator. Other OWNER/MEMBER checks must not remain as product authorization alternatives.

Source management permission does not confer document-content access or bypass current/future source ACL, principal mapping, freshness or resource/connection state. Do not claim Google Drive ACL/retrieval delivery.

## Concurrency and transactions

Use one API JpaTransactionManager for JPA and JdbcClient on the same DataSource. Explicitly configure entity/repository scanning and Hibernate schema validation; Flyway owns DDL. Disable open-in-view and permission caches. Keep worker JDBC behavior explicit instead of accidentally changing it through a transitive starter.

IAM authority mutations acquire the current Tenant row FOR UPDATE before Actor/Group/invitation locks. Resource writes acquire the same Tenant row FOR SHARE before reading current authority and Source scope, and hold it through the database mutation. Source association changes acquire the exclusive authority lock. This serializes revoke with protected writes while allowing concurrent resource writes. Bootstrap uses its existing singleton startup lock before the Tenant exists. Do not hold authorization locks during provider/network IO; reauthorize inside the transaction that commits its result. Read queries obtain fresh database authority and filter in SQL before counts/pagination. A transaction annotation alone is not evidence of revoke/write correctness.

Shared internal IamLockRepository owns lock mechanics. Public IamAuthorization exposes the resource-write guard; external capabilities never import the lock repository. Be explicit about JPA flush before dependent JDBC statements and managed-state refresh/avoidance after native updates.

Tenant authorization_version increments in the exclusive authority mutation transaction and is exposed as authorizationVersion by current identity. Frontend authority fingerprints include this revision so changing managed Groups or Source associations purges stale private views even when capability token sets stay identical. The revision is an invalidation signal, never a grant, session authority or materialized permission cache; rollback also rolls back its increment.

## API and UI contracts

Preserve existing invitation command URLs and Users routes while moving implementation ownership. Users shows Name/email, Groups with overflow, Account Type, status and compact row actions. Do not restore Tenant access/User directory headings or a percentage-width Actions column. Group membership editor preserves manager flags on unchanged edges; special system membership changes use explicit protected actions.

Groups has a searchable bounded list, New group action and a real detail surface: editable name for eligible groups, Members, Permissions and Sources. Member actions, source association actions, save/cancel/dirty states and confirmations follow the local Onyx reference. Do not copy Onyx migration banners, unsupported resource controls or their client-side authorization filtering. Group/member/source lists are server-authorized. Keep keyboard focus, confirmation focus restoration, empty/loading/error/retry states, mobile overflow and private-cache invalidation consistent with MemoryOS.

All browser calls use generated OpenAPI clients. Final wire schemas are generated from implemented controllers, not handwritten parallel clients. Main owns final API generation and integration.

## Verification

Inspect every changed IDE-supported file through JetBrains on `C:/Users/admin/orca/workspaces/MemoryOS/mem-55-users-management`, with warnings enabled, then compile. This is the project currently open in IntelliJ and the single implementation checkout. No desktop automation is used.

Run the checked-in Gradle clean check gate and frontend check once edits converge. Retain behavior regressions for union/implication, manager scope and privilege amplification, cross-Tenant foreign keys, protected owner/admin survival, mixed JPA/JDBC rollback and concurrent revoke/write. Exercise actual owner/admin, manager, member and out-of-scope sessions against PostgreSQL/Keycloak/FILE storage. Verify next-request revocation, group membership editing, grant changes, Source associations, upload/finalize/reindex, denied deletes and unfiltered-list/poll bypasses. Browser visual proof is required for Users/Groups desktop and mobile.
