# More image providers

The Image generation tab of AI Providers offered two providers, OpenAI Images and Cloudflare Workers AI. The owner asked for more (2026-10-14). This increment adds four: Google Gemini, OpenRouter, xAI Grok Imagine and fal.ai. The owner then asked for a connection a developer describes field by field, for any image API without its own adapter (2026-10-14): the fifth provider, `CUSTOM_HTTP`.

## Requirement

- A model manager can connect, test, select and disconnect each new provider on the Image generation tab exactly as for the existing two; Chat's `generate_image` and `edit_image` work with whichever is selected.
- Every new provider both generates and edits. Each catalog lists current models, newest first, all of which edit with the connection's model.
- Nothing about connections, credentials, usage records, observations or failures changes: provider errors and credentials never reach the model or the UI.

## Choice of providers

The adapter contract is one synchronous request whose answer carries the image as base64 ([chat spec](../../../specs/chat.md)). Candidates were checked against their documentation and, for OpenRouter and fal.ai, against their published model schemas (2026-10-14):

| Provider | Generation | Edit | Kept |
| --- | --- | --- | --- |
| Google Gemini | `POST /v1/models/{model}:generateContent`, `x-goog-api-key`, image in `candidates[0].content.parts[].inlineData` | Same call, the working image as an `inline_data` part | Yes |
| OpenRouter | `POST /api/v1/images`, `data[0].b64_json` always | `input_references` with a data URI | Yes |
| xAI | `POST /v1/images/generations` with `response_format=b64_json` (URL otherwise) | `POST /v1/images/edits`, JSON with the image as a data URI, not multipart | Yes |
| fal.ai | `POST https://fal.run/{endpoint}` with `sync_mode=true`, image as a data URI in `images[0].url`, `Authorization: Key` | The model's sibling `/edit` endpoint, `image_urls` with a data URI | Yes |
| Stability AI | Sync multipart with `accept: application/json` | Mask and search-and-replace endpoints, not instruction editing | No: `edit_image` sends an instruction, not a mask or a search term |
| Together AI | `response_format=base64` | Whether `image_url` takes a data URI is undocumented | No, until verified |
| Black Forest Labs, Recraft, MiniMax, Hugging Face | Queued with a polling URL; URL or WebP/SVG output; no instruction editing; undocumented raw routing | | No |

Imagen `:predict` is shut down; Gemini's own image models replace it.

## Design

- **Adapters.** `GeminiImageAdapter`, `OpenRouterImageAdapter`, `XaiImageAdapter` and `FalImageAdapter` in `chat.image.adapter`, one per new `ImageProvider` value (`GEMINI_IMAGE`, `OPENROUTER_IMAGE`, `XAI_IMAGE`, `FAL_AI`). Each has a public default endpoint, so none requires one; a manager may still point it at a proxy.
- **Aspect ratios in the catalog.** These four providers choose the pixels from an aspect ratio. A catalog size may now be `W:H` as well as `WxH`; their models declare `1:1`, `16:9` and `9:16`, which the `square`, `landscape` and `portrait` shapes already resolve to by orientation. fal.ai's FLUX and Seedream endpoints take a named size instead (`square_hd`, `landscape_16_9`, `portrait_16_9`); its adapter translates.
- **Gemini thinking.** Gemini 3 image models can return interim thought images before the final one; the adapter keeps the last inline image that is not a thought. A safety block is an answer without an image and fails like any missing image.
- **Inline images only.** `ImageCall` gains `pngDataUri` and `fromDataUri`. A hosted URL in an answer is a failure: MemoryOS never fetches a provider's file host.
- **Migration.** V165 widens `ck_chat_image_connection_provider`.
- **Web.** Names, LobeHub marks (Gemini, xAI, fal; OpenRouter's already exists), documentation sites and endpoint placeholders for the four providers. The tab keeps the stacked `ConnectionCard` list the Web search tab uses for its seven providers; the catalog's aspect ratios show in the model badges as they are.

## Custom HTTP connection

- **Reference.** Open WebUI's ComfyUI engine maps workflow nodes to prompt, size and seed; n8n's HTTP Request node and Home Assistant's RESTful integration build a request from a template and read the answer by path; LibreChat's custom endpoints add parameters to an OpenAI-compatible call. The custom connection takes the template-and-path shape.
- **Template.** `ImageTemplate`: the key's header and prefix, a `generate` step and an optional `edit` step. A step is a path below the endpoint, a JSON body, the RFC 6901 pointer to the image and its format: `BASE64`, `DATA_URI`, or `RAW` when the whole answer is the image. Variables are `{{prompt}}`, `{{model}}`, `{{aspect_ratio}}` and, for an edit, `{{image_data_uri}}` and `{{image_base64}}`. It is stored as JSON in `chat_image_connection.template`, which only `CUSTOM_HTTP` has (V165 checks it).
- **Safety.** Variables are substituted into the parsed body tree, never its text, so a prompt cannot add members; a whole-value variable without a value removes its member. A path is segments below the endpoint (no scheme, host, query, fragment, dot or empty segment) and the request host is re-checked against the endpoint's. The key travels only in the declared header, whose name and prefix cannot carry a line break. A hosted URL in an answer is a failure. The trusted model-manager endpoint policy otherwise applies unchanged.
- **Shapes.** Without a catalog, `square`, `landscape` and `portrait` map to `1:1`, `16:9` and `9:16` for `{{aspect_ratio}}`.
- **OpenAI-compatible APIs** are a preset of the custom connection, not a provider of their own: the web form offers presets (OpenAI-compatible, Together AI, fal.ai, Hugging Face) that fill the template.

## Left out

- Stability AI and Together AI adapters, for the reasons in the table.
- Per-model output format or resolution settings: the provider default resolution applies, and the stored media type follows the returned bytes.
- Price or free-tier notes in the UI: none of the four has a documented free tier for image models.
- For the custom connection: multipart bodies, variables in paths or headers, queued APIs that answer with a polling URL, and importing a cURL command.
