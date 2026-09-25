# Reference research: personal "Library / Files / Knowledge" hubs

Researched 2026-09-25. I used primary sources only: official help centers and docs, official product blogs, and open-source code (Onyx, read from a local checkout). Every row carries a link. `[UNVERIFIED]` marks a claim I did not confirm in a primary source. `[INFERENCE]` marks my own reading of the evidence.

Access note: `help.openai.com` and `support.microsoft.com` return HTTP 403 to plain fetches. I loaded the same official URLs in a headless browser and extracted the rendered text.

Onyx: all code facts come from the local checkout `.tmp/onyx` at commit **`f9e3de36c82df31cb68e4f88027b38496af59a13`** (2026-09-10). Paths are relative to that repo.

---

## 0. The 6 dimensions

| # | Dimension |
|---|---|
| D1 | Sections and tabs (Mine, Shared with me, Recent, Starred, by source, by type) |
| D2 | Asset kinds shown (uploads, generated files and images, connected-source docs, project and agent knowledge, meeting notes and recordings, conversations) |
| D3 | Ownership vs access-derived visibility, and how permissions are displayed |
| D4 | Actions per kind (preview, download, reuse in chat, add to project or agent, share, delete or trash, pin) |
| D5 | Search, filter, sort |
| D6 | Storage, quota, retention |

---

## 1. Per-product tables

### 1.1 ChatGPT (Library, Images, Projects)

Sources:
- [L] Using Library to manage files — https://help.openai.com/en/articles/20001052-using-library-to-manage-files-in-chatgpt
- [I] Images in ChatGPT — https://help.openai.com/en/articles/11084440-images-in-chatgpt
- [P] Projects in ChatGPT — https://help.openai.com/en/articles/10169521-projects-in-chatgpt
- [R] Chat and file retention — https://help.openai.com/en/articles/8983778-chat-and-file-retention-in-chatgpt

| Dim | Findings |
|---|---|
| D1 | Library opens from the sidebar; the full experience is web-only [L]. Filters: uploaded vs generated, and file type [L]. **Recently deleted** appears "if available" [L]. Where Library sharing exists, recipients find items in **Shared with me** [L]. Library can also show files and folders from connected **Google Drive, Box, Dropbox, SharePoint** [L]. Generated images also appear under a separate **Images** area [I][L]. Each Project has its own sources list [P]. Sidebar search returns Library files alongside chats and projects [L]. |
| D2 | Uploaded and generated documents, spreadsheets, presentations, PDFs and images [L]. Exclusions: temporary-chat uploads (unless the chat is saved) and ChatGPT Health uploads [L]. Connected Drive shows My Drive plus items shared directly with you; **Shared Drives are not included** [L]. Project sources can be uploaded files, pasted text, Google Drive file/folder links, Slack channel links, and **chat responses saved as project sources** [P]. Library meeting notes: not documented. |
| D3 | Library files belong to the user. Connected files "remain associated with their original service", and "ChatGPT follows the permissions of the Google account you connected" [L]. Library sharing uses Viewer/Editor access levels [L]. Projects have Owner, **Edit** and **Chat** roles [P]. When a user gets both a group grant and an individual grant, "the higher of the two will be applied" [P]. In a shared project, deleting a file removes it for everyone [P]. |
| D4 | Library: browse, search, open, upload by drag-and-drop, and **Add from library** in the composer. A Drive file can be referenced by `@mention`. You can select a Drive folder and ask across its files, keep Google Docs open beside the chat, and update the source file where authorized [L]. Multi-select **Download** [L]. **Delete** moves to Recently deleted, then **Delete forever** or **Delete all** [L]. Connected Drive items **can't be deleted from ChatGPT** [L]. Images: Copy, Save, Share, Edit. An image can only be deleted **by deleting the conversation that created it** [I]. Projects: preview, download or delete a source; save a response to the project; move a chat into the project [P]. |
| D5 | Filename search plus filters (uploaded/generated, type, and "Show all file types" for dotfiles) [L]. A **Library search** setting lets ChatGPT search Library automatically while answering; users can turn it off and workspace owners can control it [L]. Sort options are not documented. |
| D6 | Library quota: Free 500 MB, Go 4 GB, Plus/Business 20 GB, Pro 100 GB [L]. Per-file limits: 512 MB, 2M tokens per document, about 50 MB for spreadsheets, 20 MB per image [L]. A **Storage** view shows usage, remaining space and limits [L]. Deleted files are purged within 30 days [L]. **Deleting a chat does not delete its Library files** [L][R]. Enterprise, Edu and Healthcare Library files follow the workspace retention policy; transient files that are not in Library can expire after 48 h [R]. Project files stay until the project or file is deleted. Deleting a project removes files stored only in that project, but **Library copies survive** [P][R]. Project file caps: Free 5, Plus/Go 25, Pro 40, Business/Enterprise/Edu 40 [P]. |

### 1.2 Claude (Projects knowledge, Artifacts, new Projects "Library" beta)

Sources:
- [CP] What are projects — https://support.claude.com/en/articles/9517075-what-are-projects
- [CM] Create and manage projects — https://support.claude.com/en/articles/9519177-how-can-i-create-and-manage-projects
- [CV] Project visibility and sharing — https://support.claude.com/en/articles/9519189-project-visibility-and-sharing
- [CA] Artifacts — https://support.claude.com/en/articles/9487310-what-are-artifacts-and-how-do-i-use-them
- [CS] Share artifacts — https://support.claude.com/en/articles/9547008-share-artifacts
- [CU] Upload files — https://support.claude.com/en/articles/8241126-uploading-files-to-claude
- [CG] Google Workspace connectors — https://support.claude.com/en/articles/10166901-using-the-google-drive-integration
- [CC] Projects (new version, beta) — https://code.claude.com/docs/en/claude-projects
- [CR] Consumer data retention — https://privacy.claude.com/en/articles/10023548-how-long-do-you-store-my-data

