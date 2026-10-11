# OCR providers (MEM-243)

Owner decisions 2026-10-11: MemoryOS is a product that customers self-host, so each organization chooses how scans and images are read: Docling's own OCR, an OCR service it hosts, a cloud OCR service, or a language model that reads images. "No PDF leaves the building" is Tasco's requirement and stays the default, not a limit of the product.

## Requirement

- An administrator chooses one **reader** for scans and images on a new *OCR* tab of the AI Provider page: *Docling (built in)*, an OCR connection, or a model that reads images. The choice saves at once and is audited.
- The PaddleOCR-VL endpoint moves from the Worker environment (`MEMORYOS_EXTRACTION_PADDLEOCR_VL_ENDPOINT`) to an OCR connection the administrator creates. The environment variable is removed; there is one source of configuration ([ADR 0002](../../../decisions/0002-no-speculative-operational-surfaces.md)).
- With no reader chosen, Docling reads scans as it does today for an organization without PaddleOCR-VL.
- A reader's data boundary (`INTERNAL` or `EXTERNAL`) is shown where it is chosen. Choosing an external reader is the administrator's explicit act; a per-Source gate is [MEM-134](https://linear.app/memory-os/issue/MEM-134)'s.
- A cloud reader's calls are counted in `ai_usage` under a new flow `OCR`, priced per page from a hand-kept file, since LiteLLM's metadata holds only token-priced models.
- A change of reader applies to documents read after it. Existing documents keep the text they were read with; their `parser_configuration` names the reader, so they can be found and read again.

## Readers

| Reader | Data boundary | Input | Output | Fallback on failure |
| --- | --- | --- | --- | --- |
| Docling (built in) | Internal | The PDF or image | Docling's document | Today's rules |
| `PADDLEOCR_VL` connection | Set by the administrator, `INTERNAL` by default | The whole PDF or image, up to 200 pages | Layout blocks with tables and positions, as today | None: its failure stands (MEM-192) |
| `AIHAY` connection | `EXTERNAL` | One page picture as JPEG, at most 1.4 MB | The page's Markdown | None |
| A model that reads images | Its provider's | One page picture | The page's text, tables as Markdown | None |

A reader's failure fails the document's extraction with the typed failure it has today (`UNAVAILABLE` retried as in MEM-234, `MALFORMED`, `WRITE_LIMIT`), never falls back to another reader whose misread amounts would be indexed as right.

## Design

### Where things live

| Part | Module | Why |
| --- | --- | --- |
| OCR connections, the reader choice, their persistence and audit | `core`, package `ai.ocr` (named interface `ocr`) | The `ai` module holds every provider connection: the key sealing, the endpoint policy, the data boundary and `ProviderConnections` |
| OCR adapters (`PaddleOcrVlOcrAdapter`, later `AiHayOcrAdapter`) behind `OcrAdapterRegistry` keyed by `OcrProvider`, and `OcrReaders` | `sources`, `ingestion.extraction` | The extraction pipeline, PDF rendering and the PaddleOCR-VL client already live there, package-private ([ADR 0016](../../../decisions/0016-integration-bundle-named-sources.md)) |
| The connection test | `OcrProbe` declared in `ai.ocr`, implemented in `sources` by `OcrConnectionProbe`, registered by its own `OcrProbeAutoConfiguration` | The API excludes the extraction auto-configurations; it extracts nothing, only the Worker does |
| The model reader | `sources`, calling the public `ai` API for a vision call | The model, its key and its usage stay the `ai` module's |
| HTTP | `api`, `/api/ocr/...` beside the other connection controllers | |
| UI | `web`, `features/ocr`, a tab of `/admin/ai-providers` | |

### Data

`ocr_connection` (V148) follows `chat_image_connection`: one row per provider type and Tenant, `endpoint`, sealed `credential`, `data_boundary`, `revision`, and an `active` flag held unique per Tenant by a partial index; the active row is the reader, and with none Docling reads. A separate reader table was designed first and dropped: it held the same fact as the flag the image, Web and voice connections already use. The model reader (pull request 3) becomes a connection type whose row names a model configuration.

### Extraction

`SourceContentExtractor` and `ChatFileExtractor` take the Tenant. For each document the Worker asks `OcrConnectionService.reader(tenant)` and `OcrReaders` returns the connection's reader, kept while the connection's revision stands, so a change applies to the next document without a restart. A reader a change replaced may still be reading, so it is closed with the process. The PaddleOCR-VL client keeps its per-process concurrency gate, now one per connection; its timeout, concurrency and revision stay deployment settings, since they describe the service and the host, not the choice. The connection test reads with a one-minute timeout.

A reader that takes pictures receives each scanned page rendered by PDFBox as JPEG, its quality lowered until the picture fits the reader's limit. Pages are read with bounded concurrency, and their text is assembled with `DocumentAssembly` as one block a page.

### Test

*Kiểm tra* sends a small bundled picture with the line "MemoryOS OCR 2026" and answers 204 when the reading holds it, otherwise 503 *OCR service unavailable*, as the image and voice tests do; the failure kind is logged (`ocr.probe.failed`), never the service's own text.

## Pull requests

1. Backend: `ai.ocr` connections and reader, migration, API; the PaddleOCR-VL adapter behind the registry; extraction resolves the reader per Tenant; the environment variable removed.
2. Web: the OCR tab.
3. The model reader.
4. AI Hay, its price per page and the `OCR` usage flow.

## Rollout

After pull request 1 deploys, an administrator creates the PaddleOCR-VL connection on staging and on production and chooses it as the reader ([runbook](../../../runbooks/docling-extraction.md#paddleocr-vl-for-scans-and-images-mem-192)). Until pull request 2 adds the tab, that is two API calls: `PUT /api/ocr/connections/PADDLEOCR_VL` and `PUT /api/ocr/selection`. Scans read in between are read by Docling and are found by their `parser_configuration`.

## Reference

BeyondPilot BEY-102 (`docs/increments/completed/bey-102-document-reading-ocr`): an OCR purpose beside chat providers, an `OcrAdapter` registry, AI Hay's `POST /v1/ocr`, a reader that is a model or an OCR service, and its findings: a small vision model read 16 picture pages as well as AI Hay or better, at about 2,640 input tokens a page; AI Hay refuses a body over 2 MB, so pages go as JPEG; on a picture without text AI Hay returned a description.

## Left out

- Reading existing documents again when the reader changes.
- A per-Source choice of reader, and the external-data gate ([MEM-134](https://linear.app/memory-os/issue/MEM-134)).
- Describing pictures for search ([MEM-197](https://linear.app/memory-os/issue/MEM-197)).
- Other cloud services (Mistral OCR, Google Document AI, Azure Document Intelligence): one adapter each, when a customer needs one.
