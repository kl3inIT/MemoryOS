# Plan

- [ ] Rename the integration bundle to `sources/` and move its packages to their owners (ADR 0016).
- [ ] One sync engine for Google Drive and SharePoint with Onyx failure semantics, `Retry-After`, the shared error column and one selection repository.
- [ ] SharePoint scope once per run, fewer lock queries per item, Google Drive membership from the run.
- [ ] Search: index ensured once per generation, no alias HEAD per search, row-value cursor, bulk projection maintenance, logged gateway failures.
- [ ] Chat send: persona and capabilities once, bulk file reads; bulk branch copy and persona reorder.
- [ ] Meeting: one job runner, batched writes, streamed recordings, recording-upload sweep.
- [ ] Shared helpers: `PdfText`, SHA-256, LIKE-escape; provider-connection helper; voice synthesis on a shared client.

## Verification

Pending.
