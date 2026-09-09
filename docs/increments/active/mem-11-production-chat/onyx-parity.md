# MEM-11 — Phạm vi đối chiếu Onyx

Baseline đọc từ `.tmp/onyx`, commit `06aa2b09cc4aa5135fa2627e5235814e996f1514`. Đây là bảng phạm vi mục tiêu, không phải checklist tính năng MemoryOS đã hoàn thành. Xem [design](design.md) và [plan sáu phase](plan.md).

## Lần giao đầu

| Nhóm chức năng | Bằng chứng trong Onyx checkout | MemoryOS phải giao | Phase |
| --- | --- | --- | --- |
| Hội thoại và lịch sử | `backend/onyx/server/query_and_chat/chat_backend.py`: create/list/rename/delete sessions, send message, set latest message; `models.py`: parent/child messages | Conversation có owner/Tenant, history, rename/delete, selected branch và idempotent send | 2, 5 |
| Edit, regenerate, branch | `web/src/hooks/useChatController.ts`; message parent/latest-child handling ở backend | Backend sở hữu message tree; edit tạo nhánh, regenerate dưới user message đã chọn; UI hydrate/chọn nhánh | 2, 5 |
| Streaming và stop/reconnect | `chat_backend.py`: resume-stream/stop; `streaming_models.py`: typed packets | DB messages/results, transient sequence buffer và replay/fallback, stable message identity, stop và terminal partial states | 2, 4, 5 |
| Agent gọi công cụ | `backend/onyx/chat/llm_loop.py`; `backend/onyx/configs/chat_configs.py` | Native loop và giới hạn ở Phase 2; Retrieval tools thật ở Phase 3; nghiệm thu phối hợp context/cycles/Stop và token/cost policies ở Phase 4, vòng cuối tools-off khi còn được phép inference | 2–4 |
| Retrieval và nguồn | `backend/onyx/tools/tool_implementations/search/`; `streaming_models.py` citation packets | Authorized Search/context tools, multi-query/weighted RRF, validated section selection/expansion, evidence và source reader | 3, 4, 5 |
| Assistant cấu hình được | `backend/onyx/server/features/persona/models.py`: PersonaUpsertRequest, instructions, document sets, tools, model, access fields | Profile có instructions/starter prompts, sources/tools/model, validation và editor | 2, 5 |
| Model settings | Persona model overrides và chat request options | MEM-11 dùng một provider binding native, settings được validate; catalog/model selector nhiều provider, admin config và BYOK tổ chức chuyển sang MEM-77 | 1, 2, 5; catalog riêng |
| Attachments và Projects | `backend/onyx/server/features/projects/api.py`, `models.py`; chat file references | Private upload/readiness/retention, project instructions/files, scope và quyền thật | 5 |
| Chia sẻ hội thoại | `chat_backend.py`: sharing update/read paths | Chia sẻ có xác thực, quyền conversation và quyền nguồn được kiểm độc lập | 2, 5 |
| Feedback | `chat_backend.py`: feedback handler | Feedback gắn message/run và Actor, write path và UI trong trải nghiệm Chat | 5 |

“Bám sát Onyx” ở đây là hành vi sản phẩm và baseline retrieval đã chọn. Không port nguyên Redis/Vespa/Python internals sang PostgreSQL/OpenSearch/JVM. Những đường dẫn trên là bằng chứng nguồn để triển khai/đối chiếu; nghiệm thu còn cần corpus và browser scenarios.

## Mở rộng sau

[MEM-77](https://linear.app/memory-os/issue/MEM-77), assign `phamnhatanh811`, bị chặn bởi MEM-11: user chọn model được phép; admin quản lý provider/model; BYOK cấp tổ chức theo Onyx. Không có personal BYOK trong phạm vi đã chốt. Reference/contract chi tiết nằm trong issue; baseline một provider và một API process ở [design](design.md#baseline-một-provider-và-phần-mở-rộng).

Web search thêm native tool/provider và source rendering khi triển khai. Deep research cần đánh giá Embabel workflow và checkpoint/scheduling theo use case thực. Lần đầu giữ tools/model APIs có consumer thật và conversation/stream contracts dùng được; không tạo execution-strategy framework, versioned config engine hoặc placeholder research mode. “Mở rộng được” không hứa rằng không cần sửa code.

## Baseline và giới hạn phạm vi

Quyết định kiến trúc nằm duy nhất trong [design](design.md#baseline-da-chot): Onyx là baseline; MemoryOS port sang Embabel/Spring AI, assistant-ui và Retrieval hiện có. Phân biệt quyền conversation đối với transcript với quyền hiện tại khi search/mở nguồn. Chia sẻ transcript không cấp quyền mở tài liệu nguồn.

[Ma trận framework end-to-end](design.md#end-to-end-framework-integration) xác định native APIs, phần MemoryOS nối thêm và nghiệm thu từng luồng. Native cost accounting phục vụ đo/giới hạn lượt khi được cấu hình; không tự bổ sung billing UI hoặc hạn mức Tenant/User theo cửa sổ thời gian vào scope MEM-11.

Anonymous public sharing, incognito, voice, image generation, code execution và toàn bộ quản trị connector của Onyx chưa thuộc cam kết MEM-11. Search UI có thể hoàn thiện song song; Chat vẫn cần acceptance với backend và corpus thật.

## Cách nghiệm thu parity

Dùng cùng corpus có quyền và cùng câu hỏi cho các tình huống: câu hỏi đầu, follow-up, nhiều nguồn, không có bằng chứng, tool lỗi, edit/regenerate, reconnect, stop, concurrent tabs, file chưa ready và thu hồi nguồn. So sánh hành vi, evidence/citation support, latency/cost; không dùng tỷ lệ dòng code hoặc số route làm phần trăm tương đương.
