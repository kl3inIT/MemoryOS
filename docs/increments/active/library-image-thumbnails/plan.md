# Plan

## Steps

1. **Migration V106** — thumbnail columns on `chat_image_artifact`, with a check constraint keeping the
   three either all set or all absent. Done.
2. **`ImageThumbnails`** — render a 512 px JPEG at quality 0.8, reusing `ImageEditImages.decode` and
   `scale`; answer empty for a source under 64 KB, one this build cannot decode, and a rendering no smaller
   than its source. Done.
3. **Repository** — `owned` returns the thumbnail alongside the artifact; `attachThumbnail` records one
   exactly once; the cleanup claim returns the thumbnail columns as the second object. Done.
4. **`ImageArtifactService.open(actor, id, variant)`** — serve a recorded thumbnail, else render, record and
   serve one, else the artifact. `Served` now carries either stored content or rendered bytes. Done.
5. **Route** — `?variant=` on the content route, and `private, max-age=31536000, immutable` on both
   renderings; `openapi.yml` and the generated SDK refreshed. Done.
6. **Web** — `imageArtifactUrl(id, "thumbnail")` in the library rows and cards, the library picker and the
   conversation file panel; previews and the answer's own images keep the original. Done.
7. **Documentation** — [chat spec](../../../specs/chat.md) and [chat verification matrix](../../../tests/chat.md). Done.

## Verification

| What | Where |
| --- | --- |
| Rendering bounds, the byte floor, and sources with no thumbnail to make | `ImageThumbnailsTest` |
| First request writes one rendering, later reads reuse it, the original is untouched, and deletion releases both objects | `ChatArtifactCleanupIntegrationTest.theFirstThumbnailRequestWritesOneRenderingThatLaterReadsReuse` |
| An artifact with no thumbnail to make is served whole and records nothing | `ChatArtifactCleanupIntegrationTest.anImageWithNoThumbnailToMakeIsServedWhole` |
| Both renderings carry the owner-private cache header, an unknown variant is refused, and the route stays owner-only | `ChatSessionApiIntegrationTest.aGeneratedImageIsCopiedIntoOneReusableUploadThatOutlivesIt` |

Not verified here: the transfer saving against a live library page, which needs a browser against real
generated images.

## Open

- Measure a real `/library` page before and after, and record the numbers in this increment.
- Uploaded images still draw as icons; a thumbnail for them is separate work.
