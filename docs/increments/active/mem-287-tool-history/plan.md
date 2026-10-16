# MEM-287 plan

Linear [MEM-287](https://linear.app/memory-os/issue/MEM-287); design in [design.md](design.md). One pull request.

| Step | Change | Verification |
| --- | --- | --- |
| 1. Storage | V182 `chat_message_tool_calls`; repository insert and read by message ids in `chat/session/persistence` | Postgres test: written with the answer, read in order, deleted with the message |
| 2. Capture | `ChatToolActivity` keeps calls with arguments and results; the kept-result tool list; byte bounds | Unit test of the bounds and the list |
| 3. Finish | The turn's calls are written in the transaction that finishes the turn | Persistence integration test |
| 4. Replay | `TurnContext` carries each answer's calls; `ChatTurnSetup` replays them with the three-stage pruning; stripped for the search rewrite and for a turn without tools | `ChatTurnSetup` tests: order, branch, pruning, model without tools |
| 5. Documents | `docs/specs/chat.md`, `docs/tests/chat.md` | Reviewed with the change |

## Acceptance

- [ ] On staging, the workbook scenario's follow-up ("add a column chart and send me the cleaned workbook") produces the
      file, using the workbook turn 1 left in the sandbox.
- [ ] The MEM-270 comparison (data, state, cpu, shell, code) passes every scenario.