| Dim | Findings |
|---|---|
| D1 | The Projects page has three tabs, **Your projects / Organization / Shared with you**, plus an archived-projects tab [CM][CV]. Starred projects appear in the sidebar [CM]. The **Artifacts** tab saves "everything you make … view all your artifacts in one place" [CA]. Each project has a knowledge (**Files**) section [CM][CU]. In the new projects beta, the Overview pane has **Threads / Library / Pull requests / Routines**; Library holds "the files you added and the files threads produced" [CC]. |
| D2 | Project knowledge: documents, text and code, plus synced Google Docs (Drive can be added **only in private projects**, not shared ones) [CM][CG]. Artifacts: docs, decks, designs, code, images, diagrams, dashboards and interactive tools [CA]. Chat uploads stay attached to their chat [CU]. Meeting notes: none. |
| D3 | Team/Enterprise projects are **Public** (the whole org) or **Private** (invited only), with **Can view / Can edit** [CV]. Enterprise group sharing: "access follows group membership", and changes take up to 5 minutes [CV]. Chats inside a shared project stay private [CV]. Artifacts start private; access levels are **Can view / Commenter / Can edit** [CS]. Viewers of an artifact use **their own** connector access [CS]. Sharing a legacy chat artifact on Team/Enterprise **also exposes that chat's attachments and files** [CS]. An artifact made in a project requires project access [CS]. The Drive connector "mirrors your existing permissions". If you lose access to a doc, its preview is removed from past chats [CG]. |
| D4 | Upload to project knowledge; add from Drive; move a chat into a project; star, archive or delete a project (you must unarchive before deleting) [CM]. Artifacts: edit, export (Docs to Word/PDF/Markdown/Google Docs, decks to PPTX/PDF, designs to zip/PDF/PPTX/HTML), share, copy [CA]. Generated files can be saved to Drive [CG]. |
| D5 | The Organization tab supports browse and search. The Move-chat modal lets you search projects [CM][CV]. RAG turns on automatically near the context limit, adding up to 10× capacity on paid plans [CP]. Artifact filters and sort: see §4. |
| D6 | Chat uploads: 500 MB per file, 20 files per chat. Project files: 30 MB per file, unlimited count within the context budget [CU]. Artifact storage: 20 MB, text only [CA]. Free plan: 5 projects [CM]. Consumer retention: a deleted chat leaves history immediately and backend storage within 30 days [CR]. Connector data "is retained with its associated chat" [CG]. |

### 1.3 Microsoft 365 Copilot (Copilot app Library, Pages, Notebooks, Search) and OneDrive

Sources:
- [MA] Copilot app navigation — https://support.microsoft.com/en-us/accessibility/copilot/use-a-screen-reader-to-explore-and-navigate-the-microsoft-365-copilot-app
- [ML] Get started with Copilot Library — https://support.microsoft.com/en-us/microsoft-365-copilot/get-started-with-microsoft-365-copilot-library
- [MF] Library FAQ — https://support.microsoft.com/en-us/microsoft-365-copilot/frequently-asked-questions-about-microsoft-365-copilot-library
- [MLA] Admin doc — https://github.com/MicrosoftDocs/microsoft-365-docs/blob/public/copilot/copilot-library.md
- [MN] How Notebooks work — https://support.microsoft.com/en-us/microsoft-365-copilot/how-microsoft-365-copilot-notebooks-works
- [MNF] Find notebooks — https://support.microsoft.com/en-us/microsoft-365-copilot/find-all-your-microsoft-365-copilot-notebooks
- [MST] Pages/Notebooks storage — https://github.com/MicrosoftDocs/microsoft-365-docs/blob/public/microsoft-365/loop/cpcn-storage.md
- [MPM] Pages/Notebooks permissions — https://github.com/MicrosoftDocs/microsoft-365-docs/blob/public/microsoft-365/loop/cpcn-permission.md
- [MAD] Pages/Notebooks admin policies — https://github.com/MicrosoftDocs/microsoft-365-docs/blob/public/microsoft-365/loop/cpcn-admin-configuration.md
- [MSH] Share conversations — https://support.microsoft.com/en-us/microsoft-365-copilot/share-conversations-responses-in-microsoft-copilot
- [MAC] Add content to prompts — https://support.microsoft.com/en-us/microsoft-365-copilot/add-content-to-microsoft-365-copilot-chat-prompts
- [OD1] OneDrive People and Meetings views (official blog) — https://techcommunity.microsoft.com/blog/onedriveblog/feature-deep-dive-browse-files-by-people-and-meetings/3930908
- [OD2] New OneDrive (official blog) — https://techcommunity.microsoft.com/blog/onedriveblog/experience-the-new-onedrive-fast-organized-and-personalized/3804985
- [ODR] Restore OneDrive files — https://support.microsoft.com/en-us/onedrive/restore-your-onedrive-files

