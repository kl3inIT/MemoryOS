# MEM-11 — Kế hoạch chỉnh UI theo Onyx

Ngày 2026-09-11. Kế hoạch được người dùng chấp thuận và triển khai trên `feat/mem-11-onyx-ui` sau audit code và phản hồi UI. Toàn bộ thay đổi runtime/UI, tests và tài liệu đi trong **một PR vào main**, dùng checkout hiện tại, không tạo worktree. [Kiểm chứng UI](ui-verification.md) phân biệt kết quả local với review, merge và nghiệm thu triển khai.

## Phạm vi và reference

- Phạm vi lần này: bố cục Chat/sidebar, quản lý hội thoại, Projects với instructions và hội thoại, edit/regenerate/chọn nhánh, chia sẻ có xác thực và feedback.
- Theo chỉ đạo mới nhất, chưa cải tổ custom agent/Persona, picker hoặc editor của nó. Việc gỡ dải shortcut trên Chat không xóa Persona hoặc sửa cấu hình/lịch sử đã lưu.
- Attachments và file của Chat/Project/Persona tiếp tục thuộc MEM-81; trang Project lần này không đưa vào tab Files hoặc upload control chưa có runtime.
- Đối chiếu MemoryOS `d0c68130b8218c68afd4dc54ee53802e7b38ba5b` với checkout Onyx `.tmp/onyx` tại `40eb240df370688ed6eeedc1272116e7829554ac` (2026-09-10).
- Onyx quyết định luồng thao tác và bố cục các vùng chức năng. Giữ typography, màu, controls và Radix stack của MemoryOS; tái sử dụng assistant-ui ở phiên bản repo đã pin (`@assistant-ui/react` 0.15.18, `@assistant-ui/ai-sdk` 0.0.4).
- Authenticated same-Tenant sharing, selected-branch semantics và instructions precedence tiếp tục theo [Chat contract](../../../specs/chat.md). Không suy từ component mẫu ra thêm quyền hoặc tính năng sản phẩm.

Reference source đã đọc: Onyx `layouts/chromes/AppChrome.tsx`, `sections/sidebar/AppSidebar.tsx`, `sections/sidebar/ChatButton.tsx`, `lib/projects/components/{CreateProjectModal,ProjectFolderButton,ProjectContextPanel,ProjectChatSessionList}.tsx`, `views/AppPage.tsx`, `hooks/useChatController.ts`, `app/app/message/HumanMessage.tsx`, `app/app/message/messageComponents/MessageToolbar.tsx` và `sections/modals/ShareChatSessionModal.tsx`, đều dưới `.tmp/onyx/web/src/`.

## 1. Một header và sidebar có thao tác tại từng mục

- Gộp thanh hội thoại vào header của AppShell; bỏ hàng thứ hai đang chứa các link gạch chân hoặc dãy nút Đổi tên/MemoryOS/Chia sẻ/Xóa.
- Header hội thoại hiển thị tên, nút Chia sẻ và menu `…` cho thao tác phù hợp. Menu hội thoại trong sidebar có Đổi tên, Chuyển vào dự án, Chia sẻ, Xóa; Xóa có xác nhận và được tách rõ khỏi thao tác thường.
- Đổi tên trực tiếp tại dòng hội thoại: Enter lưu, Escape hủy, thất bại giữ bản nháp và cho thử lại. Cập nhật header/sidebar sau API thành công.
- Sidebar có vùng Projects với danh sách dự án thực và nút tạo; dự án mở rộng hiển thị hội thoại bên trong. Giữ danh sách hội thoại gần đây và phân trang hiện có.
- Chuyển/gỡ hội thoại qua menu; kéo thả hội thoại vào dự án trên desktop theo reference. Menu vẫn là đường thao tác dùng được bằng bàn phím và trên mobile.
- Tên trang Project phản ánh đúng ngữ cảnh; sửa điều kiện AppShell hiện biến mọi pageTitle khác Search thành Chat.
- Nút, tooltip, empty/loading/error states trong phạm vi sửa dùng tiếng Việt nhất quán. Mobile dùng drawer hiện có, không ép hàng action dài vào header.

Owner files: `app-shell.tsx`, `chat-navigation.tsx`, `chat-session-settings.tsx` và các route/layout liên quan.

## 2. Project là nơi bắt đầu làm việc bằng Chat

- Tạo Project chỉ yêu cầu tên; tạo thành công mở ngay Project vừa tạo. Mô tả đang có được giữ; instructions chỉnh tại trang Project.
- Trang Project gồm tên/menu quản lý, phần instructions gọn, composer và danh sách hội thoại có thời gian cập nhật. Dùng cùng composer/model picker/streaming behavior với Chat.
- Empty state vẫn có composer để hỏi ngay. Không bắt người dùng bấm thêm nút tạo hội thoại rồi chuyển trang.
- Chọn/mở Project chỉ cập nhật ngữ cảnh bản nháp. Gửi câu đầu mới tạo session với `projectId`, dùng nội dung câu hỏi làm title ban đầu theo luồng New chat hiện có; không thêm inference đặt tên vào phạm vi này.
- Đưa Project draft route vào layout Chat phù hợp để chuyển từ Project draft sang `/chat/{id}` giữ nguyên runtime, bản nháp và SSE reader của lượt vừa nhận. Mở Project khác hoặc New chat tạo ngữ cảnh mới theo lifecycle hiện có.
- Move/remove đổi liên kết sau server commit, đồng bộ danh sách nguồn/đích/header; không clone transcript. Xóa Project có xác nhận rõ rằng hội thoại được giữ trong lịch sử chung.
- API tạo session đã nhận `projectId`; nối tham số qua `newChatSession`/transport thay vì tạo trước một session rỗng. Cấu hình hiệu lực và quyền vẫn do backend kiểm lúc gửi.

