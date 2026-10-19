# MEM-281 verification

Staging acceptance on 2026-10-19 against release `443c1a8d`, which contains #773, #781 and #782. A Playwright script
signed in through Keycloak impersonation as `admin@memoryos.local` (agent administrator) and `test@gmail.com` (member,
Vietnamese, without `AGENTS_CREATE`). Every answer used the Tenant's default model, `cx/gpt-6-luna`.

## Results

| Check | Result |
| --- | --- |
| Eight templates provisioned in the Tenant | Pass |
| Order in Tất cả, through the API and on the page, for member and administrator: MemoryOS, featured, templates, the rest | Pass |
| A template card names "MemoryOS" without "· Công khai" and has no Sửa or Chia sẻ | Pass |
| The ⋯ menu: the administrator sees *Tạo bản riêng* and *Ẩn với mọi người*. The member, who lacks `AGENTS_CREATE`, gets no ⋯ menu | Pass |
| Edit, delete and share of a template answer 404, and the template is unchanged afterwards | Pass |
| Each template answers one question (see below) | Pass |
| *Tạo bản riêng* opens the copy in the editor. The copy is private and not a template, another member can neither list it nor open it (404), and it was deleted afterwards | Pass |
| Hiding a template sets `listed` to false. The administrator still sees it, faded, marked *Đã ẩn*. The member no longer lists it, and fetching it answers 404. *Hiện lại* restores it | Pass |
| An English account sees the eight names in English in the list, the sidebar and the conversation header | Pass |
| The viewer: the administrator sees *Tạo bản riêng* where *Sửa* stands. The member sees neither. The composer reads *Nhập câu hỏi…* | Pass |
| Phone width 390 px has no horizontal scroll. Dark theme shots were reviewed | Pass |
| No page errors and no 5xx responses | Pass |

## Answers

| Template | Question (abridged) | Answer |
| --- | --- | --- |
| Soạn email công việc | Ask the Kế toán department for three more days to submit expenses | A ready email with subject, salutation, closing and placeholders for the date and the sender |
| Dịch Việt – Anh (English account) | Translate an English sentence about an expense report into Vietnamese | A correct translation |
| Biên bản họp | Notes from a launch meeting | Minutes with main points, decisions, an action table (owner, due date) and open questions; missing facts are marked "Chưa rõ" |
| Báo cáo tuần | Work done, in progress and blocked | A weekly report grouped as done, in progress and blocked |
| Tóm tắt tài liệu | Summarise the annual-leave rules in company documents | Says no company document covers annual leave and asks for the document or a keyword |
| Hỏi đáp chính sách nội bộ | How many days of annual leave does an employee get? | "Tài liệu của tổ chức chưa có thông tin để trả lời câu hỏi này." Staging holds no leave policy, and the grounded template does not invent one |
| Rà soát hợp đồng | A one-sided late-delivery penalty and unilateral termination | Asks whether the company is Party A or Party B, names the risks in each clause and recommends legal review |
| Viết đánh giá OKR/KPI | Q3 results against targets | A self-assessment covering each target |

## Clean-up

- Soft-deleted through the API as their owner, each checked by exact name first: "Chính sách nhân sự", "Pháp chế hợp
  đồng", "Báo cáo tài chính", "OKR/KPI hằng tháng" and "Tài chính - Kinh doanh". "Benchmark - grounded" stays.
- The eight acceptance conversations and the one copy were deleted. The member's language was set back to Vietnamese.

## Observed, outside this increment

- **An unavailable assistant's address opens an empty page.** Opening `/assistants/{id}/new` for an assistant the
  reader cannot use shows the same page whether the assistant is a hidden template or a deleted ordinary assistant:
  - the sidebar header shows the assistant's face without its name;
  - the page title reads "Chat";
  - the page shows "Bạn muốn tìm hiểu điều gì?" and no composer.

  Nobody can chat there, so access holds. But the page does not say the assistant is unavailable.
- **New conversations sometimes miss the sidebar.** Right after a first answer, the assistant's sidebar sometimes
  read "Chưa có chủ đề." for that conversation, while the generated title was not yet in place. The topic appeared
  once a title existed.
- **Members cannot make a copy.** On staging, members lack `AGENTS_CREATE`, so they have no *Tạo bản riêng*. This
  matches the design: copying creates an assistant.

## Follow-ups

The owner decided on 2026-10-19 to act on two of the observations above in one pull request:

- Every member creates assistants by default: the Basic Group holds `AGENTS_CREATE` as an optional grant that a full
  administrator may switch off, and V189 gave it to every existing Basic Group ([identity spec](../../../specs/identity.md)).
- A new topic with an assistant the reader cannot use says *Trợ lý không còn dùng được.* and links back to the
  assistants, with the home sidebar ([chat spec](../../../specs/chat.md)).
