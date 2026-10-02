# Kế hoạch — CI main fast path

## PR 1: image job push thẳng lên GHCR

- [x] Action `.github/actions/push-release-images`.
- [x] `backend-images`, `frontend-image`, `interpreter`: `packages: write`, output `images`, bước push chỉ khi push lên `main`; bỏ `docker save` và artifact `candidate-*`.
- [x] `publish`: đọc digest từ output, kiểm trên GHCR, không còn tải hay push image.
- [x] `test_deploy_workflow.py`: thay assertion về `candidate-interpreter` bằng hợp đồng mới; thêm test chỉ build trên `main` mới push và `publish` không push.
- [x] [ADR 0020](../../../decisions/0020-image-jobs-push-before-the-gate.md), [runbook CI/CD](../../../runbooks/ci-cd.md).
- [x] actionlint 1.7.12 sạch; shellcheck sạch cho script của action; 79 test Python trong `infrastructure/deployment` OK (20 skip trên Windows).
- [x] CI của PR #458 xanh; merge thành `7b4182e5`.
- [x] Lần chạy `main` đầu tiên sau merge (run 37036799141, `7b4182e5`): ba image job push được, `Publish verified release` xanh trong 11s (trước 4m16s), `Deploy staging` 37037572773 thành công. Từ lúc job bắt đầu tới publish xong mất 6m37s (trước 12m40s); `interpreter` 6m15s là chậm nhất.

## PR 2: `main` không test lại

- [x] `changes` xuất `tree`; gate upload `ci-verified-<tree>` cho PR từ chính repo.
- [x] `changes` khi push lên `main`: tra artifact, kiểm run sở hữu nó, xuất `<area>_verified`; lỗi thì chạy đủ.
- [x] Điều kiện của các job test và các bước test của `interpreter`; CI Gate ba trạng thái.
- [x] [ADR 0021](../../../decisions/0021-main-reuses-verified-pull-request-trees.md) và runbook.
- [x] `VerifiedTreeReuseTest`: chạy chương trình jq của gate với các kết quả giả (chạy với jq trong Docker, OK) và kiểm các điều kiện tin cậy của bước tra cứu; actionlint 1.7.12 và shellcheck sạch; 81 test Python OK trên Windows (21 skip).
- [x] CI của PR #459 xanh, artifact `ci-verified-9b5f0a64574e970535aa4cb9ff72344ea031d756` có trên run của PR; merge thành `4c466e8b`, tree của nó trùng với artifact.
- [ ] Lần chạy `main` sau merge: các job test bị bỏ qua, image vẫn build, CI Gate xanh; ghi thời gian từ push tới publish.

## PR 3: cache layer trên GHCR

- [x] Sáu cache `type=gha` của `backend-images`, `frontend-image`, `interpreter` chuyển sang `type=registry` tại `memoryos-<component>:buildcache`; chỉ push lên `main` mới ghi, có `ignore-error=true`; ba job login GHCR trước khi build và logout ở cuối.
- [x] Runbook và design.
- [x] actionlint 1.7.12 sạch; 81 test Python OK (21 skip trên Windows).
- [x] CI của PR #461 xanh; `backend-images` 4m49s (trước 9m35s) vì PR không còn ghi cache. Merge thành `69e64297`.
- [x] Run 37043727500 (`6ac59f77`, gồm #461 và bản sửa #463): ghi được `buildcache` cho api, worker, web, interpreter; `backend-images` 6m16s; từ push tới publish 8m07s; `Deploy staging` 37044631699 thành công.
- [x] Run 37044889560 (`55d7306d`, đọc cache, chạy đủ test vì tree khác): build image API 2m32s (trước 7m05s), `backend-images` 4m39s, từ push tới publish 7m50s. Đường găng giờ là shard Playwright `frontend (2)` 7m13s, shard này luôn chậm nhất.

## Bản sửa #463

Run 37041478495 (`6bcff78c`) là lần đầu `main` bỏ test: các job test bị bỏ qua, CI Gate xanh, nhưng `Publish verified release` cũng bị bỏ qua. Một job có `if` không chứa hàm trạng thái ngầm đòi mọi job phía trước trong chuỗi phụ thuộc đã chạy thành công, nên các job test bị bỏ qua sau gate kéo theo `publish`. `Deploy staging` 37042468509 vì vậy thất bại. PR #463 (`6ac59f77`) cho `publish` dùng `!cancelled()` và kiểm trực tiếp kết quả của `gate` cùng ba job image.

- [ ] Lần chạy `main` bỏ test kế tiếp: `Publish verified release` chạy và `Deploy staging` nhận release.
