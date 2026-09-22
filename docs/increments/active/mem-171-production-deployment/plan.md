# MEM-171 — Kế hoạch triển khai

Ba phase. Phase 0 chạy được ngay, không chờ ai trả lời. Phase 1 chờ sáu quyết định trong [design](design.md). Phase 2 là lần promote đầu tiên.

## Phase 0 — Chuẩn bị đường triển khai (không bị chặn)

Một pull request cho mỗi mục, theo [quy tắc một PR mỗi phase](../../../conventions.md).

### 0.1 Tham số hoá kịch bản và workflow

* `deploy-staging.sh` → nhận tên môi trường làm tham số, chọn tệp env (`.env.staging` / `.env.production`) và danh sách Compose theo môi trường đó. Giữ nguyên toàn bộ ngữ nghĩa hiện có: `flock`, pending reservation, kiểm checksum, kiểm image revision, từ chối candidate thiếu migration đã applied, backup trước Flyway, api readiness trước worker/web, interpreter rollout cuối.
* `deploy-staging.yml` → tách phần chung thành reusable workflow nhận `environment`, `concurrency group` và tên biến. Staging gọi vào nó và **giữ nguyên hành vi hiện tại**, gồm cả `STAGING_AUTO_DEPLOY`.
* Thêm `deploy-production.yml`: chỉ `workflow_dispatch`, giới hạn `main`, `environment: production`, `concurrency: memoryos-production`, không có biến auto-deploy.
* Kiểm chứng: `infrastructure/deployment/test_deploy_workflow.py` hiện có phải mở rộng cho cả hai môi trường và vẫn xanh.

### 0.2 Hoàn thiện Compose production

> **Đây là PR duy nhất trong Phase 0 chạm vào staging đang sống, và phải deploy lên staging trước rồi xác minh xanh trước khi làm tiếp các mục khác.** Dời service giữa các tệp Compose làm đổi chính danh sách `com.docker.compose.project.config_files`; kịch bản deploy đọc danh sách trước đó từ label này và đòi nó là một tập duy nhất, nên việc tái cấu trúc bản thân nó là một lần deploy staging có gián đoạn dịch vụ, không phải refactor an toàn trên giấy.

* Dời `redis`, `interpreter` và phần cấu hình api/worker/web không đặc thù staging từ `compose.staging.yaml` xuống `compose.base.yaml`. Staging giữ `mailpit`, `pgweb`, `redisinsight` và các `oauth2-proxy`.
* Viết `compose.production.yaml` đầy đủ trên nền base đó, và overlay Search production tương ứng `compose.search.staging.yaml` (không có `opensearch-dashboards`).
* Bổ sung profile `production` cho cả hai deployable theo kiểu TLS của Redis production đã chốt: `api` chưa có tệp nào, `worker` có nhưng chỉ cấp host/port/pool chứ không cấp `spring.ssl.bundle.pem.memoryos-redis` như bản staging.
* Kiểm chứng: `docker compose config` cho tổ hợp production phải resolve sạch; so khớp danh sách service giữa staging và production để không rơi mất dịch vụ nào.

### 0.3 `production.env.example`

Phản chiếu `staging.env.example`: năm image theo digest, mật khẩu PostgreSQL, đường dẫn secret file, public origins, Tenant bootstrap, Keycloak hostname. Không giá trị secret nào nằm trong repository.

### 0.4 Runbook provisioning

Mục mới trong [runbook CI/CD](../../../runbooks/ci-cd.md) cho một host Ubuntu 24.04 trắng:

* Docker Engine + Compose plugin, `jq`, `flock`, `coreutils`, `tar`.
* `/apps/memoryos/{secrets,incoming,deployments}` với quyền đúng; `.env.production` là regular file mode `0600` của root.
* Network ngoài `proxy-network` và reverse proxy chấm dứt TLS.
* User triển khai riêng cho CD, sudoers giới hạn đúng kịch bản deploy, tắt SSH forwarding và interactive terminal cho key đó.
* **Tắt `PasswordAuthentication`** trong `sshd_config` — hiện đang bật trên public IP.

### 0.6 Tuỳ chọn hoá các khối chỉ-staging trong script realm

`infrastructure/keycloak/configure-memoryos-realm.sh` hiện bắt buộc `MEMORYOS_MAILPIT_PUBLIC_URL`, `MEMORYOS_PGWEB_PUBLIC_URL`, `MEMORYOS_REDISINSIGHT_PUBLIC_URL`, `MEMORYOS_MINIO_CONSOLE_PUBLIC_URL` cùng client secret của từng cái, cộng `MEMORYOS_KEYCLOAK_SMTP_HOST` và `SMTP_FROM` — thiếu là dừng. Production không có thứ nào trong số đó.

