# Kế hoạch — CI song song cho backend

## Thay đổi

- [x] `core/build.gradle.kts`: `maxParallelForks` đọc `MEMORYOS_CORE_TEST_FORKS`, mặc định 2.
- [x] `.github/workflows/ci.yml`: job `infrastructure` (không JVM), job `backend` matrix `core` / `api` / `sources-worker` với `MEMORYOS_CORE_TEST_FORKS=3`, report `backend-tests-<leg>`, heap diagnostics chỉ ở leg `core`; Playwright sáu shard; CI Gate cập nhật `needs`, danh sách `keys` và area backend.
- [x] [Runbook CI/CD](../../../runbooks/ci-cd.md): job theo area, ba leg backend, sáu shard, số JVM của `core`.

## Kiểm chứng

- [x] actionlint 1.7.12 sạch trên toàn bộ workflow.
- [x] `infrastructure/deployment/test_deploy_workflow.py`: 30 test, OK (8 skip vì máy Windows không có `jq`/`bash` runtime).
- [x] CI của pull request #452 (run 37031801125): `CI Gate` xanh, mọi leg `backend` thành công, leg `core` không OOM.
- [x] Ghi thời gian từng job so với lần chạy 37027896348 (PR) và 37029069637 (`main`).
- [ ] Đo lần chạy `main` đầu tiên sau merge (3574fc32) để xác nhận thời gian từ push tới publish.

## Kết quả đo

Run 37031801125 của PR #452. PR này sửa `ci.yml` nên mọi area đều được chọn:

| Job | Trước (37027896348) | Sau |
| --- | --- | --- |
| Toàn bộ PR, từ lúc bắt đầu tới `CI Gate` | ~10m | 6m48s |
| Backend Gradle | `check` 9m30s | `core` 5m43s (3 JVM); `api` 36s; `sources-worker` 41s |
| `infrastructure` | nằm trong `check` | 41s |
| `frontend`, shard chậm nhất | 7m48s (4 shard) | 6m22s (6 shard) |
| `backend-images` | 4m45s | 6m31s |

`api` và `sources-worker` nhanh vì `:api:test`, `:sources:test` và `:worker:test` được lấy từ Gradle build cache (`FROM-CACHE`): input của chúng không đổi so với `main`. Khi `core` main source thay đổi, ba task đó chạy thật, và mỗi leg có runner riêng nên vẫn không chờ `core`. Giờ đường găng là `backend-images` và shard Playwright chậm nhất, cùng khoảng 6m30s.
