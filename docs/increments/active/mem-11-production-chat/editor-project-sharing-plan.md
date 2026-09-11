# MEM-11 — Kế hoạch hội thoại, Persona, Projects, sharing và feedback

Ngày 2026-09-11, sau khi tách attachments sang MEM-81. Người dùng đã cho phép triển khai trong một PR, checkout hiện tại và không tách worktree. [Design](design.md) giữ nền kiến trúc; [plan](plan.md) giữ scope và điều kiện giao. Theo chỉ đạo tiếp theo, [persistence review](persistence-review.md) bổ sung Spring Data JPA cho các lifecycle phù hợp; Users/Groups để Nhật xử lý trong MEM-55/MEM-36.

## Bằng chứng và nền có sẵn

Onyx reference `.tmp/onyx` tại `40eb240df370688ed6eeedc1272116e7829554ac`:

| Nhóm | Source đã đọc | Hành vi reference |
| --- | --- | --- |
| Rename/delete | `backend/onyx/db/chat.py:321,379`; `configs/chat_configs.py:91` | Update description; mặc định soft delete, có cấu hình hard delete |
| Edit/regenerate/branch | `backend/onyx/chat/process_message.py:733`; `db/chat.py:844`; `server/query_and_chat/chat_backend.py:947` | Parent USER thì dùng lại user message để regenerate; parent khác tạo user message mới; history cắt ở parent được chọn; parent/latest-child xác định nhánh |
| Persona | `backend/onyx/server/features/persona/models.py:159`; `api.py:345,374,580` | Tên/mô tả, instructions, starters, document sets/tools/model và quyền; create/edit/delete có kiểm quyền riêng |
| Projects | `backend/onyx/server/features/projects/api.py:159,401,462,490,632,652`; `chat/chat_utils.py:917` | Project riêng của user, instructions, chuyển/gỡ chat; xóa Project chỉ unlink chat; custom Persona thắng Project kể cả prompt rỗng |
| Sharing | `web/src/sections/modals/ShareChatSessionModal.tsx:20`; `web/src/app/app/shared/[chatId]/SharedChatDisplay.tsx:71`; `backend/onyx/server/query_and_chat/chat_backend.py:349` | Link theo session, bật/tắt public/private; shared UI render latest message chain; permission backend có đường shared riêng |
| Feedback | `backend/onyx/server/query_and_chat/models.py:90`; `server/query_and_chat/chat_backend.py:992`; `db/feedback.py:216` | Positive/negative, nhận xét/lý do, chỉ cho assistant output và có remove feedback |

Nền trước thay đổi này: MemoryOS có `chat_session` và cây `chat_message` từ V18, một RUNNING reply/session, transaction reservation và native execution/SSE. `ChatTurnPersistence.reserve` chỉ cho gửi ở tip nhánh hiện tại; idempotency nằm trên USER row nên chưa đủ cho regenerate chỉ tạo ASSISTANT. `DefaultChatSessionService` mới create/list/get/history. `JdbcChatRepository.provisionPersona` đang upsert cấu hình mỗi create/send và model-list đường default; editor cần thay đổi tất cả caller này để không ghi đè dữ liệu người dùng. Model catalog/resolver và picker đang có tiếp tục được dùng.

## 1. Hội thoại và nhánh

### Trải nghiệm

- Menu trên mỗi hội thoại cho đổi tên/xóa. Rename cập nhật header và sidebar; thất bại trả lại tên đã lưu. Delete có xác nhận nêu đúng hội thoại bị xóa, sau thành công chuyển về New chat nếu đang mở hội thoại đó.
- Sửa một câu hỏi cũ mở editor ngay tại message; Lưu và gửi tạo USER mới cùng parent với câu hỏi cũ và ASSISTANT mới. Nhánh cũ và các câu trả lời sau nó vẫn giữ nguyên.
- Regenerate trên assistant tạo ASSISTANT mới dưới đúng USER cũ, không tạo thêm USER hoặc gửi lại câu hỏi như một lượt mới ở cuối lịch sử.
- Bộ chọn 1/2, trước/sau tại điểm rẽ hiển thị các phiên bản; chọn nhánh cập nhật latest-child và tải lịch sử tương ứng. Gửi tiếp dùng nhánh đã chọn; reload giữ đúng lựa chọn. Đổi nhánh tự nó không gọi model.

Ví dụ: `U1 → A1 → U2 → A2`. Sửa U1 tạo `U1' → A1'` dưới cùng root; regenerate A1 tạo A1' dưới U1, còn nhánh A1 → U2 → A2 vẫn đọc lại được.

