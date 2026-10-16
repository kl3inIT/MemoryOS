# Plan — OCR providers (MEM-243)

## Pull request 1 — connections, reader and PaddleOCR-VL behind the registry

- [x] V148 `ocr_connection` (`PADDLEOCR_VL`) with the `active` reader flag
- [x] `core` `ai.ocr`: `OcrProvider`, `OcrConnectionService` (providers, list, save, choose the reader, test input, reader per Tenant), `OcrProbe`; audit action `ocr_connection.change`
- [x] `sources`: `OcrReader`, `OcrAdapter`, `OcrAdapterRegistry`, `PaddleOcrVlOcrAdapter`, `OcrReaders`, `OcrConnectionProbe` with its picture and `OcrProbeAutoConfiguration`
- [x] Extractors take the Tenant and read with its reader; `PaddleOcrVlProperties` loses its endpoint
- [x] `api`: `/api/ocr` providers, connections, test, selection; `openapi.yml` and the web client regenerated
- [x] `compose.base.yaml`, the Worker configuration and `production.env.example` lose the endpoint; runbook step to create the connection after the deploy
- [x] Tests: `OcrConnectionServiceTest`, `OcrReadersTest`, `OcrConnectionProbeTest`, `PaddleOcrVlRoutingTest` and the extraction tests on the new signatures
- [x] `docs/specs/ingestion.md`, `docs/tests/ingestion.md`, `ARCHITECTURE.md`, runbooks
- [x] CI; the PaddleOCR-VL connection created and chosen on staging
- [ ] The PaddleOCR-VL connection on production, when this release is promoted

## Pull request 2 — OCR tab

- [x] `features/ocr`: the reader choice (Docling or a connection), connection cards, the connection dialog with address, key when the type needs one, data boundary and test; the model choice joins with pull request 3
- [x] The *OCR* tab on `/admin/ai-providers`; `ocr_connection.change` in the audit labels
- [x] `ocr-settings.test.tsx`; screenshots at 1440 and 390 reviewed

## Pull request 3 — model reader

- [x] V149 `MODEL` connection type naming a model configuration; V150 validates the `OCR` usage flow
- [x] `ModelLeases` out of `ModelResolver`; `OcrPageReader` (one page, the adapter's `ChatModel` and request policy, usage as `OCR`)
- [x] `ModelOcrAdapter` and `ModelOcrReader`: pages as JPEG, four at a time, one block a page; `jbig2-imageio`
- [x] The Worker wires the provider adapters and their clients through `OcrWorkerComponents`
- [x] API and tab: a model connection names a model that reads images
- [x] Tests: `OcrPageReaderTest` (Anthropic wire), `ModelOcrReaderTest`, `OcrConnectionServiceTest`, `OcrConnectionProbeTest`, `ocr-settings.test.tsx`
- [x] Staging: the model connection's test on Claude Haiku, with its usage; a whole scan in the Worker was not run ([verification](verification.md))

## Pull request 4 — AI Hay

- [x] V151 `AIHAY`; `PagePictureOcrReader` shared by the model reader and AI Hay
- [x] `AiHayOcrAdapter` on `OutboundHttp`; `OcrServiceUsage` pricing a page from `ocr/known-services.json`
- [x] The tab offers AI Hay with its public address, a key and the data boundary
- [x] Tests: `AiHayOcrAdapterTest` (loopback server), `ocr-settings.test.tsx`
- [ ] Live test with a key the owner provides: not run, no key was available

## After the merges

- [x] Verification and the move to `completed/`; what staging did not cover is listed in [verification](verification.md)
