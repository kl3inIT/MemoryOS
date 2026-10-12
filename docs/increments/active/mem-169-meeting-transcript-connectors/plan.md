# Implementation plan

[Design](design.md). Each step ends green on its narrowest tests; the backend gate is `clean check`, the web gate
`pnpm --dir web check`. Step 2 was built before the spike by owner decision; steps 3 onward start only after step 1
answers its open questions.

## 0. Decisions and prerequisites

- [x] Owner accepted the decisions on 2026-10-12: minutes on the owner's first open, global `SOURCES_MANAGE`, the
  platform-independent part first.
- [ ] Tasco confirms its Workspace edition records transcripts and adds `meetings.space.readonly` to the service
  account's domain-wide delegation.

## 1. Spike against a Workspace

- [ ] Answer the design's open questions with recorded responses: whose conferences a member's listing returns and
  how the organizer is named, participant to email, transcript states and delay, quota.
- [ ] Keep the responses as adapter fixtures, names and emails replaced, no token or key.
- [ ] Settle decision 5: what `connector` must publish to open the credential as a member, or an edge with an ADR.
- [ ] Update the design with what was found.

## 2. Meeting origin and attendees (`core`)

- [x] Migration `V155`: `meeting.origin` with backfill, `meeting_external_record`, `meeting_attendee`, the
  `IMPORTED` track.
- [x] `MeetingAccessSql.READS` admits an attendee by verified email; list, shelf and detail follow.
- [x] `MeetingImportService.importTranscript`: one platform transcript becomes one ended meeting, once; the minutes
  are queued when the owner first opens it.
- [x] `origin` on the meeting summary and detail contract, `IMPORTED` in the track enum; `openapi.yml` extended by
  hand with exactly the generated differences (a regenerated file reorders the Lark entries) and the web client
  generated from it. The web keeps recording to `MIC` and `TAB` and names a speaker of any track.
- [x] `MeetingImportTest` (6 tests): an attendee reads and cannot edit; a member who did not attend and one with an
  unverified email get `MEETING_NOT_FOUND`; a deleted imported meeting does not come back; the owner's first open
  queues the minutes and an attendee's does not; what a platform sends is cleaned.

## 3. Port, registry and the Google adapter

- [ ] `MeetingTranscriptAdapter` and its registry in `meeting`; startup fails on a missing or duplicate adapter.
- [ ] The Meet adapter in `memoryos-integration-google` on the guarded transport, with its own token scope;
  failure mapping to stable codes.
- [ ] Adapter tests on a local HTTP server serving the fixtures; the module's `*DependencyRulesTest` and
  `checkRuntimeClasspathIsolation` stay green.

## 4. Connection and import job

- [ ] Migration: `meeting_transcript_connection` and the per-member cursor.
- [ ] The import job: leased claim, per-member listing, bounded read, one `importTranscript` per conference,
  failures classified.
- [ ] `MeetingWorkerComponents`; the Worker schedules the job (`WorkerDependencyRulesTest`).
- [ ] Tests: a second run imports nothing twice; a lapsed lease loses the write; a refused delegation marks the
  connection; an organizer who is not a member is skipped.

## 5. API

- [ ] An API test that the recording, upload and ticket routes refuse an imported meeting (they refuse any ended
  meeting today).
- [ ] Connection endpoints with their contract records and typed failures.
- [ ] `openapi.yml` and the web client regenerated.

## 6. Web

- [ ] The Meetings list and detail show the origin and hide recording controls for an imported meeting.
- [ ] The administrator's connection screen: credential, on and off, status and last error.
- [ ] Vietnamese and English copy; `pnpm --dir web check`.

## 7. Verification and consolidation

- [ ] Staging against the Workspace: a transcribed meeting appears for its organizer and an attendee, not for a
  third member; its minutes are written; deleting it does not bring it back. Evidence in `verification.md`.
- [ ] Consolidate into `docs/specs/meeting.md`, `docs/tests/meeting.md`, `docs/specs/connector.md` (the delegated
  credential), `ARCHITECTURE.md` and a runbook for the Workspace setup; an ADR if an edge was added.
- [ ] Reconcile the roadmap; move the increment to `completed/` after the pull request merges.