### Backend và dữ liệu

- Mở rộng services/repository/controllers hiện có bằng rename/delete, edit, regenerate, liệt kê phiên bản và chọn nhánh. Tên route và generated client chốt khi triển khai contract, không tạo một runtime Chat thứ hai.
- Migration additive giữ nguyên message IDs/parent/latest-child cũ; bổ sung deleted state và request identity cho các command mới. Retry cùng command trả lại cùng reservation; đổi operation/target/text/model dưới cùng identity phải conflict.
- Edit/regenerate cùng transaction kiểm owner/Tenant, target thuộc session/nhánh hợp lệ, giới hạn message và một active reply/session. Chỉ sau reservation mới vào execution; mọi lần tạo mới dùng setup/context/tool state riêng từ ancestor chain đúng.
- Đề xuất soft delete theo mặc định Onyx: không trả session đã xóa qua list/history/shared path, hủy native run đang chạy và bỏ RAM replay. Session lock/deleted state phải ngăn send mới và late completion làm hội thoại xuất hiện lại. Không tự thêm UI restore hay cam kết hard purge khi chưa có policy tương ứng.
- Khi đang generate, edit/regenerate/chọn nhánh yêu cầu Stop hoặc chờ lượt kết thúc; backend kiểm lại để hai tab không tạo nhánh/turn tranh nhau. Rename có thể thực hiện độc lập. Việc thay context không được làm thay đổi một request inference đã bắt đầu.

## 2. Persona và cấu hình trợ lý

### Trải nghiệm

- Danh sách trợ lý, tạo/sửa/chọn và xóa trợ lý do mình quản lý; editor gồm tên, mô tả, instructions, câu hỏi gợi ý, nguồn được dùng, tools hiện có và model mặc định/options được phép.
- Picker ở Chat cho chọn Persona. Starter prompts áp dụng khi bắt đầu; settings mới áp dụng từ lượt sau, lịch sử và câu trả lời cũ không được viết lại.
- Chỉ hiển thị tools/model/source thực có và được phép. Chọn nguồn trong Persona thu hẹp search theo quyền của actor; không cấp thêm quyền đọc nguồn.
- Các phần upload/file của Persona thuộc MEM-81; editor lần này không có upload control hoặc dữ liệu file giả.

### Backend và dữ liệu

- Mở rộng bảng persona và các liên kết có consumer thật; dùng service/repository trong Chat, validation theo token/options/capabilities hiện có và optimistic revision khi hai editor cùng lưu.
- Chuyển builtin configuration sang seed/init idempotent một lần và dữ liệu DB do editor sở hữu. Cập nhật cả create, send và catalog-list caller đang gọi provisionPersona; giữ model configuration IDs, session references và cấu hình đã có khi migration.
- Mỗi lượt resolve Persona/settings mới rồi giữ trong setup của lượt; model override/default/fallback tiếp tục theo contract catalog hiện có và trả lý do fallback thực. Không mở catalog provider/BYOK UI thuộc MEM-77.
- Persona bị xóa hoặc không còn quyền dùng không làm mất transcript; lượt mới phải chọn cấu hình hợp lệ, không âm thầm dùng instructions đã bị thu hồi. Delete giữ reference/tombstone cần để lịch sử hiển thị.
- Quyền áp dụng: custom Persona riêng của creator; builtin do actor có MODELS_MANAGE quản lý. Không thêm owner-group/editor grants hoặc dùng chung custom Persona trong phạm vi này.

## 3. Projects với instructions và hội thoại

- Project riêng của owner/Tenant, gồm tên/mô tả/instructions. UI tạo/sửa/xóa Project, mở danh sách hội thoại của Project, tạo chat trong Project, chuyển chat vào/ra Project.
- Thêm project persistence và nullable project reference ở chat_session. Move phải kiểm quyền cả session và Project đích, không chỉ nhận Project ID từ client. Không clone transcript khi chuyển Project.
- Xóa Project gỡ liên kết các hội thoại, giữ các chat trong lịch sử chung, theo Onyx. Thao tác cập nhật danh sách/sidebar và header sau commit.
- Custom Persona dùng instructions của Persona kể cả rỗng; chỉ builtin/default Persona dùng Project instructions. Không concatenate hai bộ instructions. Chat đang chạy giữ setup đã resolve; lần gửi tiếp dùng cấu hình mới.
- Chuyển một hội thoại sang Project khác giữ lịch sử của chính hội thoại đó. Không lấy lịch sử từ các hội thoại khác trong Project làm context; không tái sử dụng instructions/tool state cũ trong setup mới.
- File của Project được triển khai hoàn toàn ở MEM-81. PR này không thêm file tables/endpoints trước khi có consumer.