Owner files: `chat-projects-page.tsx`, `chat-page.tsx`, `chat-api.ts`, `chat-transport.ts`, `chat-workspace-api.ts` và route Project/Chat.

## 3. Dùng component assistant-ui với command của MemoryOS

| Phần | Tái sử dụng | Phần MemoryOS phải nối |
| --- | --- | --- |
| Danh sách/menu hội thoại | ThreadList layout và ThreadListItemMore primitives; dùng bản controlled khi danh sách do query/router quản lý | List/pagination, rename/delete/move, route và đồng bộ cache |
| Edit inline | EditMessage hoặc edit composer composition từ Thread element | EDIT endpoint, request UUID, pending/error/cancel; giữ nguyên nhánh cũ |
| Regenerate | MessageActions và bố cục ActionBar | REGENERATE endpoint dưới đúng USER, model selection của lượt và replay |
| Chọn nhánh | MessageBranches; BranchPickerPrimitive khi runtime đã nhận đủ branch state và action binding | Branch list/selected child từ server, expected-child conflict và tải lại đúng history |
| Feedback | Hai nút đánh giá và FeedbackDialog | Lưu/gỡ theo Actor và đúng assistant message; reason/comment qua API hiện có |
| Shared viewer | SharedConversation presentation và Thread read-only hiện có | Authenticated reads, selected transcript, revoke và source authorization |

Các mẫu styled được đưa vào source và chỉnh theo tokens hiện có, không mặc định nâng dependencies theo registry latest. Bản controlled là component có props/callback do ứng dụng cấp; dùng nó cho các command mà server đang sở hữu, thay vì tự dựng lại textarea/stepper/form hoặc tạo một transcript store khác.

Theo yêu cầu cài component qua CLI, đã chạy từ `web`: `pnpm dlx assistant-ui@latest add elements-edit-message elements-message-actions elements-message-branches elements-feedback-dialog elements-thread-list --overwrite --use-pnpm`. CLI tạo năm component và helper surfaces. Các component được đặt tại thư mục assistant-ui hiện có, tùy biến từ source cài về và nối command ứng dụng; helper demo cùng `tw-shimmer` không có consumer sau khi dùng tokens/controls của MemoryOS nên được gỡ. Không đổi phiên bản dependencies đã pin. License MIT của source được giữ cùng thư mục components. Reference source dùng để đối chiếu: assistant-ui `2c22f5d7fdeb45f10891a0ab2457d046ace668fa`.

Ràng buộc tích hợp đã xác nhận: `useAISDKRuntime.onEdit` cắt mảng message và gọi send, còn `onReload` gọi AI SDK regenerate; transport MemoryOS hiện chỉ nhận `submit-message`. Vì vậy không gắn trực tiếp nút native rồi coi command persistence đã được nối. Giữ `useChatRuntime` và command APIs hiện có; tái sử dụng presentation với callback EDIT/REGENERATE/SELECT đã có. Chỉ dùng native action cho phần đã có binding đầy đủ. Không sửa `node_modules`, thêm runtime thứ hai hoặc chuyển sang Assistant Cloud để có UI.

- Edit ngay tại tin nhắn với Hủy và Lưu/gửi; lỗi giữ nội dung đã nhập. Sau EDIT thành công, hiển thị nhánh và stream mới; nhánh cũ vẫn chọn lại được.
- Regenerate không thêm USER hoặc gửi lại câu hỏi vào cuối history. Mỗi thao tác có request identity ổn định qua retry; một thành công mở một lần chạy.
- Chọn phiên bản không gọi model; reload và gửi tiếp giữ lựa chọn trên server. Khi đang chạy/recovering, edit/regenerate/chuyển nhánh theo các guard hiện hành.
- Feedback có hai nút Hữu ích/Không hữu ích riêng. Mở form với loại đã chọn theo Onyx; bấm lại đánh giá đã lưu thì gỡ. Lưu thành công mới phản ánh trạng thái đã ghi; lỗi giữ form. Mỗi phiên bản câu trả lời có feedback riêng.

Owner files: `chat-thread.tsx`, `chat-message-actions.tsx`, `chat-editing-context.ts`, phần command bridge trong `chat-page.tsx`, các element được đưa vào source.

## 4. Chia sẻ trong một hộp thoại

