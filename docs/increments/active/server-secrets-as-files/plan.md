# Kế hoạch — Server secrets are files on the server

Bốn bước. Bước 1 và 2 không chạm môi trường nào đang chạy; bước 3 là lần chuyển đổi trên staging; bước 4 áp cho production, nơi chưa có gì để chuyển.

## 1. Launcher đọc mọi `<TÊN>_FILE`

Thay bốn khối viết tay trong `api/src/main/docker/application-launcher.sh` bằng một vòng lặp: biến nào tên `<TÊN>_FILE`, trỏ tới file đọc được, thì export `<TÊN>` từ nội dung file. Giữ xử lý riêng cho `MEMORYOS_REDIS_TLS_CA_FILE` vì nó ánh xạ sang `MEMORYOS_REDIS_TLS_CA_CERTIFICATE`.

Hành vi hiện tại không đổi: bốn khoá cũ vẫn được nạp y như trước, chỉ bằng đường chung. Kiểm chứng bằng test chạy launcher với một thư mục file giả và so sánh biến môi trường thu được.

## 2. Entrypoint thôi bắt buộc Infisical

`api-entrypoint.sh` hiện `test -r "$bootstrap_file"` rồi `exec infisical run`. Đổi thành: **không còn Infisical**, `exec` thẳng launcher.

Đây là bước làm hỏng staging nếu đi một mình, nên nó và bước 3 nằm cùng một pull request và cùng một lần rollout.

Gỡ `MEMORYOS_INFISICAL_BOOTSTRAP_FILE` khỏi `compose.base.yaml`, gỡ secret `memoryos_infisical_bootstrap` khỏi api và worker, gỡ Infisical CLI khỏi `Dockerfile` — image bớt một tầng tải về lúc build.

## 3. Chuyển staging

Trên máy staging, trước khi rollout:

1. Đọc từng khoá đang ở Infisical `staging`, ghi vào `/apps/memoryos/secrets/<nhóm>/<tên>.txt`, `0600` root. Không in giá trị ra terminal.
2. Khai `<TÊN>_FILE` tương ứng trong `/apps/memoryos/.env.staging`.
3. Rollout bản mới. Xác minh api, worker, web, interpreter khoẻ.
4. **Phép thử thật sự**: chặn đường ra `secret.nhuxuanviet.com` từ máy staging rồi restart api và worker. Chúng phải lên bình thường. Mở lại sau khi xác minh.
5. Xoá file bootstrap và thu hồi machine identity `staging` trên Infisical.

Nếu bước 3 hỏng: trả lại file bootstrap, rollback về release trước, môi trường quay lại như cũ.

## 4. Production dựng thẳng theo mô hình mới

Không có gì để chuyển: máy production chưa bao giờ có file bootstrap. Provisioning sinh thẳng secret ra file khi dựng từng dịch vụ — PostgreSQL, Redis, MinIO, OpenSearch, Keycloak — và chỉ hai thứ đến từ bên ngoài: khoá API model và khoá Docling.

Khoá mã hoá credential (Google Drive, SharePoint, MCP) **sinh mới hoàn toàn cho production**, không copy từ staging: dùng chung nghĩa là ai đọc được database staging cũng giải mã được credential của khách hàng.

## Tài liệu phải cập nhật trong cùng thay đổi

* [Runbook CI/CD](../../../runbooks/ci-cd.md): mục provisioning nêu thư mục secret và cách sinh; bỏ câu *"Application secrets continue to come from the existing Infisical/server path"*.
* [Runbook development-runtime](../../../runbooks/development-runtime.md): bảng biến ghi rõ cột "Infisical quản" giờ chỉ áp dụng cho `dev`; máy chủ đọc file.
* [`production.env.example`](../../../../infrastructure/deployment/production.env.example) và `staging.env.example`: thay `MEMORYOS_INFISICAL_BOOTSTRAP_FILE` bằng các `<TÊN>_FILE` mới.
* Thủ tục xoay vòng cho từng khoá: đổi trong dịch vụ → đổi file → rollout.
* ADR sau khi bước 3 chạy xong, vì lúc đó quyết định mới thật sự được áp dụng.

## Liên quan

Sao lưu ngoài host trở nên bắt buộc hơn: khi vault không còn giữ bản sao, thư mục secret là bản duy nhất. Việc đó vẫn đang chờ mở issue riêng.
