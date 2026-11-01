# Plan — MEM-265 Chat schedules

Design: [design.md](design.md).

Status 2026-10-15: designed; not started, after MEM-270. Four pull requests, each merged and deployed before
the next.

| Step | Change | Verification |
| --- | --- | --- |
| 1. Turn composition | Move the beans `ChannelRuntimeComponents` imports to run a turn into a chat-owned composition; `memoryos-channels` and the Worker import it. Confirm `WorkerDependencyRulesTest` and the Worker's memory budget allow it before anything else | `WorkerDependencyRulesTest`, `checkRuntimeClasspathIsolation`, channels tests unchanged; Worker heap on staging measured after deploy |
| 2. Storage | `V165` creates `chat_schedule`, `chat_schedule_run` and `chat_session.schedule_id`; `JdbcChatScheduleRepository` in `chat.persistence` with the due-row claim | Repository integration tests: a claim skips locked rows and advances `next_run_at` in the same transaction; deleting the membership deletes the schedules |
| 3. Service | `ChatScheduleService`: create, update, enable, delete, run now; the cron and time-zone check with Spring `CronExpression`; the 10-schedule and one-hour bounds; the Tenant switch | Unit tests for the next time across time zones and daylight-saving changes; API cases for the bounds and ownership |
| 4. Runner | `ChatScheduleRunner`: authority checks, a new conversation, the turn as the owner with the scheduled instruction, approval-required tools declined, `update_memory` not offered, `NOTHING_NEW`, failure counting and pausing, late and overlapping runs. Worker task `memoryos-chat-schedule-dispatch-v1` (30 s) with at most 5 runs at once | Integration test against PostgreSQL and a stub model: a due schedule produces one run and one conversation; a lapsed agent share pauses it; a `NOTHING_NEW` answer deletes the conversation; an approval-required tool is declined |
| 5. API | `ChatScheduleController` with `contract/` records; Tenant switch on the Chat administration contract; `openapi.yml` and the Hey API client regenerated | `ChatScheduleApiIntegrationTest`; `OpenApiContractTest` |
| 6. Web | `/automations` with the list and the schedule page (editor, Run now, history); sidebar entry under "Trợ lý"; the agent menu and the question menu shortcuts; clock mark on scheduled conversations; the admin switch | Component tests; screenshots at 1440 and 390 px with realistic schedules, reviewed before asking the owner |
| 7. Lark delivery | `ChannelAdapter.send` and its Lark implementation (direct message by `open_id`, resolved by verified email through Lark's user lookup); the run sends the answer and a link when the schedule asks for it | Lark adapter test against a stubbed Lark API; staging run that reaches a real Lark account |
| 8. Agent follow-ups | `schedule_follow_up` tool, one-time schedules bound to a conversation, the 3-per-conversation and 30-day bounds, listed on `/automations` | Integration test: a follow-up fires in the same conversation and is deleted afterwards |
| 9. Documents | `docs/specs/chat.md` (schedules), `docs/specs/channel.md` (proactive send), `docs/tests/chat.md`, `ARCHITECTURE.md` (Worker runs turns), MEM-250's scheduled-run line, roadmap | Reviewed with each change |

Pull requests: steps 1–4, steps 5–6, step 7, step 8; documents with each.

## Acceptance

- [ ] A member creates "8:00 every Monday: summarise last week's meeting notes" and receives the answer as a new
      conversation at 8:00 in their time zone, with the clock mark.
- [ ] The same schedule with Lark delivery sends the answer and a link to the member's Lark.
- [ ] A check that finds nothing leaves no conversation and sends nothing.
- [ ] Removing the member's access to the agent pauses the schedule with the reason shown.
- [ ] "Remind me on Friday to review this" continues the same conversation on Friday.
- [ ] Turning the Tenant switch off stops every schedule from firing; turning it on resumes them.
- [ ] No prompt or answer text appears in logs.
