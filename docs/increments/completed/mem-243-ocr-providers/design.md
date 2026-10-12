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
| `AIHAY` connection | Set by the administrator, `EXTERNAL` by default | One page picture as JPEG or PNG, at most 1.4 MB | The page's Markdown, one block a page | None |
| `MODEL` connection: a catalog model that reads images | Its provider's | One page picture, JPEG at 150 DPI | The page's text, tables as Markdown, one block a page without positions | None |

A reader's failure fails the document's extraction with the typed failure it has today (`UNAVAILABLE` retried as in MEM-234, `MALFORMED`, `WRITE_LIMIT`), never falls back to another reader whose misread amounts would be indexed as right.

## Design

### Where things live

| Part | Module | Why |
| --- | --- | --- |
| OCR connections, the reader choice, their persistence and audit | `core`, package `ai.ocr` (named interface `ocr`) | The `ai` module holds every provider connection: the key sealing, the endpoint policy, the data boundary and `ProviderConnections` |
| OCR adapters (`PaddleOcrVlOcrAdapter`, later `AiHayOcrAdapter`) behind `OcrAdapterRegistry` keyed by `OcrProvider`, and `OcrReaders` | `sources`, `ingestion.extraction` | The extraction pipeline, PDF rendering and the PaddleOCR-VL client already live there, package-private ([ADR 0016](../../../decisions/0016-integration-bundle-named-sources.md)) |
| The connection test | `OcrProbe` declared in `ai.ocr`, implemented in `sources` by `OcrConnectionProbe`, registered by its own `OcrProbeAutoConfiguration` | The API excludes the extraction auto-configurations; it extracts nothing, only the Worker does |
| The model reader | `ModelOcrReader` in `sources` renders and assembles; `OcrPageReader` in `ai.ocr` makes the call | The model, its key, its request policy and its usage stay the `ai` module's |
| HTTP | `api`, `/api/ocr/...` beside the other connection controllers | |
| UI | `web`, `features/ocr`, a tab of `/admin/ai-providers` | |

### Data

`ocr_connection` (V148) follows `chat_image_connection`: one row per provider type and Tenant, `endpoint`, sealed `credential`, `data_boundary`, `revision`, and an `active` flag held unique per Tenant by a partial index; the active row is the reader, and with none Docling reads. A separate reader table was designed first and dropped: it held the same fact as the flag the image, Web and voice connections already use. The model reader is the connection type `MODEL` (V149): its row names a model configuration, with an empty address, and is removed with the model, which returns scans to Docling.

### Extraction

`SourceContentExtractor` and `ChatFileExtractor` take the Tenant. For each document the Worker asks `OcrConnectionService.reader(tenant)` and `OcrReaders` returns the connection's reader, kept while the connection's revision stands, so a change applies to the next document without a restart. A reader a change replaced may still be reading, so it is closed with the process. The PaddleOCR-VL client keeps its per-process concurrency gate, now one per connection; its timeout, concurrency and revision stay deployment settings, since they describe the service and the host, not the choice. The connection test reads with a one-minute timeout.

A reader that takes pictures receives each scanned page rendered by PDFBox as JPEG, its quality lowered until the picture fits the reader's limit. Pages are read with bounded concurrency, and their text is assembled with `DocumentAssembly` as one block a page.

### The model reader

A model is called where the scan is: in the Worker. Two ways were weighed. `ModelCalls`, which the meeting minutes use, needs Embabel's agent platform and Chat's execution configuration, which only the API wires; adding the platform to the Worker measured about 2 s of startup and 30 MiB on staging (2026-10-11), and would have brought Chat's configuration with it. The reader therefore calls the provider adapter's Spring AI `ChatModel` directly, as the adapter wire tests do: `ModelLeases` (extracted from `ModelResolver`, so Chat and OCR lease a client the same way) gives the client, the adapter's request policy clamps the output and refuses a picture a model cannot take, and one call carries the instructions, one page picture, no tools and no thinking. Every provider a model can come from reads a page the same way, the native Anthropic adapter included. The Worker gains the adapters and their clients through `OcrWorkerComponents`, not the agent platform.

What Embabel's guard would have bounded is bounded here by construction: one call a page, 8,192 output tokens a page, 200 pages a document, four pages of a document at the provider at once, and the provider read timeout (`memoryos.extraction.model-ocr.read-timeout`, two minutes). Each page's usage is recorded under the flow `OCR` with its cost from the model's price.

A page is rendered by PDFBox; `jbig2-imageio` is added because black-and-white scans are commonly JBIG2. JPEG 2000 page images are not decoded (its plugin's licence is not compatible), so such a page reads as empty.

### Page-picture readers and AI Hay

The model reader and AI Hay share `PagePictureOcrReader`: it renders, bounds, reads four pages at a time and assembles, and an adapter supplies only how one picture is read and its limits. A picture over the reader's limit is rendered again at half the resolution, and a page still over it is kept as read with no text, as BeyondPilot does, so one oversized page does not fail a document.

`AiHayOcrAdapter` posts `{"image": <base64>}` to `/v1/ocr` under the connection's address with the key as a Bearer token, through `OutboundHttp` (no redirect, a one-minute deadline, a bounded answer). A failure's kind is read from the status: 400 and 413 are a picture the service will not read, a page without text; 429, 5xx and no answer are `CONNECTION_FAILED`, retried; a refused key, a redirect and an answer that is not its JSON are `INTERNAL`. Each page read is recorded under `OCR` as one call priced from `ocr/known-services.json`, kept by hand since LiteLLM's metadata holds token prices only.

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
