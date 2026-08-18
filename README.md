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

API là OAuth2 Resource Server và fail-fast nếu thiếu identity configuration. Không lưu các giá trị binding hoặc credential trong repository.

```powershell
$env:MEMORYOS_IDENTITY_ISSUER = "https://auth.kl3in.tech/realms/memoryos"
$env:MEMORYOS_IDENTITY_JWK_SET_URI = "https://auth.kl3in.tech/realms/memoryos/protocol/openid-connect/certs"
$env:MEMORYOS_IDENTITY_AUDIENCE = "memoryos-api"
$env:MEMORYOS_IDENTITY_BINDING_ISSUER = $env:MEMORYOS_IDENTITY_ISSUER
$env:MEMORYOS_IDENTITY_BINDING_SUBJECT = "<oidc-subject>"
$env:MEMORYOS_IDENTITY_BINDING_ACTOR_ID = "<internal-actor-uuid>"

.\gradlew.bat :api:bootRun
```

Endpoints:

| Endpoint | Access | Kết quả |
| --- | --- | --- |
| `GET /actuator/health` | Public | Trạng thái API |
| `GET /api/identity/me` | Bearer JWT | Chỉ trả `{"actorId":"<uuid>"}` |

API kiểm tra JWT signature, exact issuer, audience `memoryos-api`, `exp`, `nbf` và nonblank `sub`. Sau đó exact `(issuer, subject)` được resolve thành `ActorId`; token hợp lệ nhưng chưa có binding vẫn trả `401`.

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

## Run the worker

```powershell
.\gradlew.bat :worker:bootRun
```

The foundation worker starts without a scheduler or job processor and exits cleanly. A durable processing loop will be introduced with the first worker-owned vertical slice.

## Scope

This foundation has no database, OpenFGA client, model provider, connector, MCP server, GraphRAG engine, or production deployment configuration. See [ADR 0001](docs/decisions/0001-controlled-modular-monolith.md) for the architecture decision.
