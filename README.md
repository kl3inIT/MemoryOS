# MemoryOS

MemoryOS starts as a controlled Spring Modulith monolith with separate API and worker deployables. The legacy OrgMemory repository is reference-only.

## Requirements

- JDK 25
- No system Gradle installation; use the checked-in Gradle wrapper

## Modules

| Module | Responsibility |
| --- | --- |
| `core` | Seven capability modules and their architecture rules |
| `api` | Spring Boot HTTP composition root and health endpoint |
| `worker` | Spring Boot background-processing composition root |

The core capabilities are `identity`, `authorization`, `knowledge`, `ingestion`, `retrieval`, `assistant`, and `audit`. Public contracts live at each capability root. Capability-owned persistence lives under that capability's `persistence` package and is not shared across capability boundaries.

## Build and test

Windows:

```powershell
.\gradlew.bat clean check
```

Linux or macOS:

```bash
./gradlew clean check
```

The `check` task compiles all modules, runs Spring Modulith verification, enforces ArchUnit dependency rules, and runs application context smoke tests.

## Chạy API

API là OAuth2 Resource Server và fail-fast nếu thiếu identity hoặc database configuration. Actor và exact external-identity binding được lưu trong PostgreSQL; repository không lưu credential hay giá trị binding.

```powershell
$env:MEMORYOS_IDENTITY_ISSUER = "https://auth.kl3in.tech/realms/memoryos"
$env:MEMORYOS_IDENTITY_JWK_SET_URI = "https://auth.kl3in.tech/realms/memoryos/protocol/openid-connect/certs"
$env:MEMORYOS_IDENTITY_AUDIENCE = "memoryos-api"
$env:MEMORYOS_DATABASE_URL = "jdbc:postgresql://127.0.0.1:15555/memoryos"
$env:MEMORYOS_DATABASE_USERNAME = "memoryos_app"
$env:MEMORYOS_DATABASE_PASSWORD = "<load from managed runtime secret>"

.\gradlew.bat :api:bootRun
```

PostgreSQL trên shared server chỉ bind loopback. Khi vận hành từ máy local, mở SSH tunnel tới server port `5555` trước khi dùng URL local ở trên; không public database port.

Endpoints:

| Endpoint | Access | Kết quả |
| --- | --- | --- |
| `GET /actuator/health` | Public | Trạng thái API |
| `GET /api/identity/me` | Bearer JWT | Chỉ trả `{"actorId":"<uuid>"}` |

API kiểm tra JWT signature, exact issuer, audience `memoryos-api`, `exp`, `nbf` và nonblank `sub`. Sau đó exact `(issuer, subject)` được resolve thành `ActorId`; token hợp lệ nhưng chưa có binding vẫn trả `401`.

### Provision actor và identity binding

Lấy exact `sub` từ token của normal OIDC user mà không log raw token, sau đó chạy command operator-only:

```powershell
.\gradlew.bat :api:provisionIdentityBinding --args="--memoryos.identity.provision.issuer=https://auth.kl3in.tech/realms/memoryos --memoryos.identity.provision.subject=<oidc-subject> --memoryos.identity.provision.actor-id=<internal-actor-uuid>"
```

Command dùng cùng ba biến `MEMORYOS_DATABASE_*`, chạy Flyway trước khi ghi dữ liệu và có các invariant:

1. Actor chưa tồn tại được tạo với UUID đã chọn.
2. Exact `(issuer, subject)` chưa tồn tại được bind vào actor.
3. Chạy lại cùng binding trả `unchanged`; không tạo duplicate.
4. Binding đã thuộc actor khác làm command fail và rollback; không tự rebind.
5. Xóa actor đang có binding bị foreign key `ON DELETE RESTRICT` chặn.

## Shared Keycloak

