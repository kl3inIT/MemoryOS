# MEM-135 — Kế hoạch

Thứ tự đi từ cái chặn production trước. Mỗi phase là một PR riêng, đều qua `clean check` và CI.

## Phase A — Dựng Qwen3-Embedding-0.6B trên node `serving` (thao tác server, cần đồng ý)

Làm song song với Phase B, vì hai phase không phụ thuộc nhau.

1. **Chuẩn bị máy** (Ubuntu 24.04, RTX 4090, hiện trắng)
   * `sudo ubuntu-drivers install --gpgpu` để cài bản driver server được khuyến nghị, rồi **reboot một lần**. Kiểm tra bằng `nvidia-smi`.
   * Cài Docker Engine và Compose plugin từ repo Docker, giống node `application`.
   * Cài `nvidia-container-toolkit`, chạy `nvidia-ctk runtime configure --runtime=docker`, khởi động lại Docker. Kiểm tra bằng `docker run --rm --gpus all <cuda-base> nvidia-smi`.
   * `ufw`: chặn mặc định, chỉ mở SSH và cổng TEI cho địa chỉ riêng của node `application`. Docker tự ghi iptables, nên còn phải bind cổng vào **địa chỉ riêng**, không bind `0.0.0.0`.

2. **Dịch vụ embedding**
   * Thư mục `/apps/memoryos-serving/`, gồm `compose.yaml` và `secrets/embedding-api-key.txt` (sinh ngẫu nhiên, 0600).
   * Tải trước `Qwen/Qwen3-Embedding-0.6B`, **ghim revision**, vào volume `/apps/memoryos-serving/models`. Container chạy `HF_HUB_OFFLINE=1`.
   * Container TEI bản CUDA, image ghim digest:
     * `--model-id /models/Qwen3-Embedding-0.6B`
     * `--api-key` đọc từ file
     * `--max-client-batch-size 32`, khớp `embedding-batch-size`
     * `--auto-truncate`
     * healthcheck `/health`
     * `restart: unless-stopped`
     * `deploy.resources.reservations.devices`: GPU
   * **Kiểm tra từ node `application`:**
     * gọi `POST /v1/embeddings` với tên model, nhận về 1024 chiều;
     * sai key thì bị từ chối 401;
     * đo nhanh độ trễ một lô 32 đoạn.
   * **Kiểm tra VRAM:** đo `nvidia-smi` khi chạy, dự kiến dưới 3 GB. Ghi lại để chừa chỗ cho Docling GPU.

3. **Ghi lại:** thêm mục `serving` vào runbook CI/CD. Node này do người vận hành quản lý, không thuộc release.

## Phase B — Bước 1: thế hệ cấu hình và khoá lệch (repo)

1. Migration V123:
   * `embedding_provider`, với key mã hoá bằng khoá catalog;
   * `search_settings`, với unique có điều kiện cho `PRESENT` và `FUTURE`;
   * `document_search_projection`, backfill từ `documents.search_index_identity` và `searchable_generation`.
2. Khi khởi động, gieo `PRESENT` từ `SearchProperties` hiện tại nếu chưa có. `identity` phải trùng với identity đang dùng, để staging không phải dựng lại.
3. `ValidatedEmbeddingService` và `OpenSearchIndexService` nhận thế hệ cấu hình, gồm tiền tố câu hỏi/tài liệu và ngưỡng ngữ nghĩa theo thế hệ.
4. Lệch mapping hoặc `_meta` so với thế hệ thì báo lỗi, kèm event log rõ ràng.
5. `SearchProperties`: bỏ ràng buộc "có key thì phải HTTPS", theo nguyên tắc endpoint nội bộ của catalog.
6. Compose: truyền cấu hình gieo (`MEMORYOS_EMBEDDING_ENDPOINT`, `MEMORYOS_EMBEDDING_MODEL`, `MEMORYOS_EMBEDDING_DIMENSIONS`) cho api và worker.
7. **Test:**
   * gieo đúng identity cũ;
   * lệch cấu hình thì báo lỗi;
   * có tiền tố thì câu hỏi và tài liệu được embed khác nhau;
   * HTTP nội bộ có key được chấp nhận.

## Phase C — Bước 2: dựng lại ở nền và chuyển đổi (repo)

