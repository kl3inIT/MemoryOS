# MEM-11 — Probe bổ sung để chốt public entry point

Ngày kiểm: 2026-09-09. Phạm vi được duyệt: kiểm thêm một vòng trước execution production, giữ JDBC Phase 2.1 và baseline reference implementation. Đây là verification framework cô lập, không phải Chat đã giao cho người dùng.

## Kết luận tích hợp

Chọn **public PromptRunner streaming** cho inference, dùng native SpringAiLlmService/message streamer và native Embabel tool loop. Không đưa custom LlmMessageStreamer/loop bridge của Phase 1 vào production theo mặc định. Lựa chọn này không tự chốt toàn bộ conversation/workflow architecture; [audit end-to-end](#end-to-end-audit--2026-09-09) bổ sung accounting và factory evidence, [design](design.md#end-to-end-framework-integration) sở hữu quyết định xuyên sản phẩm.

```text
MemoryOS: authorize / reserve / resolve ChatTurnSetup trong RAM
  → native ExecutingOperationContext theo lượt → Ai → PromptRunner
  → public LlmService delegate: capability đã kiểm chứng
  → native SpringAiLlmService / message streamer / tool loop
  → public Spring AI ChatModel decorator: policy và metadata theo lượt
  → Spring AI OpenAiChatModel → OpenAI Chat Completions
```

Hai extension đã compile và chạy qua chính entry point này:

- Implement public `LlmService`, delegate sender/streamer/options/metadata cho SpringAiLlmService; khai báo streaming capability đã được kiểm chứng. Không implement lại inference streamer.
- Implement public `ChatModel` bằng composition: giữ nguyên provider và native message conversion; kiểm Stop/budget/finish reason, capture usage trước aggregation, bỏ tools ở inference cuối. State của decorator thuộc một lượt; HTTP client/provider có lifecycle riêng và được đóng khi composition root shutdown.

LlmService không có nghĩa tự xây registry/model API. Không extends Kotlin data class SpringAiLlmService, không import internal streamer, không reflection và không copy loop. Phần application vẫn sở hữu IAM, message identity, transcript, admission, terminal transaction, deadline, cancel signal và replay theo design.

## Native làm được và khoảng trống đã tái hiện

| Contract | Kết quả qua public runner | Quyết định và tradeoff |
| --- | --- | --- |
| Text streaming | Text đến trước khi inference complete; model thật trả nhiều deltas | Dùng native generateStream; không tự dựng transport provider |
| History/Persona/model | Native messages giữ system instruction và lịch sử; pre-resolved service stamp model đã chọn | ChatTurnSetup giữ dữ liệu đã resolve trong RAM. Không cần conversation store thứ hai |
| Tool continuation | Tool nhận server ToolCallContext, chạy một lần, result trở lại inference sau | Dùng native loop và Tool; application tool kiểm input/quyền/Stop trước side effect |
| Tool events | before/after ToolCallInspector được gọi | Dùng hook này cho events. Không cần tự intercept engine để có tool lifecycle |
| Loop inspectors/transformers | Không được gọi trong streaming; framework có warning rõ | Không dựa vào các hook này để thực thi budget/history policy. Không suy từ API tồn tại rằng streaming hỗ trợ |
| Capability discovery | Native supportsStreaming gọi provider với prompt dò, ngoài user inference | Public LlmService delegate khai báo capability cho binding đã probe; tránh probe ẩn trên mỗi decorator theo lượt. Không hardcode true cho provider/model chưa kiểm |
| EOF | Native trả onComplete cho partial EOF không có finish reason | ChatModel decorator đổi thành error; partial đã phát vẫn giữ. Terminal DB chỉ completed khi hợp lệ |
| Iteration limit | Native public runner dừng bằng error ở mặc định 20 inference, không có final tools-off | Decorator áp dụng baseline sáu cycles, vòng cuối bỏ tools. Bản này chỉ chứng minh cap trong trần native 20; không quảng bá cấu hình vô hạn |
| Final tools-off | Real OpenAI từ chối tools absent + tool_choice none | Request cuối bỏ cả tools và tool_choice; kiểm body HTTP thật và model thật. Provider defaults không được ép tool_choice required hoặc gắn tools ngoài cấu hình lượt |
| Usage | Public output là Flux<String>; loop inspector không cung cấp usage ở đường này | Capture ChatResponse usage trước native aggregation; trailing usage-only chunk và nhiều inference đã kiểm. Không cộng từng cumulative chunk hoặc biến unknown usage thành số đo thật |
| Metadata continuation | Opaque metadata fixture trên assistant tool-call message sống qua native conversion | Bỏ mapper riêng cho baseline này. Không suy rộng sang metadata trên mọi loại message hoặc Anthropic/Gemini/Responses |
| Cancel | Dispose đến subscription provider trước token và sau partial; tool đang block cần stop guard | Dispose không thu hồi side effect đã xảy ra. Guard trước tool effect và inference sau vẫn là trách nhiệm application |
| Provider failure | HTTP 429/503, lỗi sau tool, EOF/stall/first timeout không retry hoặc gọi tool trùng | maxRetries=0 và timeout tại boundary thực; product finalization/replay còn phải kiểm trong Phase 2 |
| Context/resource lifetime | Hai native prototype contexts chạy đồng thời với process/model riêng; repository delete gỡ process reference | Dùng native per-turn context và delete trong cleanup sau khi execution kết thúc; delete không thay dispose/cancel. Không cần session AgentProcess sống lâu mặc định |

Native SpringAiLlmService capability check thực sự gửi một prompt dò, không chỉ kiểm interface. Probe có assertion riêng cho hành vi này; các test execution sau đó dùng delegate khai báo capability để số inference phản ánh đúng lượt Chat.

## Vì sao không chọn AgentProcessChatbot cho lần giao này

Đã đối chiếu source `Conversation`, `ConversationFactory`, `ChatSession`, `AgentProcessChatbot` và tài liệu store trong v1.5.1. Chưa chạy một implementation Chatbot/store thay thế JDBC; đây là quyết định về mức phù hợp, không phải kết luận framework không làm được.

AgentProcessChatbot hợp với hội thoại gắn AgentProcess/blackboard và actions dài hạn; framework quản lý session, conversation và OutputChannel. Đổi lại, để giữ cây nhánh, reserved assistant ID, request idempotency, IAM và terminal transaction hiện có, MemoryOS vẫn phải ánh xạ các semantics này vào factory/store và tránh addMessage hai lần. Nhận định trước rằng factory chưa cần vì không thay JDBC là không đủ: lợi ích common Conversation/assets/workflow API phải xét trên toàn parity. Quyết định và điều kiện dùng factory nằm ở design; probe bổ sung bên dưới kiểm được native integration, chưa phải JDBC-backed Chatbot acceptance.

Tài liệu bundled mô tả StoredConversationFactory dùng Neo4j/async persistence; implementation của package store chưa được runtime-audit. Không thêm Neo4j hoặc chuyển JDBC chỉ vì tên abstraction trùng nhau. Khi có workflow thực sự cần AgentProcess dài hạn, đánh giá lại bằng contract mới.

## Dependencies và cách kiểm

Giữ candidates Boot 4.1.1, Spring AI 2.0.1, Embabel 1.5.1, Java 25. Probe thêm `embabel-agent-starter-platform` và `embabel-agent-openai` để chạy Spring Boot/Ai thật và dùng native `Gpt5ChatOptionsConverter`; không dùng DefaultOptionsConverter làm production converter.

Starter composition ban đầu thiếu `spring-ai-autoconfigure-model-tool`, làm introspection ChatClientAutoConfiguration báo TypeNotPresentException. Thêm artifact cùng BOM 2.0.1 đã giải quyết warning đó. Đây là dependency cần giữ khi port composition đã kiểm, không phải lý do tăng version hoặc thêm starter tất cả provider. Product Gradle chưa thay đổi trong probe này.

Code và logs ở ignored `.tmp/mem11-phase1/`; không publish QA harness vào application. Tái chạy bằng checked-in wrapper, secret chỉ qua environment:

```powershell
$env:JAVA_HOME='C:\Users\admin\.jdks\temurin-25.0.2'
$env:MEM11_HIGH_LEVEL_LIVE='true'
infisical run --env=dev --silent -- .\gradlew.bat -p .tmp/mem11-phase1/jvm highLevelProbe probeClasspath --no-daemon --console=plain
```

Evidence:

- `jvm/src/main/java/probe/HighLevelProbe.java`: Boot composition, native baseline, public-interface extensions, local HTTP provider và real-model calls.
- `high-level-summary.json`: assertions cho native gaps, extension, HTTP và native process isolation/cleanup.
- `high-level-live-summary.json`: actual model/inference/tool/chunk/usage counts; fixture fact và history marker, không dữ liệu sản phẩm.
- `high-level-verified.log`: compile/runtime/dependency receipts sau khi đóng explicit OpenAI client lifecycle.
- Earlier native-gap, final-tools request rejection và correction receipts giữ trong ignored high-level logs; không biến failed experiments thành product code.

Lần chạy cuối `high-level-verified.log`: **BUILD SUCCESSFUL, 42 giây; 33 contract assertions và 4 real-model assertions đạt**, cùng Boot/public-entrypoint smoke. Một số assertions xác nhận gap native tồn tại, không có nghĩa native đáp ứng hết. Real-model lượt tool có 2 inference, 1 tool effect, 8 text chunks; total usage mỗi inference là 447 và 492 tokens trong receipt này. Probe clients/server đã đóng và không còn HighLevelProbe process sau chạy. Không rerun repository `clean check` vì lượt này không sửa product Java/schema/dependencies.

JetBrains đã được gọi với warnings enabled. Scratch Java nằm ngoài imported Gradle modules nên IDE không resolve được cả JDK/dependencies; không có claim IDE-clean cho probe Java. Scratch build.gradle inspection không báo lỗi. Wrapper compile và runtime assertions là bằng chứng ở boundary này; khi port vào product phải chạy IDE checks và repository gate như thường lệ.

## Giới hạn và bước tiếp theo

Real-model boundary là OpenAI Chat Completions/gpt-5-mini; tools là fixture. Không chứng nhận provider billing cancellation, load/capacity, product IAM/PostgreSQL, Redis/browser reconnect hoặc production Stop qua HTTP. Các scenario transport/UI của Phase 1 cũ chưa tự trở thành acceptance của đường product mới.

Bước tiếp theo là Phase 2.2 theo entry point đã chọn: port extension nhỏ nhất vào consumer thật, nối reserve/send/Stop/finalization, bounded background execution và lifecycle cleanup; chuyển contract probes thành tests của runtime đó. Không mở thêm phase nghiên cứu chung, không viết engine tương đương, không đổi persistence Phase 2.1.

## End-to-end audit — 2026-09-09

Audit được yêu cầu sau entrypoint probe để chuẩn hóa reuse theo toàn bộ parity, không chỉ Phase 2.1–2.2. Đã đọc lại native Usage/PricingModel/LlmInvocation/Budget, streaming operations/loop, Conversation/Factory/ChatSession/AgentProcessChatbot và reference implementation llm_loop, llm/cost, token_limit. Quyết định canonical nằm ở [design](design.md#end-to-end-framework-integration); kế hoạch không tăng số phase.

Probe mới `ArchitectureAuditProbe` chạy cùng Boot/Embabel/Spring AI composition của probe trước, bằng checked-in wrapper. **18 assertions đạt, BUILD SUCCESSFUL trong 15 giây**; gồm assertions xác nhận gaps/limits, không phải 18 tính năng native hoàn chỉnh. Không gọi provider thật cho phép tính cost giả lập. Real-provider/HTTP receipts của vòng trước giữ nguyên phạm vi.

| Nhóm | Bằng chứng mới |
| --- | --- |
| Native streaming accounting | Provider fixture trả usage nhưng process LlmInvocation history vẫn trống sau public generateStream |
| Public accounting integration | Capture tại ChatModel, record native LlmInvocation một lần/inference: 2 inference có tổng 8 input/9 output tokens; PricingModel dùng giá giả lập tính ra 26, không có custom cost calculator |
| Native Budget | Native token/cost policies nhận accounting này; action policy không xem 2 inference là 2 actions |
| Enforcement boundary | Cap giả lập 10, inference đầu cost 11: native policy chặn inference thứ hai. Chỉ kiểm tại inference thì tool ở giữa vẫn chạy; thêm cùng native policy vào public Tool.call chặn side effect |
| Partial/unknown | Usage đã biết khi provider error được ghi một lần; không có pricing thì native cost trả 0, chứng minh cần product unknown-state, không thể suy ra miễn phí |
| Factory/Chatbot | Custom factory với in-memory backing được native utilityFromPlatform/createSession gọi load theo conversation ID; không cần Neo4j |
| Message lifecycle gaps | onUserMessage append lại message đã nằm trong loaded conversation; saveAndSend gọi addMessage và output; cả hai cần mapping với reservation/transaction của sản phẩm |
| Branch/lifetime | Đổi backing branch không tự đổi Conversation của session đã tạo; tạo session mới load được branch mới và có process ID khác |

Giới hạn rõ ràng: factory probe dùng in-memory snapshots và native Chatbot không có business actions; không phải full conversational agent, JDBC adapter, IAM/sharing/assets hoặc process-resume acceptance. Accounting dùng giá giả lập, chưa kiểm cached-token billing, child-workflow tổng hợp, usage sau mọi kiểu cancellation hoặc exported telemetry listeners. recordLlmInvocation chỉ ghi history ở đường đã đọc, không tự phát LlmInvocationEvent. Money cap kiểm trước call có thể bị vượt bởi call đang chạy; tool guard mới được chứng minh ở synchronous fixture boundary, không thu hồi side effect đã thực hiện.

Raw receipts: ignored `.tmp/mem11-phase1/architecture-audit-final.log`, `architecture-audit-summary.json`, `jvm/src/main/java/probe/ArchitectureAuditProbe.java`. Re-run:

```powershell
.\gradlew.bat -p .tmp/mem11-phase1/jvm architectureAuditProbe --no-daemon --console=plain
```

IDE inspection đã gọi với warnings enabled; scratch Java không nằm trong imported Gradle module nên unresolved JDK/framework symbols, không claim IDE-clean. Wrapper compile/runtime đạt; product sources/schema/dependencies không thay đổi và không rerun product clean check cho audit tài liệu này.
