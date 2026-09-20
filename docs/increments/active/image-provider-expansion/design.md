# Image provider expansion

Status: In Progress. Parent: MEM-97 (generation), [MEM-109](../mem-109-chat-image-editing/design.md) (editing), [MEM-113](../mem-113-image-model-catalog/design.md) (catalog and administration). Plan: [plan.md](plan.md).

## 1. Goal

Before the production rollout, model managers can connect the image providers enterprises actually hold contracts with, pick from a wider model catalog, and manage them from an administration page that reads like the rest of the enterprise console.

MEM-113 §8 kept new providers out of its scope; this increment is where they land.

## 2. Scope

In scope:

- **Google Gemini** (`GOOGLE_GEMINI_IMAGE`): Gemini image models (`generateContent`, generate and edit) and Imagen 4 (`predict`, generate only) through the Gemini API key.
- **Azure OpenAI** (`AZURE_OPENAI_IMAGE`): the OpenAI image protocol on the Azure OpenAI v1 API; the model is the deployment name.
- **OpenAI-compatible** (`OPENAI_COMPATIBLE_IMAGE`): any gateway serving `POST /images/generations` with `b64_json` (xAI, Together, LiteLLM, vLLM, internal gateways).
- **More Cloudflare Workers AI models**: FLUX.2 [klein] 4B/9B and FLUX.2 [dev] for generation (multipart), Leonardo Lucid Origin and Phoenix 1.0, SDXL Lightning.
- A redesigned `/admin/image-generation` page.

Out of scope:

- Vertex AI (service-account OAuth instead of an API key), Amazon Bedrock (SigV4). Both need a credential type the image connection does not have.
- Several connections per protocol, per-connection edit model configuration, migrations beyond widening the provider check.
- Fetching result images by URL (would add an SSRF surface); providers must return inline image bytes.
- Live-provider acceptance for the new providers: no keys are available, so adapters are verified against documented request/response shapes with HTTP mocks. Live acceptance stays open (§7).

## 3. Decisions

| # | Decision | Rationale |
|---|---|---|
| D1 | Keep one `chat_image_connection` per protocol per Tenant; add enum values and widen the check constraint only | MEM-113 D1 still holds; one active provider per Tenant |
| D2 | Azure uses the v1 API (`{resource}/openai/v1/images/*`, `api-key` header, deployment name as `model`) | Same wire format as OpenAI, so it reuses the OpenAI adapter and needs no `api-version` or deployment field in the schema |
| D3 | A bare Azure resource name or resource URL expands to `https://{resource}.openai.azure.com/openai/v1` | Mirrors the Cloudflare account ID expansion (MEM-113); a full URL is stored as given |
| D4 | Gemini and Imagen share one provider; the adapter selects `predict` for models named `imagen-*`, `generateContent` otherwise | One Google API key serves both; managers think in "Google", not in endpoints |
| D5 | Edit model rule: a known model with `edit` edits itself; otherwise the provider's fallback `editModel` edits; a provider without a fallback edits with the configured model | Generalizes MEM-113 D9. Cloudflare keeps editing FLUX.1 schnell output with FLUX.2 klein 9B, but a configured FLUX.2 model now edits with itself. Imagen falls back to Gemini 2.5 Flash Image |
| D6 | OpenAI-compatible requests set `response_format: b64_json`; URL-only responses fail | Gateways default to URLs; the server never fetches a provider-chosen URL |
| D7 | Cloudflare responses are sniffed: raw image bytes are accepted as well as `{result: {image}}` JSON | Phoenix and SDXL Lightning return a binary image; FLUX and Lucid return base64 JSON |
| D8 | `shape` maps to Gemini/Imagen `aspectRatio` (1:1, 16:9, 9:16) and to declared `WxH` sizes elsewhere | Google models take an aspect ratio, not pixel sizes |
| D9 | Model names are restricted to `[A-Za-z0-9@._:/+-]` without `..` | Gemini, Cloudflare and Azure place the model in the request path |
| D10 | The stored media type follows the returned bytes for every provider | Providers change output formats per model |

## 4. Protocols

