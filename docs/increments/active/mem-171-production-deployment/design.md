# MEM-171 — Triển khai production trên cụm hn-fci-k8s-aioffice

## Kết quả mong muốn

Một đường triển khai production thật cho MemoryOS: promote đúng verified release đã publish từ `main`, rollout có backup và migration, xác minh image identity và health. Đường này phản chiếu staging bằng cách tham số hoá chính kịch bản staging, không phải một bản sao thứ hai.

Theo chính sách delivery ngày 2026-09-12 trong [runbook CI/CD](../../../runbooks/ci-cd.md): CD xác minh provenance, backup/migration, image identity và health/readiness. Login, upload, indexing, Search và Chat là business acceptance của người dùng, không phải cổng deployment.

## Môi trường đã kiểm chứng — 2026-09-21

SSH vào được cả hai node bằng user `ubuntu` (viết thường; `Ubuntu` bị từ chối). Node `serving` chỉ vào được qua `ProxyJump` node `application`.

| | `hn-fci-k8s-aioffice-application` | `hn-fci-k8s-aioffice-serving` |
| --- | --- | --- |
| Địa chỉ | public 167.254.65.226, private 172.24.244.120 | private 172.24.244.79 |
| OS | Ubuntu 24.04.2 LTS, kernel 6.8.0-60 | Ubuntu 24.04.2 LTS, kernel 6.8.0-60 |
| CPU / RAM | 12 vCPU / 31 GiB | 8 vCPU / 15 GiB |
| Disk `/` | 493 GB, trống 470 GB | 296 GB, trống 281 GB |
| GPU | không | **NVIDIA RTX 4090 (AD102), PCI `00:06.0`** — driver chưa cài |
| sudo | passwordless | passwordless |
| Ra Internet | GHCR và Docker Hub trả `401` | GHCR trả `401` |

Ba kết luận rút ra trực tiếp từ kiểm kê:

* **`k8s` chỉ là quy ước đặt tên.** Không có `docker`, `kubectl`, `k3s`, `containerd`, `crictl`, `helm`, `nerdctl` hay `podman` trên bất kỳ node nào; disk mới dùng 2.6 GB. Đây là hai máy Ubuntu trắng. Đường triển khai dùng Docker Compose như staging; container runtime là một bước provisioning, không phải điều kiện có sẵn.
* **Không cần private registry.** Mã `401` là phản hồi đúng của registry v2 khi chưa gửi token, tức đường mạng ra GHCR thông. Node pull theo digest bằng short-lived package-read token như staging. Private registry là tuỳ chọn, không phải điều kiện chặn.
* **SSH đang bật password authentication.** Thông báo từ chối là `Permission denied (publickey,password)` trên một public IP. Provisioning phải tắt `PasswordAuthentication` trước khi đưa dịch vụ lên.

## Hiện trạng repository và các khoảng trống thật

Staging chạy ba tệp Compose: `compose.base.yaml` + `compose.staging.yaml` + `compose.search.staging.yaml`, cộng network ngoài `proxy-network`, thư mục `/apps/memoryos/{secrets,incoming,deployments}` và `.env.staging` mode `0600`.

| Khoảng trống | Chi tiết |
| --- | --- |
| `compose.production.yaml` gần như rỗng | Chỉ có `SPRING_PROFILES_ACTIVE: production`, biến Sentry và `MEMORYOS_RELEASE`. Thiếu toàn bộ phần production thật đang nằm trong `compose.staging.yaml`: `redis`, `interpreter`, cấu hình api/worker/web, cổng và network. Không có overlay Search tương ứng `compose.search.staging.yaml`. |
| Không profile nào cấp truststore TLS cho Redis ở production | `api` **không có** `application-production.yaml`; `worker` có nhưng tệp đó chỉ cấp host/port/pool của Redis, **không** có `spring.ssl.bundle.pem.memoryos-redis` mà `application-staging.yaml` của cả hai deployable đang cấp. `compose.production.yaml` vẫn bật profile `production`. Nếu Redis production dùng TLS với CA riêng, cả api lẫn worker đều thiếu truststore; nếu dùng CA nằm trong system trust thì không. Phải chốt kiểu TLS của Redis production rồi bổ sung tệp tương ứng trước lần deploy đầu. |
| Kịch bản và workflow gắn cứng staging | `deploy-staging.sh` gắn cứng `.env.staging` và ba tệp Compose; `deploy-staging.yml` gắn cứng `concurrency: memoryos-staging`, `environment: staging` và `vars.STAGING_*`. |
| Không có SMTP thật | Ứng dụng không gửi mail; **Keycloak** gửi, và staging trỏ vào `mailpit`. Production cần một SMTP thật, nếu không lời mời thành viên không tới nơi. |
| Backup chỉ nằm cùng máy | `postgres-backup` ghi tại chỗ. Production cần đích sao lưu ngoài host cho PostgreSQL và MinIO. |

