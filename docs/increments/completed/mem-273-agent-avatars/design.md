# MEM-273 Agent avatars

Every agent gets a friendly face, generated when it is created, instead of one of thirteen icons that every new agent
starts as `bot`. The face is the DiceBear `thumbs` style, chosen by the owner on 2026-10-15 from a comparison of twelve
styles. This is the first step of [MEM-273](https://linear.app/memory-os/issue/MEM-273); binding agents to channel bots,
the agent title and reasoning level, and the seeded agents follow as their own steps.

## Today

- An agent shows its uploaded image (`persona.avatar_file_id`, served at `/api/chat/personas/{id}/avatar`), or else
  one of thirteen lucide icons named by `persona.icon_name` (V74, `^[a-z0-9-]{1,40}$`) on a tint chosen by icon
  (`web/src/features/agents/agent-icons.ts`).
- A new agent starts as `bot` (`agent-form.ts`), so every agent looks the same until its owner picks an icon in
  `AgentIconPicker` (`agent-editor-fields.tsx`).
- `AgentAvatar` (`agent-avatar.tsx`) is the only place that draws an agent. The pinned list, the gallery, the card,
  the viewer, the preview and the admin page all use it.
- Projects have their own `chat_project.icon_name` (V123) and `ProjectIcon`. Projects are not part of this change.

## How the references do it (code read 2026-10-15)

| | Face | Chosen at creation | Stored |
| --- | --- | --- | --- |
| Rakazo | 8 mascot shapes and 10 blob families drawn in code, animated eyes, 11 colours | Colour rotates with the owner's bot count; shape derived from the bot id | One string: `#hex`, `#hex::shape_N` or an image data URL |
| Hermes Bot Mode | Blob face from the name, 6 silhouettes; geometric 7 × 10; image; pixel pet | From the name, with Randomize and Lock face | Profile metadata |
| DiceBear | 60+ styles, deterministic from a seed | Any seed | The caller stores the seed |

Rakazo's faces are Apache-2.0 and could be ported, but a maintained library does the same work
([prefer a maintained SDK](../../../conventions.md#component-and-library-reuse)).

## Decisions

1. **Library.** `@dicebear/core` 10.7.0 (MIT) and the `thumbs` definition from `@dicebear/styles` 10.6.0
   (`@dicebear/styles/thumbs.json`). Both are the newest stable releases; 11.0 is still a release candidate. The style
   is CC0, so nothing has to be attributed in the app. Only `thumbs.json` is imported (12 KB, about 2 KB gzipped).
   The avatar is built as `new Avatar(new Style(definition), { seed })`, the form 10.x asks for ahead of 11.
2. **A stored seed, not the name.** The face comes from a seed kept on the agent, so renaming an agent keeps its face
   (Rakazo derives it from the id; Hermes from the name, then locks it). A new column `persona.avatar_seed`
   (`varchar(64)`, not null, `^[A-Za-z0-9_-]{1,64}$`) holds it.
   - `ChatPersonaService` generates a random seed when an agent is created without one.
   - The migration gives every existing agent a random seed.
   - The built-in agent gets the fixed seed `memoryos`.
3. **The style's own colours.** `thumbs` picks its background and face from five colours (`#0a5b83`, `#1c799f`,
   `#69d2e7`, `#f1f4dc`, `#f88c49`) with contrast rules for the eyes and mouth. Those are the colours the owner chose
   from, and they read on both themes, so no palette of ours is added.
4. **Drawn in the browser.** `AgentAvatar` builds the SVG from the seed, memoised per seed, and shows it as an
   `<img>` data URI at the existing sizes (`sm`, `md`, `lg`, `xl`). No endpoint serves it and no file is stored.
5. **Order.** An uploaded image wins, then the face. The upload path is unchanged.
6. **Icons retired for agents.**
   - `persona.icon_name` is dropped, together with `iconName` on the agent API (request and view),
     `agent-icons.ts` and the icon grid.
   - Keeping two avatar systems for agents would leave every new agent on a face and every old one on a chosen
     icon, with no reason a person could see.
   - Cost: an agent whose owner picked a meaningful icon, such as a bank for "Báo cáo tài chính", now shows a face.
     Its owner can upload an image instead.
   - Projects keep their icons.
7. **Editing, after Rakazo's avatar studio** (`apps/web/src/pages/shell/avatar-studio-popover.tsx`,
   `packages/ui-web/src/bot-avatar.tsx`).
   - **Trigger.** In the editor the avatar is a button with a small pencil badge at its bottom-right corner, growing
     slightly on hover, as Rakazo's trigger does.
   - **Dialog.** A compact dialog (about 360 px, our `Dialog` primitive) opens with a large preview and a segmented
     control with two tabs: "Khuôn mặt" and "Ảnh".
   - **Faces tab.** A grid of eight candidate faces drawn from random seeds. A tap picks one into the preview.
     "Đổi bộ khác" draws eight new faces. "Mặc định" returns to the agent's saved seed. Rakazo offers a shape grid and
     colour swatches instead; `thumbs` has no separate shapes to pick, so whole faces are picked.
   - **Image tab.** The existing upload, which also takes drag and drop and a pasted image, as Rakazo's does.
   - "Xong" closes the dialog. The choice is saved with the rest of the form, like the icon today.
   - We copy Rakazo's interaction pattern, not its code: their component draws their own shapes, while ours draws
     DiceBear.
8. **Contract.** `ChatPersonaRequest` takes an optional `avatarSeed`, validated by the same pattern. Null keeps the
   current seed. The persona view returns `avatarSeed`. `openapi.yml` and the Hey API client are regenerated. Callers
   that sent `iconName` are rejected by the generated schema, as they would be for any unknown field the contract
   drops.

## Changes made during implementation

- **Where the seed is made.** `PersonaEntity` starts with `UUID.randomUUID()`, and V177 gives the column the volatile
  default `gen_random_uuid()::text`, so every existing row, every JPA insert and every raw SQL insert (the tests have
  about twenty) gets its own seed without a step in `ChatPersonaService`. The editor proposes `crypto.randomUUID()`
  for a new agent, so the face shown before saving is the one that is saved. The built-in agent's `memoryos` is set in
  `JdbcChatRepository.provisionPersona`, which is what tenant bootstrap calls.
- **`removeAvatar`.** Picking an icon used to be the only way to drop an uploaded image (the exclusivity rule). With
  the rule gone, the request takes `removeAvatar: true` to drop it; null or false keeps it, and a new `avatarFileId`
  replaces it. *Bỏ ảnh* and picking a face send it.
- **The icon set moved, not deleted.** Projects draw from the same thirteen lucide icons, so `agent-icons.ts` became
  `web/src/features/chat/projects/project-icon-set.ts` and only the agent use is gone. The icon names stay in the
  catalog because the project picker labels them.
- **Migration version.** V177: V176 is taken by the open MEM-270 sandbox pull request.
- **Tabs.** The two tabs are our `Tabs` primitive with the `line` variant, unmodified; the built-in agent, which takes no
  upload, shows only the faces. For a new agent *Mặc định* returns to the face it was opened with.

## Not in scope

- The sidebar entries for agents ("Trợ lý" in the main menu beside "Khám phá trợ lý"): a separate decision.
- Project icons; avatars for people, Groups or channel bots.
- Animated faces (Rakazo's eyes) or more than one style.
- Rakazo's "working" ring around a bot's avatar while it answers. It would suit the pinned list during a running
  turn, but it is a separate change to the chat state the sidebar reads.

## Risks

- **Bundle size.** `@dicebear/core` is about 580 KB unminified on disk. The plan measures the production bundle
  before and after; if the agent chunk grows by more than about 30 KB gzipped, the core is lazy-loaded with the agent
  pages.
- **Dropping `icon_name` cannot be undone.** The icon choices are lost. The owner accepted this on 2026-10-15 when
  choosing faces for every agent.
