# Implementation plan

Design: [design.md](design.md). One pull request. It is accepted when CI is green, staging has deployed it and the
owner has checked the faces on staging.

## 1. Backend

- [ ] Migration (the next free version): add `persona.avatar_seed varchar(64)` with its CHECK, fill every row with a
  random seed (`gen_random_uuid()`), give the built-in agent `memoryos`, set NOT NULL, drop `persona.icon_name`.
- [ ] `PersonaEntity`: `avatarSeed` in place of `iconName`.
- [ ] `ChatPersonaService`:
  - a random seed on create when none is given;
  - `avatarSeed` validated on create and update;
  - null keeps the current seed;
  - the icon/avatar exclusivity rule (`if (avatar != null) iconName = null`) removed.
- [ ] `ChatTenantProvisioner`: the built-in agent's seed is `memoryos`.
- [ ] API contract: `ChatPersonaRequest.avatarSeed` replaces `iconName`, and the persona view returns `avatarSeed`.
  Refresh `openapi.yml` (`MEMORYOS_OPENAPI_WRITE=true`, `OpenApiContractTest`).
- [ ] Tests:
  - creating without a seed stores a random valid seed;
  - two agents get different seeds;
  - an update without a seed keeps it;
  - an invalid seed is refused with its code;
  - the built-in agent has `memoryos`;
  - the migration leaves no row without a seed (PostgreSQL boundary).

## 2. Web

- [ ] Add `@dicebear/core@10.7.0` and `@dicebear/styles@10.6.0`, pinned exactly. Run `pnpm --dir web generate:api`.
- [ ] `AgentAvatar`: the uploaded image, else the `thumbs` face from `avatarSeed`, memoised per seed, at the existing
  sizes, `alt=""` as today.
- [ ] Avatar studio, after Rakazo:
  - the avatar button with a pencil badge opens a 360 px `Dialog`;
  - a large preview, and the tabs "Khuôn mặt" and "Ảnh" as a segmented control;
  - the faces tab: a grid of eight candidate faces, "Đổi bộ khác" and "Mặc định";
  - the image tab: the existing upload, plus drag and drop and paste;
  - "Xong" closes the dialog.
  Built from our shadcn primitives (`Dialog`, `ToggleGroup` or `Tabs`, `Button`), none of which is modified.
- [ ] Editor: the studio replaces the icon grid; `agent-form.ts` carries `avatarSeed`; `agent-icons.ts` is deleted.
- [ ] i18n: the new labels in natural Vietnamese ("Khuôn mặt", "Ảnh", "Đổi bộ khác", "Mặc định", "Xong"). The icon
  names are removed.
- [ ] Tests:
  - `AgentAvatar` draws the image when there is one, and the same face for the same seed;
  - picking a face in the studio sends its seed when the form is saved;
  - "Mặc định" restores the saved seed;
  - a pasted image goes to the upload.
- [ ] Bundle: compare the production build's agent chunk before and after, and record the numbers here. Lazy-load the
  core if it grows by more than about 30 KB gzipped.

## 3. Verification

- [ ] On zm, throttled: the persona and chat tests, `ModulithArchitectureTest`, `CoreDependencyRulesTest` and
  `OpenApiContractTest`. CI runs the full gates, including `pnpm --dir web check`.
- [ ] Staging:
  - existing agents show faces;
  - a new agent gets one;
  - "Đổi mặt khác" changes it and the change is kept after saving;
  - renaming keeps it;
  - an uploaded image still wins.
  Screenshots of the pinned list, the gallery, the editor and the studio in light and dark, at desktop and phone
  width, with realistic agent names, checked against the [design tokens](../../../guidelines/design-tokens.md).
- [ ] Docs: the chat spec's agent section, the test matrix, the roadmap.
