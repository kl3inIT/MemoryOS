# Provider/model architecture — MEM-11 baseline và MEM-77

Chốt thiết kế ngày 2026-09-09. Tài liệu này giữ reference architecture xuyên backend và UI. Backend catalog, quyền, organization BYOK, selection và adapter contract được triển khai trong [increment backend](../mem-77-provider-backend/design.md); [Chat model spec](../../../specs/chat-models.md) sở hữu contract đã có. UI selector/admin chưa triển khai; Đức Anh mở rộng adapter local và kiểm endpoint thật trên nền backend này.

Phases 2.1–2.4 đã merge vào main qua PR #86. Backend provider/model foundation đang trên `feat/mem-77-provider-backend`; không coi đó là code đã merge hoặc local provider đã nghiệm thu. [Handoff](../mem-77-provider-backend/adapter-handoff.md) chỉ rõ extension point và phần kiểm chứng còn thuộc adapter mới.

## Ownership và điểm nối

Luồng: admin config/BYOK tổ chức → catalog có quyền → request/Persona/default resolution → native service → ChatTurnSetup → Ai/PromptRunner → native tool loop/Spring AI → provider. Chat lifecycle và transcript/replay bao quanh execution, không nằm trong factory provider.

| Owner | Trách nhiệm | Không sở hữu |
| --- | --- | --- |
| Chat application | Actor/Tenant, catalog access, precedence, resolve cấu hình một lần cho lượt | HTTP client construction, SQL trực tiếp, inference loop |
| Chat persistence | JDBC cho provider/model/Persona defaults và associations; constraints, atomic updates | Secret trong user DTO, model invocation |
| Provider integration | Tạo native ChatModel/service, converter/capability binding, timeout/retry/observability và đóng client | Quyền chọn model, message tree, SSE |
| Native Embabel | LlmService, options/messages/tools, Ai/PromptRunner, streamer/tool loop, Usage/PricingModel/Budget | Product catalog CRUD, Actor/Tenant authorization |
| Chat execution | Guard theo lượt, terminal lifecycle, partial/error, native accounting bridge | Provider-specific options, tự tính giá hoặc thêm loop |
| API/web | Admin/User DTOs, validation, generated client, selector và settings forms | Secret trong catalog user, tự quyết authorization |

Trong MEM-11, `api.chat` tạo provider configuration; `core.chat.execution` nhận native service. Chưa thêm capability/module AI độc lập khi Chat là consumer sản phẩm duy nhất. MEM-77 đặt catalog application/persistence trong Chat, protocol adapters tại API composition; chỉ chuyển thành shared integration module khi có consumer liên capability thực. Tên `LLMProvider`, `ModelConfiguration`, `Persona` bám Onyx; không tạo entity Assistant thứ hai hay model tiers tự đặt.

## Native integration giao ở 2.3

1. Configuration tạo client OpenAI-compatible, native Spring AI `ChatModel`, converter và `SpringAiLlmService` hoàn chỉnh. Giữ model API name, provider identity, pricing, prompt contributors, thinking/tool-response/structured-output capabilities khi đã cấu hình.
2. Resolve native service cùng per-turn `LlmOptions` trước khi dispatch execution, giữ trong RAM. Executor không đọc mutable catalog/properties lại ở từng inference. Model name gửi provider khác catalog configuration ID.
3. Executor dùng service đã resolve, thay ChatModel bằng guard của lượt ở một helper duy nhất và giữ nguyên các metadata/adapters còn lại. Không tạo HTTP client mới cho mỗi lượt. `StreamingLlmService` delegate chỉ giữ capability đã kiểm; không viết sender/streamer/tool loop.
4. Dùng `Ai.withLlmService(service)` rồi public PromptRunner streaming; bỏ manual PreResolvedModelSelectionCriteria ở caller khi native shorthand đáp ứng cùng options contract. Explicit per-turn options phải giữ pre-resolved service; không gọi role/default resolution lại ở giữa loop.
5. Existing native ModelProvider tiếp tục phục vụ consumers native cần selection. Product resolver kiểm quyền/precedence rồi đưa service đã resolve vào runner; không dựng thêm registry native song song hoặc bắt every catalog ID thành role alias.

