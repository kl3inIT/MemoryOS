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

`search_settings` gồm: provider, tên model, số chiều, tiền tố câu hỏi và tiền tố tài liệu, ngưỡng ngữ nghĩa, `identity` (tên index, băm như hiện nay có thêm tiền tố) và trạng thái `PRESENT | FUTURE | PAST`.

* Mỗi Tenant có **đúng một** `PRESENT` và **tối đa một** `FUTURE`, giữ bằng unique index có điều kiện, cùng với khoá hàng.
* Deployment này có một Tenant. Index vẫn dùng chung cho mọi Tenant như hiện nay, nên **cấu hình tìm kiếm thuộc về deployment**: chỉ Tenant vận hành (Tenant đầu tiên) sửa được. Đây là giả định cần chốt (xem cuối trang).

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

## Model và nơi chạy

* **Model:** `Qwen/Qwen3-Embedding-0.6B`, 1024 chiều, giấy phép Apache-2.0. Tiền tố câu hỏi: `Instruct: Given a question, retrieve passages that answer it\nQuery: `. Tài liệu không có tiền tố.
* **Dịch vụ:** Hugging Face Text Embeddings Inference (TEI), bản CUDA, ghim theo digest. TEI nhẹ và không giữ trước toàn bộ VRAM như vLLM. Nó có API OpenAI-compatible `/v1/embeddings` và hỗ trợ `--api-key`.
* **Nơi chạy:** GPU RTX 4090 của node `serving`, dùng chung với Docling GPU sau này. Chỉ mở trên mạng riêng tới node `application`.
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
* Docling GPU: dựng cùng node nhưng thuộc MEM-79/MEM-191.

## Điều kiện chấp nhận

* **Lệch cấu hình:** khởi động với cấu hình lệch mapping hoặc `_meta` thì báo lỗi rõ ràng, có test.
* **Đổi model trên bộ tài liệu fixture thật:**
  * trong lúc dựng, tìm kiếm vẫn trả kết quả từ `PRESENT`;
  * restart worker giữa chừng vẫn tiếp tục đúng;
  * tài liệu sửa trong lúc dựng có mặt ở index mới;
  * sau khi chuyển, truy vấn dùng model mới và tiền tố mới.
* **Hoàn tác:** làm được trong thời hạn giữ; hết hạn thì index cũ bị xoá và đếm bằng 0.
* **Production:** dùng Qwen3-Embedding-0.6B qua TEI trên `serving`. Tìm kiếm một tài liệu tiếng Việt thử nghiệm trả đúng tài liệu. Nhãn hiện Nội bộ.

## Cần chốt

* **Ai được sửa:** cấu hình tìm kiếm thuộc về deployment, và chỉ Tenant vận hành sửa được. Đề xuất: `MODELS_MANAGE` của Tenant đầu tiên.
* **Thời hạn giữ index cũ:** đề xuất 7 ngày.
* **Ngưỡng ngữ nghĩa cho Qwen3-0.6B:** tạm dùng 0.70, chỉnh lại sau khi chạy benchmark MEM-141.