- Header mở dialog với hai lựa chọn Riêng tư/Chia sẻ trong tổ chức và mô tả ngắn rằng người đọc cần đăng nhập.
- Từ trạng thái riêng tư: Tạo liên kết gọi API, giữ dialog mở, hiển thị link và sao chép khi thành công. Nếu clipboard bị từ chối, link vẫn chọn/copy thủ công được và UI báo đúng trạng thái.
- Khi đã chia sẻ, có Sao chép liên kết và chuyển về Riêng tư để thu hồi. Không bắt lưu, đóng rồi mở lại để lấy link.
- Giữ refetch revision và chặn submit khi chưa có quyền chia sẻ mới nhất; xử lý xung đột/lỗi trong dialog.
- Shared viewer giữ read-only: không composer, sửa, regenerate, feedback hoặc Stop. Dùng lại renderer/citations hiện có; mở tài liệu nguồn vẫn kiểm quyền người đọc.

Owner files: `SharingDialog` trong `chat-session-settings.tsx` hoặc component tách có consumer thực, `chat-dialog.tsx` nếu cần và `chat-shared-page.tsx`.

## 5. Kiểm chứng gắn với từng thay đổi

Mở rộng các test đang có trong `web/tests/e2e/chat.spec.ts`, `chat-workspace.spec.ts` và test transport; không dựng bộ test song song chỉ kiểm tên component.

| Luồng | Điều kiện đạt |
| --- | --- |
| Header/sidebar | Một header, menu dùng được bằng chuột/bàn phím/mobile; rename/cancel/failure và delete phản ánh đúng ở mọi nơi |
| Project | Tạo xong vào Project; vào trang/gõ/hủy chưa tạo session; gửi lần đầu tạo đúng một session trong Project và không làm mất stream khi đổi URL |
| Project membership | Menu move/remove và desktop drag/drop gọi cùng command; nguồn/đích đồng bộ; xóa Project giữ hội thoại |
| Instructions | Gửi từ Project dùng instructions của Project với builtin theo contract; sửa instructions tác động lượt sau |
| Edit/regenerate/branch | Đúng message IDs/parent, không thêm USER sai, retry không nhân bản, phiên bản cũ còn, chọn nhánh không inference, reload/gửi tiếp đúng nhánh |
| Feedback | Hai loại, lý do/ghi chú, gỡ/lỗi/reload và dữ liệu độc lập giữa các phiên bản |
| Sharing | Tạo và lấy link trong một dialog, clipboard failure, cached/stale revision, private/revoke và reader không có owner actions |
| Regression | Model selection, Stop/reconnect, citations/source panel, Search navigation, authority reset và mobile drawer giữ hành vi hiện có |

Kiểm ảnh chụp từ trình duyệt thật trên desktop và mobile cho New chat, hội thoại đã lưu, Project rỗng/có hội thoại, menu, inline editor và sharing dialog. So sánh với source Onyx theo luồng đã liệt kê; kiểm text wrapping, focus, scroll và trạng thái pending/error. Không coi HTTP 200 hoặc test API xanh là nghiệm thu UI.

Gates khi triển khai: frontend `pnpm --dir web check`, browser `pnpm --dir web test:e2e`, repository `gradlew.bat clean check --no-daemon`; kiểm IDE/compile phù hợp nếu phát sinh Java/config. Migration chỉ thêm khi một yêu cầu dữ liệu thật cần nó; đường Project draft đã có API nhận projectId nên không cần migration chỉ để nối lựa chọn này.

## 6. Thứ tự và giao nhận

1. Nối Project draft/first-send và layout dùng chung để composer có thể hoạt động đúng tại Project.
2. Hoàn thiện header, sidebar, Project workspace và các thao tác hội thoại.
3. Thay edit/branch/feedback bằng composition từ assistant-ui nối command server; hoàn thiện share dialog/viewer.
4. Kiểm từng luồng ngay khi làm, hoàn tất browser visual checks và các gate; cập nhật spec/test matrix theo implementation thực.
5. Một PR vào main, yêu cầu CodeRabbit cho thay đổi behavior; xử lý findings và CI trên commit cuối. Merge/deploy theo chỉ đạo delivery có hiệu lực, kiểm đúng revision trên staging và smoke các luồng vừa sửa.

Ghi evidence triển khai và nghiệm thu vào Linear; không mở PR tài liệu riêng chỉ để ghi receipt. MEM-11 giữ trạng thái chưa hoàn tất cho đến khi các điều kiện trong phạm vi của issue thực sự đạt; kế hoạch này không thay các receipt Phase 4/6 còn cần đối chiếu.

## 7. Review PR #93

Năm findings của CodeRabbit được xử lý trong cùng PR: tách mode header khỏi tên hội thoại; parse response tạo Project trước khi đóng dialog; chỉ đánh dấu trợ lý/dự án không khả dụng khi danh sách đã tải; Việt hóa nút mở/đóng điều hướng; sửa fixture tạo session trong Project theo đúng HTTP method. Kiểm hồi quy gồm tên `Chat`/`Search` trên mobile, response không khớp schema giữ dialog và bản nháp, danh sách trợ lý bị trì hoãn rồi tải thành công hoặc thiếu lựa chọn, tạo session bằng endpoint Project và phân trang danh sách. Không đổi thiết kế custom assistant hoặc mở rộng backend.