**Factory đã đối chiếu:** Embabel 1.5.1 `OpenAiCompatibleModelFactory` có native service construction, nhưng tự tạo sync/async clients bằng private methods, timeout 600 giây, không expose constructor parameters cho timeout/retry; `chatModelOf` không open để override. `openAiCompatibleLlm` yêu cầu pricing non-null. `buildValidated` gọi model probe và không thay contract streaming/tools. Vì vậy baseline dùng composition Spring AI với timeout/retry/close rõ ràng và native SpringAiLlmService; không subclass để can thiệp internals hoặc probe mỗi lượt. Native factory được xét lại khi public API đáp ứng các contract này.

Học OrgMemory ở separation: client factory theo protocol, observability và credential redaction; native Embabel là service/execution boundary. MEM-11 không copy gateway/workload registry. Một factory concrete có caller thật là đủ cho provider đầu; protocol dispatch được thêm ở MEM-77 khi có implementation thứ hai, output vẫn là native service. Không bắt Nhật Anh quyết định lại execution entry point.

## Catalog schema mục tiêu — triển khai trong MEM-77

| Record | Trường và invariant |
| --- | --- |
| `LLMProvider` | UUID ID, Tenant ID, display name, provider type/protocol, base URL, credential ciphertext/key reference, public access flag, timestamps. Không dùng display name làm identity; tên trùng vẫn route đúng. Chỉ nhận typed connection settings của adapter hỗ trợ |
| `ModelConfiguration` | UUID ID, Tenant/provider ID, model API name, display name, visibility, context/output limits, supported options/capabilities, optional pricing. Unique model API name trong một provider; composite FK bảo đảm cùng Tenant |
| Provider access associations | Group IDs và Persona IDs, composite ownership constraints; không sao chép Group entity sang Chat |
| Chat deployment default | Một default model configuration trong Tenant cho Chat. Chỉ tạo các flow khác khi có consumer; không copy toàn bộ LLMModelFlow của Onyx |
| Persona default | Optional model configuration ID cùng Tenant; không dùng provider display name/model string làm FK |
| Message metadata | Model/provider label và request options thực dùng ở mức cần giải thích answer; optional configuration reference không cascade-delete transcript. Không lưu key hoặc full RunConfigSnapshot |

V21 và các endpoint backend đã được triển khai theo [catalog spec](../../../specs/chat-models.md). Deployment provider khởi tạo catalog một lần với ID riêng; Persona chưa chọn ID thì dùng Tenant Chat default. Không dò tên model để đoán provider hoặc rewrite applied migration. API dùng PUT full configuration với revision thay cho PATCH trong các route dự kiến bên dưới; OpenAPI là contract thực thi.

## User select và resolution

Catalog trả các configuration ID user được chọn trong Persona đang dùng, display labels, provider labels, capabilities và limits; không có credential/base-url quản trị. Ordering ổn định, thêm provider label khi model display name trùng. Unknown capability/limits giữ unknown, không tự công bố supported.

Precedence: request override → session override nếu được đặt → Persona default → Chat default. Explicit configuration ID thắng name lookup; wire contract mới chỉ nhận ID, không cần legacy provider-name override. Khi ID stale hoặc lựa chọn không còn quyền, học Onyx fallback tới default hợp lệ và được phép; không gọi provider trái quyền, không lookup lại cùng tên. Không có default hợp lệ thì trả stable error trước reservation/model call. Response/answer metadata cho biết model thực dùng và fallback reason an toàn; không lộ tên provider/model user không được phép đọc. Malformed request/options vẫn là validation error, không dùng fallback che lỗi client.

Mỗi loại option có precedence riêng: explicit turn/session option → admin model default → user preference nếu consumer này tồn tại → deployment fallback. Provider limits là trần; không dùng generic JSON merge để biến field unsupported thành hợp lệ. Unsupported options bị từ chối trước inference. Default reasoning effort phải nằm trong max sau merge. MEM-11 dùng settings đã hỗ trợ, không thêm màn user preferences chỉ để lấp mọi tầng precedence.

Quyền provider theo Onyx: public cho phép user qua Group gate nhưng Persona allowlist vẫn áp dụng; restricted với cả Group và Persona phải đạt cả hai. Người quản lý có thể bypass Group, không bypass Persona restriction cho execution. Restricted không có Group/Persona là manager-only. Public ở đây là các Actor được IAM cho vào Tenant, không anonymous. Quyền quản trị catalog dùng capability IAM riêng khi triển khai MEM-77, cấp mặc định cho nhóm quản trị; không dùng quyền manage Sources thay thế. List và send dùng cùng policy; source ACL vẫn kiểm độc lập khi Retrieval chạy.

