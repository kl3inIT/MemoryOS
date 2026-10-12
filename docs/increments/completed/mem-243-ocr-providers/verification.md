# Verification — OCR providers (MEM-243)

Pull requests #576 (connections, PaddleOCR-VL behind the registry), #577 (OCR tab), #579 (model reader) and #580 (AI Hay), each merged on green CI on 2026-10-11.

## Automated

| Contract | Test |
| --- | --- |
| Connection rules, the reader choice, model connections | `OcrConnectionServiceTest` (7) |
| The Tenant's reader for each document, kept by revision | `OcrReadersTest` (2) |
| The connection test, one adapter per provider | `OcrConnectionProbeTest` (4) |
| PaddleOCR-VL routing by the stored reader | `PaddleOcrVlRoutingTest` (19), `PaddleOcrVlClientTest` (35) |
| One page read by a model through its provider adapter, usage as `OCR` | `OcrPageReaderTest` (2, loopback Messages API) |
| Page pictures, blocks by page, failure kinds | `ModelOcrReaderTest` (5) |
| AI Hay's request, status mapping, a page counted only when read | `AiHayOcrAdapterTest` (4, loopback server) |
| The OCR tab | `ocr-settings.test.tsx` (6) |
| API and Worker contexts with the new beans | `OpenApiContractTest`, `ControlPlaneIntegrationTest` |

## Staging, 2026-10-11

| Check | Result |
| --- | --- |
| PaddleOCR-VL connection (release `b38978f0`) | Saved with `https://ocr.vadan.app` as the owner test account; *test* answered 204, so the service read the bundled picture; chosen as the reader. Staging reads scans with PaddleOCR-VL again without the removed environment variable |
| Model connection (release `62898dc6`) | *Test* on `claude-haiku-5-5` answered 204 in 2.2 s through the native Anthropic adapter; usage recorded as `OCR`, provider Claude, 289 input and 14 output tokens. The cost is unknown there because that model has no price on staging. The connection is saved and not chosen |

## Not verified

- **A whole scan read by a model in the Worker.** The page reader is proven live by the connection test and the page pipeline by `ModelOcrReaderTest`; no scan was ingested on staging with the model as the reader, since that would send staging's documents to the provider while it is chosen.
- **AI Hay against the service.** No key was available. The request and answer shapes follow BeyondPilot's adapter, which ran against AI Hay on 2026-10-09 (BEY-102).
- **Production.** The PaddleOCR-VL connection must be created and chosen there after the release that removes `MEMORYOS_EXTRACTION_PADDLEOCR_VL_ENDPOINT` is promoted ([runbook](../../../runbooks/docling-extraction.md#paddleocr-vl-for-scans-and-images-mem-192)).

## Found on the way

- The Worker scans selected packages only, so the first pull request failed its context tests until `OcrWorkerComponents` gave it the connection service.
- The app role on staging had a connection limit of 20 against pools of 15 and 10, so the Worker ran on five connections; raised to 40 and tracked in MEM-244.