| Dim | Findings |
|---|---|
| D1 | Copilot app navigation: **Search, Library, Create, Agents, Notebooks, Apps** [MA]. Search has Quick access tabs **Recent / Shared / Favorites** [MA]. Library has two tabs [ML][MLA]: **Images** (with an "All types" filter for images, infographics, stories, posters and banners) and **Pages** ("pages you've made", with a filter for pages shared with you). Admin policy hides either tab [MLA]. Notebooks filter by **All / Just you / Shared**. In OneNote, notebooks also show Favorites, Shared with you, and **Recommended** (auto-generated) [MNF]. OneDrive views: Home with AI "For you", Shared, **People**, **Meetings**, Favorites and file-type filters [OD2][OD1]. |
| D2 | Library shows only Copilot-generated **images and pages**. It is "simply a view" and needs no extra storage [MF][MLA]. Notebooks hold *references*: docx, pptx, xlsx, pdf, .page, .loop, OneNote pages, web URLs, and SharePoint folders or sites [MN]. Chat attachments can be your own OneDrive files, files shared with you, or files shared in Teams meetings [MAC]. The OneDrive Meetings view shows files from each meeting's invite and chat **plus the Teams recording**, with recurring series grouped together [OD1]. A built-in **Files** agent works over OneDrive and SharePoint files [MA]. |
| D3 | Pages and Notebooks live in a **user-owned** SharePoint Embedded container, "private by default" [MST]. A Page is shared with edit or read-only access via a company link or a people-specific link [MPM]. **Sharing a Notebook gives recipients access to *all files referenced in it***, auto-granted where possible [MPM]. Chats inside a shared notebook stay per-user [MPM]. By contrast, **sharing a Copilot conversation does not grant access to referenced content** [MSH]. The OneDrive People view "will only show files to which you have access" [OD1]. Work search results include only what you have permission to access [MA]. Guests cannot open Pages by link; Notebooks cannot be shared externally [MPM]. |
| D4 | The Quick access file menu offers **Open, Share, Add to Calendar** (schedule a meeting about the file), **Add to To Do, Add to Favorites, Download, Delete, Convert to PDF** [MA]. Library images download as .jpeg [ML]. Notebooks: open, rename, delete, audio overview [MNF][MN]. Agents and apps can be pinned [MA]. OneDrive lets you pin people [OD1]. |
| D5 | Images sort by creation date. Pages sort by last modified and are searchable by title [MF]. Notebooks run newest to oldest, in grid or list view [MNF]. OneDrive filters by Word/Excel/PowerPoint/PDF and lets you search within the filtered view [OD2]. The Shared view has a People filter [OD2]. The Meetings view is searchable by meeting name [OD1]. |
| D6 | Pages and Notebooks count toward the **org SharePoint quota**, with a 25 TB container cap [MST]. When a user leaves, their container follows the OneDrive cleanup schedule (30 days, then soft delete, then purge after 93 days) [MST]. **Individually deleted Notebooks cannot be recovered by users or admins** (no end-user recycle bin) [MST]. Deleting a page or notebook is currently permanent [MN]. Disabling creation by policy leaves existing items intact [MAD]. OneDrive recycle bin: 93 days for work accounts, 30 for personal; **only files in your own OneDrive can be restored** [ODR]. |

### 1.4 Google Drive

Sources:
- [GD1] How to use Drive — https://support.google.com/drive/answer/2424384?hl=en&co=GENIE.Platform%3DDesktop
- [GD2] Search — https://support.google.com/drive/answer/2375114?hl=en&co=GENIE.Platform%3DDesktop
- [GD3] Delete — https://support.google.com/drive/answer/2375102?hl=en&co=GENIE.Platform%3DDesktop
- [GD4] Storage — https://support.google.com/drive/answer/6374270?hl=en
- [GD5] Shared with me — https://support.google.com/drive/answer/2375057?hl=en&co=GENIE.Platform%3DDesktop
- [GD6] Reorder and sort — https://support.google.com/drive/answer/2375177?hl=en&co=GENIE.Platform%3DDesktop

| Dim | Findings |
|---|---|
| D1 | **Home** shows files you or others opened, shared or edited, plus files related to upcoming meetings, with filter chips [GD1]. The other views are **My Drive** (things you upload or create), **Shared with me**, **Starred**, **Trash** and a **Storage** view [GD1][GD2][GD3]. The start page can be set to Home or My Drive [GD1]. The "Recent" view: see §4. |
| D2 | My Drive holds uploaded or synced files and created Docs, Sheets, Slides and Forms [GD1]. Shared with me holds files and folders shared with you and **link-shared files you have opened** [GD5]. **Shared Gemini chats, canvases and generated media** also land in Shared with me [GD5]. |
| D3 | Shared with me lists **the date shared, the owner and the type** [GD5]. Only the **owner** can move a file to trash. A non-owner can only **Remove**, and everyone else keeps access [GD3]. Search operators such as `owner:`, `creator:`, `sharedwith:`, `to:`, `from:` and `pendingowner:` express ownership and access [GD2]. **View details** shows activity and sharing permissions [GD1]. **Only files you own count toward your storage** [GD4]. |
| D4 | Rename, share (viewer/commenter/editor), move, download, star, trash, view details [GD1]. **Add shortcut** puts a shared file into My Drive [GD5]. |
| D5 | Filter chips **Type / People / Modified**, which can be layered with text [GD2]. Advanced operators include `is:starred`, `is:trashed`, `type:`, `before:`/`after:`, `createdbefore:`, `title:` and `followup:` [GD2]. Results sort by relevance, or by clicking a column [GD2]. In My Drive, Shared with me and Starred, folders can sit on top or mix with files [GD6]. The Storage view sorts largest first [GD2][GD4]. Gemini natural-language questions in the search bar return an "AI Overview" [GD2]. |
| D6 | 15 GB free, shared across Drive, Gmail and Photos [GD4]. **Trash counts toward storage** and auto-deletes after 30 days. Users can Empty trash or Delete forever [GD3]. People you shared with keep access until you permanently delete [GD3]. Staying over quota for 2 years can lead to deletion [GD4]. |