Visibility chỉ điều khiển selector, không phải revoke. Đổi quyền provider chặn các lần resolve mới; không tự cancel lượt đã bắt đầu hoặc ẩn transcript cũ. Nếu provider từ chối key đã revoke giữa lượt, runtime giữ partial/error; không retry bằng key/provider khác. User chọn model mới không đổi binding của lượt đang chạy.

## Admin config, BYOK và mutation lifecycle

| Command | Contract |
| --- | --- |
| Create provider/model | Validate typed settings, endpoint policy và unique constraints; key chỉ nhận qua admin mutation; không model call trong DB transaction |
| Edit | Omitted fields giữ nguyên; explicit reset chỉ cho nullable settings; validate merged result. Concurrent stale edit trả conflict thay vì ghi đè không hay biết |
| Change default | Cùng Tenant, tồn tại, visible và usable; không tự đổi tất cả Persona defaults. Atomic replace; không có khoảng trống default giữa transaction |
| Hide/remove | Chặn chuyển visible→hidden hoặc xóa model đang làm Chat/Persona default; admin đổi các defaults trước. Không cascade-delete messages. Bảo vệ Persona default chủ động là khác biệt đã chọn so với fallback-only: thêm thao tác admin, tránh thay model cho Persona ngoài ý muốn |
| Credential | Command rõ `KEEP`, `REPLACE`, `REMOVE`; read trả `configured`/masked state, không plaintext. `KEEP` không yêu cầu gửi masked key trở lại. `REMOVE` bị từ chối nếu đang cấp default bắt buộc và không có phương án thay; adapter cần auth thì các resolve mới trả unavailable |
| Validate connection | Admin chủ động gọi bounded provider check; lỗi auth/network được phân biệt an toàn. Không coi successful probe là đã verified tools/streaming; không tự gọi probe mỗi lượt |

Secret ciphertext thuộc provider/Tenant, master key nằm ngoài DB trong deployment secret management (Infisical). Dùng cơ chế authenticated encryption qua thư viện/platform đã kiểm, key ID để đọc ciphertext sau rotation; không viết thuật toán mã hóa. OrgMemory `core/.../shared/secret/SecretCipher`/`SecretValue` là reference cho cơ chế và redaction, chưa có bằng chứng một generic cipher tương đương đã triển khai trong MemoryOS nên không ghi là reuse sẵn. Triển khai và kiểm encrypt/decrypt/tamper/rotation ở MEM-77. Không có personal BYOK/key store theo Actor.

Endpoint policy cho custom providers nằm ở server: admin không tự bypass deployment network restrictions; hỗ trợ internal endpoint được deployment cho phép. Không cho credential trong URL; provider errors/logging không xuất raw request/secret. Không nhận arbitrary executable configuration.

## Client lifecycle và cấu hình đổi

Baseline 2.3 có một service/client dùng chung, Spring quản lý close khi bounded execution drain kết thúc. Deadline, no automatic inference retry, subscription cancellation và native observability phải hoạt động với client thực. Guard/accounting state là per-turn, không lưu mutable run state trên shared service.

MEM-77: catalog DB là authority; mỗi lượt đọc/validate cấu hình hiện tại trước khi acquire service. Client/service cache trong API process là optimization, keyed theo configuration identity và giá trị kết nối/model/options đã resolve, không chỉ model name. Không log cache key chứa credential. Không cache authorization. Giới hạn số entries và retired clients; khi đạt giới hạn thì backpressure/reject acquisition, không tích lũy client vô hạn.

Sau config commit, lượt mới lấy binding mới. Binding cũ ngừng cấp cho lượt mới; các lượt đã acquire giữ nó đến khi release trong finally. Chỉ close retired client khi không còn lượt dùng; mọi lượt có deadline nên retirement không vô hạn, shutdown vẫn có trần. Bounded stale-edit revision/ETag phục vụ concurrent admin updates không là full config history/versioning engine. Nếu native cache API đáp ứng acquire/retire/close thì dùng; nếu không, viết phần lifecycle nhỏ ở provider integration, không thêm inference registry. Contract tests bắt buộc cho race acquire/update/retire/close.

Native service có thể giữ metadata theo model trong khi HTTP transport chia sẻ theo connection; implementation được phép reuse transport nhưng phải cùng quyền sở hữu resource. Không đóng shared transport khi chỉ một model bị evict. Mất process không resume model calls, không cần distributed cache/lease ở baseline một API process.

## HTTP và UI contract mục tiêu — MEM-77

