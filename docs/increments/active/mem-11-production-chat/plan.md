# MEM-11 — Sáu phase triển khai

[Design](design.md#baseline-da-chot) sở hữu quyết định kiến trúc; [parity](onyx-parity.md) sở hữu phạm vi. [Product verification](verification.md) ghi bằng chứng Phases 2.1–2.4, backend grounded Chat 3.1 và UI 3.2 đã kiểm local. Nghiệm thu corpus/model rộng vẫn cần thực hiện. Web search/deep research nằm ngoài lần giao đầu.

## Bổ sung nền provider/model

Phases 2.1–2.4 đã merge qua PR #86; backend catalog/selection/BYOK và public adapter đã merge qua PR #88. [MEM-77](../mem-77-provider-backend/plan.md) tiếp tục UI quản trị provider/model, Tenant/Persona model default, inference self-host vận hành thật và tích hợp OpenAI-compatible qua native binding. MEM-77 dùng SmolLM2-135M của MEM-66 làm model nghiệm thu ban đầu; không yêu cầu model lớn hơn hoặc chất lượng câu trả lời cao hơn để đóng issue. Bằng chứng MEM-66 không thay nghiệm thu runtime/tích hợp trên máy đích. Main `0310a24` đã có Chat model selector và Persona editor thuộc MEM-11; lần tích hợp MEM-77 giữ nguyên các flow này và JPA lifecycle mới. Access UI mới vẫn hoãn, quyền và ownership hiện có vẫn được kiểm.

## Phạm vi PR đang triển khai — chốt ngày 2026-09-10

Ngày 2026-09-11 người dùng yêu cầu tách attachments thành [MEM-81](https://linear.app/memory-os/issue/MEM-81), Backlog, để xử lý các phần khác trước. Toàn bộ phần còn lại của MEM-11 sau khi tách được giao trong **một PR duy nhất**, làm trong checkout hiện tại, **không tách worktree**. Quyết định này thay thế yêu cầu gộp attachments vào PR trước đó; không tự tách thêm các nhóm bên dưới. Giới hạn dưới 100 file của các lần chia PR trước không được dùng để bỏ chức năng khỏi phạm vi đã điều chỉnh. Giữ nguyên yêu cầu review, migration cần thiết và verification phù hợp với thay đổi.

- [x] Hội thoại: đổi tên/xóa; edit tạo nhánh, regenerate dưới user message được chọn, chọn/tiếp tục nhánh; feedback gắn message/run và Actor. Giao cả API, persistence/reservation, UI và quyền thao tác.
- [x] Persona: tạo/sửa/chọn trợ lý, instructions, starter prompts, nguồn/tools và model settings được phép; chuyển rõ quyền sở hữu settings khỏi builtin configuration trước khi editor ghi DB.
- [x] Projects: quản lý hội thoại và instructions theo Project; giữ đúng precedence với Persona và không lẫn context khi chuyển Project hoặc nhánh. File của Project/Persona được giao cùng attachments trong MEM-81.
- [x] Sharing: chia sẻ có xác thực trong Tenant, reader chỉ đọc, thu hồi quyền; quyền conversation và quyền mở nguồn độc lập. Áp dụng owner bật/tắt link cho thành viên cùng Tenant; không thêm Group grants.

[Kế hoạch chi tiết bốn nhóm](editor-project-sharing-plan.md) giữ UX/backend/UI, source reference, quyền đã chọn và handoff persistence và tình huống nghiệm thu. Regenerate chỉ tạo ASSISTANT dưới USER cũ; xóa Project giữ hội thoại; Persona editor phải thay đường upsert mặc định hiện tại trước khi lưu settings từ UI.

Mỗi nhóm đối chiếu source Onyx theo [baseline phần còn lại](design.md#baseline-cho-phần-còn-lại--chốt-ngày-2026-09-10), triển khai và kiểm thử ngay trong cùng thay đổi. Tái sử dụng bằng chứng Phase 2–3 cho các mục Phase 4 đã đáp ứng; chỉ bổ sung tình huống thiếu bằng chứng hoặc bị thay đổi bởi tính năng mới. Nghiệm thu cuối giữ các yêu cầu còn lại ở Phase 6, không tính lại toàn bộ coverage cũ thành công việc mới. Phần tối ưu latency thêm và sửa đăng nhập CI đang tạm gác theo chỉ đạo; không đưa vào phạm vi PR này. Catalog admin UI và tích hợp provider local thuộc MEM-77.

Attachments đã chuyển toàn bộ sang [design MEM-81](../chat-attachments-production/design.md) và [plan MEM-81](../chat-attachments-production/plan.md): upload, giới hạn 100/250 MiB, readiness, file message/Persona/Project, read_file/vision/tables, private retrieval và cleanup. Không thêm placeholder upload controls, schema hoặc API file trong PR đi trước. MEM-81 là related issue, không chặn giao MEM-11 theo phạm vi điều chỉnh.

MEM-11 giữ In Progress tới khi phạm vi còn lại sau khi tách được giao và nghiệm thu. Việc tạo MEM-81 không làm MEM-11 hay attachments thành Done. Bằng chứng merge/deployment sau merge ghi trong Linear; không mở PR chỉ để cập nhật trạng thái.

## Phase 1 — Kiểm chứng framework và contract

Hoàn tất 2026-09-09 ở boundary cô lập; xem bằng chứng và giới hạn trong verification.

- [x] Đọc Onyx source; chốt baseline in-memory setup, execution nền trong API, transient replay và history theo conversation access.
- [x] Resolve stable candidates và compile isolated JVM bridge.
- [x] Real-model probe: tool loop/events, tool thứ ba và local cancellation propagation; 8 assertions PASS với giới hạn trong verification.
- [x] assistant-ui adapter: 5 tests dùng recorded events, không phải browser end-to-end.
- [x] Browser → authenticated HTTP/SSE → background execution/model qua proxy thật; disconnect/reconnect và fallback khi cache gap/expiry.
- [x] Redis outage/timeout giữa live stream: writer tiếp tục, replay degraded/fallback, bounded cache copy và trả tài nguyên.
- [x] Provider 429/503/timeout/partial EOF: một failed terminal, giữ partial answer, không tự retry/lặp tool; xác nhận trên browser.
- [x] Cancel trước token đầu tiên, khi tool block, complete/cancel race; phân biệt local cancel với provider behavior.
- [x] Last-cycle tools-off, malformed arguments, usage/continuation metadata và structured-output contracts.
- [x] Model role binding trong `ChatTurnSetup`, native framework reuse và custom-assistant/Project precedence.
- [x] Chọn dependency/model baseline theo [verification](phase-1-verification.md): OpenAI Chat Completions/gpt-5-mini; profile khác cần kiểm riêng. MEM-66 là nghiên cứu vLLM/gateway, không là prerequisite bắt buộc cho provider hiện có.

Exit: có bằng chứng framework/transport tại boundary cô lập và xác định gap MemoryOS phải xử lý. Fake auth, RAM outcome, fixture tools không thay product IAM/PostgreSQL/Retrieval acceptance. Chi tiết kết quả và giới hạn nằm trong verification.

### Probe bổ sung — Chốt public entry point

Hoàn tất 2026-09-09 theo yêu cầu kiểm thêm một vòng. [Kết quả và tradeoff](framework-entrypoint-verification.md): chọn public PromptRunner, native SpringAiLlmService/streamer/tool loop; public LlmService delegate khai báo capability đã kiểm và ChatModel decorator giữ policy/metadata theo lượt. Có real-model và HTTP fault evidence; không cần custom inference streamer của Phase 1 cho baseline này. Không đổi số phase triển khai hoặc mở rộng product scope.

Audit kế tiếp đã hoàn tất với [18 accounting/factory assertions](framework-entrypoint-verification.md#end-to-end-audit--2026-09-09). [End-to-end framework integration](design.md#end-to-end-framework-integration) là contract xuyên sáu phase: dùng native accounting/budget, tách các loại giới hạn, đánh giá factory theo consumers và lifecycle; entrypoint probe không đồng nghĩa toàn bộ kiến trúc đã acceptance.

## Phase 2 — ChatSession và một lượt Chat xuyên sản phẩm

Thiết kế chi tiết, glossary/schema và HTTP/SSE nằm ở [design](design.md#thiết-kế-chi-tiết-phase-2). Phase 2.1 đã triển khai và kiểm chứng trong working tree ngày 2026-09-09; chưa commit/deploy phần Chat. Xem [verification](verification.md). Các checkbox chỉ được đánh dấu sau khi code và verification tương ứng đạt. Persona là tên model nghiệp vụ; conversation/hội thoại là cách gọi ChatSession trên UI.

### 2.1 — Persistence, identity và quyền

- [x] Khai báo closed Chat capability với public contracts cần dùng; cập nhật boundary tests/ADR theo conventions khi implementation bắt đầu. Chỉ thêm dependency IAM hiện có consumer; Retrieval ở Phase 3.
- [x] Migration additive cho Persona, ChatSession và ChatMessage: root/parent/latest child, owner/Tenant, status/outcome, command identity và tối đa một RUNNING/session. Không tạo bảng ChatRun; single-model runId bằng reserved assistantMessageId.
- [x] Concrete repositories giữ SQL/locks/conditional writes; application kiểm quyền và sở hữu transaction. Provision Persona mặc định bằng đường runtime bình thường, không đưa fixture/probe server vào product.
- [x] Kiểm PostgreSQL thật: parent/child khác session, sai Tenant/Actor, send đồng thời, cùng clientRequestId với command giống/khác, rollback và terminal winner. History/list có giới hạn và chỉ lộ dữ liệu có quyền.

Đã có POST/GET sessions và GET session/history, cùng generated client. Phase 2.2 đã nối reservation/finalization vào send/Stop thật; SSE chưa được expose.

Đã chốt giữ `JdbcClient`; xem tradeoff trong [design](design.md#thiết-kế-chi-tiết-phase-2). Phase 2.2 dùng lại persistence; không chuyển JPA hoặc thêm benchmark hai implementation.

Follow-up đã triển khai: tách ID assistant message ban đầu của request khỏi con trỏ chọn nhánh; test PostgreSQL kiểm retry sau khi chọn câu trả lời khác và tiếp tục hội thoại. Không thêm endpoint regenerate/sharing vào Phase 2.1. Design/spec phân biệt phần bám Onyx và phần MemoryOS tự chọn; bằng chứng gate ở [verification](verification.md#original-reply-idempotency-follow-up--2026-09-09).

Exit: session/message tree được lưu và đọc lại đúng quyền; retry mạng không tạo message hoặc execution thứ hai. Root và placeholder không lọt vào model context.

### 2.2 — Setup và execution nền

- [x] Chọn public inference entry point: PromptRunner streaming, native model/options/messages/tools. Đánh giá ConversationFactory/Chatbot trên toàn parity; native wiring khả thi, retain per-turn execution cho Chat hiện tại theo [design](design.md#end-to-end-framework-integration), không bác factory vì JDBC còn tồn tại.
- [x] Kiểm native path và extension qua high-level caller: text/history/Persona/model, tool context/events, usage, EOF, Stop, vòng cuối; thêm real HTTP faults, model thật và native context isolation/cleanup. Gap và giới hạn ghi trong [verification](framework-entrypoint-verification.md); đây chưa là product acceptance.
- [x] Resolve Persona/model/options/history vào ChatTurnSetup trong RAM; native framework messages cho input. Settings thay đổi chỉ tác động lượt sau; metadata thực dùng được lưu cùng answer, không lưu full config snapshot.
- [x] Thêm dependencies cùng caller thật qua entry point đã kiểm. Framework sở hữu inference/tool loop; không mặc định gọi StreamingToolLoop.create hoặc dựng streamer riêng. Không đưa fixture tools/Search giả vào product.
- [x] Port extension đúng gap đã kiểm: capability binding; stream finish/usage capture và last-cycle request policy. Native per-turn ExecutingOperationContext, cleanup framework process reference và HTTP client lifecycle. Không mặc định gom mọi policy vào một decorator hoặc dựng engine mới.
- [x] Nối streaming usage → native Usage/LlmInvocation → process totals/PricingModel; record một lần, giữ partial/unknown và model/run correlation. Nếu dùng native listener events thì phát event đúng một lần, không suy rằng recordLlmInvocation tự phát. Không tự thêm cost calculator/ledger hay billing UI.
- [x] Tách cấu hình cycle cap, output/context token limits, deadline và admission. Native Budget/EarlyTerminationPolicy xử lý token/cost cap tại inference boundary khi được cấu hình; tool boundary thật được nghiệm thu cùng Retrieval ở Phase 3; validate semantics unknown pricing. Baseline sáu cycles trong trần native đã kiểm; final tools-off chỉ khi còn được phép inference. Không dùng Budget.actions thay cycle count.
- [x] Execution nền trong API process qua cơ chế native phù hợp; bounded admission, dispatch sau commit và setup/submit failure; explicit Actor/Tenant. Chỉ chốt lớp executor riêng sau khi xác định coordination framework còn thiếu.
- [x] Stop idempotent, complete/cancel/fail conditional finalization, partial outcome, timeout/EOF, usage unknown và release resources. DB outage giữ outcome để retry, không báo success giả. Terminal SSE sau DB commit là gate 2.3, chưa triển khai ở 2.2.
- [x] Kiểm owner signaling khi Stop tới replica khác, deadline/stale RUNNING sau process death, guard chống late writes và graceful shutdown. Đây là evidence của code 2.2; quyết định một API process bên dưới thay DB Stop signaling bằng local cancellation ở 2.3. Không cần queue/Chat worker riêng hoặc auto-retry run.
- [x] Nối send/cancel API cùng executor thật, generate client và kiểm send → model → persisted history. Chuyển hai routes từ 2.3 sang đây để có đường nghiệm thu sản phẩm, không endpoint/profile tạm.

Exit: send → model thật → persisted outcome; đóng kết nối không dừng execution. Provider errors/deadline/Stop không để active slot hoặc RUNNING mắc kẹt vô hạn.

### 2.3 — Stream buffer và HTTP contract

Triển khai trong working tree ngày 2026-09-09 theo [contract 2.3](design.md#contract-triển-khai-23-đã-chốt) và [kiến trúc provider/model toàn luồng](provider-model-architecture.md). Full design và một native provider binding nằm trong MEM-11; MEM-77 triển khai catalog/admin/user/BYOK theo schema/API/ownership/lifecycle đã chốt. [Verification](verification.md) tách gate code, proxy fixture và OpenAI thật khỏi browser/staging acceptance.

- [x] Một API process: V20 bỏ DB Stop marker, không polling active rows; local Stop có quyền, DB terminal/deadline và bounded shutdown giữ nguyên. Tests giữ terminal winner, partial và retry khi DB lỗi.
- [x] Provider composition sở hữu reusable sync/async client, timeout/retry/close/observations; ChatModelBinding giữ native service/metadata và final-request policy; setup resolve trước dispatch, executor dùng Ai.withLlmService/native runner.
- [x] Converter không phải OpenAI chạy qua cùng executor; hai lượt có accounting độc lập. OpenAI thật chạy qua product provider composition. Fixture không được coi là support provider thứ hai.
- [x] StreamBufferWriter trong RAM: byte/TTL/read limits, sequence/cursor, replay-to-live, flush trước terminal sau DB commit; không thêm Redis cho Chat.
- [x] Eviction/expiry/missing-prefix và slow-reader reset; reader cleanup không dừng writer. Test missing buffer thiết lập contract khi RAM mất, không tuyên bố token recovery sau process death.
- [x] Spring MVC Flux/ServerSentEvent, owner/IAM/cursor checks và existing mutation CSRF; generated wire schema có required/nullable fields.
- [x] Pinned product Nginx + HTTP/JWT thật với model fixture: text trước terminal, disconnect/reconnect, owner/non-owner/unauthenticated, Stop/partial và một execution. TTL/gap/overflow được kiểm tại buffer/Flux boundary; OpenAI thật kiểm riêng native send/history path.
- [x] Generate OpenAPI và SDK SSE; loại riêng stream khỏi TanStack pagination, giữ query generation cho các operation còn lại. UI/editor/sharing không thuộc phase này.

Exit đạt ở backend/contract boundary: provider baseline có extension tests; SSE/history/cancel dùng cùng assistant ID và DB outcome; buffer/reader mất không tự hủy run. Không hứa khôi phục token chưa lưu. Chat UI và consumer fallback trên browser thuộc Phase 2.4; chưa commit/deploy Chat.

### 2.4 — UI thật và nghiệm thu Phase 2

- [x] So sánh ba runtime bằng native hooks và HTTP/browser fixture, gồm tools/replay/Stop; xem [UI runtime verification](ui-runtime-verification.md). Đã chốt và triển khai `useChatRuntime` + public `ChatTransport`; xem product verification.
- [x] Chat routes trong authenticated boundary; sidebar tạo/chọn session, composer/thread với assistant-ui; runtime adapter nối generated client và SSE.
- [x] Giữ message identity khi stream/Stop/reload; hiển thị partial/error và trạng thái chờ Stop đúng; auth change dọn subscriptions/private state theo app conventions.
- [x] Browser end-to-end dùng IAM/PostgreSQL/model thật và buffer RAM của runtime: tạo session, send nhiều lượt, reload trong/sau lượt, Stop/replay và Actor khác bị từ chối. Reset/gap/EOF kiểm bằng incremental HTTP fixture qua adapter thật; không đánh đồng với provider outage. UI branch editing/Persona editor/Search tools vẫn thuộc phase đã phân công.
- [x] Giữ native server observations; kiểm lifecycle, lỗi và reader release qua runtime/browser; không ghi prompt/secret vào logs. Chuyển các contracts cần giữ từ scratch sang product tests.
- [x] Chạy static/IDE theo skill, wrapper clean check, OpenAPI và frontend/browser gates phù hợp; cập nhật architecture/spec/test docs với phần thực sự đã triển khai, QA/raw receipts giữ trong ignored scratch.

Exit Phase 2: send/stream/save/reload/Stop/reconnect hoạt động xuyên sản phẩm với quyền thật, outcome bền vững và failure handling đã kiểm. Search/citations nguồn thật ở Phase 3, loop/tools đầy đủ ở Phase 4, editor/sharing ở Phase 5; hardening cần cho Phase 2 không đẩy sang các phase đó.

## Phase 3 — Search tools và context có nguồn

### 3.1 — Backend grounded Chat (implemented and verified locally, 2026-09-10)

- Refactor shared ranked retrieval and bounded range reads on the existing OpenSearch index. Direct Search keeps its hybrid query behavior.
- Unify single/batch source eligibility. PUBLIC FILE remains the production authorization implementation; restricted/provider ACL integration belongs to IAM/connector work. No allow-all stub.
- Native Embabel SearchTool: bounded multiple queries, weighted RRF, typed section selection and neighbor expansion of server-owned authorized results. Expansion does not reauthorize source ACL; independent document preview does.
- Share native model/process accounting for selection and answers; enforce cancellation, deadline, context and spend bounds.
- One migration for bounded sources/citation metadata in the assistant outcome; expose SSE progress/sources and history. Keep the change below 100 files including tests, generated code and docs.
- Verify real OpenSearch reads, ranking/eligibility, native tools, partial/Stop/budget behavior, persistence and HTTP contracts.

### 3.2 — Chat UI, model selection and source acceptance (UI implemented and verified locally)

- Acceptance fix: keep the new-chat runtime mounted when its server ID updates the URL; load history only when entering a different conversation or reloading. Show one waiting/search indicator and no Copy action for an empty answer. Verify composer/reader continuity, real conversation switches, Stop, model choice and reload through the native browser runtime.

- Use the assistant-ui Base example's organization: centered welcome/composer before the first message, footer composer during conversation, model picker inside the composer, and one existing app sidebar. Retain MemoryOS typography, tokens and controls; reuse styled assistant-ui elements with minimal custom CSS. OrgMemory is a reference for catalog grouping and source interactions.
- Connect the authorized, session-aware model catalog to the picker and send the configuration ID for each turn. Show the backend's actual selection/fallback. Provider administration remains MEM-77; no new Thinking override or unsupported attachment/voice controls.
- Render bounded search progress and source metadata through native message state; resolve citations against each answer's server-owned sources. Follow Onyx with citation hover cards and a Sources toolbar action opening the right panel; mobile uses a drawer. Reuse the shared document reader inside the panel, keeping Chat visible on desktop. History/replay restore final sources; source failures leave the historical answer readable.
- Verify desktop/mobile layout, keyboard model selection, send/Stop/reload/reconnect, citations and document preview. Keep combined 3.1/3.2 below 100 changed paths, with no additional migration. Record fixture browser checks separately from real-corpus/model acceptance.

Reference: `.tmp/onyx` refreshed to `bd89d269bbae9c3931faaa2076f5ccb6917cbd2a` on 2026-09-10; the relevant prompts/retrieval files are unchanged from the initial `f9e3de3` read. Trace the current OpenSearch adapter and normalization pipeline, not just the Search tool's alpha constant. Both query groups use the shared hybrid pipeline (50/50 default); query-group RRF weights remain separate.

The delivered Onyx prompt flow includes configurable base instructions, history-aware semantic and keyword rewriting, relevance selection followed by classification after reading neighbors, and citation/final-cycle reminders. This functional coverage does not establish parity for helper reasoning options, grouping before selection or concurrent execution; those gaps are explicit remaining work in 3.3. Native Embabel owns typed operations/accounting; no new inference engine or additional migration is needed. Onyx's FULL_DOCUMENT execution is bounded to five neighboring chunks per side. The repository gate passes 534 tests with five optional skips; the opt-in real-model corpus check separately passes neighbor facts, follow-up references, insufficient evidence, citations and document injection. These controlled retrieval results do not replace the broader corpus/browser acceptance in 3.2.

The transport renders search/source events through native message metadata and restores sources from history after replay gaps. The UI/model picker passes 105 unit and 22 Chat/Search browser checks, including existing Search preview contracts. Actual corpus/model quality across the full browser/runtime remains a separate acceptance boundary. Current evidence is in [verification](verification.md) and [Chat tests](../../../tests/chat.md).

### 3.3 — Khắc phục latency theo baseline Onyx (đã triển khai, đang kiểm chứng)

Thêm và mở rộng ngày 2026-09-10 theo [thiết kế SearchTool](design.md#khắc-phục-latency-searchtool-trong-phase-3). Người dùng đã yêu cầu làm theo Onyx cho đủ tám điểm audit; source/time filters và query/document progress thuộc lần sửa này. Giao toàn bộ trong **một PR**, gồm code, migration/backfill, UI, tests/generated và tài liệu. A → B → C bên dưới là các nhóm công việc trong cùng PR; hoàn tất và nghiệm thu cả ba trước khi coi 3.3 đã giao. Đây là phần còn thiếu của baseline Phase 3, không chờ Phase 5/6. Migration cho thay đổi schema cần thiết và backfill dữ liệu là phần triển khai bình thường, không phải lý do giảm phạm vi hoặc tách PR.

#### 3.3A — Query, ranking, context và thời gian thực thi

- [x] **Trọng số và thứ hạng:** cộng các role weights khi query trùng trong cùng nhóm, kể cả keyword duplicates; sửa validation tổng weight, k=50 và tie-break theo Onyx. Kiểm ví dụ semantic/tool/original cùng text có weight 2.5, query khác hoa thường, nhiều role/keyword trùng và các thứ hạng bằng điểm. Không thay hybrid keyword group thành BM25-only.
- [x] **Lần tìm tiếp cùng nguồn:** chỉ rewrite ở lần Search đầu; lần sau không tự đem semantic/keyword expansion cũ đi tìm lại. Giữ original và tool queries mới theo reference, cùng search-cycle state trong RAM. Kiểm câu hỏi đầu có hai Search calls với query thứ hai mới: không thêm LLM rewrite và không chạy lại bộ expansion cũ. Ngoại lệ đổi source scope nối vào dữ liệu/filter thật tại 3.3B; không thêm global query/result cache.
- [x] **Helper options và timeout:** OFF intent cho rewrite/selection/classification, verify provider/model support và precedence sau converter bằng capture request qua adapter thật. Selection/classification mặc định timeout 60 s, không vượt remaining deadline; kiểm fallback khi helper timeout nhưng lượt còn thời gian, phân biệt với Stop/deadline/budget. Answer và các lượt khác không bị thay options. Timeout bao phủ native attempts và thật sự hủy request.
- [x] **Section trước selection:** gộp authorized hits theo Tenant/document/generation và ordinal liền nhau, giữ anchor/rank. Dùng tối đa ba chunk đại diện quanh anchor rồi mới áp tổng budget; giữ section/range đầy đủ cho expansion. Áp candidate bounds sau gộp; nghiệm thu ngưỡng hiện tại thay vì giữ cap 30 hits trước gộp mà không kiểm recall. Đưa metadata đang có và có semantics đúng vào selection; nguồn/ngày/tác giả bổ sung ở 3.3B.
- [x] **Lookup và embedding dùng chung:** kiểm alias/config một lần mỗi Search call, batch embeddings cho text duy nhất; chạy hybrid queries đồng thời. Hợp IDs và batch current-generation/eligibility với giới hạn repository trước RRF/model/progress có nội dung. Kiểm kết quả và quyền tương đương với lookup riêng, stale generation, permission revoke, một query lỗi và batch lớn; đo số lượt lookup thực tế. Không giữ transaction khi chờ model/network.
- [x] **Concurrency và expansion:** hai rewrite và các expansion/classification độc lập chạy đồng thời có giới hạn. Mỗi section phân loại một lần, đọc neighbor quanh biên section, giữ FULL_DOCUMENT ±5, gộp overlap/citations ổn định; không đọc rộng hơn khi không có neighbor. Kiểm Actor/Tenant context, budget admission, accounting đúng một lần, queued/running Stop, timeout và không có kết quả muộn sau terminal.
- [x] **Quan sát stage và kiểm runtime:** nối observation của các stage vào tool/lượt, ghi duration/count/usage an toàn; không log query/prompt/document. Kiểm request options thực, stage overlap, số query của từng chu kỳ và latency trên provider/index thật với bộ câu hỏi ban đầu, trước khi kết luận hiệu quả.

Kiểm chứng nhóm A: query/RRF/window/options/timeout đúng và giữ các bảo đảm lifecycle/quyền; tiếp tục hoàn thiện metadata/filter và progress trong cùng PR.

#### 3.3B — Metadata, filter nguồn/thời gian và đổi scope

- [x] **Metadata có nguồn gốc:** nối Document/connector/chunk/index/hit/selection, xác định loại nguồn, ngày tạo/cập nhật và authors khi có. Phân biệt ngày tài liệu với timestamp vận hành; nullable khi thiếu. Kiểm một Document có nhiều mappings: không lộ metadata trái quyền hoặc ghép ngày/loại nguồn sai. Triển khai persistence fields và migration additive cần thiết, cùng docs về semantics của FILE/provider thực sự hỗ trợ.
- [x] **Index và dữ liệu đang có:** update strict mapping, backfill bằng worker/reconciliation hiện có, giữ vectors cho content/model không đổi. Readiness phát hiện metadata cũ/thiếu kể cả document đã READY; kiểm retry/idempotency, restart, concurrent generation/delete, metadata-only update và Direct Search regression. Không dùng endpoint/profile một lần để hoàn tất backfill.
- [x] **Source/time helper theo Onyx:** auto detection bật mặc định và cấu hình được; source candidates bị giới hạn bởi nguồn thật được phép tìm. Có dưới hai loại nguồn thì không gọi source helper. Time suy luận một lần/lượt; source dùng tối đa năm user turns gần nhất và cycles, latch off khi không có chỉ dẫn nguồn. Các helper này cùng OFF intent, chạy đồng thời với rewrite khi cần; cập nhật native helper-call accounting/budget cho số call mới.
- [x] **Filter thật trong query:** khoảng ngày tạo/cập nhật có cận đầu/cuối và cận mở, apply lên cả lexical/vector branches; giao với explicit filters như design/Onyx. Kiểm giao không rỗng, xung đột giữ explicit range, parse/provider error giữ explicit scope, nguồn không được phép, thời gian tương đối với clock cố định, metadata thiếu và nguồn chưa hỗ trợ. Không báo đã lọc nếu không có filter thực áp.
- [x] **Tìm tiếp đổi scope:** dùng search cycles và source scope để chỉ lấy lại cached semantic/keyword expansion khi chuyển sang loại nguồn chưa tìm. Kiểm first call, same-source second call, new-source second call, quay lại nguồn cũ, detection không có scope và lượt mới không thừa hưởng state. Fixture multi-source phải có quyền thật ở boundary được kiểm; hiện staging FILE-only chỉ chứng nhận đường một loại nguồn.

Kiểm chứng nhóm B: metadata/backfill và filter hoạt động xuyên qua index thật; không chỉ pass typed helper hoặc query builder. Loại nguồn không có quyền tìm vẫn bị loại; tiếp tục nghiệm thu toàn bộ qua UI trong cùng PR.

#### 3.3C — Query/document progress và nghiệm thu toàn bộ

- [x] **Event/transport/UI:** thêm query thực chạy và effective filter trước retrieval; selected document metadata trước expansion. Validate bounds và authorization trước emit; hiển thị trong tiến trình Search hiện có bằng ngôn ngữ dễ hiểu. Phân biệt tài liệu đang đọc với final evidence, giữ citation identity từ evidence thực; không stream raw payload/reasoning.
- [x] **Replay/history/Stop:** các progress events dùng cùng sequence/buffer; kiểm duplicate/gap/reconnect/reload/Stop và native UI state. Buffer mất thì khôi phục final sources từ history, không bịa lại intermediate progress. Không phát source/document/query sau terminal; source preview độc lập vẫn kiểm quyền hiện tại.
- [ ] **Benchmark trước/sau:** cùng corpus/provider/model/options của answer, nhiều lần chạy có ghi số mẫu và mức đồng thời. Tách rewrite/source/time, embedding, OpenSearch, lookup quyền/generation, selection/classification, tool duration, thời gian tới token trả lời đầu tiên và toàn lượt; báo median/min/max, errors và usage khi có, không suy p95 từ mẫu nhỏ. Đo cả một Search call và nhiều Search calls trong lượt; báo riêng chi phí helper filter mới.
- [ ] **Corpus/browser acceptance:** doanh thu Orion, công tác, follow-up, tìm tiếp đổi query, exact names, Word `OrgMemory_POC_Guide.docx` 138 chunk, nhiều nguồn, câu hỏi theo ngày và không có bằng chứng. Kiểm chất lượng/ranking/recall, citations/preview/reload, filter hiển thị đúng, Stop, provider lỗi, timeout và budget. Tách fixture multi-source/date cases với kết quả thật trên nguồn đang được phép tìm.
- [ ] **Hoàn tất PR duy nhất:** IDE/static checks theo skill, wrapper `clean check`, OpenAPI/frontend/browser và runtime gates phù hợp. Cập nhật Chat/Search/metadata specs, test matrix và verification cùng code/migrations/UI; evidence triển khai/issue closure theo lifecycle hiện có. Chỉ đánh dấu từng hàng baseline sau khi có test/receipt tương ứng; cả tám mục và các tối ưu đã chốt phải hoàn thành trong PR này.

Review PR #91: bổ sung giới hạn cleanup và giữ native process/client lease/admission tới actual drain; kiểm provider cố tình bỏ qua interrupt, chống late evidence/retry/accounting. Sửa harness input/cache/corpus cleanup, metadata tác giả lỗi và title progress dài trong cùng PR; giữ default helper `minimal` và explicit options của model đã chọn. Kết quả kiểm tra cuối được ghi trong latency-verification.

Receipt hiện tại: [latency và kiểm chứng](latency-verification.md), [ma trận test](../../../tests/chat.md). A/B và event/replay đã có contract/integration/browser evidence. `clean check` toàn repository đã qua; tie-break bổ sung cũng qua focused test. Benchmark mới là mẫu local trên bản sao corpus thật, chưa phải paired same-host trước/sau: phiên đăng nhập staging hiện trả 401. Vì vậy giữ benchmark/acceptance tổng và PR lifecycle ở trạng thái chưa hoàn tất; không đánh dấu giao xong chỉ từ số giây local.

Coverage của tám điểm audit: trọng số và lần tìm tiếp ở A/B; cửa sổ selection và timeout ở A; metadata ở A/B; source/time filters ở B; progress ở C; lookup dùng chung ở A. Các mục reasoning, concurrency, batch embedding và merge trước/sau expansion đã nêu trước đó vẫn là phần bắt buộc, không bị thay thế bởi tám mục mới.

Baseline chẩn đoán: SearchTool 24.725 s trong một lượt SSE 39.659 s; bốn helper chiếm 18.409 s, tám embedding tuần tự chiếm 5.569 s. Không quy toàn bộ helper time cho reasoning khi chưa đo usage/options thực. Chỉ hoàn thành 3.3 khi A/B/C đều có bằng chứng: query/RRF/section/options đúng, các stage độc lập overlap, metadata/backfill/filter thật, progress đúng thời điểm và báo cáo latency trước/sau có cải thiện với chất lượng/quyền/Stop/budget vẫn đạt. Nếu thêm filter calls làm một nhóm câu hỏi chậm hơn, báo rõ số liệu và nguyên nhân; không dùng trung bình chung che regression hoặc bỏ filter đã được yêu cầu. Không hứa trước một số giây cố định khi chưa benchmark provider thật.

Phase 3 exit includes 3.3 latency remediation with before/after evidence, corpus/model quality and browser sources/citations/reload acceptance. The current PUBLIC FILE policy is tested; advanced source ACL and permission-aware top-k are not claimed complete. Updating a source does not hide old answers.

## Phase 4 — Nghiệm thu agent với tools và context đầy đủ

Các mục dưới đây là điều kiện tích hợp cần đối chiếu, không mặc định là phần code hoặc test chưa làm. Dùng [ma trận Chat](../../../tests/chat.md) và các receipt Phase 2–3 để xác định coverage đã có trước khi bổ sung công việc.

- [ ] Kiểm native loop/last-cycle policy đã triển khai ở Phase 2 trên real Retrieval tools của Phase 3; không xây thêm loop hoặc dời hardening nền tảng tới phase này.
- [ ] Nghiệm thu phối hợp native token/cost accounting/policy, context cap, cycles/deadline/cancel và bounded tools. Kiểm budget hết giữa inference và tool, known partial usage, pricing unknown, provider errors và không lặp side effect.
- [ ] Selected-branch context dùng truncation có token bound, giữ full transcript. Nếu cần summary compression như Onyx, đánh giá thêm inference/cost và persisted summary với consumer cụ thể trước khi chọn; chưa mặc định phải làm compaction engine.
- [ ] Final/partial outcomes; nhận biết process interruption, retry rõ ràng và tránh tự lặp tool side effects.

Exit: multi-turn tools, stop/timeout/process death có behavior đã kiểm; không hứa resume execution từ cache.

## Phase 5 — Trải nghiệm và cấu hình

Đã triển khai trong nhánh `feat/mem-11-chat-editors-projects-sharing`, cùng một PR. [Contract hiện hành](../../../specs/chat.md#conversation-editing-assistants-projects-and-collaboration), [ma trận test](../../../tests/chat.md#editors-projects-assistants-and-sharing-v36) và [receipt](editor-verification.md) phân biệt code/kiểm chứng local với merge và nghiệm thu triển khai.

- [x] V36: command identity, edit tạo USER sibling, regenerate chỉ tạo ASSISTANT, selected-child với expected child; rename/delete và ngăn late completion.
- [x] Persona CRUD/selection, instructions/starters, nguồn/Search, model và caps; builtin seed chỉ insert, quyền owner hoặc MODELS_MANAGE cho builtin.
- [x] Projects riêng của actor: instructions, CRUD, tạo/chuyển/gỡ hội thoại, xóa Project giữ history. Custom Persona thắng Project kể cả prompt rỗng; snapshot theo admission.
- [x] Sharing authenticated same-Tenant link, read-only viewer, revoke; reader không có quyền owner hoặc quyền đọc nguồn phát sinh.
- [x] Feedback theo Actor và output, cập nhật/xóa/reload; các phiên bản regenerate có đánh giá riêng.
- [x] UI assistant-ui nối command server và tải lại đúng nhánh; kiểm browser edit/regenerate/branch/reload/sharing, Persona/Project CRUD và mobile. Kiểm HTTP thật qua Java/PostgreSQL, revision/ownership/rollback qua integration tests.
- [x] Theo chỉ đạo bổ sung: Spring Data JPA cho CRUD Chat và provider/model/default lifecycle. Giữ JDBC cho cây/claims/bulk/projections; note Users/Groups cho Nhật tại MEM-55/MEM-36. Xem [review](persistence-review.md) và ADR 0010.

Attachments, file Persona/Project thuộc MEM-81. Catalog admin UI/local-provider thuộc MEM-77. PR/CI/review và deployment acceptance vẫn theo Phase 6; không coi các checkbox implementation là issue Done.

## Phase 6 — Nghiệm thu và vận hành

### UI theo Onyx — kế hoạch sửa sau phản hồi ngày 2026-09-11

Chi tiết phạm vi, reference, component mapping, thứ tự và điều kiện đạt: [kế hoạch UI theo Onyx](ui-onyx-alignment-plan.md). Đã triển khai trên `feat/mem-11-onyx-ui` trong một PR code; [kiểm chứng UI](ui-verification.md) ghi kết quả và giới hạn. Custom agent tạm gác và attachments thuộc MEM-81.

- [x] Project draft/composer và tạo session ở lần gửi đầu, giữ runtime/SSE khi đổi URL.
- [x] Một header; sidebar Projects/hội thoại và menu rename/move/share/delete; Project workspace có instructions/composer/history.
- [x] Inline edit, regenerate, chọn nhánh và feedback dùng component assistant-ui cài qua CLI nối đúng command server.
- [x] Sharing tạo/sao chép link trong cùng dialog, giữ revision guards và authenticated read-only viewer.
- [x] Browser scenarios, desktop/mobile visual checks và regression theo ma trận của kế hoạch: 79 browser tests, 106 unit tests, frontend checks và Gradle gate qua; giao một PR vào main theo quy trình CI/review.

### Nghiệm thu runtime và vận hành còn lại

- [ ] Corpus/scenarios đối chiếu Onyx; groundedness/citation support, latency, token/cost và concurrency.
- [ ] Đối chiếu [ma trận end-to-end](design.md#end-to-end-framework-integration), ghi rõ native/gap/product receipts và giới hạn provider/pricing. Web search/deep research có điểm tích hợp xác định nhưng vẫn ngoài giao đầu; không báo workflow/checkpoints đã sẵn sàng chỉ vì factory compile.
- [ ] Model/index/DB failure, RAM buffer expiry/eviction, cancellation races, restart, overload và safe retries; kiểm đúng giới hạn replay trong một API process.
- [ ] Observability/audit theo conventions hiện có; retention, backup/restore, health và rollback của runtime đã triển khai.
- [ ] Static/IDE, wrapper clean check, frontend/OpenAPI/browser và CI gates phù hợp với code thực.
- [ ] Consolidate specs/tests/runbooks và deployment evidence; đóng issue theo phạm vi thực giao.

Không lấy health-only của dependency update làm acceptance cho Chat. Khi thay đổi quyết định, đồng bộ design/plan và bỏ tài liệu/hình cũ mâu thuẫn. QA captures/raw logs/receipts ở ignored scratch; không phải tài liệu sản phẩm.