Chuyển năm khối ấy sang `if [ -n "${VAR:-}" ]`. Staging giữ nguyên hành vi vì vẫn truyền đủ biến; production chạy cùng script và không truyền, nên realm của nó không có các client đó. Kiểm chứng: dựng lại realm staging bằng script đã sửa và so sánh kết quả không đổi, rồi dựng realm production chỉ với bộ biến tối thiểu.

## Phase 1 — Cấu hình môi trường (chờ quyết định)

Chạy được ngay khi các mục tương ứng trong design được chốt.

| Việc | Chờ quyết định |
| --- | --- |
| DNS, chứng chỉ, cấu hình reverse proxy, các origin trong `.env.production` | Tên miền và TLS |
| Realm Keycloak production bằng `infrastructure/keycloak/configure-memoryos-realm.sh`, hoặc đấu nối IdP Tasco | Identity provider |
| Cấu hình SMTP cho Keycloak | Máy chủ SMTP |
| Machine identity và bootstrap file | Infisical `production` |
| Docling trên node `serving` với limit dưới 15 GiB, hoặc trỏ Worker sang `jmix-ocr` | Phân vai node |
| Tạo secret files MinIO, mật khẩu PostgreSQL, Tenant bootstrap | — |

Tạo GitHub environment `production` giới hạn `main`, với biến `PRODUCTION_HOST`, `PRODUCTION_USER`, `PRODUCTION_KNOWN_HOSTS` và secret `PRODUCTION_SSH_KEY`. Key này phải sinh riêng cho cụm — không dùng lại key staging, không copy key cá nhân vào Actions.

## Phase 2 — Lần promote đầu tiên

1. Chọn một `ci_run_id` **đã chạy thành công trên staging**. Production không phải nơi thử một release lần đầu.
2. `gh workflow run deploy-production.yml --ref main -f ci_run_id=<id>` rồi `gh run watch <id> --exit-status`.
3. Thu bằng chứng: CI run id, source SHA, năm image digest đã rollout khớp `images.env`, backup PostgreSQL và kết quả kiểm checksum, health/readiness của api, worker, web, interpreter.
4. Bàn giao cho người dùng làm business acceptance, ghi rõ deployment xanh không tuyên bố các luồng nghiệp vụ đã chạy đúng.
5. Chốt runbook theo đúng đường vừa chạy; ghi ADR cho quyết định tham số hoá thay vì fork.

## Phase 1.7 — Đóng chính sách schema giai đoạn đầu (bắt buộc, không được bỏ qua)

