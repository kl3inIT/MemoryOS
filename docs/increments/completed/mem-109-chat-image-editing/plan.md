# MEM-109 — Implementation plan

See [design.md](design.md). Branch `phamnhatanh811/mem-109-chat-image-editing`, merged by [PR #193](https://github.com/kl3inIT/MemoryOS/pull/193) (`97836eb5`).

## Phase 1 — Backend

- ✅ Workers AI probes: official schemas and live klein / SD 1.5 runs (design §3).
- ✅ `V62__chat_image_artifact_lineage.sql`; `JdbcImageArtifactRepository.inSession` and lineage on insert; `ImageArtifactService.sessionImage` and `store(..., lineage)`.
- ✅ `TurnContext.generatedImages` loaded in `ChatTurnPersistence`; `ChatTurnSetup` names earlier images.
- ✅ `ImageHttp.postMultipart`.
- ✅ `ImageEditImages`: bounded PNG/JPEG decoding, at most 1024 px on multiples of 16, inward-feathered masks, compositing that keeps unmasked pixels.
- ✅ `ImageProviderClient.edit` (Cloudflare klein, OpenAI edits); the request timer is tagged `operation`.
- ✅ `EditImageTool`, registration in `ChatModelExecutor`, `ChatPrompts` guidance.
- ✅ Tests: `ImageEditImagesTest`, `ImageProviderClientTest`, `EditImageToolTest`, `ChatTurnSetupTest`, `ChatWebPromptsTest`, `ChatPersistenceIntegrationTest`.
- ✅ Live check of the real Java transport (temporary test, not committed): generate → klein edit → composite, with unmasked pixels identical.

## Phase 2 — Web

- ✅ Edit action on generated images; optional brush-mask dialog with an instruction; `mask-for-<id>.png`; image mode through `ChatImageEditContext`; the thread composer is filled.
- ✅ The activity timeline names `edit_image`; i18n; vitest.

## Phase 3 — Completion

- ✅ `docs/specs/chat.md` and `docs/tests/chat.md`.
- ✅ CI green on PR #193 (`clean check`, frontend-check, four e2e shards, backend-images); merged to main.
- ✅ Increment moved to `completed/`; roadmap updated.
- ✅ Staging verification after deploy: an instruction edit of a generated image, a masked edit (12,642 sampled pixels outside the mask identical to the source, the mask interior recolored) and an edit of an uploaded image all ran through `edit_image`. The dialog is unit-tested; on staging the mask was uploaded through the same chat file API.

## Risks

- Workers AI free tier (10k neurons/day): a 1024 px edit costs a few hundred neurons.
- A mask targets its image through its file name; the server still authorizes both the source and the mask.
- A model without vision cannot see the mask content but still reads its name and id.
