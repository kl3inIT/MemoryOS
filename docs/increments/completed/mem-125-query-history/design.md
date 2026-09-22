# MEM-125 — Query history for administrators

An organization that deploys MemoryOS cannot see what it is being asked. A manager cannot tell whether the assistant
answers the questions people actually bring it, which Sources it leans on, or which answers people marked wrong. The
usage ledger (MEM-98) counts tokens; it says nothing about the questions.

This increment lets an authorized administrator read the Tenant's conversations: a list, one conversation's
transcript, and a CSV of a period.

It is the one surface in MemoryOS that shows a person's own words to somebody else, so what it may and may not do is
written out below rather than left to the reader.

## Scope after the split

Only query history. Analytics is [MEM-174](https://linear.app/memory-os/issue/MEM-174), standard answers are
[MEM-175](https://linear.app/memory-os/issue/MEM-175), evals are [MEM-176](https://linear.app/memory-os/issue/MEM-176).

## Reference

**Onyx** (`backend/ee/onyx/server/query_history/api.py`, `backend/ee/onyx/db/query_history.py`,
`web/src/app/ee/admin/performance/query-history/`, read at `D:\MemoryOS\.tmp\onyx`):

- one enterprise-only router behind the permission `READ_QUERY_HISTORY` ("View query history of everyone in the
  organization"), separate from every other administrative power;
- a Tenant setting `query_history_type` of `normal` / `anonymized` / `disabled`, changed by an administrator, not an
  environment variable. `anonymized` replaces the asker's e-mail with `anonymous@anonymous.invalid` and refuses the
  per-person endpoint; `disabled` refuses every read but keeps recording conversations;
- the list carries the asker's e-mail, the first question, the first answer, the assistant, feedback and the time;
  the detail carries the mainline transcript with each message's cited documents;
- a soft-deleted conversation stays visible. The code says so: *"Deleted sessions stay visible, which is what the
  detail view is for."*;
- a conversation whose incognito mode is `usage_only` holds no content and is filtered out; `full_history` incognito
  is hidden from the owner's own surfaces but readable here;
- CSV export runs as a Celery task, because it has no bounds: the default range is 1970 to now, no row cap, and each
  conversation's transcript is rebuilt from the message tree. The result is written to the file store and stays
  downloadable;
- nothing is recorded when an administrator reads somebody's conversation.

**Mobbin** shaped the screen: [Ferndesk](https://mobbin.com/screens/ec950c92-ce44-47f9-8c85-9d9648590a4c) puts three
counts above the list and gives feedback its own column;
[ElevenLabs](https://mobbin.com/screens/9369bd47-0cec-4ff6-bc1e-0e087830e574) adds filters as chips;
[Vapi](https://mobbin.com/screens/09d7e1e9-b321-4411-9892-4e298147d00a) reads the question and the answer side by
side.

## Decisions

**A capability of its own.** `CHAT_HISTORY_READ`, an ordinary grant through a Group, implied by administrator access,
exactly as `AUDIT_READ` is. [Chat](../../../specs/chat.md) already reserves this: *"Private-only access is the base
contract; sharing uses its own authorized read path and any future administrative transcript access requires a
separate operation."* So this is a new read path, not a flag on an existing one.

**Names, not anonymity, by default.** The list shows who asked, with their name and e-mail. Three visibility modes as
in Onyx — `NORMAL`, `ANONYMIZED`, `DISABLED` — set by a model manager in Chat settings, defaulting to `NORMAL`.

The screen states what `ANONYMIZED` does **not** do: it hides the asker's name and e-mail, and nothing else. A
question usually identifies its author anyway ("the contract my department signed last week"). Calling that mode
anonymity without saying so would be a false promise; Onyx does not say so.

**A conversation the owner deleted is still listed, as in Onyx**, and carries a "Deleted" mark so the reader knows
what they are looking at. Onyx shows no such mark. Hard deletion is off by default
(`memoryos.chat.retention.hard-delete`), so those rows are still in the table; the Tenant retention policy
(`chat_settings.chat_retention_days`) eventually removes them, and then they leave this screen too.

**A temporary conversation cannot appear, because there is nothing to show.**
[Chat](../../../specs/chat.md) states it: *"AI usage is still recorded for a temporary conversation; its message
content is not."* This is not a filter with a choice behind it, as Onyx's `usage_only` filter is; there is no content.

**Every read of somebody else's conversation is recorded.** Onyx records nothing. Reading a colleague's questions is
the kind of act [audit](../../../specs/audit.md) was built for — the audit design named this increment as the
consumer that would need it. Recorded: opening a transcript (`chat_history.read`, naming the reader and whose
conversation it was) and exporting (`chat_history.export`, with the row count). Not recorded: paging the list, which
shows no message body beyond the first question and answer and would drown the stream at chat volume.

**The export is one request, bounded at 50,000 rows**, with a byte-order mark and the formula guard, exactly as the
audit export is. Onyx needs a queue because its export has no bounds at all. A bounded export needs no queue, and a
queue for a limit no one has hit yet is a mechanism to maintain for nothing. If the bound turns out to bind, moving
to the Worker is a later change with evidence behind it.

**Citations are named, not opened.** A message lists the titles of what it cited, as Onyx does. Opening a citation's
content goes through the reader's own Source authority, never the asker's, which is the rule
[Chat](../../../specs/chat.md) already sets for sharing: *"Sharing grants transcript access only: citation previews
still use the reader's source authority."* Onyx can delegate this to Google Drive or Confluence; MemoryOS holds the
documents itself, so MemoryOS checks.

## Reading

`chat_settings` gains `chat_history_visibility` (`NORMAL` | `ANONYMIZED` | `DISABLED`, default `NORMAL`), changed by
a model manager and recorded through `chat_settings.change`. `chat_session` gains an index on
`(tenant_id, updated_at DESC, id)`: every index it has today is keyed by owner, which an organization-wide read
cannot use.

| Method and path | Contract |
| --- | --- |
| `GET /api/chat/history` | A page of conversations, newest first, on an opaque `(updated_at, id)` cursor as the audit log uses. Filters: `from`, `to`, `q` (asker or title), `actorId`, `feedback` (`POSITIVE`, `NEGATIVE`, `MIXED`, `NONE`). Each row: who asked, the title, the first question, the first answer, the model, the message count, feedback, whether it was deleted, and when |
| `GET /api/chat/history/{sessionId}` | One conversation's transcript on its selected branch, with each message's feedback and cited titles |
| `GET /api/chat/history/export` | The filtered conversations as CSV, at most 50,000 rows, one row per question-and-answer pair |

Every one requires `CHAT_HISTORY_READ` and is refused when visibility is `DISABLED`. Under `ANONYMIZED` the asker's
name and e-mail are dropped server-side, and `actorId` stops being an accepted filter, as in Onyx.

## Screen

**Admin › Monitoring › Query history** (`/admin/chat-history`), beside AI costs and the audit log: three counts for
the period (conversations, marked good, marked bad), one row of filters, the shared table and pager, and a row that
opens the transcript in a Sheet. The export link carries the filters on screen.

## Scope

In: the visibility setting, the capability, the three reads, the audit of reads, the screen, and the Vietnamese copy.

Out: analytics (MEM-174), standard answers (MEM-175), evals (MEM-176), a per-person view like Onyx's
`/admin/chat-sessions`, and any change to what conversations are retained.
