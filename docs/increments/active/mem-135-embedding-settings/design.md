# MEM-135 — Cấu hình embedding trên trang quản trị, dựng lại index ở nền

Linear: [MEM-135](https://linear.app/memory-os/issue/MEM-135). Liên quan: [MEM-171](../mem-171-production-deployment/design.md) (production), [MEM-141](../mem-141-rag-benchmark/design.md) (benchmark), [MEM-134](https://linear.app/memory-os/issue/MEM-134) (cổng dữ liệu ra ngoài), [MEM-66](../mem-66-vllm-cpu-gateway-research/design.md) (model tự chạy, đang tạm dừng).

## Kết quả mong muốn

Người quản trị chọn model embedding trên trang quản trị như chọn model chat. Việc đổi model không cần deploy lại, không gián đoạn tìm kiếm và không phải kéo lại hay OCR lại tài liệu. Production chạy **Qwen3-Embedding-0.6B** tự host trên GPU của node `serving`, thay OpenAI `text-embedding-3-large`, trước khi nạp tài liệu thật.

## Hiện trạng

* Model embedding là cấu hình lúc triển khai (`SearchProperties`, gắn từ `memoryos-search.yaml`): endpoint, key, model, số chiều. Compose **không** truyền endpoint, model hay số chiều vào api/worker, nên production đang chạy mặc định trong code: OpenAI `text-embedding-3-large`, 3072 chiều. Đổi model vì thế cần một bản release.
* `OpenSearchIndexService` băm endpoint, model, số chiều và `DocumentChunk.CONVENTION` thành tên index (`identity`). Đổi cấu hình thì tạo index mới, rỗng. Mapping ghi `_meta.model`, và mapping lệch thì báo `SearchUnavailableException`. Một phần của "bước 1" trong issue vì vậy đã có sẵn.
* Mỗi tài liệu chỉ có một cột trạng thái `documents.search_index_identity`. `SearchProjectionMaintenance.reconcile` đánh dấu mọi tài liệu thuộc index khác là `pending` rồi xếp hàng INDEX lại **từ các đoạn văn đã lưu trong PostgreSQL** (không kéo lại nguồn, không OCR lại). Hệ quả: đổi model hôm nay làm **tài liệu biến khỏi kết quả tìm kiếm** cho tới khi được index lại.
* `SearchProperties` từ chối gửi key qua HTTP. Nguyên tắc đã chấp nhận cho provider chat ([chat-models](../../../specs/chat-models.md#credentials-and-provider-extension)) thì cho phép HTTP nội bộ với quyền quản trị model tin cậy.
* Truy vấn và tài liệu được embed y hệt nhau. Qwen3-Embedding là model nhận chỉ dẫn: câu hỏi cần tiền tố `Instruct: …\nQuery: `, tài liệu thì không. Thiếu tiền tố này, chất lượng giảm.
* Ngưỡng `minimum-semantic-score` 0.70 được chọn cho OpenAI. Model khác có phân bố cosine khác, nên ngưỡng phải đi theo model.

## Tham chiếu Onyx (`.tmp/onyx`)

* `db/models.py::SearchSettings`: mỗi thế hệ cấu hình gồm model, số chiều, `normalize`, `query_prefix`, `passage_prefix`, `index_name`, trạng thái `PAST | PRESENT | FUTURE`, `switchover_type`, `use_port_flow` (tính lại vector từ các đoạn đã có, không chạy lại connector) và trạng thái dọn index cũ.
* `db/models.py::CloudEmbeddingProvider`: nhà cung cấp embedding là bảng **riêng** (`embedding_provider`: `api_url`, `api_key` mã hoá), không dùng chung với nhà cung cấp LLM.
* Trang `/admin/index-settings`: xem model đang dùng, chọn model mới, theo dõi tiến độ dựng lại, chuyển đổi.
* Mặc định Onyx chạy `nomic-ai/nomic-embed-text-v1` trên CPU trong model server của nó (`backend/Dockerfile.model_server`). Model đó chủ yếu cho tiếng Anh, nên không dùng cho MemoryOS.

## Quyết định thiết kế

### Nhà cung cấp embedding là bảng riêng

`embedding_provider` gồm Tenant, tên, endpoint, key mã hoá bằng khoá catalog hiện có, `data_boundary` (`INTERNAL` | `EXTERNAL`, mặc định `EXTERNAL`) và revision. Không dùng chung `llm_provider`, vì hai lẽ:

* Việc kiểm tra kết nối của provider chat gửi yêu cầu chat và gọi tool. Một server chỉ phục vụ embedding (TEI, vLLM chế độ embed) sẽ trượt kiểm tra đó.
* Onyx cũng tách như vậy.

Endpoint theo đúng nguyên tắc của provider chat: cho phép HTTP(S), kể cả host nội bộ; từ chối URL có credential, query hay fragment. `SearchProperties` bỏ ràng buộc "có key thì phải HTTPS" theo cùng nguyên tắc đó.

Giao thức duy nhất là **OpenAI-compatible `/v1/embeddings`**. Nó phủ OpenAI, TEI, vLLM, Ollama và LM Studio, nên không cần thêm adapter.

### Một thế hệ cấu hình tìm kiếm là một index

`search_settings` gồm: provider, tên model, số chiều, tiền tố câu hỏi và tiền tố tài liệu, ngưỡng ngữ nghĩa, **quy ước chunk** (`DocumentChunk.CONVENTION` lúc tạo), `identity` và trạng thái `PRESENT | FUTURE | PAST`.

**`identity` là của thế hệ, không phải mã băm của cấu hình** (quyết định 2026-09-23). Tên index là tiền tố cộng id của thế hệ. Hiện nay tên index băm cả endpoint, nên chuyển TEI sang IP hay cổng khác cũng tạo index rỗng và phải dựng lại toàn bộ. Endpoint thuộc về provider và sửa được mà không đổi index. Thứ quyết định vector là model, số chiều, tiền tố tài liệu và quy ước chunk: bốn thứ đó ghi trên thế hệ và trong `_meta` của index, và lệch nhau thì báo lỗi.

**Đổi quy ước chunk tự sinh một `FUTURE`.** Khi release mang `DocumentChunk.CONVENTION` khác với thế hệ `PRESENT`, lúc khởi động hệ thống tạo `FUTURE` cùng model và provider, dựng lại chunk và vector từ artifact đã lưu ở nền, rồi tự chuyển khi đủ (không cần người bấm, vì model không đổi). Tìm kiếm vẫn chạy trên `PRESENT` suốt lúc đó. MEM-192 vừa đổi quy ước sang v3; không có cơ chế này thì staging mất kết quả tìm kiếm cho tới khi index lại xong.

* Mỗi Tenant có **đúng một** `PRESENT` và **tối đa một** `FUTURE`, giữ bằng unique index có điều kiện, cùng với khoá hàng.
* Deployment này có một Tenant. Index vẫn dùng chung cho mọi Tenant như hiện nay, nên **cấu hình tìm kiếm thuộc về deployment**: chỉ Tenant vận hành (Tenant đầu tiên) sửa được. Đã chốt ngày 2026-09-23.

### Trạng thái sẵn sàng theo từng index

Bảng mới `document_search_projection(tenant_id, document_id, index_identity, generation, …)` thay cho việc suy ra từ một cột duy nhất `documents.search_index_identity`. Một tài liệu có thể sẵn sàng ở `PRESENT` và đang dựng ở `FUTURE` cùng lúc.

Tìm kiếm chỉ đọc `PRESENT`. Hàng đợi `search_index_operations` đã mang `index_identity` sẵn, nên worker xếp việc cho cả hai index.

### Dựng lại ở nền

Tạo `FUTURE` thì worker lần lượt tính lại vector cho từng tài liệu đã sẵn sàng ở `PRESENT`:

* đọc từ các đoạn văn đã lưu, theo lô, trong giới hạn đồng thời của provider;
* dùng lại cơ chế reconcile hiện có, nhưng với `identity` của `FUTURE`;
* nếu worker khởi động lại giữa chừng thì tiếp tục từ chỗ dừng.

Tài liệu mới hoặc vừa thay đổi trong lúc dựng được ghi vào **cả hai** index.

### Chuyển đổi và giữ index cũ

* **Chỉ `REINDEX`:** khi `FUTURE` đủ, tức số tài liệu sẵn sàng bằng số của `PRESENT` và không còn việc treo, người quản trị bấm Chuyển. Hệ thống đổi trạng thái trong một transaction: `PRESENT` thành `PAST`, `FUTURE` thành `PRESENT`.
* **Không làm `INSTANT`:** hệ thống chưa có user để cần chuyển tức thì mà chấp nhận thiếu kết quả.
* **Giữ lại:** `PAST` ngừng được đọc nhưng giữ 7 ngày để hoàn tác (đổi ngược về `PRESENT`). Sau đó xoá index, có đếm lại. Lỗi xoá lặp lại thì chuyển sang trạng thái chặn và cảnh báo (bước 3 của issue).

### Khởi động không dựa vào đoán

* Lần đầu chạy migration, thế hệ `PRESENT` được **gieo từ cấu hình triển khai hiện tại**. Nhờ vậy staging giữ nguyên index OpenAI đang có, không phải dựng lại gì.
* Sau đó biến môi trường chỉ dùng để gieo, không còn quyết định model.
* Index nào không khớp mapping và `_meta` của thế hệ của nó thì **báo lỗi rõ ràng**, không lặng lẽ tìm kiếm sai. Đây là bước 1 của issue.

### Trang quản trị

**Vị trí:** mục **Cấu hình tìm kiếm**, dưới nhóm Mô hình. Dùng lại các thành phần sẵn có: `provider-editor`, `data-boundary`, `provider-test` và các nguyên tắc chung của repo về dùng lại component.

**Nội dung trang:**

* Thẻ **Đang dùng**: provider, model, số chiều, nhãn Nội bộ/Bên ngoài, số tài liệu, thời điểm dựng.
* **Nhà cung cấp embedding**: thêm, sửa, xoá; kiểm tra kết nối bằng một lần gọi `/v1/embeddings` thật. Kết quả kiểm tra cho biết số chiều thực trả về.
* **Đổi model**:
  * chọn provider và model, số chiều, tiền tố;
  * với model đã biết (Qwen3-Embedding 0.6B/4B/8B, BGE-M3, OpenAI `text-embedding-3-small/large`, multilingual-e5), tiền tố và số chiều **điền sẵn**;
  * xác nhận rồi mới bắt đầu dựng.
* **Tiến độ dựng**: số tài liệu đã xong trên tổng, số lỗi, ước lượng thời gian còn lại; nút Huỷ, và nút Chuyển khi đủ.
* **Index cũ**: thời hạn còn giữ, nút Hoàn tác.

**Quyền:** `MODELS_MANAGE`, cùng quyền với catalog model, vì cùng là quyền cấu hình endpoint và credential ra ngoài.

### Hợp đồng API

Chốt trước để trang quản trị và backend làm song song (2026-09-23). `openapi.yml` được sinh từ controller và `OpenApiContractTest` bắt mọi chỗ lệch, nên backend phải khớp đúng các tên dưới đây. Tag OpenAPI `Search settings`. Mọi thao tác yêu cầu `MODELS_MANAGE` của Tenant vận hành; Tenant khác nhận 403. Lỗi dùng `ProblemDetail` như các API khác.

| Method | Path | Body | Trả về |
| --- | --- | --- | --- |
| GET | `/api/search/settings` | | `SearchSettingsResponse` |
| POST | `/api/search/settings/future` | `SearchGenerationRequest` | `SearchGenerationResponse`; 409 khi đã có `FUTURE` |
| DELETE | `/api/search/settings/future` | | 204; huỷ dựng lại, xoá index của `FUTURE` |
| POST | `/api/search/settings/future/switch` | | `SearchSettingsResponse`; 409 khi chưa đủ |
| POST | `/api/search/settings/past/{generationId}/restore` | | `SearchSettingsResponse`; 409 khi hết hạn giữ hoặc đang có `FUTURE` |
| GET | `/api/search/embedding-providers` | | `EmbeddingProviderResponse[]` |
| POST | `/api/search/embedding-providers` | `EmbeddingProviderRequest` | `EmbeddingProviderResponse` |
| PUT | `/api/search/embedding-providers/{providerId}` | `EmbeddingProviderRequest` | `EmbeddingProviderResponse`; 409 khi `revision` cũ |
| DELETE | `/api/search/embedding-providers/{providerId}` | | 204; 409 khi thế hệ nào còn dùng |
| POST | `/api/search/embedding-providers/test` | `EmbeddingProviderTestRequest` | `EmbeddingProviderTestResponse` |
| GET | `/api/search/embedding-models` | | `EmbeddingModelPresetResponse[]` |

Schema:

* `SearchSettingsResponse`: `present` (`SearchGenerationResponse`), `future` (`SearchGenerationResponse` hoặc `null`), `past` (`SearchGenerationResponse[]`, chỉ những thế hệ còn trong hạn giữ), `rebuild` (`SearchRebuildProgressResponse` hoặc `null`).
* `SearchGenerationResponse`: `id` (uuid), `status` (`PRESENT` | `FUTURE` | `PAST`), `providerId` (uuid), `providerName`, `dataBoundary` (`INTERNAL` | `EXTERNAL`), `model`, `dimensions`, `queryPrefix`, `documentPrefix`, `minimumSemanticScore` (number), `chunkConvention`, `automatic` (boolean: sinh vì đổi quy ước chunk, tự chuyển khi đủ), `documentCount`, `createdAt`, `activatedAt` (hoặc `null`), `retainedUntil` (chỉ `PAST`, hoặc `null`).
* `SearchRebuildProgressResponse`: `ready`, `total`, `failed`, `pending` (số tài liệu), `estimatedSecondsRemaining` (hoặc `null`), `switchable` (boolean).
* `SearchGenerationRequest`: `providerId`, `model`, `dimensions`, `queryPrefix`, `documentPrefix`, `minimumSemanticScore`.
* `EmbeddingProviderResponse`: `id`, `name`, `endpoint`, `dataBoundary`, `hasApiKey` (key không bao giờ trả về), `revision`, `inUse` (boolean).
* `EmbeddingProviderRequest`: `name`, `endpoint`, `apiKey` (`null` giữ key cũ, chuỗi rỗng xoá key), `dataBoundary`, `revision` (chỉ khi sửa). Đổi endpoint mà `apiKey` là `null` trong khi đang lưu key thì bị từ chối (400): key đã lưu chỉ đi tới endpoint nó được nhập cho; kiểm tra kết nối cũng vậy.
* `EmbeddingProviderTestRequest`: `providerId` hoặc `endpoint` + `apiKey`, cùng `model` và `dimensions` (có thể `null`). Gọi `/v1/embeddings` thật một lần.
* `EmbeddingProviderTestResponse`: `ok`, `model` (tên provider trả về), `dimensions` (số chiều thật), `latencyMs`, `error` (hoặc `null`).
* `EmbeddingModelPresetResponse`: `model`, `label`, `dimensions`, `queryPrefix`, `documentPrefix`, `maxInputTokens`: các model đã biết để điền sẵn (Qwen3-Embedding 0.6B/4B/8B, BGE-M3, OpenAI `text-embedding-3-small/large`, multilingual-e5-large-instruct).

## Model và nơi chạy

* **Model:** `Qwen/Qwen3-Embedding-4B` revision `5cf2132abc99cad020ac570b19d031efec650f2b`, 2560 chiều, giấy phép Apache-2.0, đổi từ 0.6B ngày 2026-09-25 (xem [Chọn model](#chọn-model-2026-09-25)). Generation của MemoryOS để trống tiền tố câu hỏi và tài liệu; preset vẫn điền tiền tố `Instruct: …` như Qwen khuyến nghị.
* **Dịch vụ:** Hugging Face Text Embeddings Inference (TEI) v1.9.4, image cho Ada Lovelace `ghcr.io/huggingface/text-embeddings-inference:89-1.9.4`, ghim theo digest. TEI nhẹ và không giữ trước toàn bộ VRAM như vLLM. Nó có API OpenAI-compatible `/v1/embeddings` và hỗ trợ `--api-key`; key là secret file sinh tại chỗ trên `serving`, cùng cách với các secret khác.
* **Model không nằm trong image.** Một bước bootstrap chạy một lần trong `compose.serving.yaml` tải đúng revision đã ghim vào volume, rồi TEI chạy `HF_HUB_OFFLINE=1` với `depends_on: service_completed_successfully`, theo mẫu `minio-bootstrap`.
* **RAM.** Hai service PaddleOCR-VL giới hạn 7 GiB và 4 GiB trên node 15 GiB nhưng lúc chạy dùng khoảng 7 GiB cộng lại; TEI giới hạn 6 GiB theo số đo của 4B (4,6 GiB sau khi nạp, đỉnh 5,9 GiB lúc nạp).
* **Spike 2026-09-23** trên `serving`, image `89-1.9.4@sha256:1a284d9ca1adcc20b78c261d4d052c06057f0a3cb49a15c5d2c00930f710fce2`, model `Qwen/Qwen3-Embedding-0.6B` revision `97b0c614be4d77ee51c0cef4e5f07c00f9eb65b3` (Apache-2.0), chạy FlashQwen3 trên CUDA:
  * `--api-key`: thiếu key và sai key đều nhận 401.
  * `/v1/embeddings` trả `model` đúng tên đã gửi và 1024 chiều; `dimensions: 512` trả 512 chiều.
  * Đầu vào 20 000 từ với `--auto-truncate` bị cắt ở 16 384 token và vẫn trả vector; chunk của MemoryOS tối đa 768 token nên không chạm giới hạn này.
  * Lô 32 đoạn khoảng 700 từ: 0,38 giây.
  * VRAM 1,6 GB, RAM khoảng 1 GB. Giới hạn container 3 GiB là đủ; cùng PaddleOCR-VL (khoảng 9,7 GB) node còn khoảng 13 GB VRAM cho MEM-193.
  * Tải model lần đầu và nạp lên GPU mất khoảng 190 giây.
* **So sánh TEI và vLLM, 2026-09-24** trên `serving`, cùng model và revision, PaddleOCR-VL đang chạy:

  | | TEI 1.9.4 | vLLM 0.30.0 `--runner pooling` |
  | --- | --- | --- |
  | Nạp tài liệu, 1 luồng gửi (lô 32 đoạn khoảng 700 từ) | 92 chunk/s | 174 chunk/s |
  | Nạp tài liệu, 4 luồng gửi | 96 chunk/s | 268 chunk/s |
  | Câu hỏi p50 / p95 | 8,7 / 11,1 ms | 19,9 / 23,8 ms |
  | RAM | 1,1 GB | 3,8 GB |
  | VRAM | khoảng 1,7 GB (đỉnh 2,4) | khoảng 3 GB, giữ trước ở 0,12 |
  | Thời gian khởi động | 183 s | 213 s |

  **Chọn TEI**: độ trễ câu hỏi thấp hơn một nửa và RAM ít hơn ba lần trên node 15 GiB đã có hai service PaddleOCR-VL. Tốc độ nạp của TEI thấp hơn nhưng vẫn đủ: chỉ dựng lại index mới cần tốc độ đó, và nó chạy ở nền. Cả hai đều nói `/v1/embeddings`, nên đổi sang vLLM sau này chỉ là đổi endpoint của provider.
* **Nơi chạy:** GPU RTX 4090 của node `serving`, dùng chung với OCR của [MEM-192](../mem-192-ocr-gpu/design.md) qua một file `compose.serving.yaml`. Chỉ mở trên mạng riêng tới node `application`.
* **Lên 4B sau này:** đổi trên trang quản trị, cùng TEI; không cần release.

## Phạm vi

1. Migration `embedding_provider`, `search_settings`, `document_search_projection`, và gieo `PRESENT` từ cấu hình hiện tại.
2. `ValidatedEmbeddingService` theo từng thế hệ, gồm tiền tố câu hỏi/tài liệu và kiểm tra model/số chiều trả về. `OpenSearchIndexService` nhận thế hệ thay vì `SearchProperties`.
3. Worker dựng `FUTURE` ở nền, ghi song song, tiếp tục được sau khi restart.
4. API quản trị: provider, thế hệ, tiến độ, huỷ, chuyển, hoàn tác.
5. Trang Cấu hình tìm kiếm, bản dịch tiếng Việt.
6. Dọn `PAST` sau thời hạn giữ.
7. Compose truyền cấu hình gieo, bỏ ràng buộc HTTPS cho key. Runbook dựng TEI trên `serving`.
8. Cập nhật `docs/specs/search.md` và `docs/tests`.

## Ngoài phạm vi

* Chọn chunking hay reranker.
* Chuyển tức thì (`INSTANT`).
* Một model riêng cho từng Tenant.
* Cổng dữ liệu ra ngoài (MEM-134): nhãn Nội bộ/Bên ngoài chỉ để hiển thị.
* Docling GPU và OCR: dựng cùng node nhưng thuộc [MEM-192](../mem-192-ocr-gpu/design.md).

## Điều kiện chấp nhận

* **Lệch cấu hình:** khởi động với cấu hình lệch mapping hoặc `_meta` thì báo lỗi rõ ràng, có test.
* **Đổi model trên bộ tài liệu fixture thật:**
  * trong lúc dựng, tìm kiếm vẫn trả kết quả từ `PRESENT`;
  * restart worker giữa chừng vẫn tiếp tục đúng;
  * tài liệu sửa trong lúc dựng có mặt ở index mới;
  * sau khi chuyển, truy vấn dùng model mới và tiền tố mới.
* **Hoàn tác:** làm được trong thời hạn giữ; hết hạn thì index cũ bị xoá và đếm bằng 0.
* **Production:** dùng Qwen3-Embedding-4B qua TEI trên `serving`. Tìm kiếm một tài liệu tiếng Việt thử nghiệm trả đúng tài liệu. Nhãn hiện Nội bộ.

## Chọn model (2026-09-25)

So vector trực tiếp trên 5.257 chunk của staging, 87 câu MEM-141 chấm theo tài liệu và 10 câu probe chấm theo đoạn văn:

| Model / tiền tố câu hỏi | recall@5 tài liệu | nDCG@5 | recall@5 đoạn văn | MRR đoạn văn |
| --- | --- | --- | --- | --- |
| Qwen3-Embedding-4B, không tiền tố | 0,966 | 0,826 | 1,0 | 0,737 |
| BGE-M3 | 0,994 | 0,841 | 0,6 | 0,483 |
| multilingual-e5-large-instruct | 0,966 | 0,851 | 0,6 | 0,414 |
| Qwen3-Embedding-0.6B, không tiền tố | 0,960 | 0,827 | 0,5 | 0,410 |
| OpenAI `text-embedding-3-large` | 0,902 | 0,742 | 0,6 | 0,481 |
| AITeamVN/Vietnamese_Embedding | 0,931 | 0,715 | 0,4 | 0,333 |
| Qwen3 0.6B/4B có tiền tố `Instruct: …` | 0,84–0,94 | 0,74–0,79 | 0,3–0,4 | 0,12–0,30 |

* Với 11 tài liệu, chấm theo tài liệu gần chạm trần; chấm theo đoạn văn phân biệt được model nhưng mới có 10 câu. Benchmark qua API sau khi chuyển staging là bằng chứng quyết định.
* Tiền tố câu hỏi của Qwen, tiếng Anh hay tiếng Việt, làm kết quả kém hơn trên kho này, nên generation của MemoryOS để trống.
* multilingual-e5 chỉ nhận 512 token, ngắn hơn chunk 768 token, nên bị loại.
* Ngưỡng 0,70 (cosine ≥ 0,4) không cắt kết quả của Qwen3-4B: trung vị cosine top-1 là 0,698 và cả top-20 đều trên 0,4.
* **Tài nguyên trên `serving`:** 4,6 GiB RAM sau khi nạp, đỉnh 5,9 GiB lúc nạp; khoảng 8,4 GB VRAM, cả GPU 18,1 GB cùng PaddleOCR-VL. MEM-193 còn khoảng 6 GB VRAM thay vì 13 GB.
* Trọng số 4B chia hai shard, nên `tei/download-model.sh` đọc `model.safetensors.index.json` khi có.

## Sai khác khi làm part 2 (2026-09-24)

* **Tài liệu được dựng lại:** mọi tài liệu tìm được (eligible, đã trích xuất, Tenant đang hoạt động), không chỉ tài liệu đã sẵn sàng ở `PRESENT`. `switchable` là "không còn tài liệu chờ và mọi tài liệu `PRESENT` đang phục vụ đều sẵn sàng ở `FUTURE`", nên một tài liệu lỗi ở cả hai index không chặn việc chuyển. So số lượng thì không đủ: một tài liệu lỗi ở `FUTURE` có thể bị bù bởi một tài liệu chỉ có ở `FUTURE`, và chuyển sẽ làm mất tài liệu đầu khỏi tìm kiếm.
* **Nạp việc theo cửa sổ:** worker nạp tối đa 64 việc đang treo cho `FUTURE` mỗi 5 giây (`memoryos.search.rebuild-window`) thay vì xếp cả kho một lần, để việc của `PRESENT` không chờ sau hàng chục nghìn tài liệu.
* **Báo các process:** repo chưa có bus sự kiện, nên không "phát sự kiện": mỗi process đọc một phiên bản của các thế hệ đang hoạt động (id, trạng thái, revision provider) tối đa mỗi 5 giây. Trong 5 giây đó process khác vẫn đọc index cũ, vẫn còn vì được giữ 7 ngày.
* **Huỷ:** `FUTURE` bị huỷ thành `PAST` đã hết hạn giữ; index bị xoá ngay và thế hệ bị xoá sau khi đếm lại. Nếu xoá lỗi, dọn dẹp hằng giờ làm tiếp. Một việc đang chạy đúng lúc huỷ có thể ghi lại vào index vừa xoá và OpenSearch tự tạo một index không mapping; rủi ro nhỏ, chưa xử lý.
* **Hợp đồng API:** thêm `cleanupBlocked` vào `SearchGenerationResponse`, vì trang phải cảnh báo khi dọn lỗi lặp lại mà hợp đồng chưa có trường nào; `past` gồm cả thế hệ hết hạn nhưng bị chặn. Mô tả 409 của chuyển, hoàn tác và sửa provider là mô tả chung (giới hạn của springdoc); mã lỗi `SEARCH_SETTINGS_*` vẫn phân biệt.
* **Quy ước chunk:** release chỉ có một bộ chia chunk, nên trong lúc `FUTURE` tự động đang dựng, tài liệu đổi được ghi vào `PRESENT` với quy ước mới. Readiness ghi số chunk đã ghi vào từng index để sửa chữa của `PRESENT` không so với chunk mới. `FUTURE` tự động chỉ được tạo lúc khởi động: huỷ nó thì phải chờ lần khởi động sau. Một `FUTURE` do người tạo bằng release cũ vẫn ghi quy ước cũ; sau khi chuyển, lần khởi động sau sẽ dựng lại thêm một lần.
* **Provider không có key:** gửi bearer giữ chỗ `no-key`, vì SDK bắt buộc có key; Ollama, LM Studio, TEI không `--api-key` bỏ qua nó.
* **Lỗi kiểm tra kết nối:** trả câu của chính MemoryOS (key bị từ chối, không tới được, sai model hay số chiều, mã HTTP), không trả thông điệp của provider.

## Đã chốt (2026-09-23)

Chủ sản phẩm chọn làm toàn bộ MEM-135 trong một PR riêng, xếp sau PR của MEM-192. Ba điểm còn treo lấy theo đề xuất:

* **Ai được sửa:** `MODELS_MANAGE` của Tenant vận hành (Tenant đầu tiên); cấu hình tìm kiếm thuộc về deployment.
* **Thời hạn giữ index cũ:** 7 ngày.
* **Ngưỡng ngữ nghĩa cho Qwen3:** 0.70 tạm thời, chỉnh sau benchmark MEM-141.
