# Reasoning per task model

Status: in progress 2026-10-03. Follows [no deployment Chat model](../../completed/no-deployment-chat-model/design.md) and the reasoned meeting minutes (#466).

## Problem

Every task beside the conversation (naming, the question check, meeting minutes, transcript corrections) ran with thinking off, because they are helper calls.

- That suits a label or a title, but not the minutes. On staging, `cx/gpt-6-luna` found three action items in one run of a meeting and none in the next.
- #466 makes the minutes reason at medium, but in code. An administrator could pick the model of each task and not how hard it thinks.

## Reference

assistant-ui's Model selector (`/elements/model-selector`, already installed as `components/assistant-ui/elements/model-selector.tsx` and used by Chat and the agent editor):

- It puts the reasoning effort in the same popover as the model.
- A "Thinking" row of levels shows only while the selected model declares `efforts`.
- The trigger reads "model · level".
- Switching to a model without efforts hides the row and keeps the choice.

## Decision

**Each task row stores a reasoning level beside its model.**
- V143 adds `model_flow_default.reasoning_effort` (`OFF`, `LOW`, `MEDIUM`, `HIGH`). Null means the task's own default:
  - `MEDIUM` for the meeting minutes;
  - `OFF` for naming, the question check and transcript corrections, which stay fast helper calls unless an administrator asks for more.
- The flow API reads the effective level and takes one in the same `PUT` as the model. Omitting it returns the task to its default, as omitting the model returns the task to the conversation model.
- The audit `before`/`after` of a task change carries the level.

**The task call honours it.**
- Off keeps the helper path: thinking off, the helper effort, the task's small output cap.
- Any other level keeps thinking on and asks for that level through the binding's sampling, so a level named in the model's configuration still outranks it, as for the minutes in #466.
- Such a call gets the room a reasoning model needs: at least 4,096 output tokens for naming and the question check, and 16,384 for the minutes. Naming also gets 60 seconds instead of 10.
- The question check keeps its 20-second bound: it runs before every guarded answer, and a check that runs past it leaves the turn without a verdict, which the answer model still guards.
- A model that does not reason gets no level, as today.

**The Models page keeps its picker and adds the Thinking row of assistant-ui's Model selector.**
- The rows keep the Onyx-style `ModelPicker` (search, provider groups with their boundary tag, model logos). Replacing it with the assistant-ui selector would drop those for one row of levels.
- A task row's picker ends with a Reasoning row ("Tắt / Thấp / Vừa / Cao", the Chat labels) while the selected model reasons, and the trigger names the level beside the model, as assistant-ui does.
- The row is a single-choice `ToggleGroup` in a new `segmented` toggle variant. The default on-state (`bg-muted`) matches the popover surface in dark mode (#26262b on #262626), so the chosen level gets the sunken surface, a border and primary text. assistant-ui's own row uses the accent surface the same way.
- Choosing a model or a level saves at once, as the rows already do, and the picker stays open so levels can be compared.
- The Chat default row has no Reasoning row: a conversation's level is the member's (pinned on the conversation or in their preferences).

## Out of scope

- A level for the Chat default.
- Per-provider level lists (`xhigh`, `minimal`).
- Search helper calls, which run on the conversation model (MEM-210).
