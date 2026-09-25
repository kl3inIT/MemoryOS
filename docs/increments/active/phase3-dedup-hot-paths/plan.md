# Plan

- [x] Rename the integration bundle to `sources/` and move its packages to their owners (ADR 0016).
- [x] Search: index ensured once per generation, no alias HEAD per search, row-value cursor, bulk projection maintenance, logged gateway failures.
- [x] Chat send: persona and capabilities once, bulk file reads; bulk branch copy and persona reorder.
- [x] Meeting: one job runner, batched writes, streamed recordings, recording-upload sweep.
  - `shared.LeasedJob` runs the minutes, recording, chat export, library archive and usage-report passes (corrections are a window, not a leased job). Utterances, minutes items, shares and correction proposals are written with `INSERT … SELECT unnest`. `Recording` carries an `AudioSource` and its size and is streamed into the multipart upload. Retiring a recording is one sweep over meetings still holding the upload of a `DONE`/`FAILED` recording (V129); delete retires first. One row/utterance/item/correction column list, one speaker and reader mapper, `SpeakerNames` for key, fallback name and clock.
- [x] Shared helpers: `PdfText`, SHA-256, LIKE-escape; provider-connection helper; voice synthesis on a shared client.
  - `shared.PdfText`, `shared.Sha256`, `shared.LikePattern` in the root package (no named interface to declare). `ai.ProviderConnections` for image, Web and voice. `HttpAudioStream` cancels its own `sendAsync` exchange. Left for the passes that own those areas: SHA-256 in `document` (`DefaultExtractionArtifactService`, `DocumentChunkService`, `StructuredDocumentChunker`), `retrieval` (`OpenSearchIndexService`, `SearchGenerations`), `connector` (`DefaultGoogleDriveSourceService`, `GoogleDriveSelectionTree`, `DefaultSharePointSourceService`) and `api/source` (`GoogleDriveAccountClient`, `GoogleDriveAuthorizationSessionState`).
- [x] One sync engine for Google Drive and SharePoint with Onyx failure semantics, `Retry-After`, the shared error column and one selection repository (V131).
- [x] SharePoint scope once per run, fewer lock queries per item, Google Drive membership from the run.

## Verification

- Meeting and shared helpers (2026-09-25): `:core:test` for `io.memoryos.meeting.*`, `io.memoryos.voice.*`, `io.memoryos.shared.*`, `io.memoryos.usage.report.*`, `io.memoryos.audit.*`, `io.memoryos.library.*`, `io.memoryos.objectstorage.*`, `io.memoryos.iam.invitation.*`, `io.memoryos.iam.group.*`, `io.memoryos.chat.image.*`, `io.memoryos.chat.web.*`, `ChatExportIntegrationTest`, `ChatHistoryServiceTest`, `ModulithArchitectureTest`, `CoreDependencyRulesTest`; `:api:test` `OpenApiContractTest` and the meeting, recording, voice, speaker, minutes, correction, bookmark and Web methods of `ChatSessionApiIntegrationTest`; `:worker:test` `ControlPlaneIntegrationTest`. `openapi.yml` unchanged. The rest pending.
