# Implementation plan

Design: [design.md](design.md). One pull request. It is accepted when CI is green, staging has deployed it and the
owner has checked the faces on staging.

## 1. Backend

- [x] Migration V177 (V176 is held by the open MEM-270 pull request): add `persona.avatar_seed varchar(64)` with its CHECK, fill every row with a
  random seed (`gen_random_uuid()`), give the built-in agent `memoryos`, set NOT NULL, drop `persona.icon_name`.
- [x] `PersonaEntity`: `avatarSeed` in place of `iconName`.
- [x] `ChatPersonaService`:
  - a random seed on create when none is given;
  - `avatarSeed` validated on create and update;
  - null keeps the current seed;
  - the icon/avatar exclusivity rule (`if (avatar != null) iconName = null`) removed.
- [x] Tenant provisioning (`JdbcChatRepository.provisionPersona`): the built-in agent's seed is `memoryos`.
- [x] API contract: `ChatPersonaRequest.avatarSeed` replaces `iconName`, and the persona view returns `avatarSeed`.
  Refresh `openapi.yml` (`MEMORYOS_OPENAPI_WRITE=true`, `OpenApiContractTest`).
- [x] Tests (`anAgentKeepsTheFaceItWasGivenAndOnlyAValidSeedChangesIt`):
  - creating without a seed stores a random valid seed;
  - two agents get different seeds;
  - an update without a seed keeps it;
  - an invalid seed is refused with its code;
  - the built-in agent has `memoryos`;
  - the migration leaves no row without a seed (PostgreSQL boundary).

## 2. Web

- [x] Add `@dicebear/core@10.7.0` and `@dicebear/styles@10.6.0`, pinned exactly. Run `pnpm --dir web generate:api`.
- [x] `AgentAvatar`: the uploaded image, else the `thumbs` face from `avatarSeed`, memoised per seed, at the existing
  sizes, `alt=""` as today.
- [x] Avatar studio, after Rakazo:
  - the avatar button with a pencil badge opens a 360 px `Dialog`;
  - a large preview, and the tabs "Khuôn mặt" and "Ảnh" as a segmented control;
  - the faces tab: a grid of eight candidate faces, "Đổi bộ khác" and "Mặc định";
  - the image tab: the existing upload, plus drag and drop and paste;
  - "Xong" closes the dialog.
  Built from our shadcn primitives (`Dialog`, `ToggleGroup` or `Tabs`, `Button`), none of which is modified.
- [x] Editor: the studio replaces the icon grid; `agent-form.ts` carries `avatarSeed`; `agent-icons.ts` moves to `features/chat/projects/project-icon-set.ts`, which projects still use.
- [x] i18n: the new labels in natural Vietnamese ("Khuôn mặt", "Ảnh", "Đổi bộ khác", "Mặc định", "Xong"). The icon
  names stay for the project picker.
- [x] Tests (`agent-avatar-studio.test.tsx`, `agent-editor.test.tsx`, `agents.spec.ts`):
  - `AgentAvatar` draws the image when there is one, and the same face for the same seed;
  - picking a face in the studio sends its seed when the form is saved;
  - "Mặc định" restores the saved seed;
  - a pasted image goes to the upload.
- [x] Bundle: compare the production build's agent chunk before and after, and record the numbers here. Lazy-load the
  core if it grows by more than about 30 KB gzipped.

  Measured on zm with `vite build` and `scripts/assert-bundle-budget.mjs`, main at `c96982dd` against this branch:

  | | Before | After |
  | --- | --- | --- |
  | Initial load (entry and preloads, gzip) | 319.5 KiB | 319.6 KiB |
  | `agent-avatar` chunk (gzip) | 488 B | 27,312 B |
  | `agent-icons` chunk (gzip) | 1,063 B | gone; the set moved to the project icons |
  | All JavaScript (gzip, concatenated) | 4,013,776 B | 4,039,693 B |

  DiceBear lands in the lazily loaded `agent-avatar` chunk (+26.8 KB gzip, under the 30 KB threshold) and the initial
  load is unchanged, so no extra lazy loading was added.

## 3. Verification

- [x] On zm, throttled: the persona and chat tests, `ModulithArchitectureTest`, `CoreDependencyRulesTest` and
  `OpenApiContractTest`, all green on 2026-10-16: `ChatPersistenceIntegrationTest` (49), `ChatSessionApiIntegrationTest`
  (3), `OpenApiContractTest` (write, then compare), `ModulithArchitectureTest`, `CoreDependencyRulesTest`. `pnpm check` also
  passed on zm (191 files, 1,036 tests). CI runs the full gates.
- [x] Staging, 2026-10-19 on `396b72f6` ([verification](verification.md)):
  - existing agents show faces;
  - a new agent gets one;
  - "Đổi mặt khác" changes it and the change is kept after saving;
  - renaming keeps it;
  - an uploaded image still wins.
  Screenshots of the pinned list, the gallery, the editor and the studio in light and dark, at desktop and phone
  width, with realistic agent names, checked against the [design tokens](../../../guidelines/design-tokens.md).
- [x] Docs: the chat spec's agent section, the test matrix, the roadmap.
