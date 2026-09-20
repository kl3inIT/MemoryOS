# MEM-113 — Plan

See [design.md](design.md).

- Branch: `phamnhatanh811/mem-113-image-model-catalog`.
- Worktree: `.claude/worktrees/mem-113-image-model-catalog`, based on `origin/main` at `97836eb5` (includes MEM-109).
- One commit per step. Run `./gradlew clean check` or `pnpm check` before committing.

## Step 1 — Backend catalog

- [x] `ImageProvider`: add `defaultEndpoint`, `endpointRequired`, `editModel`, `knownModels()`, and a `KnownModel` record with its invariants (D3, D4, D9). Model names, sizes, and edit support verified against the OpenAI and Cloudflare documentation (`gpt-image-2`, `gpt-image-1.5`, `gpt-image-1` confirmed on `/images/generations` and `/images/edits`).
- [x] `ImageProviderClient` reads the Cloudflare edit model from `ImageProvider.editModel` instead of `CLOUDFLARE_EDIT_MODEL` (D10); keep `ImageProviderClientTest` green.
- [x] `ImageConnectionService.providers(actor)` (requires `MODELS_MANAGE`). `save` rejects an empty endpoint when `endpointRequired`.
- [x] `GET /api/chat/images/providers` + `ImageProviderResponse` DTO. Add the path to `BROWSER_API_PATHS` in `OpenApiContractTest`, then regenerate `openapi.yml` and the hey-api client.
- [x] Tests:
  - catalog invariants and `sizeFor` aspect mapping (done in `ImageProviderTest`);
  - authorization: 403 without `MODELS_MANAGE`, catalog listing for managers (`ImageConnectionServiceTest`);
  - required endpoint (`ImageConnectionServiceTest`);
  - OpenAPI contract (path added to `BROWSER_API_PATHS`; `openapi.yml` and hey-api client regenerated after the `origin/main` merge).

## Step 2 — Administration page

- [x] Route `/admin/image-generation` and a sidebar tab in `app-shell.tsx`, shown with `MODELS_MANAGE`.
- [x] `chat-image-settings.tsx`:
  - in-use provider block with its generation and edit models;
  - provider list (`ProviderCard`);
  - connect dialog: endpoint, key KEEP/REPLACE, generation model from the catalog or "Other model…", fixed edit model shown read-only, connection test;
  - set as default, turn image generation off (noting that editing is disabled too);
  - disconnect with a replacement choice: `select` → reload revision → save with `REMOVE`.
- [x] Logos in `provider-marks.ts`: OpenAI already exists; add a Cloudflare mark. vi/en i18n in `app-translations.ts`.
- [x] Vitest (`chat-image-settings.test.tsx`):
  - card states (disconnected/connected/in-use);
  - saving with KEEP/REPLACE;
  - catalog model versus manual entry;
  - hidden deprecated models;
  - disconnecting the active provider.
- [x] The composer keeps the image toggle row disabled with a notice while `GET /api/chat/images` reports no available connection; the active state shows as a chip that toggles off when clicked.

## Step 3 — `shape` for `generate_image`

- [x] Replace `size` with `shape`, mapped through the catalog `sizes`. Update the `ChatPrompts` guidance; add tool and adapter tests. `edit_image` is unaffected.

## Step 4 — Acceptance and documentation

- [x] Run the API and web app locally: (2026-09-20: owner acceptance.)
  - configure Cloudflare through the UI → test → set as default → generate and edit an image in Chat;
  - disconnect → Chat reports image generation as unavailable.
- [x] Update `docs/specs/chat.md` (Image generation) and `docs/tests/chat.md`. `./gradlew clean check`, `pnpm check`, and CI green.
- [x] After the PR merges: move the increment to `completed/`, reconcile the roadmap, and remove its line from `AGENTS.md`. (2026-09-20.)

## Risks

- The MEM-109 increment is still under `active/`; if its closing docs change `AGENTS.md`, `docs/specs/chat.md`, or `docs/tests/chat.md` while this branch is open, rebase and resolve.
- Regenerating the hey-api client on Windows is manual because the `pnpm dlx` shim is broken.
- The connection test generates a real image and may incur provider charges; the UI must say so.
