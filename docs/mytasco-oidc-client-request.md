# Yêu cầu tạo OIDC client `memoryos-test` cho Mytasco

## Mục đích

MemoryOS dùng Keycloak làm OIDC broker cho đăng nhập Mytasco. Người dùng đăng nhập bằng cơ chế hiện có của Mytasco, gồm cả OTP số điện thoại; MemoryOS không nhận hoặc lưu mật khẩu/OTP.

## Thông tin cần cung cấp

- Realm/issuer URL Mytasco.
- OIDC Discovery URL, dạng:

  ```text
  https://sso.<mytasco-domain>/realms/<mytasco-realm>/.well-known/openid-configuration
  ```

- `client_id`: `memoryos-test`.
- `client_secret`: gửi qua kênh quản lý secret đã được hai bên thống nhất, không gửi qua chat/email thường.

## Cấu hình client Mytasco

| Setting | Giá trị |
| --- | --- |
| Client type | Confidential |
| Standard Flow / Authorization Code Flow | Bật |
| PKCE | S256 |
| Client Credentials / Service Account | Không cần |
| Web Origins/CORS | Không cần cho broker callback |

Alias broker trên Keycloak MemoryOS là `mytasco`; đây là định danh kỹ thuật ổn định.

### Redirect URI đăng nhập

Cho phép đúng URI sau, không dùng wildcard:

```text
https://auth.kl3in.tech/realms/memoryos/broker/mytasco/endpoint
```

### Post-logout redirect URI

Discovery document nên công bố `end_session_endpoint`. Nếu Mytasco Keycloak yêu cầu đăng ký redirect URI khi logout, cho phép đúng URI sau:

```text
https://auth.kl3in.tech/realms/memoryos/broker/mytasco/endpoint/logout_response
```

Mytasco Keycloak cần chấp nhận `id_token_hint` và `post_logout_redirect_uri` ở logout endpoint. MemoryOS dùng logout broker/back-channel khi upstream hỗ trợ; URI trên là callback fallback cho browser logout.

## Scope và claim

Client cần cho phép scope chuẩn:

```text
openid email profile
```

Cấu hình mapper/client scope để các claim sau xuất hiện trong **ID Token và UserInfo**, không chỉ access token:

```json
{
  "sub": "stable-keycloak-user-id",
  "email": "hung@tasco.com.vn",
  "email_verified": true,
  "name": "Nguyễn Văn Hùng",
  "given_name": "Hùng",
  "family_name": "Nguyễn"
}
```

Yêu cầu bắt buộc:

| Claim | Yêu cầu |
| --- | --- |
| `sub` | ID ổn định, duy nhất trong Realm; không dùng số điện thoại làm định danh có thể thay đổi |
| `email` | Email Mytasco hiện hành của user |
| `email_verified` | Boolean phản ánh trạng thái xác thực thực tế của email |
| `name` | Tên hiển thị nếu có |

`given_name` và `family_name` là optional; nếu không có, MemoryOS giữ trống.

### Chuẩn bị cho mapping Google Drive email khác Mytasco email

Nếu Mytasco quản lý quan hệ user với nhiều Google Drive email, có thể chuẩn bị custom claim đa trị, có xác thực:

```json
{
  "drive_emails": [
    "hung@gmail.com",
    "hung@tasco.com"
  ]
}
```

MemoryOS hiện chưa sử dụng `drive_emails`; claim này chỉ là đầu vào cho hạng mục mapping Actor → verified Drive emails sau này. Không cần gửi `groups`, `roles`, `department` hoặc `employee_code` cho integration hiện tại vì MemoryOS không dùng chúng để cấp quyền nội bộ.

## User test

Cần ít nhất hai user test:

1. **User A**: `email` đã verified và có quyền Google Drive trên ít nhất một file/folder của Source Auto Sync.
2. **User B**: `email` đã verified nhưng không có quyền trên các file/folder Google Drive đó.

Nếu login dùng OTP số điện thoại, vui lòng hướng dẫn quy trình test OTP hoặc cung cấp account test phù hợp.

## Không yêu cầu ở giai đoạn này

- Không cần quyền Admin Console Mytasco.
- Không cần danh sách toàn bộ user Realm.
- Không cần Client Credentials cho chatbot theo user.
- Không dùng access token Mytasco gọi thẳng API MemoryOS ở giai đoạn broker login này; đó là integration API riêng cần thỏa thuận issuer/audience/token policy sau.
