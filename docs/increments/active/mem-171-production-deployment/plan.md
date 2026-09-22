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

## Phase 1 — Dựng node production (đã chốt quyết định, 2026-09-22)

Node `application` (`hn-fci-k8s-aioffice-application`, `167.254.65.226`, Ubuntu 24.04.2, 12 vCPU, 31 GiB, 469 GiB trống). Kiểm ngày 2026-09-22.

**Đã có**: Docker 29.8.1 + Compose v5.5.1, `jq`, `flock`, `tar`, `curl`; `/apps/memoryos/{deployments,incoming,secrets}` mode đúng; DNS `app.vadan.app`, `auth.vadan.app`, `objects.vadan.app` đều trỏ `167.254.65.226`.

**Chưa có**: `proxy-network`, reverse proxy, tường lửa, user triển khai, secret, `.env.production`, realm. Không container nào đang chạy.

**Mở toang**: `PasswordAuthentication yes` và `PermitRootLogin yes` trên IP công cộng, `ufw` tắt. Đây là việc gấp nhất, làm trước mọi thứ khác.

Thứ tự dưới đây là thứ tự phụ thuộc thật, không phải thứ tự cho đẹp.

### 1.1 Đóng đường vào trước khi mở dịch vụ

Làm trước tiên, vì từ bước 1.2 trở đi máy bắt đầu mở cổng ra Internet.

* Nạp khoá công khai của người vận hành vào `~ubuntu/.ssh/authorized_keys`, **đăng nhập thử bằng khoá ở một phiên thứ hai** rồi mới sửa `sshd_config`. Không bao giờ sửa SSH khi chỉ có một phiên đang mở.
* `PasswordAuthentication no`, `PermitRootLogin prohibit-password`, `KbdInteractiveAuthentication no`. `sshd -t` trước khi `systemctl reload ssh`.
* `ufw`: `deny incoming`, `allow outgoing`, mở `22`, `80`, `443`. Bật sau cùng trong bước này.
* Không mở `81` (giao diện Nginx Proxy Manager) ra Internet ở bất kỳ bước nào — xem 1.3.

Nghiệm thu: đăng nhập bằng mật khẩu bị từ chối; `ufw status` liệt kê đúng ba cổng; phiên đang mở không đứt.

### 1.2 `proxy-network` và Nginx Proxy Manager

* `docker network create proxy-network` — Compose khai nó là `external`, nên thiếu là deployment hỏng chứ không tự tạo.
* NPM trong compose riêng của nó (không thuộc stack MemoryOS, vì nó phải sống qua mọi lần deploy): cổng `80` và `443` publish ra ngoài, cổng `81` **chỉ bind `127.0.0.1`**, nối vào `proxy-network`.
* Đổi mật khẩu tài khoản mặc định `admin@example.com / changeme` ngay lần đăng nhập đầu.
* `client_max_body_size` nâng lên (đề xuất `512m`) trong Advanced của host `objects.vadan.app`. Mặc định NPM là `1m`; để nguyên thì upload file lớn chết ở proxy chứ không phải ở ứng dụng, và thông báo lỗi sẽ không chỉ về đúng chỗ.

Nghiệm thu: `docker network inspect proxy-network` tồn tại; `curl -I http://127.0.0.1:81` trả 200 trên máy; `ss -tln` không thấy `0.0.0.0:81`.

### 1.3 Giao diện NPM truy cập được từ ngoài, nhưng không phơi cổng 81

Yêu cầu: quản trị proxy từ trình duyệt, không phải qua SSH tunnel.

Cách làm: cho NPM tự phục vụ chính nó. Thêm một proxy host `proxy.vadan.app` → `127.0.0.1:81` với chứng chỉ Let's Encrypt như mọi host khác, rồi gắn **Access List** của NPM lên host đó (HTTP Basic, tài khoản riêng, không trùng tài khoản NPM). Cổng `81` vẫn chỉ nghe trên loopback; người ngoài đi qua `443`.

Vì sao không publish thẳng `81`: đó là trang đăng nhập quản trị toàn bộ reverse proxy, chạy HTTP trần, không giới hạn số lần thử. Publish nó nghĩa là toàn bộ TLS của hệ thống chỉ còn cách Internet đúng một mật khẩu, truyền dạng rõ.