### 1.5 Glean (Library, Projects/Collections, Meeting notes, Pins, Agents)

Sources:
- [GL1] Library — https://docs.glean.com/user-guide/assistant/assistant-library
- [GL2] Projects — https://docs.glean.com/user-guide/knowledge/projects/how-projects-work
- [GL3] Collections — https://docs.glean.com/user-guide/knowledge/collections/how-collections-work
- [GL4] File upload — https://docs.glean.com/user-guide/assistant/file-upload
- [GL5] Meeting notes — https://docs.glean.com/user-guide/assistant/meeting-notes
- [GL6] Pins — https://docs.glean.com/user-guide/knowledge/pins/how-pins-work
- [GL7] Agent sharing and permissions — https://docs.glean.com/agents/concepts/sharing-permissions
- [GL8] Agent knowledge source types — https://docs.glean.com/agents/knowledge-source-types
- [GL9] How Glean accesses information — https://docs.glean.com/user-guide/assistant/how-glean-accesses-info

| Dim | Findings |
|---|---|
| D1 | Library tabs are **Artifacts / Projects / Meeting notes / Go Links / More (Answers, Announcements)**. "Which tabs you see depends on the features enabled" [GL1]. Artifacts opens on **Created by me** and toggles to **Shared with me** [GL1]. Projects appear in the left nav with a **Pinned** section [GL2]. Meeting notes have their own left-nav item [GL5]. Collections are being migrated into Projects [GL3][GL2]. |
| D2 | Artifact types: **Audio, Documents, Emails, Images, Interactive, Messages, Slides, Spreadsheets** [GL1]. Projects hold chats, documents from any connected app, interactive content, uploaded files and URLs [GL2]. Meeting notes are "first-class artifacts": transcript, summary, decisions and action items. **Raw audio is not stored** [GL5]. Agent knowledge can be connectors, containers (Drive folder, SharePoint site, Confluence space and so on) or single documents [GL8]. Chat uploads live inside the chat session [GL4]. |
| D3 | Content is private by default and the creator stays **Owner** [GL1]. Sharing has **People with access** (groups, departments, teammates) and **General access**: Restricted, or "Anyone at [company] can view" [GL1]. A separate checkbox, **"Visible across your entire org via Search and Library"**, controls discoverability. **Link-only shares stay out of Library** [GL1]. Pins "respect all source permissions" [GL6]. Collection children inherit the parent's permissions [GL3]. Agents have **Viewer / Editor / Owner** rights; in the web app **"the agent uses the signed-in user's individual permissions"** [GL7]. Uploaded files can be downloaded only by the uploader or by people with access to the shared chat [GL4]. Results change quickly when connector permissions change [GL9]. |
| D4 | Open an artifact beside its originating conversation, keep editing, share, export [GL1]. **Add to Collection/Project** straight from search results [GL3][GL2]. Pin or unpin projects [GL2]. Pin search results for Only me or Teammates, with a department audience [GL6]. Deleting a collection does not touch the underlying docs [GL3]. An upload can be removed only before the first query; after that, only by deleting the chat [GL4]. |
| D5 | **Search library**, a **Type** filter, and **Creator + Department** filters in Shared with me. Sort by **Recently viewed** (default) or **Created date** [GL1]. The Collection filter or the `app:collections` operator works in search [GL3]. Ranking is personalized by activity [GL9]. |
| D6 | Glean-created content "stays in Library until it is deleted". Chat responses follow chat retention [GL1]. Uploads are kept with the chat session; the cap is 5 files × 64 MB on 128K models [GL4]. Meeting summaries follow org retention. Transcript retention is admin-configurable and cannot exceed chat retention [GL5]. Per-user Library quota: not documented. |

### 1.6 Onyx (open source, local checkout `f9e3de36c`)

