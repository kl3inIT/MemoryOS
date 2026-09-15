# MEM-105 — Source access modes: Public, Private and Auto Sync

## Approved boundary (2026-09-15)

This increment replaces the two current Source access values, PUBLIC and RESTRICTED, with three Onyx-style modes. It enforces the Google Drive permissions collected by [MEM-88](../mem-88-google-drive-acl-sync/design.md) in Search, Chat document search, citation passage reads and original-PDF reads. It takes over the per-file Google token phase that [MEM-93](../../completed/mem-93-chat-search-authorization/plan.md) deferred to the MEM-88 contract.

| Mode | Stored value | Source types | A reader sees a document when |
| --- | --- | --- | --- |
| Public | `PUBLIC` | FILE, Google Drive | they are an active Tenant member |
| Private | `PRIVATE` (was `RESTRICTED`) | FILE, Google Drive | they are a member of a Group associated with the Source |
| Auto Sync | `SYNC` | Google Drive only | the Source's retained Google permissions for that file grant them; Group associations grant nothing |

Out of scope:
- Google Group membership expansion. This needs Directory access or a service account; see MEM-90.
- The Sources UI redesign, which is the next branch.
- Incremental synchronization through the Drive Changes API.
- Audit.

## Reference

The reference is Onyx `ec08b5f948165d4640f51343e04e77081b33ea32` (2026-08-27), in the local checkout `E:\Project\onyx`:

- `AccessType` is PUBLIC, PRIVATE or SYNC on the connector–credential pair.
- `backend/ee/onyx/external_permissions/google_drive/doc_sync.py:38-63`: a domain permission with `allowFileDiscovery=false` grants nothing, because only domain users who already hold the link can open it. A discoverable domain permission maps to a domain group that the group sync fills with real Workspace members.
- `doc_sync.py:215-259`: a user permission becomes the user's email. A group permission becomes an external group whose members come from the group sync. An `anyone` permission makes the document public, regardless of discovery.
- `backend/onyx/access/access.py:114-135`: a reader's ACL is their email, their prior emails and PUBLIC. `backend/onyx/access/models.py:176-199` formats the document ACL.

**Adopted from Onyx:**
- the mode vocabulary;
- matching readers by email;
- `anyone` makes a document public;
- a link-only domain share grants nothing;
- a Google Group grants only through its membership;
- ACL tokens are written at index time and matched by a query-time filter.

**Deliberate deviations:**
1. Domain membership is approximated by the domain of the reader's verified email, because MemoryOS has no Directory group sync.
2. Google Group entries grant nothing, because MemoryOS has no group sync.
3. There is no fallback grant to the retrieving account when a permission read fails. MEM-88 already rejected this.
4. Deleted entries and entries whose `expirationTime` has passed are ignored. Onyx ignores expiry.
5. There are no prior-email aliases.

## Decisions

All decisions were approved with the user on 2026-09-15.

1. **One branch for all three modes.** It merges only when Auto Sync enforces access, so no selectable mode is cosmetic ([ADR 0002](../../../decisions/0002-no-speculative-operational-surfaces.md)).
2. **Reader identity.** A reader is identified by their verified login email: `actor_profiles.email` with `email_verified = true`, compared in lower case. ADR 0011 amends the [identity contract](../../../specs/identity.md): email still never binds, admits or JIT-provisions an identity. It is used only to match provider Source permissions.
3. **Permission interpretation** (Onyx, with the deviations above):

   | Google permission | Effect |
   | --- | --- |
   | `user` with an email | readers whose verified email matches |
   | `domain` with `allowFileDiscovery` not false | readers whose verified email is in that domain |
   | `domain` with `allowFileDiscovery = false` | nothing |
   | `anyone`, discoverable or link-only | every active Tenant member |
   | `group` | nothing |
   | `deleted = true`, a passed `expirationTime`, or a missing email/domain | nothing |

   Every role (reader, commenter, writer, organizer, owner) can read.
4. **Freshness.** Enforcement uses the last successful snapshot for as long as the file's Document stays retrieval-eligible in that Source. This holds while a newer traversal has not yet re-read it (`STALE`), after a later read fails, and after a selection or credential revision change. An unobserved file (no successful snapshot, or no row) is denied. A deselected or removed file loses mapping eligibility and is hidden by the existing rules. The MEM-88 state table in the connector spec is rewritten to match. Revocation takes effect at the next successful permission read, which is the same bound Onyx has.
5. **Modes by Source type.** The mode can be changed after creation. FILE Sources offer Public and Private. Google Drive Sources offer all three, and new Drive Sources default to Auto Sync. Existing Drive Sources become Private, so their behaviour does not change.
6. **Who may choose which mode.**
   - Public requires global `SOURCES_MANAGE`.
   - Scoped managers may choose Private or Auto Sync, and must still associate at least one Group they manage. For Auto Sync, those Groups scope management only.
   - Changing the mode keeps today's requirement of exclusive global `SOURCES_MANAGE`.

## Design

### Data and contract

- **V61 migration:**
  - drop `ck_pairs_access`;
  - update `RESTRICTED` to `PRIVATE`;
  - add `CHECK (access_type IN ('PUBLIC','PRIVATE','SYNC'))`.

  "SYNC only on Google Drive" is a cross-table rule. The service and the repository update enforce it, and tests cover it.
