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