| Provider | Generate | Edit | Auth |
|---|---|---|---|
| OpenAI | `POST {base}/images/generations` | `POST {base}/images/edits` (multipart, `input_fidelity=high` for gpt-image) | `Authorization: Bearer` |
| Azure OpenAI | same paths on `{resource}/openai/v1` | same | `api-key` |
| OpenAI-compatible | same, `response_format=b64_json` | same, without `input_fidelity` | `Authorization: Bearer` |
| Google Gemini | `POST {base}/models/{model}:generateContent` with `responseModalities [TEXT, IMAGE]` and `imageConfig.aspectRatio`; Imagen: `POST {base}/models/{model}:predict` | `generateContent` with the working PNG as `inlineData` | `x-goog-api-key` |
| Cloudflare Workers AI | FLUX.2: multipart `prompt,width,height` on `/ai/run/{model}`; others JSON | multipart FLUX.2 with `input_image_0` | `Authorization: Bearer` |

## 5. Catalog

- OpenAI: unchanged (`gpt-image-2`, `gpt-image-1.5`, `gpt-image-1`).
- Azure OpenAI: `gpt-image-1.5`, `gpt-image-1`, `gpt-image-1-mini`, all generate+edit, OpenAI sizes. The deployment name may differ; "Other model…" covers it.
- Google Gemini: `gemini-3-pro-image-preview`, `gemini-2.5-flash-image` (generate+edit); `imagen-4.0-ultra-generate-001`, `imagen-4.0-generate-001`, `imagen-4.0-fast-generate-001` (generate only). Fallback edit model `gemini-2.5-flash-image`.
- Cloudflare Workers AI: FLUX.2 klein 9B, klein 4B, dev (generate+edit, 1024×1024 / 1280×768 / 768×1280), Lucid Origin, Phoenix 1.0, SDXL Lightning (generate), FLUX.1 schnell (generate, no sizes). Fallback edit model FLUX.2 klein 9B.
- OpenAI-compatible: no catalog; the model is typed.

## 6. UI

Mobbin references:

| Screen | What we take |
|---|---|
| [Braintrust AI providers](https://mobbin.com/screens/e4d603f8-78bc-439b-ad77-721850970ead) | Dense provider table: logo, name, status column, row action |
| [Adaline Providers](https://mobbin.com/screens/46113df2-db45-4deb-b260-0b0e565ebe45) | "Custom" provider as the last row |
| [Retool AI](https://mobbin.com/screens/fb50d9b9-96bc-4f79-ad46-c19672f1552c) | Default block on top, providers below |
| [Twenty AI](https://mobbin.com/screens/1d6ceb31-c5ac-47da-9822-f81bbac13093) | Default model summarized as a single row with its purpose |
| [Cloudflare AI Gateway](https://mobbin.com/screens/684f9c00-a417-4908-a217-12ccc5f8cf64) | Configuration in a right-hand sheet that keeps the list visible |
| [Google AI Studio model selection](https://mobbin.com/screens/7fd4e4c8-3e16-44ab-8897-17c84a9d857c) | Model list with capability chips inside the side panel |

Layout:

- **Default** block: active provider, generation model, edit model, "Change" (focuses the provider list) and "Turn off image generation". When off, an empty state explains how to enable it.
- **Providers** grouped as *Model vendors* (OpenAI, Google Gemini), *Cloud platforms* (Azure OpenAI, Cloudflare Workers AI), *Custom* (OpenAI-compatible). Each row shows logo, name, the configured model or a one-line description, a status badge (In use / Connected / Not connected) and actions (Set as default, Configure/Connect).
- **Configure sheet** (existing `Sheet`, right side): endpoint field labelled per provider (Account ID, Azure resource, Base URL) with help text, API key, model list with capability chips (format, sizes/aspect ratios, edit support), "Other model…", computed edit behaviour, test connection with inline result, footer with Disconnect / Save.
- Existing behaviours stay: deprecated models hidden unless configured, test-before-save, disconnect with replacement.

## 7. Verification

- Unit: catalog invariants, endpoint normalization, model-name validation, edit model rule, per-provider request shape (URL, headers, body), response decoding, error redaction.
- Web: provider grouping, sheet flows, edit model summary.
- `./gradlew clean check`, `pnpm check`.
- Open: live acceptance with Gemini, Azure OpenAI and an OpenAI-compatible gateway key; Cloudflare FLUX.2 generation and Phoenix/SDXL binary responses on a live account.
