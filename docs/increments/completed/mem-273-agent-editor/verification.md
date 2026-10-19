# MEM-273 verification

Staging acceptance ran as Playwright scripts that sign in through Keycloak impersonation as `admin@memoryos.local`
(administrator) and `test@gmail.com` (member, Vietnamese). Each run created its own throwaway assistant and deleted it
with its conversations afterwards. Answers used the Tenant's default model, `cx/gpt-6-luna`.

## Earlier runs

| Step | Date and release | What passed |
| --- | --- | --- |
| 1–3. Fields, editor, test chat | 2026-10-17 | Create through the dialog; edit, test the unsaved assistant beside the editor, save and reopen; the Chia sẻ tab saves on change; Save and Cancel only on Cấu hình (#738). Light and dark, desktop and phone |
| 4. A bot's assistant | 2026-10-17 | Binding a bot from the bot page, moving it with confirmation, the bot's assistant on the Kênh chat page, the Kênh tab listing it |
| 5. Group chats | 2026-10-17, after #740 and #746 | 11 of 11: the bot page's "Trợ lý mặc định" picker saves on change, "Theo nhóm chat" is listed, the Kênh tab is read-only with links to the bot page, no horizontal scroll at 390 px, the bot's original assistant restored afterwards |

## Q12. An app the person has not connected

2026-10-19, staging release `f9ccabf5` (contains #766). The administrator created "QA Linear 273" with the Linear MCP
server (OAuth, per person) and shared it with the member, who has never connected Linear.

| Check | Result |
| --- | --- |
| The member's Linear state is `NOT_CONNECTED` | Pass |
| The answer names the reason: "Bạn chưa kết nối Linear. Hãy kết nối Linear bằng nút Connect bên dưới câu trả lời…" | Pass |
| A card "Linear chưa kết nối" with Kết nối shows under the answer | Pass |
| The card is still there after a reload | Pass |
| Kết nối asks for the authorization (`POST /api/mcp/connections/{id}/authorization` → 200) and navigates to `mcp.linear.app`; the probe aborts that navigation, and Linear stays `NOT_CONNECTED` | Pass |
| No page errors and no 5xx responses | Pass |

The return to the conversation after a completed sign-in was not exercised, and the expired-sign-in card was not
produced on staging (no safe way to expire a real token); unit and e2e tests cover both.

The model wrote "nút Connect" while the Vietnamese card reads "Kết nối", because the notice names "the Connect button"
(`McpTools.unavailableNotice`); a follow-up should name the button without its English label.

The same run confirmed the roster and the faces on a new assistant: opening the assistant opens its latest topic, the
topic is listed in the assistant's sidebar, the session list filtered by the assistant returns it, and the new
assistant has an `avatarSeed`.

## Not accepted here

- A question in a live Lark group (step 5d). It needs the Lark app's owner to add `im.chat.member.bot.added_v1` and
  add the bot to a group: [MEM-296](https://linear.app/memory-os/issue/MEM-296).
- A question to a live Zalo bot.
