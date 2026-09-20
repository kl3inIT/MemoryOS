# Image provider expansion — Plan

See [design.md](design.md).

- Branch: `phamnhatanh811/image-providers`, based on `origin/main` at `8251bdfe`.
- Run `./gradlew clean check` and `pnpm check` before committing.

## Step 1 — Backend providers

- [x] `ImageProvider`: `AZURE_OPENAI_IMAGE`, `GOOGLE_GEMINI_IMAGE`, `OPENAI_COMPATIBLE_IMAGE`; extended Cloudflare catalog; Azure endpoint expansion (D3); `editModelFor` (D5); model-name validation (D9); empty catalog allowed for OpenAI-compatible.
- [x] `ImageProviderClient`: Azure/compatible on the OpenAI adapter (D2, D6), Gemini `generateContent` and Imagen `predict` (D4, D8), Cloudflare FLUX.2 multipart generation and binary responses (D7), Gemini edits, byte-sniffed media type (D10).
- [x] `ImageConnectionService`: model-name validation on save and probe.
- [x] Migration widening `ck_chat_image_connection_provider`.
- [x] Tests: `ImageProviderTest`, `ImageProviderClientTest`, `ImageConnectionServiceTest`.
- [x] Regenerate `openapi.yml` and the hey-api client.

## Step 2 — Administration page

- [x] Grouped provider list, default block, configure sheet (design §6).
- [x] Azure and Gemini marks; generic mark for OpenAI-compatible.
- [x] vi/en strings; Vitest updates.

## Step 3 — Consolidation

- [x] Update the image sections of `docs/specs/chat.md` and `docs/tests/chat.md`.
- [ ] Live acceptance with Gemini, Azure OpenAI and an OpenAI-compatible gateway key; live Cloudflare FLUX.2 generation and Phoenix/SDXL binary responses (no keys available in this change).