| Dim | Findings (code paths) |
|---|---|
| D1 | I found **no standalone library page** in the chat UI at this revision `[INFERENCE from code search]`. What exists: a **Recent files** popover with an "all recent files" modal (`web/src/refresh-components/popovers/FilePickerPopover.tsx` L142-304), and a per-project **Files** panel with a "View all" modal (`web/src/lib/projects/components/ProjectContextPanel.tsx` L119-256). Both reuse `web/src/sections/modals/UserFilesModal.tsx`. The Craft (build) feature has a separate **User Library** tree with folders (`web/src/app/craft/components/UserLibraryModal.tsx`; `backend/onyx/server/features/build/user_library/api.py`). A backend enum `UserDocument {CHAT, RECENT, FILE}` exists (`backend/onyx/db/models.py` L5550-5553). |
| D2 | `UserFile` holds user uploads, linked M:N to projects (`Project__UserFile`) and to assistants (`Persona__UserFile`) (`models.py` L4341, L5506-5643). Connector documents are reached through Search, not a library (`backend/onyx/context/search/models.py` L104-161). Assistant knowledge = user files plus explicitly attached connector documents and folder/space "hierarchy nodes" (`AssistantKnowledgeFilters`, L137-149). Craft library files are raw binaries synced into sandboxes (`build/user_library/api.py` L179-280). |
| D3 | A `UserFile` has one owner (`user_id`). Projects are **owner-only**: the list is filtered by `user_id` and there is no project sharing endpoint (`backend/onyx/server/features/projects/api.py` L53, L146-155). **A file's ACL grows through assistant sharing**: owner + assistant owner + assistant's shared users, and **public if any attached assistant is public** (`backend/onyx/access/access.py` L203-218). EE adds the assistant's user groups (`backend/ee/onyx/access/access.py` L152-181). Assistant-attached connector docs are searched "in addition to ACL filtering" (`search/models.py` L140-142). |
| D4 | Upload to a chat or project (`projects/api.py` L174-234). Pick a recent file into chat; view; delete; unlink from project (L279); move a chat into a project (L631). Status polling (L578-628). **Delete is refused while the file is linked to projects or assistants**, and the response lists their names (`projects/api.py` L517-547). The UI shows "Cannot delete file. It is associated with projects: {projects} and assistants: {assistants}" (`web/src/refresh-components/popovers/FilePickerPopover.tsx` L228-246; `web/src/i18n/messages/en.json` L11082-11086). Craft: upload files or zip, create folder, delete, toggle per-file/folder sync (`build/db/user_library.py` L176-193). |
| D5 | Client-side name filter in `UserFilesModal` (`useFilter(files, f => f.name)`). Recent files are sorted by `last_accessed_at DESC` and exclude FAILED, DELETING and incognito files (`backend/onyx/server/manage/users.py` L1451-1468). Craft tree: folders first, then alphabetical (`UserLibraryModal.tsx` L543-547). Connector search filters: `source_type`, `document_set`, created/updated ranges, `tags` (`search/models.py` L104-109). |
| D6 | **No trash.** Delete sets status `DELETING` and queues an immediate async task (`projects/api.py` L548-566). Incognito uploads are swept with their session (`models.py` L5580-5590). Craft library defaults (env-configurable): **10 GB per user, 500 MB per file, 100 files per upload**, plus a zip-bomb size check (`backend/onyx/server/features/build/configs.py` L228-248; `user_library/api.py` L129-148, L187-226). I found no quota on regular chat/project user files. |

### 1.7 Notion (Library, Home, Meetings, AI connectors)

Sources:
- [N1] Sidebar — https://www.notion.com/help/navigate-with-the-sidebar
- [N2] Library — https://www.notion.com/help/manage-your-library
- [N3] AI connectors — https://www.notion.com/help/notion-ai-connectors
- [N4] AI Meeting Notes — https://www.notion.com/help/ai-meeting-notes

| Dim | Findings |
|---|---|
| D1 | Library tabs: **Teamspaces / Recents / Favorites / Shared / Private / AI Meeting Notes / Agents** [N2]. Top-level sidebar items: Home, Chats with Notion AI, **Meetings**, Inbox, **Library** [N1]. Home sections include Upcoming events, Recents, Favorites, Agents, Teamspaces, Shared and Private, each with **"Open in Library"** [N1]. Trash also sits in the sidebar [N1]. |
| D2 | Pages and databases, AI meeting notes and Custom Agents [N2]. AI chats have their own history section [N1]. Content from connected apps (Slack, Drive, SharePoint/OneDrive, Teams, Jira, GitHub, Linear, Gmail, Outlook, Calendar) can be **searched through AI and Search**, but it is not a Library tab [N3][N2] `[INFERENCE: absence from tab list]`. |
| D3 | Private means "only visible to you". Shared means shared with selected people. Teamspaces are team-wide [N1]. Dragging a page into Private removes others' access [N1]. Meeting notes **inherit the permissions of their page** [N4]. Notes started from calendar entry points are auto-shared with participants who are workspace members [N4]. The Meetings list shows notes you created **plus notes linked to events you attended**, wherever they are stored [N4]. AI connectors honor source permissions and act as you [N3]. |
| D4 | Bulk actions: move to a teamspace, delete, remove from Favorites, edit icons [N2]. Favorite; add or remove a section from the sidebar [N2][N1]. Meeting note: copy link, duplicate, move to a page; delete transcript or audio [N1][N4]. Agent: favorite, settings, copy link, delete. Chat: rename, change icon, delete [N1]. |
| D5 | Library search by name, filters, and a choice of which properties are shown [N2]. Private and Shared can be sorted Manual or Last edited [N1]. The Meetings list supports filter, sort, search and grouping [N4]. |
| D6 | Trash keeps items **30 days** by default; Enterprise owners can change this [N1]. Uploaded meeting audio is kept up to 3 days for processing retries. Enterprise can schedule automatic transcript deletion, which skips pages under legal hold [N4]. Connector data is deleted within a day of disconnecting. Indexing lag is up to 3 h. Connectors can backfill about 1 year [N3]. Storage and upload quotas: not researched. |

### 1.8 Dropbox Dash (Stacks, universal search, start page)

Sources:
- [DD1] Using Stacks — https://help.dropbox.com/organize/using-stacks
- [DD2] Share Stacks — https://help.dropbox.com/organize/share-stacks
- [DD3] Search and explore — https://help.dropbox.com/view-edit/dropbox-dash-search-and-explore
- [DD4] Start page — https://help.dropbox.com/view-edit/dropbox-dash-start-page
- [DD5] Connect apps — https://help.dropbox.com/integrations/connect-apps-to-dash

