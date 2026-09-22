# Server secrets are files on the server

## Quyết định

Máy chủ — staging lẫn production — đọc **mọi** secret từ file dưới `/apps/memoryos/secrets/`, sinh ngay trên máy đó và không rời nó. Entrypoint của api và worker không còn gọi Infisical. Laptop lập trình viên **giữ nguyên** Infisical `dev`.

Chia theo **đối tượng dùng**, không theo môi trường: con người cần phân phát khoá dùng chung và thu hồi khi rời nhóm — đó là việc vault làm tốt; máy chủ cần khởi động được mà không phụ thuộc ai — đó là việc file làm tốt.

## Vì sao đổi

**Biên an ninh không đúng như vẻ ngoài.** File bootstrap của Infisical nằm ngay trên máy chủ, và nó là credential mở được toàn bộ environment. Ai đọc được file đó thì kéo được mọi khoá. Nghĩa là biên thật vẫn là "ai có quyền đọc file trên máy này" — y hệt mô hình file, không hơn. Vault ở đây không bảo vệ khỏi thứ người ta tưởng nó đang bảo vệ.

**Phụ thuộc lúc khởi động vào một máy ngoài tầm kiểm soát.** Container gọi `infisical run` khi khởi động. `secret.nhuxuanviet.com` không truy cập được thì không deploy, không restart, không rollout được — dù ứng dụng đang chạy vẫn chạy. Với môi trường khách hàng, đó là một điểm hỏng nằm ngoài môi trường đó.

**Hai cơ chế song song.** Repo đã để MinIO, Redis, TLS và khoá interpreter dạng file; phần còn lại ở vault. Người mới phải học cả hai, và mỗi khoá mới lại phải quyết định nó thuộc bên nào.

**Lợi ích xoay vòng khoá nhỏ hơn vẻ ngoài.** Đổi mật khẩu PostgreSQL vẫn phải đổi *trong PostgreSQL* trước; vault chỉ giữ một bản sao và không làm hộ phần khó. Với hai máy và một nhóm nhỏ, vault không trả đủ tiền vé.

## Mẫu đã có sẵn, chỉ mở rộng

`api/src/main/docker/application-launcher.sh` **đã** đọc bốn khoá từ file rồi export thành biến môi trường: `MEMORYOS_REDIS_PASSWORD_FILE`, `MEMORYOS_OBJECT_STORAGE_SECRET_KEY_FILE`, `MEMORYOS_INTERPRETER_API_KEY_FILE`, `MEMORYOS_REDIS_TLS_CA_FILE`. Đây không phải cơ chế mới; nó là mẫu hiện hành, chỉ chưa phủ hết.

Thay vì viết tay từng khoá, launcher xử lý chung: biến nào có dạng `<TÊN>_FILE` và trỏ tới file đọc được thì export `<TÊN>` từ nội dung file. `MEMORYOS_REDIS_TLS_CA_FILE` giữ xử lý riêng vì nó ánh xạ sang `MEMORYOS_REDIS_TLS_CA_CERTIFICATE` chứ không phải bỏ hậu tố.

## Khoá nào chuyển

Những khoá runbook đang ghi là Infisical quản: `MEMORYOS_DATABASE_PASSWORD`, `MEMORYOS_BROWSER_CLIENT_SECRET`, `MEMORYOS_KEYCLOAK_ADMIN_CLIENT_SECRET`, `MEMORYOS_EXTRACTION_DOCLING_API_KEY`, `MEMORYOS_OBJECT_STORAGE_SECRET_KEY`, `MEMORYOS_REDIS_PASSWORD`; cộng khoá mã hoá credential (Google Drive, SharePoint, MCP), khoá API model, và credential OpenSearch.

`MEMORYOS_SEARCH_REPLICAS` không phải secret — nó là cấu hình, thuộc `.env.<environment>`.

## Giữ nguyên

Secret vào container dạng Docker secret file chứ không phải biến môi trường, vì biến hiện nguyên văn trong `docker inspect`, log crash và `/proc/<pid>/environ`. Quyền `0600`, chủ sở hữu root. Không giá trị bí mật nào trong file Compose hay trong Git.

Infisical `dev` cho lập trình viên giữ nguyên: dev chạy `infisical run` trên máy cá nhân, không qua entrypoint của container, nên thay đổi này không chạm vào họ.

## Còn nợ sau bước này

`postgres` và `keycloak` vẫn nhận mật khẩu database dạng biến môi trường, vì container database tự tạo role của nó lúc khởi tạo lần đầu và script bootstrap đọc biến đó. Chuyển chúng sang file cần sửa `bootstrap-shared-databases.sh` và cách Keycloak nhận `KC_DB_PASSWORD` — là một thay đổi riêng, không gộp vào đây. Giá trị vẫn nằm trong `.env.<environment>` mode `0600` của root, nên biên bảo vệ trên đĩa không đổi; khác biệt là chúng hiện trong `docker inspect` của hai container đó.

## Ngoài phạm vi

* Đổi quy trình của lập trình viên.
* Xác thực ngắn hạn gắn với máy thay cho client secret tĩnh — chỉ đáng làm nếu quay lại dùng vault cho máy chủ.
* Vault cho nhiều khách hàng, nhiều deployment. Nếu tương lai có, quyết định này phải được xem lại: lúc đó vault trả đủ tiền vé.

## Rủi ro

**Một lần chuyển đổi trên staging.** Rủi ro nằm ở lần chuyển, không nằm ở trạng thái sau. Đổ khoá ra file, bỏ file bootstrap, rollout, xác minh — nếu hỏng thì khôi phục bằng cách trả lại file bootstrap.

**Sao lưu trở nên quan trọng hơn.** Khi vault không còn giữ bản sao, thư mục secret là bản duy nhất. Nó phải nằm trong kế hoạch sao lưu ngoài host — vốn đã là việc phải làm và đang chờ mở issue riêng.

**Xoay vòng khoá cần thủ tục viết ra.** Mỗi khoá một cách: đổi trong dịch vụ rồi đổi file rồi rollout. Runbook phải nêu từng cái, không để người sau tự đoán.

## Nghiệm thu

Staging khởi động và chạy khoẻ với **file bootstrap Infisical đã bị xoá** và máy chủ **chặn đường ra `secret.nhuxuanviet.com`** — chứng minh không còn phụ thuộc. Production dựng theo đúng cách đó ngay từ đầu, không bao giờ có file bootstrap. Không lần gọi `infisical` nào trong entrypoint.
