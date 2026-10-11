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
- [ ] CI; after the deploy, the PaddleOCR-VL connection on staging and production

## Pull request 2 — OCR tab

- [x] `features/ocr`: the reader choice (Docling or a connection), connection cards, the connection dialog with address, key when the type needs one, data boundary and test; the model choice joins with pull request 3
- [x] The *OCR* tab on `/admin/ai-providers`; `ocr_connection.change` in the audit labels
- [x] `ocr-settings.test.tsx`; screenshots at 1440 and 390 reviewed

## Pull request 3 — model reader

- [ ] Page pictures as JPEG within a limit; bounded concurrency
- [ ] The reader calls the chosen model through the `ai` API, with its usage

## Pull request 4 — AI Hay

- [ ] `AiHayOcrAdapter`, `known-services.json` price per page, the `OCR` usage flow
- [ ] Live test with a key the owner provides

## After the merges

- [ ] Staging: a scan read by each reader; verification; move to `completed/`
