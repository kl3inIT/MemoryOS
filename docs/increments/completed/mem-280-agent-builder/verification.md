# MEM-280 verification

Staging acceptance on 2026-10-20 against release `ece49d0f`, which contains #798, #799, #800, #802 and #804. A
Playwright script signed in through Keycloak impersonation as `test@gmail.com` (member, who creates assistants through
the Basic group), `test123321@gmail.com` (a viewer of the test assistant) and `admin@memoryos.local` (only to list the
Sources the member cannot read). The builder ran on the Tenant's `AGENT_BUILDER` task model, `cx/gpt-6-luna`.

## Results

| Check | Result |
| --- | --- |
| A new assistant with a description opens the editor on *✦ Agent Builder* and sends the description | Pass |
| The builder fills title, instructions, greeting and suggested questions; each change is a card with before → after; nothing is saved | Pass |
| *Chat thử* answers with the unsaved form; *Lưu* publishes it | Pass |
| "Trả lời ngắn hơn nữa" changes the instructions | Pass |
| "Thêm tìm web" turns web search on (and adjusts the instructions' documents-only line to match) | Pass |
| "Đổi sang model rẻ hơn" asks first and changes nothing; after "Đồng ý" the model changes | Pass |
| A Source the member cannot read is refused, and the form is unchanged | Pass |
| "Chia sẻ … cho cả công ty" is declined with the Chia sẻ tab named; sharing is unchanged | Pass |
| A message with a blocked phrase is stopped by the Tenant guardrail | Pass |
| *Hoàn tác* right after a change restores its fields; a field changed since, by hand or by a later card, is kept | Pass (see below) |
| "Trả lời dài quá" changes only the instructions | Pass |
| A line the person wrote in the instructions survives "trả lời dài quá" and "thân thiện hơn chút"; the name is not changed | Pass |
| A file dropped on the builder's composer is ignored | Pass |
| *Hủy* leaves without saving the builder's changes, after the leave-page dialog | Pass |
| A viewer of the assistant has no *Agent Builder* tab | Pass |
| Phone width 390 px has no horizontal scroll; dark theme shots were reviewed | Pass |
| No page errors and no 5xx responses | Pass |

The full run reported *Hoàn tác* on the "ngắn hơn" card as failing: by then "thêm tìm web" had edited the
instructions again, so the undo kept them, as decision 3 says. A separate run that pressed *Hoàn tác* right after the
"ngắn hơn" card restored the instructions ("Đã hoàn tác 1 mục").

## Fixes found by acceptance

- **#800.** The `assistant_change` event carried unchanged fields as `null`, and the web's strict schema rejected the
  whole stream. The event now carries only the changed fields.
- **#802 and #804.** "Trả lời ngắn hơn nữa" sometimes shortened the greeting and description instead of the
  instructions, and a new assistant could be left without instructions. #802 said so in the prompt. #804 rewrote the
  prompt from the other builders' prompts and chose it by a probe against the builder's model; the sources and the
  probe's scores are in [design, decision 6](design.md#decisions).

## Clean-up

Every test assistant was deleted through the API by its owner (HTTP 204). The Keycloak admin login was read into a
temporary file, used and deleted, never printed.

## Observed, outside this increment

- **The builder cannot tell which model is cheaper.** The options list carries model names only, so "đổi sang model rẻ
  hơn" picks by name. The model configuration holds pricing; showing it to the builder would make the choice
  grounded.
- **Using the last *Chat thử* turn when editing** (as Dify does) is left for later; it needs the test chat handed to
  the builder.
