# Kế hoạch — CI song song cho backend

## Thay đổi

- [x] `core/build.gradle.kts`: `maxParallelForks` đọc `MEMORYOS_CORE_TEST_FORKS`, mặc định 2.
- [x] `.github/workflows/ci.yml`: job `infrastructure` (không JVM), job `backend` matrix `core` / `api` / `sources-worker` với `MEMORYOS_CORE_TEST_FORKS=3`, report `backend-tests-<leg>`, heap diagnostics chỉ ở leg `core`; Playwright sáu shard; CI Gate cập nhật `needs`, danh sách `keys` và area backend.
- [x] [Runbook CI/CD](../../../runbooks/ci-cd.md): job theo area, ba leg backend, sáu shard, số JVM của `core`.

## Kiểm chứng

- [x] actionlint 1.7.12 sạch trên toàn bộ workflow.
- [x] `infrastructure/deployment/test_deploy_workflow.py`: 30 test, OK (8 skip vì máy Windows không có `jq`/`bash` runtime).
- [ ] CI của pull request: `CI Gate` xanh, mỗi leg `backend` thành công, leg `core` không OOM (`core-test-diagnostics` không xuất hiện).
- [ ] Ghi thời gian từng job so với lần chạy 37027896348 (PR) và 37029069637 (`main`); nếu leg `core` không nhanh hơn rõ rệt hoặc GC log cho thấy heap sát giới hạn, đưa số JVM về 2.

## Kết quả đo

Chưa có.
