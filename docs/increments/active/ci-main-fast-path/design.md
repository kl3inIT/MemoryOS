# Thiết kế — CI main fast path

Tiếp nối [CI song song cho backend](../../completed/ci-parallel-backend/design.md). Sau increment đó, PR mất 6m48s, còn push lên `main` vẫn khoảng 14.5 phút trước khi staging được deploy (run 37029069637). Có hai lý do:

1. `Publish verified release` mất 4m08s để tải lại khoảng 1.9 GB image archive mà các job build vừa upload, `docker load` rồi push lên GHCR.
2. `main` chạy lại mọi job test, dù PR vừa verify đúng nội dung đó.

Chủ dự án đã chấp nhận cả hai thay đổi ngày 2026-10-02. Hai thay đổi đi thành hai pull request: cả hai cơ chế chỉ chạy khi push lên `main`, nên lần chạy `main` sau mỗi lần merge chỉ thử một cơ chế mới.

## 1. Image job push thẳng lên GHCR

Quyết định ghi trong [ADR 0020](../../../decisions/0020-image-jobs-push-before-the-gate.md).

* Action dùng chung [`push-release-images`](../../../../.github/actions/push-release-images/action.yml): login, kiểm label revision/source, push tag `sha-<sha>-<run>-<attempt>`, lấy digest, logout. Output là các dòng `MEMORYOS_<COMPONENT>_IMAGE=<digest>`.
* `backend-images` (api, worker, keycloak), `frontend-image` (web), `interpreter` (interpreter, interpreter-executor) gọi action ở bước cuối, chỉ khi push lên `main`.
* `publish` cần `gate` và ba job image. Nó ghép output, đòi đúng một digest cho mỗi component theo regex, kiểm digest có trên GHCR bằng `docker buildx imagetools inspect`, rồi ghi `images.env` theo thứ tự cũ. Quyền chỉ còn `contents: read` và `packages: read`.
* `landing` giữ nguyên cách cũ.

## 2. `main` không test lại thứ PR đã verify

* PR: `changes` xuất `tree` của merge commit. Nếu `CI Gate` qua và PR đến từ chính repo, gate upload artifact `ci-verified-<tree>` chứa các area đã verify.
* Push lên `main`: `changes` tìm artifact `ci-verified-<tree của HEAD>`. Artifact chỉ được tin khi run sở hữu nó chạy `.github/workflows/ci.yml`, là sự kiện `pull_request`, kết luận `success`, từ chính repo, và thuộc PR mà commit `main` đó đến từ. Mọi lỗi khi tra cứu đều dẫn tới chạy đủ.
* Area đã verify bỏ job chỉ để test (`infrastructure`, `backend`, `frontend-check`, `frontend`, `frontend-preview`, các bước test của `interpreter`), nhưng vẫn build và push image, vì label revision mang SHA của `main`.
* CI Gate có ba trạng thái cho mỗi area: không chọn thì mọi job phải bị bỏ qua; chọn và chưa verify thì mọi job phải thành công; chọn và đã verify thì job test phải bị bỏ qua và job image phải thành công.

So sánh tree an toàn vì tree trùng nghĩa là nội dung file giống hệt thứ PR đã test, kể cả `ci.yml`. Chỉ tin PR từ chính repo, vì artifact của một PR từ fork có thể mang tên tree của một PR khác.

## 3. Cache layer Docker nằm trên GHCR

Thêm ngày 2026-10-02, sau khi đo run 37038355600. `backend-images` chậm dần qua từng lần chạy: 4m45s, 6m31s, 8m15s, rồi 9m35s. Trong lần chậm nhất, riêng bước build image API mất 7m05s, trong đó **229s là ghi cache lên GitHub Actions Cache**. Repo dùng 10.7 GB Actions cache, vượt giới hạn 10 GB, nên GitHub liên tục xoá cache cũ. Các cache Docker `mode=max`, cộng với cache Gradle của ba leg backend, pnpm và uv, cứ bị xoá rồi ghi lại.

* Cache layer chuyển sang registry, đặt cạnh image: `ghcr.io/kl3init/memoryos-<component>:buildcache`, `mode=max`. Đây là dạng `<image>:buildcache` mà tài liệu Docker dùng làm ví dụ cho registry cache. GHCR không có giới hạn 10 GB.
* Mọi run đều đọc cache. Chỉ push lên `main` mới ghi, theo đúng khuyến nghị tránh để PR làm rác cache (cùng ý tưởng với `int128/docker-build-cache-config-action`). PR vì vậy không còn trả thời gian ghi cache.
* Ghi cache dùng `ignore-error=true`, để cache lỗi không bao giờ làm fail build.
* Ba job image login GHCR bằng token của job trước khi build, để đọc được cache trong package private. Credential chỉ nằm ở client Docker trên runner; container service của bước e2e interpreter chỉ nhận docker socket, không nhận credential.
* `landing` giữ cache `type=gha`, vì image nhỏ và job không có quyền ghi package.

`buildcache` là cache layer, không phải image chạy được, và không release nào trỏ tới nó. Quy tắc "không có tag deploy thay đổi được" vẫn giữ nguyên.

## Kết quả mong đợi

Push lên `main` tới lúc publish xong còn khoảng 7 phút, bằng thời gian build image chậm nhất.