**Cần anh**: thêm bản ghi DNS `proxy.vadan.app` → `167.254.65.226`. Chưa có bản ghi này thì Let's Encrypt không cấp được chứng chỉ.

Nghiệm thu: `https://proxy.vadan.app` hỏi Basic auth rồi tới trang đăng nhập NPM; `http://167.254.65.226:81` không kết nối được từ ngoài.

### 1.4 Chứng chỉ TLS cho ba host ứng dụng

DNS đã trỏ đúng nên HTTP-01 dùng được ngay, không cần DNS challenge.

| Host | Đích trong `proxy-network` | Ghi chú |
| --- | --- | --- |
| `app.vadan.app` | `memoryos-web:8080` | WebSocket bật (Chat stream, ghi âm cuộc họp) |
| `auth.vadan.app` | `memoryos-keycloak:8080` | Phải khớp `KC_HOSTNAME`, nếu không Keycloak từ chối |
| `objects.vadan.app` | `memoryos-minio:9000` | `client_max_body_size 512m` |

Bật **Force SSL** và **HTTP/2** cho cả ba. Apex `vadan.app` hiện trỏ `72.62.193.33` (staging) — không đụng tới, nhưng ghi lại ở đây để người sau không tưởng là nhầm.

Nghiệm thu: cả ba trả chứng chỉ hợp lệ; `auth.vadan.app` mở được trang đăng nhập Keycloak sau bước 1.7.

### 1.5 User triển khai và sudoers

* Tạo user `memoryos-ci`, không mật khẩu, chỉ vào bằng khoá.
* Sinh **cặp khoá SSH mới riêng cho cụm này**. Không dùng lại khoá staging, không copy khoá cá nhân vào GitHub Actions.
* `authorized_keys` của nó thêm `no-agent-forwarding,no-port-forwarding,no-X11-forwarding,no-pty` trước phần khoá.
* `/etc/sudoers.d/memoryos-production-ci`: chỉ cho chạy đúng `deploy.sh`, `NOPASSWD`. **Ghim đúng tên `deploy.sh`** — staging từng ghim tên script cũ và mọi lần deploy chết ở `sudo: a password is required`. Kiểm bằng `visudo -c` trước khi đặt file vào chỗ.
* Thêm `memoryos-ci` vào group `docker` nếu `deploy.sh` cần, hoặc để nó gọi qua `sudo` — chọn một, ghi vào runbook.

Nghiệm thu: `ssh memoryos-ci@... 'sudo /apps/.../deploy.sh'` không hỏi mật khẩu; `ssh memoryos-ci@...` không mở được shell tương tác.

### 1.6 Sinh secret thành file

Máy production **chưa bao giờ** có file bootstrap Infisical và sẽ không bao giờ có. Mọi secret sinh tại chỗ, `0600`, chủ sở hữu `root`.

Sinh mới hoàn toàn: mật khẩu PostgreSQL (platform + app + keycloak), root/api/worker MinIO, ba mật khẩu Redis + bộ chứng chỉ TLS nội bộ, mật khẩu service OpenSearch, khoá API interpreter, khoá API Docling, hai client secret Keycloak (browser + admin) và secret provisioner.

**Ba khoá mã hoá credential — Google Drive, SharePoint, MCP — sinh mới, tuyệt đối không copy từ staging.** Dùng chung nghĩa là ai đọc được database staging cũng giải mã được credential của khách hàng thật.

Khoá API model là thứ duy nhất đến từ bên ngoài; xem 1.10.

Ngay sau bước này: thư mục secret là **bản duy nhất** của những giá trị đó. Không có vault giữ hộ nữa. Sao lưu ngoài host từ đây là bắt buộc, không phải "nên có" — đã ghi ở cuối tài liệu này.

Nghiệm thu: mọi đường dẫn `file:` mà `compose.base.yaml` khai đều tồn tại, khác rỗng, mode `0600`.

### 1.7 `.env.production` và realm Keycloak

`.env.production` chép từ `production.env.example`, mode `0600` của root, **regular file** (kịch bản deploy từ chối symlink). Bắt buộc `MEMORYOS_SEARCH_REPLICAS=0`: một node giữ mọi shard, có replica thì shard không bao giờ được gán, cluster không bao giờ xanh, healthcheck treo mãi.