[Chính sách persistence](../../../guidelines/persistence.md#early-project-schema-evolution) hiện đang mở: *"Optimize for the clean target schema, not backward-compatible rollout machinery. Do not add expand/contract phases..."*, và nó tự nêu điều kiện kết thúc: *"Until MemoryOS holds external durable user data or a release milestone explicitly closes this policy"*, cộng *"Revisit this policy before onboarding external users or declaring durable customer data. From that point, migration and recovery plans must preserve committed data."*

**MEM-171 chính là cái mốc đó.** Đưa hệ thống lên môi trường khách hàng nghĩa là từ thời điểm đó dữ liệu không còn disposable, và mọi điều khoản đang cho phép reset phá huỷ, bỏ qua bảo toàn dữ liệu và cấm expand/contract đều trở thành nguy hiểm. Chính sách phải được đóng **tường minh bằng một thay đổi tài liệu cộng ADR**, không phải ngầm hiểu là ai cũng biết.

Nội dung đóng:

* Viết lại mục `Early-project schema evolution` trong `docs/guidelines/persistence.md`: nêu rõ mốc đóng, ngày, và issue đóng nó.
* Từ mốc đó: mọi thay đổi schema tách hai nhịp. Nhịp một chỉ thêm (cột nullable, bảng, index) và code phiên bản trước vẫn chạy được trên schema mới; nhịp hai xoá phần cũ, ở một release sau.
* Bỏ hẳn quyền "một lần reset phá huỷ được phê duyệt" đối với production.
* Ghi ADR: đây là quyết định đã áp dụng, không phải đề xuất.

Hệ quả tốt: lùi một release chỉ còn là đổi digest, không phải restore database — điều kiện tiên quyết để có rollback tự động và về sau là zero-downtime. Hiện runbook ghi thẳng *"Failure or cancellation reports the attempted transaction without automatically rolling back"*, và gốc rễ nằm ở chỗ migration đang buộc chặt với code, chứ không phải thiếu script.

## Phase 3 — Siết chặt sau khi production đứng vững

Không chặn lần deploy đầu, nhưng không được để trôi.

* **Ký artifact bằng cosign keyless + OIDC** và verify trước khi rollout. Hiện provenance được xác minh bằng logic tự viết trong workflow, chỉ GitHub kiểm được và hết hiệu lực khi run log hết hạn; chữ ký thì ai cũng kiểm được, offline. Lưu ý `build-push-action` đang đặt `provenance: false` và `sbom: false` — bật lên là gần như đủ cho phần attestation.
* **Diễn tập restore tự động.** Một bản backup chưa từng restore thì chưa phải backup. Định kỳ kéo bản mới nhất vào một database tạm, chạy Flyway, đọc vài bảng, xanh mới tính là có backup.

## Đối chiếu pipeline với repo khác — 2026-09-22

Đã đọc CI thật của ba repo so sánh được: `onyx-dot-app/onyx` (47 workflow, riêng `deployment.yml` 2173 dòng), `immich-app/immich` (27 workflow, app self-hosted Compose + Postgres) và `calcom/cal.com` (50 workflow, monorepo nhiều khu vực). MemoryOS có 2 workflow và 1191 dòng kể cả script deploy, tức ở phía nhỏ.

**Kết luận sửa lại một nhận định trước đó.** Cặp `changes` + `gate` không phải phức tạp tự chuốc: Immich có `pre-job` xuất JSON `should_run` rồi mọi job gắn `if: fromJSON(...)`; Onyx có `determine-builds` + `audit-gate`; Cal.com có `all-checks.yml` làm job tổng hợp. Ba trên ba làm đúng cùng một mẫu. **Giữ nguyên**, không xoá.

Chỗ thật sự thiếu so với họ là **merge queue**: Onyx và Cal.com đều dùng `merge_group`, MemoryOS không, nên lớp lỗi "PR xanh, `main` xanh, gộp lại hỏng" hiện không ai chặn. Onyx còn có mẫu đáng học trong `merge-group.yml`: queue **không chạy lại test nặng**, chỉ kiểm rằng verdict của lần chạy `pull_request` được tính trên một base đã land (`check-tested-base`), nên qua trong vài giây.

Chỗ **không có mẫu để đối chiếu**, và đúng là chỗ rủi ro nhất: không repo nào trong ba cái deploy lên máy thật. Trong 2173 dòng `deployment.yml` của Onyx, `ssh` xuất hiện 0 lần, `backup` và `pg_dump` 0 lần. Toàn bộ `deploy-staging.sh` — backup trước Flyway, `flock`, pending reservation, `current.compose` — là phần tự sáng tác. Hệ quả: đừng tin nó đúng vì nó chi tiết; nó cần review kỹ và cần diễn tập restore, chứ không cần đơn giản hoá.

Về ký artifact: cả ba repo đều không dùng `cosign` hay attestation. Trên toàn GitHub, code search cho ~24.800 file dùng `actions/attest-build-provenance` và ~30.300 file dùng `sigstore/cosign-installer`. Đọc đúng: ký image không phải mặc định của ngành, cũng không phải chuyện lạ — xếp vào nâng cấp có chủ đích cho môi trường khách hàng, không phải khắc phục thiếu chuẩn.

## 0.5 Gom bước setup lặp lại (không chặn ai)

`Check out repository` lặp 9 lần và bước cài pnpm lặp 4 lần trong `ci.yml`. Gom vào một composite action, bớt khoảng 60–80 dòng lặp mà không đụng cấu trúc job. Đây là phần duy nhất của đề xuất "đơn giản hoá" còn đứng được sau khi đối chiếu.

## Điều kiện vận hành phải mở issue riêng

* Đích sao lưu ngoài host cho PostgreSQL và MinIO. `postgres-backup` hiện ghi cùng máy; mất máy là mất cả dữ liệu lẫn bản sao lưu. Không được để ngầm định.
* **CD thế hệ hai** ([MEM-173](https://linear.app/memory-os/issue/MEM-173)): mô hình kéo thay cho đẩy, đảo thứ tự build/test để kiểm chính artifact sẽ ship, và thêm merge queue theo mẫu Onyx. Không bao gồm việc xoá `changes`/`gate` — đối chiếu ở trên cho thấy mẫu đó đúng.