| Dim | Findings |
|---|---|
| D1 | Left sidebar: **Home, Search, Chats, Stacks** [DD4]. The start page has **Recents** and **Stacks** tabs. Stacks filter by **For you / Created by me / Shared with me / From my company** [DD4]. The page also shows Upcoming events and an **Activity feed** that can be filtered by app [DD4]. |
| D2 | A Stack can hold files, app content, browser links and messages [DD1]. Dash also makes **suggested Stacks** from your activity, which you can keep or remove [DD1]. Search covers Dropbox and connected apps, including images, video and design files [DD3]. |
| D3 | "Dash always respects app permissions and never overrides them" [DD5]. Some apps use team-wide permissions, and the admin is told when connecting them [DD5]. Apps come in two kinds: individually connected and admin-connected [DD5]. **Adding a person to a Stack by name or email automatically grants them access to the Dropbox files and folders in it, including items added later.** Sharing by link keeps existing permissions [DD2]. Folder-level link restrictions can block Stack links [DD2]. |
| D4 | Create a Stack (web, desktop, extension, tab groups); keep or remove suggested Stacks; comment; notifications; share internally or externally [DD1][DD2]. Stack notifications cover archive and unarchive [DD1]. The start page can attach sources to chat [DD4]. |
| D5 | Filters **App / Type / Updated / People**. Text operators `from:`, `in:`, `time:`, `type:`. Image-content search and a "Text in image" filter [DD3]. Ranking uses titles, content and recent interactions [DD3]. |
| D6 | Dash indexes content rather than storing it. Retention: see §4. |

---

## 2. Cross-product quick matrix

✅ = documented, — = not found. Sources are in the tables above.

| Capability | ChatGPT | Claude | M365 Copilot/OneDrive | Drive | Glean | Onyx | Notion | Dash |
|---|---|---|---|---|---|---|---|---|
| Mine vs Shared-with-me split | ✅ | ✅ (projects) | ✅ | ✅ | ✅ | — | ✅ | ✅ |
| Recent | ✅ (composer) | — | ✅ | ✅ (Home) | ✅ (sort) | ✅ | ✅ | ✅ |
| Starred/Favorites/Pinned | — | ✅ (projects) | ✅ | ✅ | ✅ (pin) | — | ✅ | — |
| By-type filter | ✅ | — | ✅ | ✅ | ✅ | — | ✅ (filters) | ✅ |
| Generated assets as first-class | ✅ | ✅ | ✅ | ✅ (Gemini shared) | ✅ | — | — | — |
| Connected-source docs browsable in library | ✅ (Drive etc.) | — (add to project) | ✅ (via OneDrive) | n/a | via search/projects | via search | search-only | search + Stacks |
| Meetings as a tab/view | — | — | ✅ (OneDrive Meetings) | ✅ (Home "upcoming meetings") | ✅ | — | ✅ | ✅ (events panel) |
| Trash / restore | ✅ (if available) | — | ✅ (OneDrive; not Notebooks) | ✅ 30 d | — | ❌ none | ✅ 30 d | — |
| Per-user quota shown | ✅ | — | org quota | ✅ | — | Craft only | — | — |
| "Where used" guard on delete | — | — | — | — | — | ✅ | — | — |
| Discoverability flag separate from access | — | ✅ (public project) | — | — | ✅ | — | — | ✅ ("From my company") |

---

## 3. Synthesis

### 3.1 Common patterns

1. **Two axes: relationship × kind.** Every mature hub combines a relationship axis (*Created by me / Mine, Shared with me, Recent, Favorites or Starred*) with a kind axis (*Images, Pages, Artifacts, Meeting notes, Projects, Agents*).
   - Examples: Glean Artifacts/Projects/Meeting notes plus Created-by-me vs Shared-with-me [GL1]; Notion's seven Library tabs [N2]; M365 Library Images/Pages plus Notebooks All/Just you/Shared [ML][MNF]; Dash stack filters [DD4]; Drive My Drive, Shared with me and Starred [GD1].
2. **Generated output is its own kind, with a link back to where it came from.** Glean opens an artifact "alongside the conversation where it was created" [GL1]. ChatGPT separates uploaded from generated and keeps Images [L][I]. M365 Library exists only for Copilot-generated images and pages [MF]. Claude has a dedicated Artifacts tab [CA].
3. **Connected-source documents are referenced, not copied, and live permissions govern them.**
   - ChatGPT's Library browses connected Drive, Box, Dropbox and SharePoint files that "remain associated with their original service", follows the connected account's permissions, and cannot delete them [L].
   - Dash "never overrides" app permissions [DD5]. Glean pins and agents use the viewer's permissions [GL6][GL7]. Claude drops the preview when access is lost [CG].
4. **Items you cannot access are hidden, not shown locked.** OneDrive's People view "will only show files to which you have access" [OD1]. Glean pins appear only when the user can open the underlying item [GL6].
5. **Shared with me = explicit share or explicit publish.** Link-only shares stay out of Glean's Library [GL1]. Drive's Shared with me adds a link-shared file only after you open it [GD5].
6. **Owner vs non-owner actions differ.** In Drive the owner can trash while a non-owner can only remove it from their own view [GD3]. OneDrive restores only your own files [ODR]. Only owned bytes count toward storage [GD4].
7. **Soft delete, usually for 30 days.** Drive trash 30 days [GD3]; ChatGPT Recently deleted, purged within 30 days [L]; Notion trash 30 days [N1]; OneDrive for work 93 days [ODR].
8. **A library item's lifecycle is separate from its container's.** Deleting a chat does not delete its Library file. Deleting a project deletes files stored only in that project but keeps Library copies [L][P][R]. Deleting a Glean collection does not delete its documents [GL3].
9. **Meetings are becoming a first-class kind, and visibility follows attendance.** Examples: Glean's Meeting notes tab [GL5]; Notion's AI Meeting Notes tab and Meetings list covering "events that you attended" [N2][N4]; OneDrive's Meetings view with Teams recordings and series grouping [OD1]; Drive Home surfacing files for upcoming meetings [GD1].
10. **Reuse comes before management.** The main actions are reuse actions:
    - ChatGPT: "Add from library", `@mention`, "ask across a folder" [L].
    - Glean: "Add to Collection/Project" from search [GL3][GL2].
    - Copilot: add references to a Notebook [MN]; "Add to Calendar/To Do" [MA].
    - ChatGPT: save a chat response as a project source [P].

