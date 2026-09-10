# MEM-11 — Sáu phase triển khai

[Design](design.md#baseline-da-chot) sở hữu quyết định kiến trúc; [parity](onyx-parity.md) sở hữu phạm vi. [Product verification](verification.md) ghi bằng chứng Phases 2.1–2.4, backend grounded Chat 3.1 và UI 3.2 đã kiểm local. Nghiệm thu corpus/model rộng vẫn cần thực hiện. Web search/deep research nằm ngoài lần giao đầu.

## Bổ sung nền provider/model

Phases 2.1–2.4 đã merge qua PR #86; backend catalog/selection/BYOK và public adapter đã merge qua PR #88 theo [plan backend](../mem-77-provider-backend/plan.md). Đức Anh nhận catalog admin UI và tích hợp provider local trong MEM-77; dropdown model ở Chat vẫn thuộc MEM-11.

## Phân chia PR

Phase 2 đã dùng nhánh tích hợp và được đưa vào `main`. Phase 3.1 backend và 3.2 UI đang cùng nằm trên `feat/mem-11-phase-3-1-grounded-chat` theo yêu cầu tiếp tục triển khai; tổng thay đổi vẫn dưới 100 file để review cùng nhau. Mỗi PR giữ dưới 100 file; số file không thay thế việc kiểm CI và review. Chưa đóng MEM-11 chỉ vì backend 3.1 đã xong.

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

- Use the assistant-ui Base example's organization: centered welcome/composer before the first message, footer composer during conversation, model picker inside the composer, and one existing app sidebar. Retain MemoryOS typography, tokens and controls; reuse styled assistant-ui elements with minimal custom CSS. OrgMemory is a reference for catalog grouping and source interactions.
- Connect the authorized, session-aware model catalog to the picker and send the configuration ID for each turn. Show the backend's actual selection/fallback. Provider administration remains MEM-77; no new Thinking override or unsupported attachment/voice controls.
- Render bounded search progress and source metadata through native message state; resolve citations against each answer's server-owned sources. Follow Onyx with citation hover cards and a Sources toolbar action opening the right panel; mobile uses a drawer. Reuse the shared document reader inside the panel, keeping Chat visible on desktop. History/replay restore final sources; source failures leave the historical answer readable.
- Verify desktop/mobile layout, keyboard model selection, send/Stop/reload/reconnect, citations and document preview. Keep combined 3.1/3.2 below 100 changed paths, with no additional migration. Record fixture browser checks separately from real-corpus/model acceptance.

Reference: `.tmp/onyx` refreshed to `bd89d269bbae9c3931faaa2076f5ccb6917cbd2a` on 2026-09-10; the relevant prompts/retrieval files are unchanged from the initial `f9e3de3` read. Trace the current OpenSearch adapter and normalization pipeline, not just the Search tool's alpha constant. Both query groups use the shared hybrid pipeline (50/50 default); query-group RRF weights remain separate.

The delivered Onyx prompt flow includes configurable base instructions, history-aware semantic and keyword rewriting, relevance selection followed by classification after reading neighbors, and citation/final-cycle reminders. Native Embabel owns typed operations/accounting; no new inference engine or additional migration is needed. Onyx's FULL_DOCUMENT execution is bounded to five neighboring chunks per side. The repository gate passes 534 tests with five optional skips; the opt-in real-model corpus check separately passes neighbor facts, follow-up references, insufficient evidence, citations and document injection. These controlled retrieval results do not replace the broader corpus/browser acceptance in 3.2.

The transport renders search/source events through native message metadata and restores sources from history after replay gaps. The UI/model picker passes 105 unit and 22 Chat/Search browser checks, including existing Search preview contracts. Actual corpus/model quality across the full browser/runtime remains a separate acceptance boundary. Current evidence is in [verification](verification.md) and [Chat tests](../../../tests/chat.md).

Phase 3 exit remains corpus/model quality plus browser sources/citations/reload acceptance. The current PUBLIC FILE policy is tested; advanced source ACL and permission-aware top-k are not claimed complete. Updating a source does not hide old answers.

## Phase 4 — Nghiệm thu agent với tools và context đầy đủ

- [ ] Kiểm native loop/last-cycle policy đã triển khai ở Phase 2 trên real Retrieval tools của Phase 3; không xây thêm loop hoặc dời hardening nền tảng tới phase này.
- [ ] Nghiệm thu phối hợp native token/cost accounting/policy, context cap, cycles/deadline/cancel và bounded tools. Kiểm budget hết giữa inference và tool, known partial usage, pricing unknown, provider errors và không lặp side effect.
- [ ] Selected-branch context dùng truncation có token bound, giữ full transcript. Nếu cần summary compression như Onyx, đánh giá thêm inference/cost và persisted summary với consumer cụ thể trước khi chọn; chưa mặc định phải làm compaction engine.
- [ ] Final/partial outcomes; nhận biết process interruption, retry rõ ràng và tránh tự lặp tool side effects.

Exit: multi-turn tools, stop/timeout/process death có behavior đã kiểm; không hứa resume execution từ cache.

## Phase 5 — Trải nghiệm và cấu hình

- [ ] Migration và reservation cho regenerate assistant-only, command identity riêng, edit từ parent không phải tip và selected-child command. V18/2.2 hiện chỉ nhận send tại tip; không tái dùng mù điều kiện đó cho regenerate.
- [ ] Persona settings dùng native binding của một provider hiện có; resolve options rồi bọc guard theo lượt. Builtin Persona hiện configuration-backed; editor cần chuyển quyền sở hữu settings rõ ràng trước khi ghi DB. Backend catalog/BYOK is merged through PR #88. MEM-77 owns catalog admin UI and local-provider integration; the Chat model selector remains in MEM-11.
- [ ] Trước triển khai sharing, chốt grant model cho authenticated readers trong Tenant và tách read authorization khỏi owner mutations. Onyx PUBLIC/PRIVATE cho phép anonymous link; phạm vi đã chọn loại anonymous. Lợi ích: giữ identity/Tenant boundary; tradeoff: không tương đương public-link UX. Không mặc định thêm Group grants khi chưa có yêu cầu.
- [ ] assistant-ui list/composer, edit/regenerate/branch, source/progress, feedback, stop/retry/reload và Search handoff.
- [ ] Persona settings/editor (assistant trên UI), starter prompts, source/tool selection và settings của binding hiện có, validation và quyền quản lý. Catalog administration/local-provider integration belong to MEM-77; Chat model-selection UI belongs to MEM-11.
- [ ] Private attachments/readiness/retention, Projects và chia sẻ có xác thực theo parity.
- [ ] Kiểm E2E branch A→B, edit/regenerate, đổi Persona/Project và sharing: history/assets/identity của context cũ không lọt vào lượt mới. Nếu action/tool của feature tiêu thụ native Conversation/AssetView, thêm adapter/factory với consumer đó theo design; không dựng store thứ hai hay kích hoạt long-lived Chatbot chỉ để lưu history.
- [ ] Browser scenarios: route changes, concurrent tabs, mạng chập chờn; conversation revoke khác source revoke, update và delete.

Exit: phạm vi giao đầu chạy xuyên backend; history giữ semantics đã chốt, source reader kiểm quyền hiện tại.

## Phase 6 — Nghiệm thu và vận hành

- [ ] Corpus/scenarios đối chiếu Onyx; groundedness/citation support, latency, token/cost và concurrency.
- [ ] Đối chiếu [ma trận end-to-end](design.md#end-to-end-framework-integration), ghi rõ native/gap/product receipts và giới hạn provider/pricing. Web search/deep research có điểm tích hợp xác định nhưng vẫn ngoài giao đầu; không báo workflow/checkpoints đã sẵn sàng chỉ vì factory compile.
- [ ] Model/index/DB failure, RAM buffer expiry/eviction, cancellation races, restart, overload và safe retries; kiểm đúng giới hạn replay trong một API process.
- [ ] Observability/audit theo conventions hiện có; retention, backup/restore, health và rollback của runtime đã triển khai.
- [ ] Static/IDE, wrapper clean check, frontend/OpenAPI/browser và CI gates phù hợp với code thực.
- [ ] Consolidate specs/tests/runbooks và deployment evidence; đóng issue theo phạm vi thực giao.

Không lấy health-only của dependency update làm acceptance cho Chat. Khi thay đổi quyết định, đồng bộ design/plan và bỏ tài liệu/hình cũ mâu thuẫn. QA captures/raw logs/receipts ở ignored scratch; không phải tài liệu sản phẩm.