- Realm: `memoryos`
- Issuer: `https://auth.kl3in.tech/realms/memoryos`
- JWKS: `https://auth.kl3in.tech/realms/memoryos/protocol/openid-connect/certs`
- Public client: `memoryos-integration`
- Flow: Authorization Code + PKCE S256
- Redirect URIs: `http://127.0.0.1:8765/callback` và `http://localhost:8765/callback`

Realm `memoryos` là prerequisite do operator của shared Keycloak tạo một lần; provisioner không tạo hoặc xóa realm. Desired state trong `infrastructure/keycloak/` chỉ reconcile client `memoryos-integration` và audience mapper bên trong realm này.

Chạy `configure-memoryos-realm.sh` bằng `kcadm.sh` với tài khoản chỉ có `realm-management/view-realm` và `realm-management/manage-clients` trong realm `memoryos`; không cấp `realm-management/realm-admin`. Truyền admin password từ runtime secret hoặc interactive environment; không đưa password vào command history, Git, Linear hoặc log.

Ứng dụng không dùng tài khoản quản trị Keycloak. Real-login verification phải tạo normal user tạm, chạy Authorization Code + PKCE, gọi `/api/identity/me`, rồi xóa user.

Troubleshooting:

1. Startup thất bại vì thiếu biến môi trường là fail-fast đúng thiết kế.
2. `401` với token thật: kiểm tra `iss`, `aud`, thời gian token, `sub` và binding; không in raw token.
3. Không đổi identity bằng email hoặc username. Binding luôn dùng exact `(issuer, subject)`.
4. Không sửa realm ứng dụng `orgmemory`. Khi operator tạo realm `memoryos` lần đầu, Keycloak tự thêm client quản trị built-in `memoryos-realm` vào `master`; provisioner không truy cập hoặc sửa `master`.

## Persistence operations

- Shared PostgreSQL deployment: `/apps/postgres/docker-compose.yml`.
- MemoryOS database/user: `memoryos` / `memoryos_app`.
- Runtime password location: `/apps/memoryos/secrets/postgres-password`; không ghi giá trị secret vào Git, Linear, log hoặc command history.
- Flyway migrations thuộc `core/src/main/resources/db/migration/`. Migration đã apply là immutable; thay đổi schema bằng migration version mới, không sửa checksum cũ.
- Trước migration có thao tác phá hủy, tạo và kiểm tra backup của database `memoryos`. Shared PostgreSQL data nằm trong external volume do `/apps/postgres` quản lý; không recreate hoặc rename volume trong runbook MemoryOS.
- Khi provision conflict, đọc exact `(issuer, subject, actor_id)`, xác minh ownership và backup trước. Không sửa theo email/username/domain và không xóa binding để “thử lại”. Rebind là thao tác recovery có phê duyệt, thực hiện trong một transaction rồi kiểm tra `/api/identity/me`.
- Sau restore, chạy API hoặc provisioning command để Flyway validate schema history, kiểm tra actor/binding counts, rồi thực hiện Authorization Code + PKCE smoke test bằng normal user tạm.

## Production-first persistence

Dữ liệu có lifecycle hoặc phải tồn tại qua restart/deploy mặc định dùng deployable PostgreSQL, versioned migration, database-enforced uniqueness/foreign keys, transaction, backup và recovery trong cùng issue. In-memory/H2 chỉ dùng cho test cô lập hoặc experiment được ghi rõ; không được thay thế persistence production chỉ để giảm scope của feature.

## Run the worker

```powershell
.\gradlew.bat :worker:bootRun
```

The foundation worker starts without a scheduler or job processor and exits cleanly. A durable processing loop will be introduced with the first worker-owned vertical slice.

## Scope

This foundation has no OpenFGA client, model provider, connector, MCP server, GraphRAG engine, or production application deployment configuration. PostgreSQL persistence is deployable through runtime configuration and the shared-server database runbook above. See [ADR 0001](docs/decisions/0001-controlled-modular-monolith.md) for the architecture decision.
