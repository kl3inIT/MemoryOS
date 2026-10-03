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

**The Models page rows use assistant-ui's Model selector, as Chat and the agent editor do** (owner, 2026-10-03: "dùng component của họ rồi chế cháo gì thì thêm").
- `ModelPicker` is built from the element's parts: search, one group per provider with its data boundary tag in the heading, model logos, and the Thinking row (`ModelSelectorEffort`, labelled "Reasoning") with "Tắt / Thấp / Vừa / Cao", the Chat labels. The row shows only while the selected model reasons.
- The Onyx-style collapsible provider groups are gone; the Chat picker never had them.
- Choosing a model or a level saves at once, as the rows already do. The picker stays open while a level is picked, so levels can be compared.
- The Chat default row has no Reasoning row: a conversation's level is the member's (pinned on the conversation or in their preferences).

**Two additions on top of the element.**
- The trigger shows the level as a pill (`ReasoningLevel`): a brain icon, the level and one to three bars, in one light tint of the selection accent (`reasoning-surface`, `reasoning-content`); the bars tell the level. Off and a model that does not reason show nothing, as ChatGPT shows its Think chip only while thinking is on. The owner found the grey level text of the element hard to see, and Mobbin shows no product colouring each level differently: ChatGPT colours the chip while on, Cofounder counts marks. A first version strengthened the tint per level up to a solid fill at High; the owner found High too loud (2026-10-03), so every level shares the light tint.
- The element's checked level used `bg-accent`, which matches the popover in dark mode (#26262b on #262626); it takes the reasoning tint instead, an edit in the copied element.

## Chat composer

Found while reviewing the levels with the owner (2026-10-03): the composer could not pin a conversation's level. `ChatModelPicker` composed its own popover content without the selector's Thinking row, so `pinEffort` (wired since 20/09) had no control, and only Settings › Chat set a level.

- The picker ends with the Thinking row, labelled Reasoning, as the Models page does, and its trigger shows the pinned level with the same pill.
- A level chosen before the conversation exists was kept in the page only: creating a conversation takes no level. The transport now pins it right after the first send creates the conversation, before the question is sent.
- The pill shows only a pinned level. A conversation without one runs at the model configuration's level, then the member's default, and the browser does not know the first, so showing the member default could be wrong.
- The level names reach the English UI translated ("Low", "Medium", "High" were missing).

## Out of scope

- A level for the Chat default.
- Per-provider level lists (`xhigh`, `minimal`).
- Search helper calls, which run on the conversation model (MEM-210).