## Quyết định thiết kế

**Tham số hoá, không fork.** `deploy-staging.sh` dài 223 dòng và `deploy-staging.yml` chứa toàn bộ logic provenance. Nhân đôi chúng cho production là vi phạm [quy tắc tái sử dụng](../../../conventions.md#component-and-library-reuse) và tạo hai bản sẽ trôi khác nhau. Kịch bản nhận tên môi trường làm tham số (chọn tệp env và danh sách Compose); workflow tách thành một reusable workflow mà cả hai môi trường gọi vào.

**Dời phần production-like từ staging xuống base.** `redis`, `interpreter` và cấu hình api/worker/web trong `compose.staging.yaml` không phải đặc thù staging. Chuyển chúng xuống `compose.base.yaml`, để `compose.staging.yaml` giữ đúng phần chỉ staging mới có: `mailpit`, `pgweb`, `redisinsight` và các `oauth2-proxy` đi kèm. Production khi đó là base cộng một overlay mỏng thật sự, thay vì một bản sao thứ ba.

**Không có chế độ tạm.** [ADR 0002](../../../decisions/0002-no-speculative-operational-surfaces.md): không ship runtime mode một lần, profile tạm hay endpoint speculative để môi trường "chạy được". Không dùng `nip.io` hay chứng chỉ tự ký trên môi trường khách hàng. Hoặc có đường production thật, hoặc chưa deploy.

**Không tự động promote.** Staging có `STAGING_AUTO_DEPLOY`; production chỉ chạy bằng `workflow_dispatch` với `ci_run_id` được chọn tường minh.

## Tách hẳn hai môi trường

Production không chia sẻ gì với staging: PostgreSQL, MinIO, OpenSearch, Redis, Keycloak, Infisical environment, GitHub environment và khoá SSH đều riêng. Staging không được là điều kiện để production sống.

Chỗ rò rỉ phải bịt, vì chúng **mặc định im lặng trỏ về staging** chứ không báo lỗi:

* `compose.base.yaml` đặt `KC_HOSTNAME: ${MEMORYOS_KEYCLOAK_HOSTNAME:-https://auth.kl3in.tech}`. Quên khai biến này trong `.env.production` thì Keycloak production khởi động với hostname của staging và không có cảnh báo nào. Overlay production phải khai tường minh `https://auth.vadan.app`; cân nhắc đổi mặc định thành `:?` để thiếu là hỏng ngay thay vì chạy sai.
* `shared-keycloak` mang alias `orgmemory-keycloak` và tham gia network `shared-infra` — di sản của ranh giới shared runtime với OrgMemory. Production không chia sẻ Keycloak với ai, nên overlay production không được kế thừa hai thứ đó.
  **Đã làm 2026-09-23.** Hai alias `orgmemory-keycloak` và `shared-keycloak` chuyển sang `compose.staging.yaml`. Production cũng thôi chạy image `orgmemory-keycloak` do repo OrgMemory build: image đó mang file realm `orgmemory-realm.prod.json` và theme của OrgMemory. Thay vào đó, mỗi release build image `memoryos-keycloak` có sẵn theme `memoryos` bên trong, và deploy thay Keycloak cùng release. Lý do: theme mount từ thư mục release thì Keycloak (uid 1000) không đọc được, vì thư mục release chỉ root đọc. Ngày 2026-09-23 Keycloak production đã âm thầm rơi về giao diện mặc định vì đúng lỗi này, và phải chạy tạm từ `~/bootstrap`. Staging giữ image OrgMemory vì realm `orgmemory` ở đó dùng theme `orgmemory-shadcn`. Quy tắc nằm ở [runbook CI/CD](../../../runbooks/ci-cd.md#keycloak-runtime).
* Rà toàn bộ `${VAR:-giá trị}` trong `compose.base.yaml` theo cùng tiêu chí: mặc định nào trỏ tới một host, origin hay tên miền cụ thể đều là mầm rò rỉ môi trường. Mặc định an toàn là mặc định không đoán ra ngoài phạm vi máy.

## Phạm vi

1. Runbook và kịch bản provisioning cho Ubuntu 24.04 trắng: Docker Engine + Compose plugin, `jq`, `flock`, `coreutils`, `tar`; cây thư mục `/apps/memoryos`; network `proxy-network`; reverse proxy và TLS; user triển khai riêng với sudoers giới hạn đúng kịch bản deploy; tắt SSH password authentication.
2. Tham số hoá `deploy-staging.sh` thành kịch bản theo môi trường và tách `deploy-staging.yml` thành reusable workflow; thêm `deploy-production.yml` chỉ dispatch thủ công.
3. Hoàn thiện `compose.production.yaml` và overlay Search production; bổ sung `api/src/main/resources/application-production.yaml`.
4. `production.env.example` phản chiếu `staging.env.example`.
5. GitHub environment `production` giới hạn `main`, SSH identity riêng, biến và secret tương ứng.
6. Cập nhật runbook CI/CD với mục production; ghi ADR sau khi implementation bắt đầu.

## Ngoài phạm vi

* Business acceptance sau deploy.
* Rollback tự động; recovery vẫn thủ công như staging.
* Di trú dữ liệu từ staging sang production.
* Landing page (release riêng theo [runbook landing](../../../runbooks/landing.md)).
* Đích sao lưu ngoài host: ghi nhận là điều kiện vận hành bắt buộc nhưng tách thành issue riêng, vì nó cần hạ tầng lưu trữ mà khách hàng chưa cấp.

## Quyết định cần khách hàng hoặc chủ sản phẩm chốt

Những mục này chặn Phase 1, không chặn Phase 0.

**Đã chốt 2026-09-21 — tên miền.** Production dùng `vadan.app`: ứng dụng ở `app.vadan.app`, Keycloak ở `auth.vadan.app`, MinIO S3 endpoint ở `objects.vadan.app`. Apex `vadan.app` và `www` giữ nguyên landing đang chạy trên VPS staging; MEM-171 chỉ thêm subdomain, không đụng hai bản ghi đó. Chứng chỉ cấp qua Nginx Proxy Manager và Let's Encrypt như staging.

**Đã chốt 2026-09-21 — Keycloak.** Production chạy Keycloak riêng trên node `application`, không dùng `auth.kl3in.tech` của staging và không nằm trong ranh giới shared runtime. Realm dựng bằng `infrastructure/keycloak/configure-memoryos-realm.sh`. Hệ quả: `MEMORYOS_KEYCLOAK_HOSTNAME` thành `https://auth.vadan.app`, và alias `orgmemory-keycloak` cùng network `shared-infra` trong `compose.base.yaml` phải được xem lại — production không chia sẻ Keycloak với OrgMemory, nên overlay production không được kế thừa các alias đó như một chuyện đương nhiên.

**Đã chốt 2026-09-21 — không có SMTP, chỉ bật JIT.** Production không cấu hình máy chủ gửi thư; việc admit người dùng đi qua [JIT admission của MEM-59](../../completed/mem-59-tasco-jit/design.md) khi tích hợp Keycloak phía đối tác. Cần bật: `MEMORYOS_JIT_ALLOWED_PROVIDER_ALIASES` (mặc định rỗng, phải opt-in tường minh đúng alias), identity provider tương ứng trong realm production, và protocol mapper `User Session Note` trên client `memoryos-web` để `identity_provider` thành claim `memoryos_identity_provider` trong ID token.

Hệ quả phải chấp nhận tường minh, không được để ngầm: **luồng mời thành viên local-password ngừng hoạt động.** [ADR 0005](../../../decisions/0005-keycloak-invited-user-provisioning.md) mời người mới bằng cách gọi Keycloak `execute-actions-email`; không có SMTP thì lời mời thất bại tại provider. Trên production, người dùng vào được chỉ qua JIT từ provider tin cậy, hoặc là tài khoản đã tồn tại và đã verified. Tới khi tích hợp xong Keycloak đối tác thì chưa ai JIT vào được, nên trước mốc đó production chỉ có Tenant owner được bootstrap.

**Đã chốt 2026-09-21 — hoãn các surface kiểm tra.** MinIO Console, OpenSearch Dashboards, pgweb, RedisInsight **không cài trên production** cho tới khi chủ sản phẩm yêu cầu. Do đó không cần DNS/chứng chỉ cho chúng, và `install-dashboards-proxy.py` chưa chạy trên môi trường này. Truy cập kiểm tra khi cần đi qua SSH tunnel.

**Vướng mắc phát hiện 2026-09-22 — script realm đang gắn cứng hình dạng staging.** `infrastructure/keycloak/configure-memoryos-realm.sh` bắt buộc (dùng `:?`, thiếu là dừng) toàn bộ nhóm biến sau: `MEMORYOS_MAILPIT_PUBLIC_URL` và secret của nó, `MEMORYOS_PGWEB_PUBLIC_URL`, `MEMORYOS_REDISINSIGHT_PUBLIC_URL`, `MEMORYOS_MINIO_CONSOLE_PUBLIC_URL` cùng các client secret tương ứng, cộng `MEMORYOS_KEYCLOAK_SMTP_HOST` và `MEMORYOS_KEYCLOAK_SMTP_FROM`.

Điều đó đối đầu trực tiếp với hai quyết định đã chốt: production **hoãn mọi surface kiểm tra** và **không có SMTP**.

**Đã chốt 2026-09-22 — một script, khối chỉ-staging thành tuỳ chọn.** Các khối mailpit, pgweb, redisinsight, minio console và SMTP chuyển sang dạng `if [ -n "${VAR:-}" ]`, bỏ qua khi không khai. Production chạy cùng script đó và đơn giản là không truyền các biến ấy, nên realm production không có những client đó.

Phương án viết script riêng cho production bị loại: phần chung giữa hai môi trường — client `memoryos-web`, `memoryos-client`, `memoryos-user-provisioner`, audience mapper, identity-provider mapper, theme, chủ sở hữu đầu tiên, tắt self-registration — sẽ tồn tại hai bản và trôi khác nhau, đúng sai lầm đã tránh với `deploy-staging.sh`. Truyền giá trị giả cho client không dùng cũng bị loại: nó tạo OAuth client vô chủ trong realm production, đúng loại bề mặt [ADR 0002](../../../decisions/0002-no-speculative-operational-surfaces.md) cấm.

Hệ quả phải ghi vào realm production cho người sau đọc được: **realm này cố ý không có khả năng gửi thư.** Mọi luồng Keycloak cần email — quên mật khẩu, xác minh email, `execute-actions-email` của luồng mời — đều không hoạt động, và đó là thiết kế chứ không phải hỏng.

Còn lại:

**Đã chốt 2026-09-22 — máy chủ không dùng Infisical.** Secret trên máy chủ là file sinh tại chỗ; Infisical chỉ còn phục vụ laptop lập trình viên. Xem [increment riêng](../server-secrets-as-files/design.md). Production vì vậy **không cần** environment `prod` hay machine identity nào, và không bao giờ có file bootstrap.
2. **Phân vai node `serving`** — nó có một **RTX 4090 chưa cài driver**, nên câu hỏi rộng hơn mức OCR. Kiểm kê ngày 2026-09-21 ghi nhầm là không có GPU: lúc đó chỉ kết luận từ việc thiếu `nvidia-smi`, mà thiếu công cụ không chứng minh được thiếu phần cứng. `lspci` ngày 2026-09-22 cho thấy card nằm ở `00:06.0`, không module `nvidia` nào được nạp và không có `/dev/nvidia*`.

   Ba việc có thể đặt lên nó, không loại trừ nhau: Docling OCR chạy GPU (nhanh hơn nhiều so với CPU và không còn phụ thuộc `jmix-ocr` của cụm Jmix); sinh embedding tại chỗ thay vì gọi ra ngoài; và model serving tự host — thứ [MEM-66](../mem-66-vllm-cpu-gateway-research/design.md) đã park ngày 2026-09-19 *"cho tới khi có một môi trường đủ điều kiện"*. Một 4090 24 GB có thể chính là môi trường đó, nên MEM-66 cần được xem lại chứ không giữ nguyên trạng thái park.

   Ràng buộc thật của node này là **15 GiB RAM hệ thống**, không phải VRAM. Trước khi đặt việc lên đây phải cài driver NVIDIA và NVIDIA Container Toolkit; chừng nào chưa cài thì GPU không dùng được từ container.

   Nếu vẫn chọn Docling CPU tại chỗ thì limit phải hạ xuống dưới 15 GiB, thấp hơn mức 8 CPU / 16 GiB đang đặt trên cụm Jmix của MEM-79. Trỏ Worker sang `jmix-ocr` sẵn có vẫn là lựa chọn, nhưng nó tạo phụ thuộc chéo môi trường và giờ khó biện minh hơn khi máy tại chỗ có GPU. OpenSearch ở lại node 31 GiB.
3. **Đích sao lưu ngoài host** cho PostgreSQL và MinIO.

## Nghiệm thu

Một lần deploy production thành công từ một verified release của `main`, kèm bằng chứng: CI run id, source SHA, năm image digest đã rollout khớp `images.env`, backup PostgreSQL đã tạo và kiểm checksum, health/readiness xanh cho api, worker, web và interpreter. Runbook mô tả đúng đường vừa chạy. Deployment xanh không tuyên bố business acceptance.