- **Enum:** `SourceAccess` becomes `PUBLIC, PRIVATE, SYNC`. The OpenAPI enum changes accordingly and the hey-api client is regenerated. Only the MemoryOS web client consumes this enum.
- **Requests:**
  - `CreateGoogleDriveSourceRequest` gains a nullable `access`; an absent value means `SYNC`.
  - `CreateFileSourceRequest` rejects `SYNC`.
  - `updateSourceAccess` accepts FILE and Google Drive Sources, and returns a conflict for FILE → `SYNC`.
- **Remove the hard-coded Drive RESTRICTED:**
  - `DefaultGoogleDriveSourceService` (creation, validation intent and group replacement);
  - `JdbcGoogleDriveSourceRepository` insert;
  - the `access_type = 'RESTRICTED'` guard in `JdbcGoogleDriveCredentialRepository`;
  - `SEARCHABLE_SOURCE`, which becomes FILE or GOOGLE_DRIVE.
- **`SourceAccessPolicy` defaults:**
  - FILE: `PUBLIC` for global managers, `PRIVATE` for scoped managers.
  - Google Drive: `SYNC`.
  - A scoped manager requesting `PUBLIC` is still rejected.
- **Management scope:** `SourceScopeSql` keeps its `access_type <> 'PUBLIC'` semantics, so Private and Auto Sync Sources are scoped by their associated Groups.

### Auto Sync tokens: one rule

One SQL fragment in `JdbcSourceDocumentRepository` derives the grant tokens for a Source and provider file that has a successful snapshot (`observation_revision > 0`). It follows the interpretation table:
- `google_user:<lower(email)>`
- `google_domain:<lower(domain)>`
- a public marker

Two consumers use this same fragment, so what is indexed and what is rechecked cannot drift:

- **Index time.** `documentAccess` unions each retrieval-eligible mapping's contribution:
  - PUBLIC: everyone.
  - PRIVATE: `group:<id>` for its Group associations.
  - SYNC: the fragment's tokens, with the public marker setting `everyone`.

  A SYNC Source's Group associations contribute nothing.
- **Post-query recheck.** `READ_SCOPE` becomes: active membership AND (PUBLIC OR (PRIVATE AND member of an associated Group) OR (SYNC AND the mapped file's tokens intersect the reader's tokens)).
  - The document-level SYNC branch applies wherever the mapping is joined: readable documents, citation/Source metadata and original PDF.
  - The Source-level lists (`searchableSources`, `searchableSourceOptions`) show a SYNC Source to every active member. A Source name is not document content, and per-document tokens still decide which results appear.

**Reader tokens.** `actorAccessTokens` adds `google_user:<email>` and `google_domain:<domain>` when the Actor's profile email is verified. They are resolved per request, so a change of email or verification needs no index write. The recheck binds them as a text array.

### Propagation

- **Snapshot changes.** `GoogleDriveAclChanged` is published in the snapshot transaction and already carries `documentIds`. A new `SearchProjectionMaintenance` listener calls `enqueueDocumentAccess(tenant, documentIds, identity)`. Like `enqueueSourceAccess`, it resets a pending or running refresh so the next claim reads the newest access. The worker rewrites only the access fields and reuses the vectors.
- **Mode changes** publish the existing `SourceAccessChanged`.
- **Expiry.** A passed `expirationTime` is enforced immediately by the recheck. The stale index token only affects which candidates are returned, not authorization.
- **Backstop.** `metadata_hash` includes access, so `reconcile()` repairs any drift.

### Web (minimal; the redesign is a separate branch)

- FILE creation: Public and Private.
- Drive creation: a mode choice that defaults to Auto Sync and uses short, Onyx-like descriptions. Scoped managers see Private and Auto Sync.
- Source detail: Change visibility, with three options for Drive and two for FILE.
- The Sources list gains an Auto Sync filter and badge.
- All strings are translated.

### Documentation

- ADR 0011.
- `identity.md`, the email clause.
- The connector spec: Source creation, Google consent, the browser access line, the Google setup exclusions, the MEM-88 boundary paragraph and the state table.
- The connector and identity test matrices.
- `document.md`, ARCHITECTURE, README and the roadmap.
- The MEM-93 plan note, which will link here.
- The AGENTS active list.

## Verification

- **PostgreSQL tests:**
  - every interpretation-table row;
  - verified and unverified reader email;
  - a Document mapped from two Sources with different modes;
  - an unobserved file is denied;
  - grants are retained after STALE, a later failure, and scope and credential revision changes;
  - index-time tokens equal recheck decisions;
  - FILE rejects SYNC;
  - a scoped manager cannot choose Public;
  - the V61 migration.
- **Search integration with OpenSearch:**
  - all three modes × readers inside and outside the ACL or Group;
  - a mode change and an ACL change are reflected through the ACCESS refresh without re-embedding.
- **API and web:** OpenAPI contract, API integration, web unit tests and `pnpm check`.
- **Live local runtime through Orca,** with the small YOUNGXV AUTO fixture:
  - two MemoryOS accounts, one whose verified email is on the fixture ACL and one that is not;
  - all three modes, each checked in Search and in Chat;
  - no staging document permissions are changed and no email addresses are recorded in evidence.
- **Gate:** `gradlew clean check`.

## Risks and limitations

- Readers without a verified login email, for example brokered-IdP users whose provider does not assert a verified email, see no Auto Sync documents until their email is verified.
- A failed permission read keeps the last grants until the next successful read, as in Onyx. Revocation is only as fresh as the synchronization cadence.
- The name of a SYNC Source is visible to all active members in the Search Source filter.
