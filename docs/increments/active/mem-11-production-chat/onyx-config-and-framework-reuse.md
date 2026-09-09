# Onyx turn configuration và ưu tiên abstraction có sẵn

Đối chiếu source local ngày 2026-09-09: Onyx `06aa2b09cc4aa5135fa2627e5235814e996f1514`; Embabel stable v1.5.1 `bd25d07d2c34e4d6f06dea54a39732ae1bdcfb52`. Context7 đã truy vấn Spring AI tools/upgrade và Embabel model/tool APIs; source stable quyết định các chi tiết version-specific.

## Onyx làm gì

- `backend/onyx/chat/process_message.py:647-705`: mỗi lượt load ChatSession với persona eager-loaded; chọn request LLM override hoặc session override, rồi gọi `get_llm_for_persona`.
- `backend/onyx/llm/factory.py:154-240`: precedence model là override → persona model → default; còn user defaults và provider access/fallback. Không có một phép merge chung cho tất cả loại cấu hình.
- `process_message.py:854-857,1060-1101`: lấy prompt/files/search settings và tạo `ChatTurnSetup`; commit DB transaction trước streaming.
- `backend/onyx/chat/chat_state.py:195-258`: `ChatTurnSetup` là `@dataclass(frozen=True)`, giữ persona, LLM objects, prompt, history, search settings, file metadata và run identity. Đây là immutable top-level execution context, không phải deep-frozen serializable snapshot: fields vẫn có list/ORM references.
- `process_message.py:1743`: detach ORM objects bằng expunge_all; execution dùng setup đã dựng. `construct_tools` ở dòng 1331 vẫn mở short-lived sessions khi cần, nên không khẳng định mọi tool/provider setting đều được snapshot toàn bộ tại cùng một thời điểm.
- `backend/onyx/db/models.py:3317-3323` và `chat/save_chat.py:228`: lưu model display name/request_params cho message. Chưa thấy durable full turn-config snapshot tương đương đề xuất MemoryOS trong đường đi/schema đã kiểm.