Realm phải có **trước** khi `api` khoẻ được, vì `api` lấy JWK set lúc khởi động. Nên trình tự là:

1. `docker compose ... up -d postgres keycloak` — chỉ hai dịch vụ này.
2. Chạy `infrastructure/keycloak/configure-memoryos-realm.sh` với bộ biến tối thiểu: `KEYCLOAK_URL`, tài khoản admin, `MEMORYOS_BROWSER_CLIENT_SECRET` (file đã sinh ở 1.6), `MEMORYOS_BROWSER_REDIRECT_URI`, `MEMORYOS_KEYCLOAK_PROVISIONER_CLIENT_SECRET`, và danh tính chủ sở hữu đầu tiên.
3. **Không truyền** biến SMTP và bốn khối công cụ kiểm tra (Mailpit, pgweb, RedisInsight, MinIO Console) — script đã cho phép vắng mặt từ 0.6, nên realm production không có các client đó. Thành viên vào bằng JIT ([MEM-172](https://linear.app/memory-os/issue/MEM-172)), không qua email mời.
4. `MEMORYOS_INITIAL_OWNER_SUBJECT` trong `.env.production` phải **đúng bằng** subject mà script vừa tạo. Lệch là Tenant bàn giao cho một người không tồn tại.

Bootstrap security của OpenSearch chạy sau, khi overlay search đã lên.

Nghiệm thu: `auth.vadan.app` trả realm `memoryos`; JWK set lấy được từ ngoài; realm không có client nào của công cụ kiểm tra và không có cấu hình SMTP.

### 1.8 GitHub environment `production`

Giới hạn nhánh `main`. Biến `PRODUCTION_HOST`, `PRODUCTION_USER`, `PRODUCTION_KNOWN_HOSTS`; secret `PRODUCTION_SSH_KEY` là khoá riêng sinh ở 1.5. Không đặt biến auto-deploy — production chỉ chạy khi có người bấm.

`PRODUCTION_KNOWN_HOSTS` lấy bằng `ssh-keyscan` **từ một máy tin cậy**, và đối chiếu vân tay với cái đọc được trên chính máy chủ. Lấy qua đường không kiểm chứng thì mục đích của known_hosts mất sạch.

Nghiệm thu: workflow `deploy-production` đọc được cả ba biến (bước in tên host, không in khoá).

### 1.9 Điều kiện trước khi promote

Không bắt đầu Phase 2 khi còn thiếu một trong số:

* 1.1 xong và đã kiểm lại bằng một phiên SSH mới.
* Ba host TLS hợp lệ, `proxy.vadan.app` có Access List.
* Mọi file secret tồn tại và khác rỗng.
* Realm trả JWK set.
* `docker compose --env-file .env.production ... config --quiet` chạy sạch **trên chính máy chủ** — phiên bản Compose trên máy chủ và trên runner GitHub đã từng bất đồng một lần về default lồng nhau.
* Đích sao lưu ngoài host đã có, hoặc đã được chấp nhận rủi ro bằng văn bản.

### 1.10 Còn chờ người quyết

| Cần | Vì sao chặn | Ai |
| --- | --- | --- |
| Danh tính chủ sở hữu đầu tiên: username, email, subject | 1.7 không chạy được; Tenant không có người nhận | Chủ sản phẩm |
| DNS `proxy.vadan.app` | 1.3 không cấp được chứng chỉ | Người quản trị tên miền |
| Đường embedding: API ngoài hay tự chạy trên node `serving` | Quyết trước khi index dữ liệu thật; đổi sau là dựng lại toàn bộ index cho tới khi [MEM-135](https://linear.app/memory-os/issue/MEM-135) bước 2 xong | Chủ sản phẩm |

Khoá Docling **không** nằm trong danh sách này: Docling tự chạy trong stack, khoá là chuỗi mình tự sinh đặt ở hai đầu, không phải thứ đi mua. Khoá model chat cũng không: credential provider nằm trong database, mã hoá bằng khoá catalog, cấu hình ở trang quản trị — chỉ embedding mới cần giá trị lúc triển khai.

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
