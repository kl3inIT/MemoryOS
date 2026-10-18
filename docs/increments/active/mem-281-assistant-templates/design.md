# MEM-281 Assistant templates

A new Tenant has one assistant, the built-in MemoryOS assistant. Someone who opens Trợ lý for the first time sees
"Tạo trợ lý" and an empty form, and has to write instructions before the assistant is worth anything. This increment
gives MemoryOS a small set of reviewed assistant templates for office work. Anyone who may create an assistant can
start from one and gets a normal assistant they can edit. It is [MEM-281](https://linear.app/memory-os/issue/MEM-281).
The owner started it on 2026-10-18, after [MEM-273](../mem-273-agent-editor/design.md) shipped the editor.

## Today

- `persona.builtin_key` marks the built-in assistant. `ChatTenantProvisioner` inserts it when a Tenant is bootstrapped,
  insert-only, with every tool (`JdbcChatRepository.provisionPersona`). There are no other seeded rows.
- An assistant is created from a short dialog with name, title and description (MEM-273 decision 14). It starts with
  search and web search (`ChatPersonaService.DEFAULT_TOOLS`).
- "Khám phá" is the `is_listed` switch and "Nổi bật" is `is_featured`, both set by agent administrators on the Chia sẻ
  tab. The agents page lists listed assistants under "Tất cả", featured ones first
  (`JdbcAgentRepository.list`). There is no separate explore page; the sidebar's compass opens `/agents`.
- There is no way to copy an assistant. MEM-273 decision 14 said "Templates come through Fork (step 3)", but step 3
  shipped the test chat and Fork was never built.
- `persona.display_priority` orders the list, but no screen sets it. The web only passes the stored value back when it
  saves the listing switches (`agent-share-panel.tsx`).

## How the references do it

| Product | What a new workspace has | Templates | What using one does | Verified |
| --- | --- | --- | --- | --- |
| Onyx | One built-in assistant (`DEFAULT_PERSONA_ID = 0`). In September 2025 Onyx deleted its other built-in assistants (Search, General, Paraphrase, Art) and merged them into it (`alembic/versions/505c488f6662_merge_default_assistants_into_unified.py`). | None. Administrators create agents and mark them featured (`AgentRowActions.tsx`, `is_featured`). | — | Code |
| LobeHub | An inbox agent | Discover / agent market: community agents (`lobe-chat-agents`, 505 agents, MIT) | Adds a copy as the user's own agent. The copy keeps its `marketIdentifier`, and `agentService.checkByMarketIdentifier` tells whether one already exists (`src/services/agent.ts`). | Code |
| Dify | Nothing | "Create from template" in the create-app dialog (`create-app-dialog/app-list`) | Imports the template's DSL as a new app in the workspace (`useImportDSL`) | Code |
| Coze Studio, FastGPT | Nothing | "Template or blank" when creating ([MEM-273 design](../mem-273-agent-editor/design.md#how-the-references-do-it)) | A new app from the template | Code (MEM-273 research) |
| Copilot Studio | Nothing | Prebuilt agents under "Explore agents" on the Create page | Opens the agent's configuration with name, description, instructions and knowledge filled in; Create makes the maker's own agent. Microsoft leaves the safety and legal review of a template to the maker. ([template fundamentals](https://learn.microsoft.com/microsoft-copilot-studio/template-fundamentals)) | Docs |
| Glean | Agent Library | Templates in the library ([agent library](https://www.glean.com/ai-agents/agent-library)). Administrators cannot publish company templates yet ([community request](https://community.glean.com/discussion/605/feature-request-admin-created-agent-templates)). | A copy; a duplicate keeps the original's open sharing | Marketing page and community posts only |
| ChatGPT Enterprise | Workspace GPTs | No template copying documented ([GPT access in workspaces](https://help.openai.com/en/articles/8555535)) | — | Docs (partial) |

Every product that ships templates copies a template when someone uses it. None seeds template rows into each
workspace. Onyx, the one that used to seed several built-in assistants, removed them for a single one.

## lobe-chat-agents as a source

- MIT licence, maintained (last commit 2026-10-15). There are 505 agents: 209 written in English and 296 written in
  Chinese. Each has `locales/<id>/index.vi-VN.json`.
- Fields: `meta.title`, `meta.description`, `meta.tags`, `meta.avatar` (an emoji), `config.systemRole`,
  `config.openingMessage`, `config.openingQuestions`. They map to MemoryOS's name, description, labels, avatar,
  instructions, greeting and starters. The model, `params`, `historyCount`, `inputTemplate`, `displayMode`, `author`
  and `homepage` have no MemoryOS field and are dropped.
- The `vi-VN` text is machine-translated, and the agents were written for other markets. Samples read on 2026-10-18:
  - `business-email`: Chinese–English emails and GitHub open-source etiquette.
  - `business-contract`: construction contracts, with Chinese headings left in the prompt ("Role (角色)").
  - `translation-specialist`: Spanish–English.
  - `minimalist-translation`: Chinese–English, and refuses to explain its own rules.
  - `meeting`: construction-site progress and safety training.
  - `interviewer-assistant`: only new-graduate product manager hiring.
  - Several openings and questions sound translated ("Tôi là trợ lý họp v1.0").
- None of the agents knows about company documents. In MemoryOS, internal search is what makes an assistant useful.

So the templates are MemoryOS's own text. lobe-chat-agents gives the shortlist of jobs and the shape of each prompt
(role, steps, output format). Where a prompt follows a lobe agent closely, its template names the lobe identifier and
the repository's third-party notice carries the MIT notice.

## Decisions

1. **A catalogue copied on use, not seeded rows.** Templates are not assistants in the database. Using one creates a
   normal assistant, the way LobeHub, Dify, Coze, FastGPT and Copilot Studio work. This means:
   - seeded rows would go stale, and a template update could never reach a Tenant without overwriting an
     administrator's edits;
   - a Tenant that never wants "Rà soát hợp đồng" never sees it in its assistant list;
   - the built-in assistant stays the only provisioned row, as in Onyx.
2. **Templates ship with the release.** Each template is one JSON file under
   `memoryos-core/src/main/resources/chat/assistant-templates/`. The file holds the key, the Vietnamese and English
   text, tools, `grounded`, the avatar seed and, where it applies, `basedOn` with the lobe identifier. The files are
   reviewed in pull requests like code. Nothing syncs from a community repository, because community prompts are
   untrusted text (prompt injection, uneven quality); this follows the issue's own rule. A loader validates every file
   at startup against the same limits as an assistant: name 200 characters, title 80, description 2,000, greeting 500,
   at most four starters of 1,000 characters, and known tools. A bad file fails the build's tests, not a request.
3. **Using a template copies it.** `POST /api/chat/personas/templates/{key}` creates an assistant from the template in
   the caller's account language: Vietnamese for `vi`, English otherwise, as for replies. It takes the name, title,
   description, instructions, greeting, starters, tools, `grounded` and the template's avatar seed, and opens the
   editor. It needs `AGENTS_CREATE`, like "Tạo trợ lý". The copy belongs to its creator and is private, as in every
   reference; the Chia sẻ tab shares it, and an agent administrator can list it in Khám phá or feature it there. No
   Sources or Document Sets are attached, so with the documents switch on, the assistant searches everything the
   person may read (MEM-273 decision 6).
4. **The copy remembers its template.** `persona.template_key` (nullable, 64 characters) is set on copy and never
   changes; LobeHub's `marketIdentifier` does the same. The catalogue marks a template the caller already holds a copy
   of as "Đã dùng", with "Mở" beside "Dùng lại". A later release never changes a copy: a copy is the person's own
   assistant from the moment it exists.
5. **Where people find templates: the create dialog.** "Tạo trợ lý" opens on two choices, as Dify, Coze, FastGPT and
   Copilot Studio offer "Template or blank":
   - "Trống", today's short form;
   - the template grid: avatar, name and one line each, with a preview of the greeting and starters before "Dùng mẫu".

   The agents page's empty "Của tôi" view links to the grid. There is no separate templates page and no sidebar entry,
   since eight templates fit in the dialog.
6. **Eight templates.** The shortlist below covers the jobs the owner's own staging test assistants tried (HR policy,
   contracts, finance reports, OKR/KPI) and the everyday writing that lobe-chat-agents' office agents cover. Templates
   that read company documents keep search on; only the policy assistant is grounded.
7. **No general copy of an assistant in this increment.** Onyx has none. Copilot Studio and Glean duplicate agents,
   and Glean's community calls its duplicate clumsy because the copy keeps open sharing. A general "Nhân bản" is a
   separate decision. This replaces MEM-273 decision 14's "Templates come through Fork".
8. **The built-in assistant stays as it is.** It is the default for chat and bots, as Onyx's unified assistant is.
9. **`display_priority` goes.** No screen sets it, and Onyx orders featured agents without one. A migration drops the
   column, the API drops the field, and ordering becomes builtin, featured, name.

## Shortlist

| Key | Name (vi) | What it does | Tools | Based on |
| --- | --- | --- | --- | --- |
| `email` | Soạn email công việc | Writes and replies to work emails in Vietnamese or English, in the tone the person asks for | search | `business-email` (shape only) |
| `meeting-minutes` | Biên bản họp | Turns notes or a transcript into minutes: decisions, tasks with owner and deadline, open questions | search | `meeting` |
| `weekly-report` | Báo cáo tuần | Turns a list of work into a weekly report: done, in progress, blockers, next week | search | `write-report-assistant-development` |
| `document-summary` | Tóm tắt tài liệu | Summarises a file or a document found by search, with the key figures and citations | search | `summary-assistant` |
| `policy-qa` | Hỏi đáp chính sách nội bộ | Answers questions on internal rules, HR and process only from company documents, and says when it finds nothing | search, grounded | — (MemoryOS's own) |
| `contract-review` | Rà soát hợp đồng | Reads a contract and lists its key terms, risks and missing clauses, saying it is not legal advice | search | `business-contract`, `tech-lawyer` (shape only) |
| `okr-kpi` | Viết đánh giá OKR/KPI | Drafts a monthly or year-end OKR/KPI review from results and evidence | search | `kpi-hero` |
| `translate-vi-en` | Dịch Việt – Anh | Translates between Vietnamese and English and keeps terms, format and tone | — | `translation-specialist` (shape only) |

Alternates if the owner swaps one: Công thức Excel (`excel-formula-master`, with Code Interpreter), Phỏng vấn tuyển
dụng (`interviewer-assistant`), Tối ưu slide (`ppt`).

## API

| Operation | What it does |
| --- | --- |
| `GET /api/chat/personas/templates` | The templates in the caller's language: key, name, title, description, greeting, starters, tools, avatar seed, and `usedBy` (the id of the caller's newest copy, if any) |
| `POST /api/chat/personas/templates/{key}` | Creates the caller's assistant from the template; `201` with the assistant. `404 AGENT_TEMPLATE_NOT_FOUND` for an unknown key |
| `GET /api/chat/personas/{id}` | Gains `templateKey` |
| Listing and persona updates | Lose `displayPriority` |

## Staging cleanup

When the templates are on staging, delete through the app the test assistants "Chính sách nhân sự", "Pháp chế hợp
đồng", "Báo cáo tài chính", "OKR/KPI hằng tháng" and "Tài chính - Kinh doanh" (owner `admin@memoryos.local`). Keep
"Benchmark - grounded", which the RAG benchmark (MEM-230) uses. Production has only the built-in assistant.

## Not in scope

- A general "Nhân bản" of any assistant (decision 7).
- Templates an administrator writes for their own Tenant (Glean's open request). This can come once people use the
  catalogue.
- botdirectory.ai's scheduled bots, which wait for schedules (MEM-289) and the Agent Builder (MEM-280).
- Filling the create dialog with AI ("Điền giúp", MEM-276).

## Risks

- **Template text ages.** A wording fix reaches only new copies. This is intended (decision 4); release notes name
  changed templates.
- **A template copied by many people makes many near-identical assistants.** Copies are private, and Khám phá shows
  only what an administrator lists, so the shared list stays clean.
- **English copies read worse than Vietnamese ones.** Both languages are written and reviewed in the same pull
  request; a test fails a template missing either language.