Hệ quả: thay assistant sau khi turn setup đã lấy prompt/model không khiến những giá trị đã resolve ấy tự đổi giữa lượt. Lượt sau load lại cấu hình. MemoryOS đã chọn cùng cách này: setup trong bộ nhớ và execution nền trong API process; không lưu full effective-config record vào DB. Quyết định chuẩn nằm trong [design](design.md#baseline-da-chot).

## Sửa precedence để bám sát Onyx

`chat_utils.py:917-945` và `process_message.py:314-337` xác định custom persona thắng Project: custom assistant dùng instructions/files của chính nó, kể cả instructions/files rỗng; Project chỉ giữ vai trò tổ chức. Default assistant trong Project mới lấy Project instructions/files. Message attachments vẫn là một loại input riêng với ownership checks.

Baseline MEM-11 phải theo quy tắc này. Không tự concatenate assistant+project instructions hoặc union cả hai file scopes. Nếu sau này muốn composition, đó là thay đổi sản phẩm có policy/acceptance riêng. Source selection vẫn chịu Tenant/source ACL ceilings; precedence nội dung không cấp quyền.

## Framework reuse map

| Việc AI | Abstraction ưu tiên | MemoryOS bổ sung |
| --- | --- | --- |
| Model selection | Embabel ModelProvider, ModelSelectionCriteria, LlmOptions, LlmService | MEM-11 một provider binding; catalog, quyền chọn model và credential references mở rộng ở MEM-77; bind kết quả đã resolve vào lượt |
| Query rewrite/structured section selection | Embabel Ai/PromptRunner typed generation; Spring AI structured output bên dưới | Allowlisted IDs/ranges, parent budget và domain validation |
| Provider inference | Public Embabel PromptRunner và native Spring AI ChatModel, Prompt, Message, ChatOptions, ChatResponse/metadata | Public capability delegate và stream guard cho gap đã probe; không tự dựng message streamer |
| Tools | Embabel Tool/@LlmTool, ToolCallContext, ToolCallInspector; existing SpringToolCallbackAdapter khi nối Spring ToolCallback/ToolContext | Authorized handlers và product catalog metadata; không schema/execution framework thứ hai |
| Streaming loop | Native streamer/tool loop được PromptRunner gọi; tool callbacks/inspectors có sẵn | Transient stream/replay, Stop/deadline và last-cycle request policy; không thêm outer loop |
| Usage và cost | Embabel Usage, LlmInvocation, PricingModel, process totals, Budget/EarlyTerminationPolicy | Nối usage streaming vào accounting một lần; gọi native policy tại inference/tool boundary, giữ unknown và correlation |
| Conversation và assets | Embabel Conversation/ConversationFactory/AssetView khi có action/tool tiêu thụ; PromptRunner.withMessages cho turn hiện tại | Adapter trên JDBC nếu consumer cần; quyền, selected branch, stable message IDs và terminal writes vẫn thuộc Chat |
| Prompts/context | Framework prompt/template/message abstractions | Selected branch và model-context compaction theo history semantics đã chốt |
| Retrieval | Reuse MemoryOS Retrieval với Spring AI EmbeddingModel hiện có | Query orchestration/cross-query fusion; không thêm VectorStore/index thứ hai chỉ để dùng abstraction |
| Future research | Ưu tiên Embabel agent/action/goal/process abstractions khi có use case thật | Chỉ đánh giá workflow/checkpoints khi có use case thực; chưa thuộc MEM-11 lần đầu |

Spring AI docs có ToolCallingManager/manual loop; khi Embabel đã sở hữu loop thì không dựng thêm vòng ToolCallingManager chạy song song. ChatMemory/model-context APIs không thay product conversation/message tree hoặc stream replay. Có thể adapter sang chúng nếu có consumer thật, không tạo kho chat thứ hai.

Stable Embabel ModelProvider.resolveLlmOptions được gọi theo operation. Resolve answer/query/selection roles một lần cho lượt vào `ChatTurnSetup` trong bộ nhớ; execution dùng binding đó thay vì đọc mutable role alias mỗi step. Không serialize framework services hoặc secrets. Assistant settings vẫn có persistence sản phẩm thông thường; không thêm RunConfigSnapshot/config versioning engine.

## Kết quả kiểm chứng và giới hạn

Baseline một provider và phần mở rộng nằm trong [design](design.md#baseline-một-provider-và-phần-mở-rộng). MEM-11 sở hữu [full provider/model architecture](provider-model-architecture.md), gồm cả schema/API/user/admin/BYOK/native integration/client lifecycle. 2.3 giao code/test mẫu với một provider; [MEM-77](https://linear.app/memory-os/issue/MEM-77) triển khai mở rộng theo thiết kế này, chia User select, Admin config và BYOK cấp tổ chức theo Onyx; personal BYOK ngoài phạm vi. Issue ghi contract từ `db/llm.py`, `llm/factory.py`, `server/manage/llm/api.py` và `server/gateway/model_catalog.py`: identity/precedence, quyền, visibility khác revoke, default lifecycle, omitted updates và masking/rotation. Giữ phân biệt contract Onyx với adapter cần cho stack Java.

Reference bổ sung: `D:/OrgMemory` tại HEAD `db999fab09a2f012f276909878840e1941fa2711`, các file trong `integrations/ai-model-gateways/.../gateway/`: `SpringAiChatModelFactory`/`SpringAiChatModelFactories` tách tạo native client theo protocol; `SpringAiChatModelProvider` resolve/cache và evict khi cấu hình đổi; `AiModelCatalogProbe` giới hạn discovery, timeout và redaction. Học separation và lifecycle cần thiết, không copy toàn bộ gateway/workload/profile registry vào baseline một provider. Eviction chưa chứng minh resource đã được đóng. Native Embabel `ConfigurableModelProvider` 1.5.1 nhận list services khi construction; catalog CRUD động cần kiểm refresh/client lifecycle riêng trong MEM-77.

Public PromptRunner ở Embabel 1.5.1 đã được kiểm qua native SpringAiLlmService → SpringAiLlmMessageStreamer → DefaultStreamingToolLoop; đường này thay custom streamer của probe Phase 1. [Entrypoint verification](framework-entrypoint-verification.md) ghi real-model/HTTP receipts và các public extension cần thiết. Native streamer dùng Spring AI aggregation; provider adapter vẫn sở hữu ghép raw tool fragments. Capture usage trước aggregation để nối vào native accounting, không tự viết lại phép tính cost.

[Audit end-to-end](framework-entrypoint-verification.md#end-to-end-audit--2026-09-09) kiểm thêm accounting/budget và factory/Chatbot lifecycle. Factory có thể tích hợp mà không thay JDBC, nhưng phải giải quyết message reservation và branch freshness khi có consumer. [Design](design.md#end-to-end-framework-integration) sở hữu quyết định xuyên toàn bộ sáu phase. Native accounting không đồng nghĩa streaming tự ghi usage, native Budget không thay cycle/context/deadline limits, và probe fixture không thay product IAM/DB/browser acceptance.

Giữ MemoryOS code ở product orchestration/persistence/authorization và các adapter có lý do cụ thể. Mỗi adapter phải chỉ rõ upstream gap; ưu tiên bỏ adapter khi supported framework API đáp ứng đủ observable contract.