1. Tạo `FUTURE`: khoá hàng `PRESENT`, kiểm tra kết nối bằng một lần embed thật, rồi ghi `FUTURE` và tạo index trong cùng một bước. Đã có `FUTURE` thì trả `409`.
2. Worker:
   * reconcile cho mỗi thế hệ đang hoạt động;
   * INDEX của `FUTURE` đọc các đoạn đã lưu;
   * `DocumentChanged` xếp việc cho cả `PRESENT` và `FUTURE`;
   * giới hạn đồng thời theo provider.
3. Tiến độ: đếm `document_search_projection` sẵn sàng theo `identity`, so với `PRESENT`. Việc lỗi được đếm riêng.
4. Chuyển: chỉ khi đủ và không còn việc treo. Đổi trạng thái trong một transaction, rồi phát sự kiện để các process nạp lại thế hệ.
5. Huỷ: xoá `FUTURE` và index của nó, rồi huỷ các việc đang xếp.
6. **Test tích hợp (Testcontainers OpenSearch và PostgreSQL, embedding giả):**
   * trong lúc dựng, tìm kiếm vẫn trả kết quả từ `PRESENT`;
   * dựng xong vẫn còn dữ liệu nếu restart giữa chừng;
   * sửa tài liệu trong lúc dựng thì bản mới có ở cả hai index;
   * sau khi chuyển, truy vấn dùng tiền tố mới.

## Phase D — Trang Cấu hình tìm kiếm (web)

1. Kiểm tra thư viện và component sẵn có trước khi viết mới, theo quy ước dùng lại component. Dùng lại `provider-editor`, `data-boundary` và `provider-test` của `features/models`.
2. Trang `/admin/search-settings`, mục menu dưới Mô hình:
   * thẻ Đang dùng;
   * danh sách provider embedding;
   * luồng đổi model, với model đã biết thì điền sẵn;
   * tiến độ (poll), huỷ, chuyển;
   * index cũ và hoàn tác.
3. Bản dịch tiếng Việt, giữ các thuật ngữ `model`, `provider`, `embedding` bằng tiếng Anh.
4. **Test:** unit cho logic điền sẵn và trạng thái; Playwright cho luồng đổi model với API giả, ở 1440px và 390px, có ảnh chụp tự soát.
5. `pnpm typecheck` và `pnpm build`.

## Phase E — Bước 3: dọn index cũ (repo)

* `PAST` giữ 7 ngày, rồi xoá có đếm lại.
* Lỗi lặp lại thì chuyển sang trạng thái chặn, và trang quản trị cảnh báo.
* Test: hoàn tác trong thời hạn; hết hạn thì đếm bằng 0.

## Phase F — Đưa production sang Qwen3-0.6B

1. Deploy các phase B–D lên staging. Kiểm tra staging giữ nguyên index OpenAI (`PRESENT` được gieo, không dựng lại).
2. Deploy production.
3. Trên trang quản trị production:
   * thêm provider `serving-embedding` (endpoint HTTP nội bộ, key, nhãn **Nội bộ**);
   * chọn `Qwen/Qwen3-Embedding-0.6B`, 1024 chiều;
   * bắt đầu dựng. Production chưa có tài liệu thật, nên dựng xong gần như ngay.
4. Chuyển đổi. Nạp một tài liệu tiếng Việt thử nghiệm, tìm kiếm và kiểm tra kết quả.
5. Xoá secret `model_api_key` khỏi luồng embedding nếu không còn dùng.
6. Chạy benchmark MEM-141 trên câu hỏi tiếng Việt khi đã có tài liệu, để chỉnh ngưỡng ngữ nghĩa và cân nhắc 4B.

## Rủi ro đã biết

* **Kích thước câu hỏi và đoạn văn:** `ValidatedEmbeddingService` ước lượng token bằng tokenizer OpenAI, giới hạn 8191. TEI mặc định cắt đầu vào theo giới hạn của model. Phải xác minh đoạn văn dài nhất của chúng ta không bị cắt âm thầm, và ghi con số lại.
* **Tham số `dimensions`:** cần xác minh TEI chấp nhận `dimensions` trong yêu cầu và trả `model` trùng tên. `ValidatedEmbeddingService` kiểm tra cả hai.
* **Ngưỡng 0.70** chưa được kiểm chứng cho Qwen3. Nếu quá chặt, tìm kiếm ngữ nghĩa trả ít kết quả. Tạm thời phần BM25 bù lại, cho tới khi benchmark chỉnh ngưỡng.
