# MEM-113 — Image model catalog and image-provider administration

Status: In Progress. Issue: [MEM-113](https://linear.app/memory-os/issue/MEM-113), a sub-issue of MEM-97. Foundation: [MEM-97](../../completed/mem-97-chat-image-generation/design.md) (generation) and [MEM-109](../mem-109-chat-image-editing/design.md) (editing). Plan: [plan.md](plan.md).

## 1. Goal

Model managers (`MODELS_MANAGE`) configure image generation entirely from the UI: choose a model from the catalog, connect a provider, test it, and select the active provider or turn image generation off.

Today this requires calling `/api/chat/images/*` by hand and typing exact model names such as `@cf/black-forest-labs/flux-1-schnell`.

## 2. Current state on main (`97836eb5`)

- **Generation (MEM-97).** `generate_image` calls the active connection's model: OpenAI `POST {base}/images/generations`, Cloudflare `POST {base}/ai/run/{model}` (JSON, base64 JPEG).
- **Editing (MEM-109).** `edit_image` uses the same active connection:
  - OpenAI edits call `POST {base}/images/edits` with the configured `gpt-image-*` model.
  - Cloudflare edits always call `@cf/black-forest-labs/flux-2-klein-4b` (`ImageProviderClient.CLOUDFLARE_EDIT_MODEL`), whatever generation model is configured.
- **Dead fallbacks.** `ImageProviderClient` still carries blank-model fallbacks (`gpt-image-1`, `flux-1-schnell`), although `save` already rejects a blank model.

## 3. Onyx reference (onyx-dot-app/onyx, commit `f9e3de36`)

- The catalog is a static frontend registry (`web/src/views/admin/ImageGenerationPage/constants.ts`). Each entry is one model: stable key, model name, protocol, i18n description, and a `deprecated` flag. The backend only knows three protocols: `openai`, `azure`, and `vertex_ai`.
- Each image configuration owns a hidden `llm_provider` + `model_configuration` that stores its credential. `image_generation_config.is_default` selects one model for the whole tenant. Agents attach only the tool, never an image model.
- The admin page groups models by vendor; each card is disconnected, connected, or in use. Each protocol has its own form, and credentials are tested before saving. Removing the in-use model requires choosing a replacement or "none".

## 4. Decisions

| # | Decision | Rationale |
|---|---|---|
| D1 | Keep `chat_image_connection`: one connection per protocol per tenant; no migration | A tenant uses one provider at a time. Onyx's per-model granularity serves its one-card-per-model UI and adds nothing at runtime |
| D2 | Do not attach image models to `llm_provider`/`model_configuration` | `model_configuration` requires a `tokenizerProfile` and a streaming chat model. [chat-models.md](../../../specs/chat-models.md#credentials-and-provider-extension) and MEM-97 D1/D2 keep image generation out of the chat catalog. Onyx itself has to hide these providers, filter them from every listing, and avoid delete cascades |
| D3 | Declare the catalog in the backend per `ImageProvider`, in the spirit of `ChatProviderAdapter.knownModels()` | One source for the UI, validation, and the adapter; only the backend knows which models an adapter can serve |
| D4 | The catalog lists only generation models the current adapter handles end to end | Workers AI request/response formats differ per model: flux-1-schnell takes JSON and returns base64, while FLUX.2 needs multipart, which MEM-109 implemented for edits only. Listing unsupported models would advertise something that fails. Other models can still be entered manually |
| D5 | Catalog entries carry no free-text descriptions; the UI shows localized capability chips | Backend strings bypass i18n (`ui()` / `app-translations.ts`) |
| D6 | The test endpoint accepts the dialog's unsaved endpoint/model/key like Onyx, and also keeps the bodyless saved-connection probe | Managers test before saving; the transient probe validates like `save` but persists nothing, and a blank key falls back to the stored credential |
| D7 | "Disconnect" saves with `credentialAction: REMOVE`; no delete endpoint | `save` already deselects a connection that loses its key, so no new endpoint is needed ([ADR 0002](../../../decisions/0002-no-speculative-operational-surfaces.md)) |
| D8 | Keep returning only `credentialConfigured` (Onyx returns keys masked to the first and last four characters) | Existing MemoryOS credential policy |
| D9 | Catalog entries declare whether the model also serves edits; a provider may declare a fixed edit model | After MEM-109, Cloudflare generates and edits with different models, while OpenAI uses one model for both. Admins need to see what the active connection will actually do |
| D10 | The Cloudflare edit model moves from `ImageProviderClient.CLOUDFLARE_EDIT_MODEL` into `ImageProvider`; the client reads it from there | One source for the UI and runtime (D3); no behavior change. A configurable edit model per connection would need a schema change and stays out of scope |

## 5. Backend contract

- Each `ImageProvider` declares:
  - `defaultEndpoint`: `https://api.openai.com/v1` for OpenAI; none for Cloudflare.
  - `endpointRequired`: Cloudflare requires its account endpoint.
  - `editModel`: `@cf/black-forest-labs/flux-2-klein-4b` for Cloudflare; none for OpenAI, whose edits use the configured model.
  - `knownModels()`.
- `ImageProvider.KnownModel(modelName, displayName, outputMediaType, sizes, edit, deprecated)`:
  - `edit` means the model itself also serves `edit_image`.
  - Invariants: `modelName` is non-blank, at most 200 characters, and unique within the provider; each `sizes` element has the form `WxH`.
- Initial catalog (re-verify model names and parameters when implementing):
  - `OPENAI_IMAGE`: `gpt-image-1` (PNG; 1024x1024, 1536x1024, 1024x1536; generate and edit). Add `gpt-image-1.5` / `gpt-image-2` only after verifying them on both `/images/generations` (returning `b64_json`) and `/images/edits`.
  - `CLOUDFLARE_WORKERS_AI`: `@cf/black-forest-labs/flux-1-schnell` (JPEG; generate only; edits go through the provider `editModel`).
- `ImageConnectionService.providers(actor)` (requires `MODELS_MANAGE`) is exposed as `GET /api/chat/images/providers` (`listChatImageProviders`). The response lists each provider with its credential requirement, `defaultEndpoint`, `endpointRequired`, `editModel`, and `knownModels`.
- `save` rejects an empty endpoint when `endpointRequired`; today that error only surfaces at generation time. For Cloudflare the dialog asks for the account ID, and `ImageProvider.normalizeEndpoint` expands a bare 32-hex ID into the account endpoint before validation; a pasted full account URL still passes through unchanged.
- `ImageProviderClient` reads the Cloudflare edit model from `ImageProvider.editModel` (D10).

## 6. UI design

Mobbin references:

| Screen | What we take |
|---|---|
| [Retool AI](https://mobbin.com/screens/fb50d9b9-96bc-4f79-ad46-c19672f1552c) | In-use block at the top; provider list with status and "Set up" below |
| [Vapi Integrations](https://mobbin.com/screens/f89613a7-c22c-4482-b0bc-a4827d3b9c69) | Grouping by provider type; Deprecated cards |
| [Mistral Connectors](https://mobbin.com/screens/37547cbc-0809-41c6-b19c-d4df9f2cca59) | Connected / Connect status on the card |
| [Magnific](https://mobbin.com/screens/d0059628-3b84-4d3e-9e93-1a90abc18611) | One row per image model with capability chips (References, resolution, generation time) |
| [Langdock: model selection](https://mobbin.com/screens/4513a553-1cfa-4f66-949e-a7047ba11af3), [deployment setup](https://mobbin.com/screens/2dec84ee-c9ed-4b72-9f23-bad9abd6774d) | Model cards grouped by provider; key + model ID + "Test & continue" step |
| [n8n credential](https://mobbin.com/screens/f862fcdd-7fd3-43dc-b203-69e8542a4972) | "Connection tested successfully" confirmation with Retry |
| [Braintrust](https://mobbin.com/screens/d9dce614-f66b-4ae7-8bc9-5c405e6d4625) | Compact key dialog with only "Test key" and "Save" |
| [Grok models](https://mobbin.com/screens/d038957a-b375-45da-a3c4-3f76dcee7b2f) | Separate group for image generation models, priced per image |

Wireframe (English locale; source strings follow the existing `ui()` convention):

```text
Image generation                                               (PageHeader, Image icon)
Configure the provider Chat uses to generate and edit images.

In use
┌───────────────────────────────────────────────────────────────────────────────┐
│ [CF] Cloudflare Workers AI                  ✓ In use  [Turn off image generation] │
│      Generate: FLUX.1 schnell · Edit: FLUX.2 klein 4B                            │
└───────────────────────────────────────────────────────────────────────────────┘
(no provider selected → notice "Select a provider to enable image generation in Chat.")

Providers
┌───────────────────────────────────────────────────────────────────────────┐
│ [OpenAI] OpenAI Images       gpt-image-1 · generate + edit    ✓ Connected  │
│                                              [Set as default] [Configure]  │
├───────────────────────────────────────────────────────────────────────────┤
│ [CF] Cloudflare Workers AI   flux-1-schnell · edit: klein 4B  ✓ In use     │
│                                                              [Configure]  │
└───────────────────────────────────────────────────────────────────────────┘

Dialog "Cloudflare Workers AI"
  Account ID         [<ACCOUNT_ID>]
                     the 32-character id in the dashboard URL dash.cloudflare.com/<ACCOUNT_ID>
  API key            [••••••]   Key saved; leave blank to keep it
  Generation model
    (•) FLUX.1 schnell      [JPEG] [1024×1024]
    ( ) Other model…        [@cf/...]
  Editing            FLUX.2 klein 4B (fixed for this provider)
  ✓ Connection test succeeded    (the test generates a real image and may incur provider charges)
  [Disconnect]                            [Test connection] [Close] [Save]
```

- Route `/admin/image-generation`; sidebar tab in the "Configuration" group right after Web search (`app-shell.tsx`), gated by `MODELS_MANAGE`.
- Component `features/chat/chat-image-settings.tsx`, modeled on `chat-web-settings.tsx`. Reuse `ProviderCard`, `ProviderLogo`, `StatusBadge`, `SettingsLayout` / `PageHeader`, Radix `Dialog`, `Input`, `Button`, and `presentProblem` / `useProblemMessage`. No new card or dialog components.
- Turning image generation off also disables editing, because both tools use the active connection. The page says so.
- The connection test exercises generation only; editing uses the same endpoint and credential.
- The test probes the values shown in the dialog before anything is saved (D6): it is enabled once the required endpoint, model and a key (typed or already stored) are present, and reports success inline like n8n.
- Disconnecting the active provider opens a confirmation that offers a replacement (connected providers only) or "Turn off image generation". It then calls `select`, reloads the revision, and saves with `REMOVE`.
- `deprecated` models are hidden unless currently configured, in which case they show a "Deprecated" chip.

## 7. `shape` for `generate_image`

Replace the `generate_image` `size` argument with `shape` (square / portrait / landscape) and map it to the model's catalog `sizes`; models without declared `sizes` ignore the shape. MEM-109 has merged, so this step is no longer blocked. `edit_image` keeps its normalized working size (MEM-109 D8) and is unaffected.

## 8. Scope control

Out of scope:
- migrations or a change in connection granularity;
- a configurable edit model per connection;
- new providers;
- reusing the chat provider's key, or seeding a connection from the deployment key;
- a standalone image generation endpoint;
- changes to image editing behavior (MEM-109).
