# MEM-135 — Kế hoạch

Thứ tự đi từ cái chặn production trước. Mỗi phase là một PR riêng, đều qua `clean check` và CI.

## Phase A — Dựng Qwen3-Embedding-0.6B trên node `serving` (thao tác server, cần đồng ý)

Làm song song với Phase B, vì hai phase không phụ thuộc nhau.

1. **Chuẩn bị máy**: xong ngày 2026-09-23, dùng chung với [MEM-192](../mem-192-ocr-gpu/plan.md). Driver, Docker, NVIDIA Container Toolkit và ufw ghi ở [runbook](../../../runbooks/ci-cd.md#serving-node). Cổng TEI được chain `DOCKER-USER` của MEM-192 giới hạn cho `172.24.244.120`; ufw không lọc được cổng Docker publish.

2. **Dịch vụ embedding**
   * [x] TEI là service `tei` trong `compose.serving.yaml` của repo, dùng chung với PaddleOCR-VL của MEM-192. API key là file `/apps/memoryos-serving/secrets/tei/api-key.txt` sinh tại chỗ, mode 0600; `deploy-serving.sh` từ chối rollout khi thiếu file (2026-09-24).
   * [x] `tei-model-download` tải `Qwen/Qwen3-Embedding-0.6B`, **ghim revision** `97b0c614…`, vào volume `tei-models` một lần rồi thoát ngay ở các lần sau. TEI chạy `HF_HUB_OFFLINE=1`, mount volume chỉ đọc.
   * [x] Container TEI bản CUDA, image ghim digest:
     * `--model-id /models/Qwen3-Embedding-0.6B`, `--served-model-name Qwen/Qwen3-Embedding-0.6B`
     * key đọc từ file, đưa vào biến `API_KEY` (không qua đối số dòng lệnh)
     * `--max-client-batch-size 32`, khớp `embedding-batch-size`
     * `--auto-truncate`
     * healthcheck `/health` có key, và `/v1/embeddings` không key phải bị 401
     * `restart: unless-stopped`
     * `deploy.resources.reservations.devices`: GPU
   * Đã xác minh trên image CPU cùng phiên bản (2026-09-24): `--served-model-name`, `--auto-truncate`, `--max-client-batch-size` có thật, key đọc từ biến `API_KEY`; image CUDA có `sh`, `curl` và chạy bằng root; bảy file tải về đều có ở revision đã ghim. **Chưa xác minh:** TEI nạp model từ thư mục cục bộ với đúng bảy file đó (spike tải qua hub); lần rollout đầu trên `serving` sẽ chứng minh. Không có `--served-model-name` thì `/v1/embeddings` trả đường dẫn thư mục làm tên model và client từ chối.
   * **Còn lại, cần deploy production (thao tác server, cần đồng ý): kiểm tra từ node `application`:**
     * gọi `POST /v1/embeddings` với tên model, nhận về 1024 chiều;
     * sai key thì bị từ chối 401;
     * đo nhanh độ trễ một lô 32 đoạn.
   * **Kiểm tra VRAM:** đo `nvidia-smi` khi chạy, dự kiến dưới 3 GB. Ghi lại để chừa chỗ cho Docling GPU.

3. **Ghi lại:** số đo VRAM và RAM vào bảng ngân sách trong [design MEM-192](../mem-192-ocr-gpu/design.md#ngân-sách-node-serving). Dịch vụ trên `serving` được deploy bằng workflow production, không do người vận hành tự quản: image Docling do repo build nên phải đi qua release.

## Phase B — Bước 1: thế hệ cấu hình và khoá lệch (repo)

1. [x] Migration **V125** (V123, V124 đã có người dùng):
   * `embedding_provider`, với key mã hoá bằng khoá catalog;
   * `search_settings`, với unique có điều kiện cho `PRESENT` và `FUTURE`;
   * `document_search_projection`, backfill từ `documents.search_index_identity` và `searchable_generation`.
2. [x] Khi khởi động, gieo `PRESENT` từ `SearchProperties` hiện tại nếu chưa có. `identity` phải trùng với identity đang dùng, để staging không phải dựng lại. Provider gieo là `Deployment`, credential là tham chiếu `deployment` tới `MEMORYOS_EMBEDDING_API_KEY`, không chép key vào database. Chưa có Tenant vận hành thì dùng thế hệ suy ra từ cấu hình, không lưu, và thử gieo lại sau 30 giây.
3. [x] `ValidatedEmbeddingService` và `OpenSearchIndexService` nhận thế hệ cấu hình (`SearchGenerations`, cache trong process), gồm tiền tố câu hỏi/tài liệu và ngưỡng ngữ nghĩa theo thế hệ. `document_search_projection` được ghi khi sẵn sàng và được đọc cho mọi kiểm tra theo `identity`.
4. [x] Lệch mapping hoặc `_meta` so với thế hệ thì báo lỗi, kèm event log `search.index.generation_mismatch`; kiểm cả lúc khởi động. Index cũ chỉ có `identity` và `model` thì được ghi bổ sung `_meta` một lần.
5. [x] `SearchProperties`: bỏ ràng buộc "có key thì phải HTTPS", theo nguyên tắc endpoint nội bộ của catalog.
6. Compose: truyền cấu hình gieo (`MEMORYOS_EMBEDDING_ENDPOINT`, `MEMORYOS_EMBEDDING_MODEL`, `MEMORYOS_EMBEDDING_DIMENSIONS`) cho api và worker. **Chưa làm:** staging và production đều đã có index OpenAI theo giá trị mặc định trong code, nên gieo từ mặc định là đúng; truyền biến chỉ cần cho một deployment mới muốn gieo model khác. Worker đã nhận khoá catalog (`chat_catalog_encryption_key`) để giải mã key của provider embedding.
7. [x] **Test:** `SearchGenerationsIntegrationTest`, `EmbeddingProviderTest`, `ValidatedEmbeddingServiceTest`, `SearchPropertiesTest`, `OpenSearchRetrievalIntegrationTest`, `SearchIndexWorkIntegrationTest`:
   * gieo đúng identity cũ;
   * lệch cấu hình thì báo lỗi;
   * có tiền tố thì câu hỏi và tài liệu được embed khác nhau;
   * HTTP nội bộ có key được chấp nhận.

## Phase C — Bước 2: dựng lại ở nền và chuyển đổi (repo)

1. [x] Tạo `FUTURE`: kiểm tra kết nối bằng một lần embed thật (ngoài transaction), rồi khoá Tenant, ghi `FUTURE` và tạo index trong cùng một transaction; lỗi ở bước nào cũng không để lại gì. Đã có `FUTURE` thì trả `409`.
2. [x] Worker:
   * mỗi việc trong `search_index_operations` mang index của nó; claim việc của index không còn là `PRESENT`/`FUTURE` thì huỷ (`SEARCH_OBSOLETE`), reconcile mỗi phút huỷ phần còn lại;
   * INDEX của `FUTURE` đọc các đoạn đã lưu (chỉ chia lại từ artifact khi quy ước chunk đổi), không kéo nguồn, không OCR;
   * `DocumentChanged`, đổi quyền Source và ảnh chụp quyền Drive xếp việc cho cả `PRESENT` và `FUTURE`;
   * `memoryos-search-rebuild-v1` mỗi 5 giây nạp thêm việc cho `FUTURE`, tối đa `memoryos.search.rebuild-window` (64) việc đang treo, nên việc của `PRESENT` không phải chờ cả kho; mọi trạng thái nằm trong PostgreSQL nên worker khởi động lại thì làm tiếp;
   * giới hạn đồng thời theo provider: `PRESENT` và `FUTURE` cùng provider dùng chung một bộ permit.
   * chỉ index đang phục vụ mới ghi cột trạng thái trên `documents`; dựng lại không làm tài liệu biến khỏi `PRESENT`, lỗi của `FUTURE` không hiện trên Source.
3. [x] Tiến độ: đếm theo `document_search_projection` của `FUTURE` trên các tài liệu tìm được; việc lỗi đếm riêng; ước lượng thời gian còn lại theo tốc độ từ lúc tạo `FUTURE`; `switchable` khi không còn tài liệu chờ và mọi tài liệu `PRESENT` đang phục vụ đều sẵn sàng ở `FUTURE` (một câu SQL trên `document_search_projection`).
4. [x] Chuyển: chỉ khi `switchable`; một transaction đổi `PRESENT` → `PAST` (giữ 7 ngày), `FUTURE` → `PRESENT`, và cột trạng thái trên `documents` theo index mới. Không có bus sự kiện sẵn trong repo, nên mỗi process đọc lại một "phiên bản" của các thế hệ đang hoạt động (id, trạng thái, revision của provider) tối đa mỗi 5 giây; `_meta` của index phải ghi đúng id thế hệ.
5. [x] Huỷ: huỷ việc đang xếp, xoá index của `FUTURE` ngay, rồi xoá thế hệ; xoá index lỗi thì dọn dẹp hằng giờ làm lại.
6. [x] Hoàn tác trong thời hạn giữ; bị từ chối khi đang có `FUTURE` hoặc hết hạn.
7. [x] Quy ước chunk đổi: lúc khởi động, api hoặc worker (tiến trình nào lên trước) tạo một `FUTURE` tự động cùng provider và model, không cần gọi embed; nó tự chuyển khi đủ. Thay cho log `search.generation.chunk_convention_outdated` của part 1.
8. [x] **Test tích hợp** `SearchRebuildIntegrationTest` (PostgreSQL và OpenSearch Testcontainers, server `/v1/embeddings` giả), 10 test:
   * trong lúc dựng, tìm kiếm vẫn trả kết quả từ `PRESENT` bằng model cũ;
   * process khác thấy thế hệ mới sau khi chuyển mà không cần khởi động lại;
   * worker chết giữa chừng, worker mới làm tiếp cả việc bị bỏ dở;
   * sửa tài liệu trong lúc dựng thì bản mới có ở index mới;
   * sau khi chuyển, truy vấn dùng model mới và tiền tố mới;
   * huỷ xoá index và hàng đợi; hoàn tác trong thời hạn; dọn sau thời hạn có đếm lại; lỗi dọn lặp lại thì bị chặn;
   * quy ước chunk đổi thì tự dựng và tự chuyển; `FUTURE` thứ hai bị `409`; provider từ chối key thì không bắt đầu dựng.

## Phase D — Trang Cấu hình tìm kiếm (web)

**Đã làm (2026-09-23 web, 2026-09-24 backend).** Hợp đồng từng được viết tay vào `openapi.yml` theo bảng "Hợp đồng API" của design để web làm trước; nay controller sinh ra nó (mục 6).

1. **Hợp đồng trong `openapi.yml`:** tag `Search settings`, lỗi `ApiProblem`, mọi thao tác ghi đòi header `X-MemoryOS-CSRF`. `operationId`:
   * `getSearchSettings`, `createSearchFutureGeneration` (201), `cancelSearchFutureGeneration` (204), `switchSearchFutureGeneration`, `restoreSearchPastGeneration`;
   * `listEmbeddingProviders`, `createEmbeddingProvider` (201), `updateEmbeddingProvider`, `deleteEmbeddingProvider` (204), `testEmbeddingProvider`, `listEmbeddingModelPresets`.
   * Chi tiết phải tự chốt: `revision` của `EmbeddingProviderRequest` nằm trong body (khác provider chat, để ở query); `apiKey`, `revision`, cả ba trường `providerId`/`endpoint`/`apiKey` của yêu cầu kiểm tra, `future`, `rebuild`, `activatedAt`, `retainedUntil`, `estimatedSecondsRemaining`, `model`/`dimensions`/`error` của kết quả kiểm tra đều nullable nhưng vẫn `required`. Số đếm là `int64`, số chiều `int32`, ngưỡng `double`.
2. **Trang `/admin/search-settings`** (`web/src/features/search-settings`), mục menu "Cấu hình tìm kiếm" ngay dưới Mô hình, quyền `MODELS_MANAGE`:
   * Đang dùng: model, provider, nhãn Nội bộ/Bên ngoài, số chiều, số tài liệu, thời điểm kích hoạt; nút Đổi model bị khoá khi đang có `FUTURE`.
   * Đang dựng lại: model đích, thanh tiến độ ready/total, số lỗi, thời gian còn lại; Chuyển index chỉ bật khi `switchable`; Hủy có xác nhận. Thế hệ `automatic` hiện nhãn Tự động và không có nút Chuyển.
   * Đổi model: chọn provider và model đã biết (điền sẵn số chiều và tiền tố, sửa được), ngưỡng ngữ nghĩa, kiểm tra kết nối, xác nhận rồi mới `POST` future.
   * Provider embedding: thêm, sửa, xoá; kiểm tra bằng một lần `/v1/embeddings` và hiện model, số chiều, độ trễ trả về. Key không bao giờ hiện lại. Xoá bị 409 thì hiện câu của trang kèm `detail` của server.
   * Index cũ: hạn giữ và Hoàn tác có xác nhận.
   * Poll `GET /api/search/settings` mỗi 5 giây khi có `FUTURE`, dừng khi không còn.
   * 403 từ Tenant không vận hành hiện trạng thái Không có quyền; người không có `MODELS_MANAGE` không gửi request nào.
3. **Dùng lại:** `CatalogDialog`, `DataBoundaryField`/`DataBoundaryTag`, `useModelAction` (thêm tham số mô tả lỗi) và `useProviderTest` (tách thành `useConnectionTest` nhận hàm gọi; `useProviderTest` giữ nguyên hành vi). **Không** dùng `ProviderEditor`: nó gắn với adapter, Group, Persona và API của provider chat. Trình sửa provider embedding viết mới nhưng theo đúng cách giữ key của nó (key trong `ref`, xoá khi `pagehide`).
4. Bản dịch: khoá tiếng Việt, bản tiếng Anh trong `app-translations.ts`; giữ `model`, `provider`, `embedding`, `index`.
5. **Kiểm thử:**
   * `pnpm vitest run src/features/search-settings`: điền sẵn, định dạng tiến độ và thời gian còn lại, nút nào bật ở trạng thái nào, lỗi theo từng thao tác, kết quả kiểm tra.
   * `pnpm exec playwright test tests/e2e/search-settings.spec.ts`: đổi model tạo future, poll tới khi chuyển được rồi chuyển, hủy, thế hệ tự động, hoàn tác, kiểm tra provider, 409 khi xoá, 403, người không có quyền; chạy ở 1440px và 390px. `MEMORYOS_SCREENSHOTS=1` ghi ảnh sáng/tối vào `.tmp/mem-135-screens` để tự soát.
   * `pnpm lint`, `pnpm typecheck`, `pnpm build`, `pnpm check:i18n`, `pnpm format:check` đều qua.
6. [x] `openapi.yml` nay sinh từ `SearchSettingsController` (`OpenApiContractTest` qua) và client web được sinh lại. Hai chỗ khác bản viết tay: `SearchGenerationResponse` thêm `cleanupBlocked` (bắt buộc), và mô tả 409 của switch, restore, sửa provider thành mô tả chung của tag vì springdoc để phản hồi cấp class đè lên method có nhiều `@ApiResponse`. Trang không đổi hành vi vì chỉ rẽ nhánh theo status.
7. [x] Thẻ "Đang dựng lại" gọn lại: `model cũ → model mới`, provider và nhãn dữ liệu bên dưới, Hủy (tertiary, danger) và Chuyển index ở góc phải trên (xuống dòng dưới model trên điện thoại), thanh tiến độ mảnh, một dòng trạng thái màu nhạt; bỏ đoạn "Sẵn sàng chuyển". Index cũ bị chặn xoá hiện nhãn "Không xoá được index". Ảnh 1440/390 sáng/tối trong `.tmp/mem-135-screens`.
8. [x] API: `SearchSettingsApiTest` kiểm quyền (thiếu `MODELS_MANAGE`, Tenant khác, chưa đăng nhập) và mã trạng thái của cả 11 thao tác.

## Phase E — Bước 3: dọn index cũ (repo)

* [x] `PAST` giữ 7 ngày; `memoryos-search-generation-cleanup-v1` mỗi giờ xoá index và pipeline, kiểm lại index đã mất (đếm lại), rồi mới xoá thế hệ cùng các hàng readiness và việc của nó.
* [x] Lỗi xoá được đếm (V126 `cleanup_attempts`); từ lần thứ ba thế hệ bị đánh dấu chặn (`cleanup_blocked_at`), log ERROR `search.generation.cleanup_blocked`, trang quản trị hiện cảnh báo, và vẫn được thử lại.
* [x] Test: hoàn tác trong thời hạn; hết hạn thì index bị xoá và đếm bằng 0; ba lần lỗi thì bị chặn rồi vẫn dọn được.

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
