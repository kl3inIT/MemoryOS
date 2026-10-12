# MEM-169 — Meeting transcripts from Google Meet, Teams and Zoom

Status: **decisions accepted 2026-10-12; the platform-independent part is implemented (plan step 2), the Google
adapter waits for a Workspace to run the spike against.** Linear:
[MEM-169](https://linear.app/memory-os/issue/MEM-169).

## Problem

A meeting held on Google Meet, Microsoft Teams or Zoom often has a transcript the platform already wrote. Today a
member gets it into MemoryOS only by recording the meeting again or uploading a recording
([Meetings](../../../specs/meeting.md)). The issue asks to pull those transcripts into the **Cuộc họp** menu, not into
Sources as documents, so that everything MEM-92 built applies: speaker names, minutes, decisions, actions, the biên
bản and the file library. An imported meeting is read by **the people who attended it**; nobody else, an administrator
included, sees it.

## Scope

This increment delivers **Google Meet** through the Tenant's existing Google service-account credential
([MEM-90](../../completed/google-drive-service-account/design.md)), and the provider boundary the next platforms plug
into. Teams and Zoom are separate increments under the same issue; Fireflies and Gong only if Tasco uses them.

Out: joining a meeting with a bot, importing recordings or audio, Gemini smart notes, Calendar-driven detection,
Workspace Events push (polling fits the existing leased-job pattern), and per-person OAuth for Meet.

## Owner decisions (2026-10-12)

1. **First platform: Google Meet**, through the service account with domain-wide delegation. Tasco confirms its
   Workspace edition records transcripts and adds the Meet scope to the delegation.
2. **Owner of an imported meeting is its organizer**, matched to an active member by verified login email
   ([ADR 0011](../../../decisions/0011-verified-email-source-permission-matching.md)). A meeting whose organizer is
   not a member is not imported. Cost: an attendee does not see a meeting an outsider organized.
3. **The import runs in the Worker**, as other provider polling does; the minutes job stays in the API and picks the
   imported meeting up from the queue it already reads.
4. **Not a Source.** `SourceSyncEngine` turns items into versions, Documents and index work; a transcript becomes a
   `meeting` with utterances and never a Document. Only the credential and the adapter-registry pattern are reused.
5. **No new `core` edge if the spike allows it.** `meeting` may not depend on `connector`
   (`allowedDependencies`), and `connector` holds the credential. The Google integration module may use the public
   API of both, so it implements `meeting`'s port and asks `connector` for the delegated credential. If `connector`
   cannot publish that without leaking internals, the alternative is an edge `meeting → connector` with an ADR.
6. **Minutes are written when the owner first opens an imported meeting**, not at import: most imported meetings
   are never read and each run is a model call. Until the owner opens it, an attendee reads the transcript without
   minutes.
7. **Global `SOURCES_MANAGE` turns the connection on and off**, since it already guards the credential it uses.
8. **The platform-independent part is built first**, before the spike: the origin of a meeting, the attendee rule
   and the import of one transcript. If the spike changes decision 2, part of that schema changes with it.

## What Google offers

Read from Google's documentation on 2026-10-12; **nothing here was run against a Workspace yet** (plan step 1).

| Question | Answer |
| --- | --- |
| API | Meet REST API v2: `conferenceRecords.list` (filter on `start_time`, `end_time`; 100 a page), `conferenceRecords.participants`, `conferenceRecords.transcripts`, `transcripts.entries` |
| Scope | `https://www.googleapis.com/auth/meetings.space.readonly` |
| Who may read | The meeting space owner or a participant; domain-wide delegation is supported |
| An entry | The text, `languageCode`, a link to the participant who spoke, start and end time; listed by start time |
| Retention | Entries are deleted 30 days after the conference ends. The transcript document stays in the organizer's Drive (`docsDestination.document`) |
| Edition | Transcripts exist only on Workspace editions that include them, and only when transcription was on in that meeting |

Open questions for the spike:

- Whose conferences `conferenceRecords.list` returns for an impersonated user (organized only, or attended too), and
  how the organizer is identified when it returns both.
- How a signed-in participant (`signedinUser.user`) resolves to an email through the Directory API the credential
  already reads, and what an anonymous or phone participant carries.
- When a transcript becomes readable after the meeting ends, and its states.
- The quota of the Meet API per user and per project.

## Design

### Placement ([ADR 0027](../../../decisions/0027-integrations-by-platform-and-memoryos-module-names.md))

| Module | Holds |
| --- | --- |
| `core` `io.memoryos.meeting` | The transcript source of a meeting, the import service and its leased job, the attendee rule, a `MeetingTranscriptAdapter` port behind a registry keyed by the platform enum (one adapter from the first platform, [ADR 0019](../../../decisions/0019-provider-families-use-adapters-behind-a-registry.md)), `MeetingWorkerComponents` |
| `core` `io.memoryos.connector` | At most one published way to open the Tenant's service-account credential as a named user for a given scope list (decision 5) |
| `memoryos-integration-google` | The Meet adapter and its client on the module's guarded transport, in its own package beside the Drive code |
| `memoryos-worker` | Imports `MeetingWorkerComponents` and schedules the job |
| `memoryos-api` | The connection endpoints and the new fields of the meeting contract |

The Meet scope is requested in its **own token**, never added to `GoogleDriveGateway.SERVICE_ACCOUNT_SCOPES`: Google
refuses a token whose scope list names one scope the delegation lacks, so adding it there would break every existing
Drive Source until an administrator changed the delegation.

### Data

V155 holds what does not depend on a platform; the connection and the cursor come with the import job.

- `meeting.origin` (V155): `RECORDED`, `UPLOADED` or `GOOGLE_MEET`; existing rows are backfilled from `audio_status`.
- `meeting_external_record` (V155): Tenant, platform, the platform's conference identifier and the meeting it
  became (nullable), unique on Tenant, platform and conference. It is the idempotency key of the import, and it
  **outlives the meeting**: an owner who deletes an imported meeting leaves the row, so the next run does not bring
  the meeting back.
- `meeting_attendee` (V155): Tenant, meeting, lower-case email. Names are already the meeting's participants.
- `meeting_transcript_connection`: one row per Tenant and platform with the credential, whether it is on, the time
  it imports from, its status and last error code, and the lease of the running import.
- A per-member cursor (the end time read up to), so a run continues where the last one stopped.
- `meeting_utterance.track` and `meeting_speaker.track` accept a third value, `IMPORTED`. `MIC` cannot be reused:
  an online meeting reads an unnamed `MIC` voice as the owner (`SpeakerNames`).

### The import

1. An administrator turns the connection on and names the service-account credential. The first run reads at most
   the last 30 days, since older entries no longer exist.
2. A leased job (`LeasedJob`, as the recording and minutes jobs) takes one connection, and for each active member
   with a verified email lists that member's conferences that ended since their cursor, acting as that member.
   Members are the only possible owners (decision 2), so the Workspace directory is not enumerated.
3. For a conference with a finished transcript that is not yet in `meeting_external_record`: read its participants
   and entries outside any transaction, bounded by a page and a time budget, then in one transaction insert the
   meeting as `ENDED` with its start and end time, the speakers named as the platform names them, the utterances
   (`confidence` 1, no uncertain stretches, so no correction pass is offered), the attendees and the external
   record (`MeetingImportService.importTranscript`, implemented). The minutes are queued when the owner first opens
   the meeting (decision 6).
4. A conference without a transcript yet is looked at again by later runs for a bounded time, then left.
5. Failures follow the connector's vocabulary: a refused delegation or missing scope marks the connection as needing
   attention with a stable code and stops; throttling waits as long as Google asked; one unreadable conference is
   skipped and retried by the next run. Logs carry counts and codes, never a title, a name or transcript text.

### Who reads an imported meeting

`MeetingAccessSql.READS` gains one more branch: the reader's verified login email is in `meeting_attendee` for that
meeting. It is resolved per request, as Auto Sync grants are, so a person who joins the Tenant later sees the meetings
they attended and a changed email takes effect at once. An attendee is a reader: the transcript, the names, the
minutes and the biên bản, never the owner's notes or an edit. The owner keeps every owner action, including sharing
with more people and deleting. An attendee without a verified email, or one outside the Workspace, matches nobody.
No administrative capability reads a meeting.

### API and web

- The meeting summary and detail carry `origin`; an imported meeting offers no recording, upload or ticket, and its
  list row says where it came from.
- Connection endpoints for an administrator (read, turn on with a credential, turn off, status), guarded by global
  `SOURCES_MANAGE` (decision 7).
- Every contract change refreshes `openapi.yml` and the generated web client in the same change.

## Risks

- **Privacy.** Turning the connection on imports every transcribed meeting its members organized. It is off by
  default, an administrator turns it on, the change is audited, and the transcript never leaves the meeting page
  (the library file still carries the minutes only).
- **Per-user delegation** was left open by MEM-90; this increment is the first to act as each member.
- **An API fact that turns out different** in the spike (who lists what, how an organizer is named) changes step 2 of
  the import and possibly decision 2. The spike runs before any schema is written.
- **Minutes wait for the owner** (decision 6). An attendee who opens the meeting before its owner sees no minutes
  and cannot ask for them.

## Later, under the same issue

- **Teams:** Microsoft Graph transcripts with the Entra application of the SharePoint credential, in
  `memoryos-integration-microsoft`; it needs its own application permission and a Teams application access policy.
- **Zoom:** cloud recording transcripts, readers by access to the recording.