### 3.2 Outliers and conflicting designs

- **Does sharing a container grant access to what is inside it?** Products disagree:
  - Grants underlying access: Copilot Notebook sharing (to all referenced files) [MPM]; Dash Stack shared by email (to Dropbox files) [DD2]; Claude legacy chat-artifact sharing (to that chat's attachments) [CS].
  - Does not grant access: Copilot conversation sharing [MSH]; Dash Stack shared by link [DD2].
  - Onyx: attaching a user file to a shared assistant **silently widens the file's ACL** to that assistant's audience, or to everyone if the assistant is public (`backend/onyx/access/access.py` L203-218).
- **Where-used guard:** only Onyx refuses to delete a file that is still linked to projects or assistants, and it names them (`projects/api.py` L517-547).
- **Library as a view vs as storage:** M365 Library is "simply a view" with no extra storage [MF]. ChatGPT Library carries its own quota [L].
- **Connectors inside shared projects:** Claude turns off the Drive connector for shared projects [CG]. ChatGPT limits Drive/Slack source links to **private** projects [P]. Neither lets live connector permissions leak into a shared space.
- **No recycle bin:** Copilot Notebooks [MST], Onyx user files (`projects/api.py` L548-566), and Glean uploads, which can only be removed by deleting the chat [GL4].
- **Generated images tied to their chat:** ChatGPT images can be deleted only by deleting the conversation [I]. Library files, by contrast, outlive their chat [L].
- **Search-only connectors:** Notion indexes connected apps for AI and Search but does not browse them in Library [N3][N2].

---

## 4. Recommendations for an enterprise RAG app's Library (MemoryOS `/library`)

Each recommendation lists its evidence. Mapping to MemoryOS assets (uploads, code-generated files, generated images, Drive/SharePoint Source documents with Group access, Projects, Assistants/Agents, Meetings with minutes) is my synthesis.

**R1. Use two axes: relationship tabs plus a kind filter.**
- Tabs: *Của tôi* (uploaded or created by me), *Được chia sẻ với tôi*, *Gần đây*, *Có gắn sao*, *Thùng rác*.
- Kind chips across all tabs: *Tệp tải lên · Tệp do code tạo · Ảnh tạo · Biên bản họp · Tài liệu nguồn · Tri thức dự án/trợ lý*.
- Evidence: Glean tabs and Created-by-me/Shared-with-me [GL1]; Notion Library tabs [N2]; M365 Library plus Notebooks filters [ML][MNF]; Dash stack filters [DD4]; Drive views [GD1].

**R2. Show Source documents (Drive/SharePoint) as live, read-only references filtered by Group ACL, not as copies.**
- Resolve visibility at query time from Source and Group access, and hide what the user cannot open rather than showing it locked.
- Allow Preview, Open in source, Ask Chat, Add to project, Add to assistant, Star.
- Never offer Rename, Delete or Move.
- Group them by Source (connector) with a source filter.
- Evidence: ChatGPT Library for Drive [L]; Dash [DD5]; OneDrive People view [OD1]; Glean pins and agents [GL6][GL7]; Claude lost-access behavior [CG].

**R3. Show on every row *why* the user can see it.**
- Examples: "Chủ sở hữu: bạn", "Chia sẻ bởi A · ngày", "Qua nhóm X", "Qua dự án Y", "Qua trợ lý Z", "Nguồn: SharePoint/Drive".
- Only the owner gets *Xóa → Thùng rác*. Everyone else gets *Ẩn khỏi thư viện của tôi*.
- Evidence: Drive Shared with me shows date shared, owner and type [GD5]; Drive owner trashes while non-owner removes [GD3]; Drive ownership operators [GD2]; OneDrive restores own files only [ODR].

**R4. Add a where-used guard, and keep asset lifecycles separate from their containers.**
- Before trashing a file attached to Projects or Assistants, list them and ask the user to unlink first, or confirm removal for everyone.
- Deleting a chat must not delete a library file.
- Deleting a project deletes only files that exist solely in that project.
- Evidence: Onyx `delete_user_file` association check (`projects/api.py` L517-547) and its UI message (`en.json` L11082-11086); ChatGPT chat vs Library and project vs Library lifecycles [L][P][R]; ChatGPT shared-project delete affects everyone [P].

**R5. State the side effects of sharing in the UI and pick one rule per kind.**
- For Source documents, sharing a project or assistant never grants access. Recipients still need their own Group access. This matches how Copilot conversation sharing works [MSH].
- For user uploads, attaching a file to a shared project or assistant does widen its audience. Onyx does this implicitly (`access.py` L203-218). Copilot Notebooks do it for referenced files [MPM]. MemoryOS should do it only with a visible label, for example "Thành viên dự án có thể dùng tệp này khi hỏi".
- Evidence: Dash email vs link sharing [DD2]; Claude legacy chat-artifact exposure [CS].

**R6. Make Meetings a first-class kind, with visibility by role.**
- Visible to: organizer, attendees, and members of the linked Project.
- Group entries by meeting and recurring series, showing minutes, transcript and recording together.
- Keep a separate, admin-configurable retention for transcripts and recordings.
- Evidence: Glean Meeting notes tab, no raw audio, transcript retention capped by chat retention [GL5][GL1]; Notion Meetings list of attended events, page-inherited permissions, auto-share to participants, transcript auto-delete schedule [N4]; OneDrive Meetings view with recordings and series [OD1].

**R7. Treat generated assets as first-class, with a link back to their source.**
- Code-generated files and images get "Mở trong cuộc trò chuyện", a type filter, and sensible default sorts: images by creation date, documents by last modified.
- Evidence: Glean opens artifacts beside their conversation, with Type filter and sort by Recently viewed or Created date [GL1]; M365 sorts images by created and pages by last modified [MF]; ChatGPT uploaded vs generated filter [L].

**R8. Baseline search, filter and sort.**
- Name search, plus filters for kind, file type, Source, owner/creator, Group/department, and modified date.
- Default sort: *Xem gần đây* in "Của tôi", *Ngày chia sẻ* in "Được chia sẻ".
- Optional operators such as `owner:`, `type:`, `source:`.
- Evidence: Drive chips and operators [GD2]; Glean Type, Creator, Department and sort [GL1]; Dash App, Type, Updated, People and operators [DD3]; OneDrive type filters and search within a filter [OD2].

**R9. Quota and retention.**
- The per-user quota counts only bytes the user owns. Source documents never count.
- Items in trash count until purged.
- Provide a "Dung lượng" view sorted largest first.
- 30-day trash with Khôi phục / Xóa vĩnh viễn / Dọn thùng rác.
- Admin retention policy for library files and meeting transcripts.
- Evidence: ChatGPT quotas and Storage view [L]; Drive "only files you own count", trash counts, 30-day auto-delete, Storage sorted by size [GD4][GD3][GD2]; Notion 30-day trash with Enterprise override [N1]; ChatGPT workspace retention for Library [R].

**R10. Put reuse actions first, and allow asking across a set.**
- Per-item actions: *Hỏi Chat* (attach), *Thêm vào dự án*, *Thêm vào trợ lý*, *Gắn sao*.
- *Hỏi về các mục đã chọn / thư mục / dự án* to ask across a selection, folder or project.
- Evidence: ChatGPT "Add from library", `@mention`, "select a folder … and ask" [L]; Glean "Add to Collection/Project" from results [GL3][GL2]; Copilot Notebooks answer only from added references, up to 300 files [MN]; ChatGPT "Save to project" for responses [P].

### 4.1 Suggested action matrix (synthesis of R2–R10)

| Kind | Preview | Download | Ask Chat | Add to project/assistant | Share | Rename | Trash (owner) | Star |
|---|---|---|---|---|---|---|---|---|
| My upload | ✅ | ✅ | ✅ | ✅ (widens audience, labeled) | ✅ | ✅ | ✅ (where-used guard) | ✅ |
| Code-generated file / image | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| Source document (Drive/SharePoint) | ✅ | per source policy | ✅ | ✅ (reference only, no access grant) | open in source | — | — | ✅ |
| Meeting minutes / transcript | ✅ | ✅ (export) | ✅ | ✅ | organizer only | organizer only | organizer only | ✅ |
| Shared-with-me item | ✅ | per permission | ✅ | ✅ | — | — | hide from my library | ✅ |

---

## 5. Unverified points (explicit)

- **ChatGPT:** Library sort options are undocumented. Bulk actions other than Download are unconfirmed, as is whether Library lists project-only files. A third-party report (piunikaweb, 2026) says the "Images" sidebar item moved into Library. The official text read only says generated images "also appear in Images" [L][I]. Treat the current Images placement as unverified.
- **Claude:** No global cross-project "Files" view in claude.ai chat is documented. Artifacts-tab filters and sort are not documented. Team/Enterprise retention for project files was not researched. The new projects Library is beta, Pro/Max only, and absent on Team/Enterprise [CC].
- **Microsoft:** Where Copilot Chat uploads are stored (a "Microsoft Copilot Chat Files" OneDrive folder with 30-day auto-delete) comes only from secondary sources. The OneDrive Shared, People and Meetings view details come from 2023 official blogs, and current GA state was not rechecked in support docs.
- **Google Drive:** The "Recent" view is not described in the help articles read, although "Find recently edited files" uses the Activity panel [GD6]. The Home "Activity" tab comes only from third-party sources.
- **Glean:** No "Saved" feature appears in the docs index. Per-user Library storage quota is not documented. The 60-day agent soft delete appears only in the docs-index description of `/administration/managing-agents/deleting-and-restoring-agents.md` (I did not read the page).
- **Onyx:**
  - "No standalone library page" is inferred from code search at `f9e3de36c`.
  - Not traced: whether code-interpreter or generated outputs become `UserFile` rows, and where the `UserDocument` enum is used.
  - No quota or retention was found for regular user files.
  - The `backend/.../user_documents` path named in the brief does not exist at this revision.
- **Notion:** Storage and upload quotas were not researched. "Connector content is not listed in Library" is inferred from the tab list.
- **Dropbox Dash:** Storage and retention are undocumented. Stack archive behavior is known only from the notification list. Exact Stack sharing roles were not extracted.
