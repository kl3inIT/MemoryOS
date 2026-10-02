# Thiết kế — CI song song cho backend

## Vấn đề

CI là điểm nghẽn: PR mất khoảng 10 phút, push lên `main` mất 14–16 phút trước khi staging được deploy. Số liệu lần chạy `main` 37029069637 (2026-10-02):

| Job | Thời gian |
| --- | --- |
| `check` (`./gradlew clean check`, cả bốn module) | 10m08s, trong đó Gradle 8m48s |
| `frontend` shard chậm nhất / nhanh nhất | 7m49s / 6m08s |
| `interpreter` | 5m46s |
| `backend-images` | 5m20s |
| `Publish verified release` | 4m08s |

Thời gian test theo module trong `check`: `core` 685s qua 221 class, `api` 291s, `sources` 209s, `worker` 115s. Cả bốn module chạy trên một runner 4 vCPU, nên `core` (2 JVM) tranh CPU với ba module còn lại và kết thúc cuối cùng. Class chậm nhất là `OpenSearchRetrievalIntegrationTest` (155s) và `SearchRebuildIntegrationTest` (96s), cả hai đều trong `core`.

## Quyết định

1. **Tách `check` thành hai job.**
   * `infrastructure`: actionlint, shellcheck, `docker compose config`, promtool và năm bộ unittest Python của `infrastructure/`. Không cần JVM.
   * `backend`: matrix ba leg, mỗi leg một runner: `core` → `:core:check`; `api` → `:api:check`; `sources-worker` → `:sources:check :worker:check`. Ba leg gộp lại phủ đúng `check` của root (`build.gradle.kts` chỉ phụ thuộc bốn task đó). `api` đứng riêng vì 291s của nó chạy tuần tự đã gần bằng `core` khi `core` có ba JVM.
2. **`core` chạy ba JVM trên CI.** `maxParallelForks` đọc `MEMORYOS_CORE_TEST_FORKS`, mặc định vẫn là 2 cho máy dev. Leg `core` có cả runner (4 vCPU / 16 GB): ba JVM × 1.5 GiB heap, ba container PostgreSQL và tối đa ba container OpenSearch 512 MiB vẫn nằm trong 16 GB. Biến môi trường theo đúng cách `MEMORYOS_SEARCH_AUTHZ_MEASURE` đã đọc, tương thích configuration cache.
3. **Playwright từ bốn lên sáu shard.** Sau khi backend được tách, `frontend` (7m49s) trở thành đường găng. Repo public nên runner GitHub-hosted miễn phí; thêm shard chỉ tốn khoảng một phút cài đặt cho mỗi shard.
4. **CI Gate** đổi danh sách job: area backend gồm `infrastructure`, `backend`, `backend-images`. Kết quả của một job matrix chỉ là `success` khi mọi leg thành công.

Không đổi tên `CI Gate` và `Publish verified release`, vì `deploy.yml` tìm hai job này theo tên. `main` không có branch protection nên không có required check nào phụ thuộc tên `check`.

## Đã cân nhắc và loại

* **Self-hosted runner trên máy `PRODUCTION_HOST`.** Máy đó có 4 vCPU giống runner GitHub, nhưng chỉ còn khoảng 4.7 GB RAM khả dụng, swap 4 GB đã đầy, và đang chạy 35 container. Một runner sẽ chạy các job lần lượt thay vì khoảng mười job song song. Repo public nên PR từ fork chạy code tùy ý trên máy. Runner GitHub-hosted miễn phí cho repo public.
* **Merge queue để `main` không verify lại.** Repo thuộc tài khoản cá nhân, không có merge queue.

## Việc tiếp theo

Cả hai việc dưới đây được chấp nhận ngày 2026-10-02 và đang làm trong [CI main fast path](../ci-main-fast-path/design.md).

* **Publish 4 phút trên `main`.** Mỗi lần chạy, khoảng 1.9 GB image archive (`candidate-interpreter` 1.07 GB, `candidate-backend` 816 MB) được upload rồi tải lại. Có thể bỏ bước này bằng cách đẩy image lên GHCR dưới tag candidate rồi gắn tag release sau khi gate qua. Cách này đổi hợp đồng phát hành hiện tại: hiện chưa có gì lên registry trước khi CI Gate thành công. Vì vậy cần chủ dự án quyết định trước.
* **`main` verify lại thứ PR vừa verify.** Không có merge queue thì chỉ còn cách so tree của commit `main` với merge commit đã được verify. Image vẫn phải build lại vì label revision mang SHA của `main`.
