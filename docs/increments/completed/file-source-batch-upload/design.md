# FILE Source batch upload

## Goal

Create one FILE Source from a selected batch of documents. The Source name, visibility, and Groups apply to every file in the batch.

## Existing boundary

The existing `POST /api/sources/file`, per-file upload authorization, direct object PUT, and finalization APIs already model one Source with many `source_uploads`. No batch endpoint or persistence change is needed.

## Decisions

- The browser accepts multiple supported files in the FILE setup drop zone and file picker. A selection containing an unsupported, empty, or over-100 MiB file is rejected without changing the existing selection.
- Before Source creation, users can remove individual selected files. Selecting one file keeps the current filename-derived default Source name; selecting a first batch leaves the name explicit rather than deriving a misleading collection name.
- The browser creates the Source once, then processes files serially: hash, authorize, direct PUT, finalize. Serial execution bounds browser memory/network work and gives each object a durable server receipt before the next starts.
- A finalized file remains accepted when a later file fails. Retry resumes the current file; it never recreates the Source or repeats earlier finalized uploads. The in-memory selection must remain open for retry; a page reload cannot recover local browser `File` bytes.
- The existing app-level recovery context remains one `sourceId`/`uploadId`/filename receipt. It is sufficient because only the current object can have reached storage without finalization. It never retains bytes or a speculative batch queue.

## Scope

Update FILE setup selection, progress and completion copy; preserve all authorization, direct-upload, finalization and indexing behavior. Update the connector contract and browser coverage for a shared Source, one failed current upload, and retry continuation.

## Non-goals

No backend batch endpoint, concurrent PUTs, persistent browser file blobs, changed per-file size/type policy, or change to uploads started from existing Source detail.