| Surface | Contract |
| --- | --- |
| `GET /api/chat/models?personaId=...` | User catalog được phép dùng và default/fallback information an toàn; ID/capabilities/limits; không secrets |
| Existing session/send/Persona commands | Thêm optional `modelConfigurationId` và typed model options tại đúng consumer. Omitted override giữ semantics default; không đổi các stable message IDs |
| `GET/POST /api/chat/providers` | Admin list/create provider; read redacted, create có credential command |
| `GET/PATCH/DELETE /api/chat/providers/{providerId}` | Admin read/update/delete, stale-write precondition; khóa defaults/references trước deletion |
| `POST /api/chat/providers/{providerId}/models` và `PATCH/DELETE .../models/{modelId}` | Model settings/visibility lifecycle, ownership checks |
| `PUT /api/chat/model-default` | Atomic Chat default change bằng configuration ID |
| `POST /api/chat/providers/{providerId}/validate` | Explicit admin bounded connection check, không side-effect catalog sync tự động |

Các route mới là design, chưa được expose hoặc generate vào OpenAPI trước implementation. Tất cả mutation dùng IAM/CSRF hiện có; backend authorize trước provider/secret access. Validation error, not found/unauthorized resource, stale edit, conflicting default và unavailable provider theo Problem Details conventions. Tests chốt status/code khi controllers được triển khai, không thêm generic error registry.

UI gồm model selector trong Chat, provider list/detail và model settings trong admin, credential replace/remove trong provider form. Save cấu hình và validate connection là thao tác khác; UI không báo đã hỗ trợ tools chỉ vì key check pass. Khi fallback, hiển thị model thực dùng. Không có lựa chọn key cá nhân hoặc người trả tiền.

## Kiểm chứng và bàn giao

MEM-11 provider integration phải chứng minh: service có converter/metadata khác chạy qua cùng executor không sửa provider logic; per-turn wrapper giữ pricing/capabilities/adapters; hai lượt không lẫn guard/accounting; client được reuse/close đúng; options không bị GPT-5 hardcode trong executor. HTTP fake provider kiểm observable request/finish/usage/Stop; OpenAI thật kiểm native streaming route. Đây là extension contract test, không phải chứng nhận provider thứ hai.

MEM-77 chạy lại bộ integration contracts rồi thêm matrix catalog identity/precedence/visibility/access/defaults/omitted update/credential/rotation. Kiểm cả admin config → user select → actual model call trên browser, hai configured provider/model bindings thực, concurrent update với run đang stream. Handoff gồm code exemplar trong MEM-11, test entry points và tài liệu này; Nhật Anh thực hiện schema/UI/provider extension, không chọn lại architecture.

Giới hạn: một API process; organization BYOK; một provider được implementation/verification trong MEM-11. Personal BYOK, gateway platform, multi-replica coordination, web search/deep research và toàn bộ Onyx model flows ngoài scope. Thiết kế đủ các luồng trong phạm vi không đồng nghĩa tạo trước mọi bảng hoặc chạy mọi adapter.

## Reference và tradeoff

- Onyx `.tmp/onyx` `06aa2b09cc4aa5135fa2627e5235814e996f1514`: `db/models.py`, `db/llm.py`, `llm/factory.py`, `server/manage/llm/api.py`, `server/gateway/model_catalog.py`, `chat/chat_state.py`. MEM-77 có bảng tám contract/source pinned; identity, default fallback, quyền provider và config-per-turn là baseline, không suy diễn visibility thành revoke.
- OrgMemory `D:/OrgMemory` `db999fab09a2f012f276909878840e1941fa2711`: `SpringAiChatModelFactory`, `SpringAiChatModelFactories`, `SpringAiChatModelProvider`, `OpenAiCompatibleChatModelFactory`, `AiModelCatalogProbe`. Học separation/client lifecycle; không copy gateway/workload model. Source eviction không tự chứng minh safe close khi run còn giữ client.
- Embabel `.tmp/embabel-agent-1.5.1`: `Ai.withLlmService`, `SpringAiLlmService`, `ModelProvider`, `OpenAiCompatibleModelFactory`. Native service/runner giảm code inference; composition vẫn cần xử lý lifecycle và metadata gaps có bằng chứng. Factory cao hơn không mặc nhiên đáp ứng mọi production contract.
- Scope full design thêm công thiết kế và tests nền ngay trong MEM-11; đổi lại MEM-77 không tự quyết schema/API/ownership/entrypoint. Kiến trúc này không được tuyên bố tốt hơn Onyx nói chung; các khác biệt có consumer và tradeoff ở trên.
