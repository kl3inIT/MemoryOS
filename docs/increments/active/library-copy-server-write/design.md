# Library copies use the server-write lifecycle

## Requirement

Owner decision 2026-09-24 (audit item "Hai lifecycle upload/write trong `objectstorage`", best practice even if the data model changes): a file the server copies into a person's library — a generated file or image copied by `POST /api/chat/library/{source}/{id}/copy`, or meeting minutes taken into the library — is written through `ObjectWriteService` (stage outside a transaction, adopt in the transaction that records the file, discard otherwise). The hybrid `ObjectUploadService.write`, which ran a server write through the browser-upload lifecycle, is removed. Browser uploads keep their lifecycle unchanged, and no HTTP contract changes.

The hybrid had two defects beyond duplicating `stage`'s reserve/key code: a storage failure left a `PENDING` upload and `STAGED` object waiting for the abandoned-upload sweep instead of discarding at once, and a failure between verification and the owning transaction left a `VERIFIED` upload to expire.

## Design

A Chat user file references the stored object it *is*, not only the browser upload that produced it.

- V125 adds `chat_user_file.stored_object_id` and makes `upload_id` nullable. `upload_id` stays the browser intent (resume, verify, adopt, retire); `stored_object_id` is the object every reader serves. A row is either a browser upload or a copy: `CHECK (upload_id IS NOT NULL OR copied_from_id IS NOT NULL)`. A partial unique index keeps one file per object.
- `stored_object_id` has no foreign key, like V118's `thumbnail_stored_object_id`: object storage removes `stored_objects` rows in its own sweeps (an abandoned browser upload, a retired upload) without knowing about Chat, and a restricting key would block those sweeps. It is nullable for the same reason: an expired upload has no object.
- A browser upload records its object when it is finalized, in the transaction that adopts the upload. A copy records it on insert, in the transaction that adopts the staged write. The file work clears it when it releases the file (`DELETED`), next to the thumbnail columns. The backfill sets it from `object_uploads.stored_object_id` for every row past upload and not yet released.
- Serving (`raw`) and the file work claim join `stored_objects` on `stored_object_id` directly. `raw` requires `stored_objects.state = 'ACTIVE'` in place of `object_uploads.status = 'ADOPTED'`; both lifecycles activate the object when they adopt it. The expiry of never-finished uploads keeps its `object_uploads` join, because only browser uploads wait in `UPLOADING`.
- `ChatLibraryService.copy` and `publish` stage the bytes outside any transaction, then under the owner lock either adopt and insert the copy, or return the raced copy or nothing. Whatever was not adopted — a race, a deleted artifact, a changed membership, or a failure inside the transaction — is discarded in `finally`, as `ChatFileContentService`, `ImageArtifactService` and the exports do. A storage failure inside `stage` is discarded by `stage` itself and surfaces as `OBJECT_UPLOAD_STORAGE_UNAVAILABLE`, as before.
- Releasing a deleted file keeps retiring a browser upload through `ObjectUploadService.retireAdopted`, whose sweep deletes the bytes. A copy's object is an adopted write, which generic reapers never select, so the file work releases it itself inside its transaction, with the sequence it already uses for the thumbnail: mark delete-pending, delete the key, `releaseAdopted`, remove the metadata. A storage failure rolls the release back and the DELETE work retries.

A copy's object has `input_kind = 'BINARY'`, not `CHAT_FILE`. Copies are bounded at 32 MiB, well inside the binary bound, nothing reads the input kind of a Chat file after admission, and every other server write Chat makes (images, thumbnails, exports) is already `BINARY`.

## Excluded

- Merging the two lifecycles further. Browser uploads need a signed PUT, a resume and a verification claim; server writes do not. The spec keeps them separate on purpose.
- A file sent into a temporary conversation while still `UPLOADING` and then purged has no object to release. That edge predates this change (its retirement was already refused because the upload was not adopted) and is not addressed here.

## Reuse

`ObjectWriteService` (`stage`, `adopt`, `discard`, `releaseAdopted`), `StoredObjectRegistry` and the thumbnail release sequence in `DefaultUserFileWorkService` are reused as they are; nothing new is added to object storage.