## 4. Chia sẻ có xác thực và feedback

### Sharing — Tenant member có link

Người đọc phải đăng nhập trong cùng Tenant; owner quản lý session, reader chỉ đọc; quyền transcript độc lập với quyền nguồn. Sau yêu cầu bắt đầu triển khai, áp dụng phương án Tenant member có link như đã đề xuất và thông báo trong phiên: owner bật chia sẻ → sao chép link → Tenant member có link được đọc. Không thêm recipient/group grants trong phạm vi này.

- Với đề xuất Tenant link, lưu sharing state tại session và giữ private mặc định; owner bật/tắt ở dialog. Đây là link tới hội thoại hiện hành, không phải snapshot xuất bản bất biến; trang đọc hiển thị nhánh hiện đang chọn và nội dung đã lưu, như reference shared viewer.
- Shared reader không có composer, edit/regenerate/delete hoặc quyền Stop; mở trang không đăng ký vào stream điều khiển của owner. Không gọi model khi mở link.
- Thu hồi share, xóa session hoặc mất Tenant membership chặn lần đọc API tiếp theo; cache và hydration không được dùng dữ liệu của owner để bỏ qua kiểm quyền reader. Không hứa thu hồi được nội dung đã được người đọc xem/sao chép trước đó.
- Citation trong transcript vẫn là dữ liệu đã chia sẻ; mở tài liệu nguồn phải qua reader kiểm quyền hiện có, không dùng authority của owner. Sharing không tự cấp quyền Search hoặc file trong MEM-81.

### Feedback

- Nút hữu ích/không hữu ích tại assistant message, cho nhập nhận xét/lý do khi cần, đổi hoặc gỡ đánh giá; reload phản ánh đánh giá của actor đó.
- Bảng feedback gắn Tenant/Actor và assistant message/run; mỗi actor có một đánh giá hiện hành cho một output, upsert/remove idempotent. Đây là mapping đáp ứng contract Actor của MemoryOS, không khẳng định Onyx đã dùng cùng schema/upsert.
- Regenerate tạo output mới với feedback riêng. Không gắn đánh giá vào USER, không chuyển feedback từ một phiên bản câu trả lời sang phiên bản khác.
- Lần này feedback ở hội thoại owner được phép thao tác; shared reader vẫn read-only. Nội dung nhận xét lưu ở Chat persistence, không đưa vào generic logs/traces và không mở dashboard/billing mới.

## Thứ tự giao trong một PR

1. Triển khai hội thoại/branch và feedback với migrations, command concurrency và API/UI. Chốt các lựa chọn quyền ở nhóm liên quan, không để câu hỏi sharing chặn nhóm hội thoại độc lập.
2. Persona editor/selection, chuyển builtin settings ownership và áp options/source/tool scope vào inference thật.
3. Project CRUD/move và precedence; giữ history đúng khi chuyển Project/Persona.
4. Sharing UI/API/reader và revoke theo lựa chọn người dùng; giữ độc lập quyền mở nguồn.
5. Kiểm contract mới cùng mỗi nhóm và E2E các luồng liên quan; không chạy lại toàn bộ Phase 4 như một phase mới. Required repository/CI/review gates giữ nguyên, attachments/latency follow-up không kéo vào PR.

## Nghiệm thu tập trung

- Rename/delete đúng owner, hai tab thao tác, delete trong lúc stream và late finish không làm sống lại session.
- Edit ở đầu/giữa lịch sử; regenerate không thêm USER; hai nhánh có đủ history; chọn/reload/gửi tiếp đúng nhánh; retry không sinh hai model runs.
- Persona editor không bị config upsert ghi đè; simultaneous edits conflict rõ; model/tool/source bị thu hồi không được bypass từ settings cũ.
- Project move kiểm cả hai đầu; xóa Project giữ chat; custom Persona prompt rỗng vẫn không nhận Project prompt; đổi settings không đổi lượt đang chạy.
- Reader cùng Tenant đúng phạm vi được đọc; sai Tenant, private/revoked/deleted bị từ chối; các write/Stop endpoints vẫn chỉ cho owner và nguồn vẫn được kiểm độc lập.
- Feedback create/update/remove/reload đúng actor/output, tách biệt giữa các lần regenerate.

Implementation và kiểm chứng local đã ghi trong Chat spec/test matrix và [editor verification](editor-verification.md). Nghiệm thu triển khai và issue closure vẫn cần bằng chứng sau merge.
