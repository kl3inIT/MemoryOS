# MEM-281 Assistant templates in Khám phá

A new Tenant has one assistant, the built-in MemoryOS assistant. Someone who opens Trợ lý for the first time finds
nothing ready for a job and has to write instructions before an assistant is worth anything. This increment makes
Khám phá the company's assistant marketplace. It opens with eight assistants that MemoryOS writes, reviews and
updates; anyone can chat with one at once. This is
[MEM-281](https://linear.app/memory-os/issue/MEM-281). The owner decided the direction on 2026-10-18, after
[MEM-273](../mem-273-agent-editor/design.md) shipped the editor.

## Today

- `persona.builtin_key` marks the built-in assistant. `ChatTenantProvisioner` inserts it when a Tenant is bootstrapped,
  insert-only, with every tool (`JdbcChatRepository.provisionPersona`). `AgentAccessSql` lets everyone use it and
  agent administrators edit it; it cannot be deleted or shared.
- Khám phá is the agents page (`/agents`, the sidebar's compass). "Tất cả" lists every assistant the person may use
  that is listed, and their own. The listing switches "Hiện ở Khám phá" (`is_listed`) and "Nổi bật" (`is_featured`)
  are set by agent administrators on the Chia sẻ tab. Featured assistants come first (`JdbcAgentRepository.list`).
- A conversation, a pin, the recent list and a bot binding all reference a `persona` row. A turn reads the persona
  row (instructions, tools, Sources, files, model) for each question (`JdbcChatRepository.persona`).
- An assistant cannot be copied. MEM-273 decision 14 said "Templates come through Fork (step 3)"; Fork was never
  built.
- `persona.display_priority` orders the list. Agent administrators set it by dragging rows on Quản trị → Quản lý trợ
  lý (`/admin/agents`, `admin-agents-page.tsx`), which writes the whole order through `reorderChatPersonas`
  (`ChatPersonaService.reorder`); the Chia sẻ tab passes the stored value back when it saves the listing switches
  (`agent-share-panel.tsx`).

## How the references do it

| Product | Ready-made assistants | Used how | Who changes them | Verified |
| --- | --- | --- | --- | --- |
| WorkBuddy | Expert Center: experts grouped by category; Skills Market | "Summon" uses an expert as it is | The vendor | Docs: workbuddy.ai Expert Center and Skills Market pages, read 2026-10-18 |
| ChatGPT | GPT Store | A GPT is used as it is; nobody but its builder edits it | Its builder | Docs |
| Onyx | One built-in assistant. Onyx deleted its other built-ins in September 2025 (`alembic/versions/505c488f6662_merge_default_assistants_into_unified.py`) | — | Administrators feature their own agents (`AgentRowActions.tsx`) | Code |
| LobeHub | Agent market (`lobe-chat-agents`, 505 agents, MIT) | Added as a copy that keeps its `marketIdentifier` (`src/services/agent.ts`) | The user, on the copy | Code |
| Dify, Coze Studio, FastGPT | Templates when creating an app | A new app from the template (`useImportDSL`) | The user, on the new app | Code |
| Copilot Studio | Prebuilt agents under "Explore agents" | Create makes the maker's own agent ([template fundamentals](https://learn.microsoft.com/microsoft-copilot-studio/template-fundamentals)) | The maker, on the copy | Docs |

Two models exist:

- **Use as it is.** WorkBuddy and the GPT Store offer a vendor's assistant that people use without setting anything
  up, and nobody else edits it.
- **Copy to change.** LobeHub, Dify, Coze, FastGPT and Copilot Studio copy a template into the user's own assistant.

MemoryOS offers both: chat with a template at once, and "Tạo bản riêng" for someone who wants to change it.

## lobe-chat-agents as a source

- MIT licence, maintained (last commit 2026-10-15). There are 505 agents, each with `locales/<id>/index.vi-VN.json`.
  `systemRole`, `openingMessage`, `openingQuestions`, `title`, `description` and `tags` map to MemoryOS fields. The
  model, `params`, `historyCount`, `inputTemplate` and `displayMode` have none.
- The `vi-VN` text is machine-translated and written for other markets. Samples read on 2026-10-18:
  - `business-email`: Chinese–English email and GitHub etiquette.
  - `business-contract`: construction contracts only, with "Role (角色)" left in the prompt.
  - `translation-specialist`: Spanish–English.
  - `meeting`: construction progress and safety training.
  - Several openings read as translated ("Tôi là trợ lý họp v1.0").
- No agent knows about company documents, which is what makes a MemoryOS assistant useful.

So MemoryOS writes its own Vietnamese and English text. lobe-chat-agents supplies only how the jobs are split and the
shape of each prompt (role, steps, output format). The repository's third-party notice carries its MIT notice.

## Decisions

1. **Khám phá is the company's assistant marketplace.** "Tất cả" lists the built-in assistant, then the company's
   featured assistants, then the visible templates, then the rest, in one list with no section of its own and no
   category filter: a Tenant has a few dozen assistants at most, and the page already filters by label and maker
   (GPT Store and WorkBuddy group by category because they hold thousands). Clicking a template's card opens its
   viewer and "Bắt đầu chat" a conversation, with no setup (WorkBuddy's "Summon", the GPT Store).
2. **Eight templates.** Soạn email công việc, Dịch Việt – Anh, Biên bản họp, Báo cáo tuần, Tóm tắt tài liệu, Hỏi đáp
   chính sách nội bộ, Rà soát hợp đồng and Viết đánh giá OKR/KPI (table under "Templates").

3. **A template works with the person's own documents.** It attaches no Sources, Document Sets or files and has
   document search on, so it searches everything the person may read (MEM-273 decision 6). "Hỏi đáp chính sách nội
   bộ" therefore answers from the company's policies on day one; it is the one template with "Chỉ trả lời có căn cứ"
   on. Every template except Dịch Việt – Anh has search on; none has web search, image generation or Code
   Interpreter, which a person adds on their own copy.
4. **MemoryOS owns the content.** Nobody in a Tenant edits, shares, deletes or transfers a template, and no bot can be
   bound to one, because binding needs edit rights on the assistant. This follows WorkBuddy and the GPT Store, where a
   vendor's assistant is not edited by its users. An agent administrator may only hide or show a template for the
   Tenant. A company version is made with "Tạo bản riêng", edited and shared with the organization, and then appears
   in Khám phá as any shared assistant does.
5. **Storage: one system-owned `persona` row per template and Tenant, written from a catalogue in code.**
   - **Catalogue.** Each template is one JSON file under `memoryos-core/src/main/resources/chat/assistant-templates/`:
     key, Vietnamese and English text (name, title, description, greeting, at most four starters),
     instructions, tools, `grounded` and avatar seed. Files are reviewed in pull requests; nothing syncs from a
     community repository. A test loads every file and checks both languages and the assistant limits.
   - **Rows.** `persona.template_key` (nullable, 64, unique per Tenant among live rows) marks a template's row.
     `AssistantTemplateProvisioner` writes them in the Tenant bootstrap transaction next to the built-in assistant,
     and again at startup for every Tenant. It inserts a missing template, rewrites a row whose content or tools differ
     from the catalogue (compared field by field in the provisioner, so an unchanged row is not written), restores a
     template that returns and soft-deletes the row of a template the catalogue no longer has. A rewrite is safe because
     nobody else edits the row.
   - **Why rows and not a catalogue alone.** Conversations, pins, the recent list, the turn's persona read and every
     access check reference `persona.id`. A row lets a template conversation run through the existing turn path,
     history, pins and recents unchanged. A catalogue-only template would need a second kind of assistant id in each
     of them.
   - **Hiding** is the template row's `is_listed`. The existing Chia sẻ listing switch writes it, and it is the only
     change an agent administrator may make to a template row.
   - **Access.** `AgentAccessSql.USES` admits a template row only while it is listed: `template_key IS NOT NULL AND
     is_listed`. `EDITS` and `OWNS` exclude template rows. A hidden template leaves Khám phá, pins and recents; its
     conversations stay in history and cannot be continued, as with an assistant someone may no longer use. An agent
     administrator still finds a hidden template in "Tất cả" and opens it, to show it again. Showing it again
     restores both.
   - **Language.** The row stores the Vietnamese text. The persona views, Khám phá and the empty conversation show a
     template's name, title, description, greeting and starters in the reader's account language from the catalogue,
     by `template_key`. The instructions are one text written in English, and the reply language follows the account
     language hint as for every assistant (`docs/specs/chat.md`, "Account reply language").
   - **Updates.** A wording or prompt change reaches every Tenant at the next deployment and applies from the next
     question, as an edit to any assistant does.
6. **"Tạo bản riêng" copies a template into the person's own assistant.** `POST /api/chat/personas/{id}/copy` accepts
   only a template row and needs `AGENTS_CREATE`. The copy takes the text in the caller's language, the instructions,
   tools, `grounded` and a new avatar seed, and opens the editor. It belongs to its creator and is private, as in
   LobeHub, Dify and Copilot Studio; the Chia sẻ tab shares it. The copy does not record its template: a template is
   used as it is, so nothing tracks who copied what.
7. **The card is the existing assistant card** (owner's card review, 2026-10-19; the references mark a vendor's
   assistant by its maker, never with a "template" badge: Onyx names "Onyx", the GPT Store "By ChatGPT", LobeHub and
   Coze the author).
   - The footer names "MemoryOS", as on the built-in assistant, without "· Công khai": MemoryOS's own assistants are
     everyone's by design. Other assistants keep it, since "Của tôi" tells private from shared by it.
   - On hover a template offers Pin and ⋯ only. ⋯ has "Tạo bản riêng" for someone with `AGENTS_CREATE`, and "Ẩn với
     mọi người" or "Hiện lại" for an agent administrator. In the viewer "Tạo bản riêng" takes the place of "Sửa".
   - A hidden template appears only to agent administrators, faded, with "Đã ẩn" and no "Bắt đầu chat" or pin.
   - Every viewer's composer reads "Nhập câu hỏi…", as the chat composer does; "Hỏi {tên}…" read "Hỏi Hỏi đáp…".
   - A template is named in the reader's account language wherever the API names an assistant: the persona views, the
     sidebar's assistants and pins, and the model administrator's list of assistants.
8. **No general copy of an assistant in this increment.** A "Nhân bản" for any assistant is a separate decision; this
   replaces MEM-273 decision 14's "Templates come through Fork".
9. **The built-in assistant stays as it is,** the default for chat and bots, as Onyx's unified assistant is.
10. **`display_priority` stays, with the administrator's drag order.** An earlier draft removed it on the claim that
    no screen sets it; Quản trị → Quản lý trợ lý does, by dragging, and Onyx keeps the same administrator order
    (`persona.display_priority`, `patch_agents_display_priorities` in `backend/onyx/server/features/persona/api.py`). The list orders the built-in assistant,
    then featured assistants, then the templates, then the rest (`JdbcAgentRepository.list`: `builtin_key`,
    `is_featured`, `template_key IS NULL`, `display_priority`, name). Templates keep that fixed place: they are never
    featured, `reorder` refuses them and their priority stays empty, so they follow one another by name. The
    administrator's order applies within the featured assistants and within the rest.

## Templates

| Key | Name (vi) | Job | Search | Based on |
| --- | --- | --- | --- | --- |
| `email` | Soạn email công việc | Writes and replies to work email in Vietnamese or English, in the tone asked | yes | `business-email` (shape) |
| `translate-vi-en` | Dịch Việt – Anh | Translates both ways, keeping terms, format and tone | no | `translation-specialist` (shape) |
| `meeting-minutes` | Biên bản họp | Turns notes or a transcript into minutes: decisions, tasks with owner and deadline, open questions | yes | `meeting` |
| `weekly-report` | Báo cáo tuần | Turns a list of work into done, in progress, blockers and next week | yes | `write-report-assistant-development` |
| `document-summary` | Tóm tắt tài liệu | Summarises a file or found documents, with key figures and citations | yes | `summary-assistant` |
| `policy-qa` | Hỏi đáp chính sách nội bộ | Answers on rules, HR and process only from company documents, and says when it finds nothing | yes, grounded | MemoryOS's own |
| `contract-review` | Rà soát hợp đồng | Lists key terms, risks and missing clauses, and says it is not legal advice | yes | `business-contract`, `tech-lawyer` (shape) |
| `okr-kpi` | Viết đánh giá OKR/KPI | Drafts a monthly or year-end OKR/KPI review from results and evidence | yes | `kpi-hero` |

## API

| Operation | Change |
| --- | --- |
| Persona views (`GET /api/chat/personas`, `/{id}`) | Gain `templateKey` on template rows; text in the reader's language |
| `POST /api/chat/personas/{id}/copy` | New. `201` with the caller's new assistant; `409 AGENT_NOT_A_TEMPLATE` for any other assistant |
| Persona update, delete, sharing, transfer and reorder on a template row | `404 CHAT_UNAVAILABLE`, as for any assistant the caller may not change |
| Listing switches on a template row | `is_listed` only, by agent administrators; *Nổi bật* or a priority is `CHAT_INVALID_REQUEST` |

## Staging cleanup

When the templates are on staging, delete through the app the test assistants "Chính sách nhân sự", "Pháp chế hợp
đồng", "Báo cáo tài chính", "OKR/KPI hằng tháng" and "Tài chính - Kinh doanh" (owner `admin@memoryos.local`). Keep
"Benchmark - grounded", which the RAG benchmark (MEM-230) uses. Production has only the built-in assistant.

## Not in scope

- Templates an administrator writes for their own Tenant beyond "Tạo bản riêng" and sharing.
- A general "Nhân bản" (decision 8).
- botdirectory.ai's scheduled bots, which wait for schedules (MEM-289) and the Agent Builder (MEM-280).

## Risks

- **A template changes under its users.** A release that rewrites a prompt changes the next answer in every Tenant.
  Template changes go through review with the same evaluation as the built-in assistant's prompt, and release notes
  name them.
- **Startup writes touch every Tenant.** The provisioner writes only rows that differ, in one statement per template,
  so a release without template changes writes nothing.
- **English text reads worse than Vietnamese.** Both are written and reviewed in the same pull request, and a test
  fails a template missing either.
