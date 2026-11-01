# MEM-265: Chat schedules

## Why

Chat answers only when someone asks. Much of what members ask is the same question at a fixed time: what the
week's meetings decided, which contracts expire within thirty days, which bids are due, what changed in the
HR policies. Each answer is worth something only before a moment (the Monday meeting, a deadline, the month's
close), and it is lost if the member forgets to ask.

A schedule is a saved question to one agent with a time. When the time comes, MemoryOS asks it as the member, keeps
the answer in a conversation of its own and, when the member wants, sends it to them in Lark. An agent can also
set a one-time follow-up in a conversation ("remind me on Friday to review this estimate").

## References

| Product | Revision read | What it does |
| --- | --- | --- |
| Onyx Craft | `40eb240df` | `scheduled_task` (prompt, canonical 5-field cron, `editor_mode` as a UI hint, `next_run_at` as the dispatcher's only read field, soft delete) and `scheduled_task_run` (status, session, trigger source); a Celery beat dispatcher picks due rows, each run opens a fresh session, a 30-minute budget, no retry, a notification on failure or when the run needs approval (`backend/onyx/db/models.py`, `server/features/build/scheduled_tasks/`) |
| Dust | `3a1e97b` | Triggers by cron or webhook on Temporal; each run creates a new conversation for the trigger's editor; a run past a usage cap leaves a failed agent message explaining the limit; at most 42 runs a day per trigger; agents set their own wake-ups in a conversation (`front/temporal/triggers/activities.ts`, `front-api/routes/w/[wId]/assistant/conversations/[cId]/wakeups/`) |
| Glean | Documentation, read 2026-10-15 | Schedule triggers off by default and enabled by an administrator for everyone or chosen groups; at most 10 active background agents per user; tools that need confirmation are either allowed by an administrator to run unattended or the run emails the user instead of skipping the step; past runs appear in Chats under an Automations filter |
| MateClaw (Spring AI) | `bd41a71` | `ThreadPoolTaskScheduler` with ShedLock across instances; the model call runs outside any transaction, between a transaction that records the run and one that saves the answer; the scheduled prompt asks the model to answer `[SILENT]` when there is nothing to report, and such a run delivers nothing (`mateclaw-server/src/main/java/vip/mate/cron/`) |
| Spring AI, Embabel, Agno | Source and changelog | Spring AI and Embabel have no scheduling; applications use `@Scheduled`, Quartz or ShedLock around the agent call. Agno's `SchedulerTools` lets an agent create and change its own cron jobs |

## Decisions

| Topic | Reference | MemoryOS | Why |
| --- | --- | --- | --- |
| What a schedule is | Onyx: prompt and cron. Dust: agent, prompt and cron or webhook | An agent, a prompt, a schedule with a time zone, and whether to send the answer to Lark. The agent's default model answers | The agent already carries tools, Sources and model. Webhook triggers are out of scope |
| Storage of the schedule | Onyx: cron string plus `next_run_at` in its own table. Dust: Temporal schedules | `chat_schedule` holds a Spring `CronExpression`, an IANA time zone and `next_run_at`. The page's editor (daily, weekly, monthly, every N hours, advanced) compiles to the expression; the editor mode is kept only to reopen the same editor | The API edits a row in its own transaction; the dispatcher reads one indexed column. A db-scheduler persistent schedule per row would keep a second copy of the schedule in `scheduled_tasks` that the API cannot update without a scheduler client |
| Dispatch | Onyx: Celery beat. MateClaw: an in-process scheduler with ShedLock | One db-scheduler recurring task in the Worker, every 30 seconds, claims due rows with `FOR UPDATE SKIP LOCKED`, inserts a run, advances `next_run_at` in the same transaction, then starts the runs | db-scheduler already gives the Worker a single recurring execution and dead-owner recovery ([MEM-45](../../completed/mem-45-db-scheduler-control-plane/design.md)); no ShedLock or Quartz |
| Where the turn runs | Onyx: a Celery worker. MateClaw: the server | In the Worker, through `ChatTurnService` with the owner's `ActorId`, as `memoryos-channels` already runs Lark turns | Scheduled runs are durable background work; the API serves browsers and the channels process scales with bot traffic. The Worker imports the turn composition that `ChannelRuntimeComponents` imports today, moved into a chat-owned composition both use |
| Whose authority | Dust: the trigger's editor. Glean: the subscriber | The owner's, checked at every run: an active membership, `CHAT_WRITE`, the agent still usable by them (`AgentAccessSql.USES`), and the owner's spend limit (MEM-123) | A run reads exactly what the owner could read by asking. A schedule never runs as a service account |
| Conversation per run | Onyx and Dust: a new session or conversation per run | A new conversation per run, private to the owner, surface `WEB`, titled with the schedule's name and the run date, linked to its schedule (`chat_session.schedule_id`) | Runs do not leak into each other's context; the member can continue the conversation as an ordinary one. The sidebar marks it with a clock |
| Nothing to report | MateClaw `[SILENT]` | The scheduled turn's instruction asks for exactly `NOTHING_NEW` when there is nothing to report; such a run is recorded `NOTHING_NEW`, its conversation is deleted and nothing goes to Lark | A daily check that mostly finds nothing would otherwise fill the sidebar and the member's Lark, and the member would turn it off |
| Tools that need approval | Onyx: the run waits for approval. Glean: email the user | An MCP tool marked `approval_required` (MEM-253) is declined at once in a scheduled turn, and the step shows it was not run | Nobody is watching the run; waiting would hold the turn until it times out. Tools that run without approval are the administrator's existing decision |
| Memory | MEM-250 left scheduled runs without memory | The scheduled turn reads the owner's memory and does not offer `update_memory`; a turn the member types later in that conversation is ordinary | The owner alone reads the answer, so their memory may shape it ("my project"). The prompt is fixed text, not something the member said, so it must not write memory. This revises the MEM-250 line on scheduled runs |
| Failures | Onyx: no retry, notify. Dust: a failed message explaining a limit | No retry. A failed run keeps its conversation with the failure, as a failed web turn does. Three failures in a row, or the owner losing access to the agent or Chat, pause the schedule with the reason | A retry repeats the cost of a turn that failed for a lasting reason; a paused schedule with a reason is visible on the page |
| Late and overlapping runs | Onyx: a residency limit drops stale queued runs | A run more than one hour late is recorded `MISSED` and the next time is computed from now. A run due while the previous run of the same schedule is still going is recorded `SKIPPED` | After a Worker outage the member gets at most one late answer, never a burst |
| Limits | Glean: 10 active per user. Dust: 42 runs a day per trigger | At most 10 enabled schedules per member, at least one hour between two runs of a schedule, and at most 5 scheduled turns running at once in a Worker | Bounded cost and load without a new quota system; spend is already bounded per member by MEM-123 |
| Administrator control | Glean: off by default, enabled per group | A Tenant switch "Schedules" on the Chat administration page, on by default. Off: no new schedules, existing ones stop firing and stay saved | One owner-level control is enough for one Tenant; per-group enablement waits for a customer who needs it |
| Lark delivery | Glean: Slack message as an output | When the schedule asks for it, the answer and a link to the conversation go to the owner as a Lark direct message from the Tenant's Lark bot. The owner's Lark user is resolved by their verified email and cached in `channel_identities` | The answer is useful only if it reaches the member. `ChannelAdapter` gains a proactive `send`; today it can only reply to an inbound message |
| Agent follow-ups | Dust wake-ups. Agno `SchedulerTools` | A `schedule_follow_up(when, note)` tool creates a one-time schedule bound to the current conversation; when it fires, the note is asked in that same conversation. At most 3 pending follow-ups per conversation, at most 30 days ahead | "Remind me on Friday" belongs to the conversation that has the context; a new conversation would lose it |
| Where the member manages it | Onyx: a scheduled tasks page. Glean: Automations filter | A sidebar entry "Tự động hoá" (`/automations`) under "Trợ lý": the member's schedules with their next run and an on/off switch saved on change; a schedule's page with its editor, "Run now" and its run history. Shortcuts: "Schedule" on an agent's menu, and "Repeat on a schedule" on a question in a conversation | The member's own work, not a setting; the shortcuts start from the place where the member found the question worth repeating |

## Contracts

- `chat_schedule(id, tenant_id, owner_actor_id, persona_id, name, prompt, cron, time_zone, editor_mode, deliver_lark,
  enabled, paused_reason, session_id NULL, next_run_at NULL, consecutive_failures, created_at, updated_at, deleted_at)`.
  `session_id` is set only for an agent follow-up, which has no cron and is deleted after it fires. Foreign keys to the
  owner's membership and to the persona with `ON DELETE CASCADE`. Partial index on `next_run_at` for enabled, undeleted
  rows.
- `chat_schedule_run(id, schedule_id, session_id NULL, status, error_code NULL, scheduled_for, started_at,
  finished_at)`; status `RUNNING`, `SUCCEEDED`, `NOTHING_NEW`, `FAILED`, `SKIPPED`, `MISSED`. Runs older than 90 days are
  purged.
- `chat_session.schedule_id NULL` with `ON DELETE SET NULL`.
- REST under `/api/chat/schedules`: list, create, read, update, delete, `PUT .../{id}/enabled`, `POST .../{id}/run`
  and `GET .../{id}/runs`. A member sees and changes only their own schedules. Codes: `CHAT_SCHEDULE_LIMIT_REACHED`,
  `CHAT_SCHEDULE_INVALID`, `CHAT_SCHEDULES_DISABLED`.
- Tenant setting `chat.schedules.enabled` on the existing Chat administration contract.
- Logs: `chat.schedule.run.started`, `.succeeded`, `.nothing_new`, `.failed` (with `error_code`), `.skipped`, `.missed`,
  `chat.schedule.paused`; never the prompt or the answer.

## Out of scope

Webhook and event triggers, schedules shared with other members or subscribed to by them, per-group enablement,
choosing a model per schedule, delivery by email or to a Lark group, retries, and in-app notifications.
