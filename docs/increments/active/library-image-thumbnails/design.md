# Library image thumbnails and cacheable artifact bytes

## Problem

The file library shows a generated image in a cell a few hundred pixels wide, but every surface that draws
one — the library rows and cards, the library picker, and the conversation file panel — requested the full
artifact at `GET /api/chat/image-artifacts/{id}/content`. A page of fifty generated images therefore cost
tens of megabytes: the images in a reported library page ran from 430 KB to 740 KB each.

The route also answered `Cache-Control: no-store`, so nothing was kept between requests. Reopening the
library, changing a filter, paging back, or returning to the page in the same session paid the whole
transfer again. The bytes were also streamed through the API rather than read from storage by the browser,
so each page occupied fifty request threads for content that never changes.

## Decision

Two changes, both on the artifact-serving route.

**A derived thumbnail.** `chat_image_artifact` gains `thumbnail_stored_object_id`, `thumbnail_object_key`
and `thumbnail_media_type` (V106). `GET .../content?variant=THUMBNAIL` serves that rendering: a JPEG whose
long side is 512 px, twice the largest cell the library draws, so it stays sharp on a dense display. The
rendering is written the first time it is asked for rather than when an image is generated, which needs no
backfill and leaves images nobody looks at unrendered. Decoding and scaling reuse `ImageEditImages`, so an
untrusted artifact is read under the pixel ceiling and subsampling that edits already apply.

Three cases produce no thumbnail, and the route then serves the artifact itself, which is correct and only
larger: an original at or below 64 KB, where a second stored object would cost more than the transfer it
saves; bytes this build cannot decode, such as a provider's WebP; and a rendering no smaller than its
source.

**Cacheable bytes.** Both renderings answer `Cache-Control: private, max-age=31536000, immutable`. An
artifact's bytes never change under its id — deletion removes the id rather than replacing its content —
and the route authorizes every read, so the owner's own browser may keep them. `private` keeps them out of
any shared cache, because the response is owner-private.

## Why the thumbnail is a stored object

The alternative, rendering on every request, trades transfer for CPU on a route the library calls fifty
times a page. Keeping the rendering makes the second read a plain storage read. The cost is a second object
per image, which the deletion sweep already knows how to release: `chat_file_artifact` has carried a second
object since V73 (the converted PDF of a presentation), and `JdbcChatArtifactCleanupRepository` claims it
as `preview`. The image claim now returns the thumbnail columns in those fields, so nothing in
`ChatArtifactCleanupService` changes and a thumbnail cannot outlive what it was made from.

A thumbnail is derived from bytes the owner is already charged for, so it does not take from their storage
limit, and `size_bytes` — what the library reports and the quota counts — keeps meaning the artifact.

## Concurrency

Two requests can render the same image at once. Both stage an object; `attachThumbnail` records one, under
`thumbnail_object_key IS NULL AND deleted_at IS NULL`. The loser rolls its adopt back and discards the
object it staged, and still serves the rendering it made. The `deleted_at` condition is what stops a
request racing the deletion sweep into leaving bytes behind.

## Excluded

Uploaded images (`source = UPLOAD`) are drawn as icons in the library, so they need no thumbnail; giving
uploads one is separate work. Presigned URLs that would take the API out of the byte path, and re-encoding
generated images to WebP at generation time, are both larger changes and are not in this increment.
