# MEM-11 — Chat theo baseline Onyx

Frontend runtime đã chốt và triển khai ở Phase 2.4 ngày 2026-09-09: `useChatRuntime` + public `ChatTransport`, giữ wire contract Phase 2.3; xem [so sánh và giới hạn kiểm chứng](ui-runtime-verification.md). Product contracts và verification nằm trong Chat spec và verification của increment; probe so sánh vẫn giữ đúng ranh giới fixture.

Cập nhật ngày 2026-09-09. Phase 2.1–2.3 persistence, native background execution, provider binding, local Stop và RAM replay/SSE đã triển khai trong working tree; Chat UI Phase 2.4 đã nối qua adapter, không đổi wire contract của backend. [Verification hiện tại](verification.md) và [Chat spec](../../../specs/chat.md) phân biệt implementation với thiết kế còn lại. [Plan](plan.md), [parity](onyx-parity.md), [framework reuse](onyx-config-and-framework-reuse.md), [reference review](spring-ai-reference-review.md) và [Phase 1 verification](phase-1-verification.md) ghi phạm vi và bằng chứng. Áp dụng [quy tắc thiết kế theo reference](../../../conventions.md#reference-based-design-and-scope-control).

Backend provider/model foundation bổ sung được theo dõi tại [increment riêng](../mem-77-provider-backend/design.md); đây là phần nền backend đã được yêu cầu, không thêm UI/web search/image generation. Phases 2.1–2.4 đã merge qua PR #86; các ghi chú working-tree lịch sử phía dưới không đại diện trạng thái merge hiện tại.

## Phạm vi

Production là yêu cầu ngay từ đầu cho phạm vi đã chọn: đúng chức năng/quyền, toàn vẹn dữ liệu, giới hạn tài nguyên, xử lý lỗi, quan sát và kiểm chứng vận hành phù hợp. Kiểm soát scope không được dùng để bỏ hardening cần thiết hoặc hạ mục tiêu thành prototype. Mỗi cơ chế phải gắn với yêu cầu hoặc tình huống lỗi cụ thể; không cần chờ sự cố thật mới xử lý.

Giao Chat trên kiến thức nội bộ: hội thoại, streaming, agent search, citations, stop/reconnect, edit/regenerate/branch, assistant/model settings, attachments, Projects và chia sẻ có xác thực theo [parity](onyx-parity.md). Web search/deep research chưa thuộc lần giao đầu. Không suy rộng “giống Onyx” thành triển khai mọi tính năng của Onyx.

MEM-11 tiếp nhận multi-query/weighted RRF và context selection/expansion từ MEM-71/72, dùng Search backend MEM-46. Search UI có thể phát triển song song; nghiệm thu Chat vẫn cần Search thật. MEM-66 nghiên cứu gateway/vLLM độc lập, không là prerequisite cho provider hiện có; contract MEM-25/audit cần được kiểm theo phạm vi nghiệm thu; lần cập nhật này không đổi trạng thái Linear.

## Baseline da chot

Người dùng chọn giữ cách Onyx làm vì các phần bổ sung trước đó chưa chứng minh lợi ích tương xứng. Bảng này thay thế đề xuất worker/journal/snapshot và history dependency ACL trong các bản nháp trước.

| Nội dung | Onyx tại source đã đọc | MemoryOS đã chọn | Điểm mạnh và giới hạn chấp nhận |
| --- | --- | --- | --- |
| Cấu hình lượt | Dựng `ChatTurnSetup` frozen trong bộ nhớ, giữ model/prompt/context đã resolve | Dùng `ChatTurnSetup` trong bộ nhớ; lưu assistant settings và metadata message thông thường | Ít serialization và persistence bổ sung; không có full execution-config replay từ DB. Không triển khai durable `RunConfigSnapshot` hay config revision engine |
| Nơi chạy | Model threads và writer trong API process; reader disconnect không dừng writer | Execution nền do application quản lý trong API process, tách khỏi kết nối browser | Ít thành phần vận hành; API process chết thì execution bị gián đoạn. Không thêm Chat worker/dispatch queue riêng; worker Ingestion hiện có giữ trách nhiệm của nó |
| Stream/replay | DB giữ messages/results; shared cache giữ chunks có sequence và TTL; gap quay về message DB | Một API process: buffer trong RAM có sequence, TTL và giới hạn byte; DB giữ transcript/outcome | Bớt dependency vận hành cho phạm vi hiện tại; reconnect chỉ được bảo đảm trong cùng process còn sống. Restart mất buffer; không khôi phục token chưa lưu. Không có PostgreSQL event journal |
| History và quyền | Đường history kiểm quyền conversation rồi trả saved messages/citations; fresh search kiểm quyền nguồn | Giữ quyền conversation cho transcript; kiểm quyền hiện tại khi retrieval, đọc nguồn/file và thao tác mới | History ổn định, ít kiểm tra phụ thuộc. Revoke nguồn không tự xóa nội dung đã lưu trong transcript; không dựng transitive dependency ACL cho toàn bộ answers/summaries |
| Nguồn cập nhật | `SearchDoc` lưu metadata lúc retrieval tách khỏi canonical Document; chưa thấy rule che answer theo generation tại đường history đã đọc | Không che answer cũ chỉ vì source/index generation đổi; truy vấn mới dùng nguồn hiện tại | Giữ đúng lịch sử, nhưng answer cũ có thể lỗi thời. Citation không mở được và transcript retention là hai hành vi riêng |
| Assistant/Project | Custom persona dùng instructions/files riêng, kể cả rỗng; default persona mới dùng Project | Giữ đúng precedence; message attachments là input riêng có quyền | Dễ dự đoán, không tự concatenate instructions hoặc union file scopes |

Đây là lựa chọn baseline, không phải tuyên bố Onyx tốt hơn trong mọi tình huống. Worker độc lập, durable replay hoặc thu hồi nội dung lịch sử chỉ được xét lại khi có yêu cầu thực và tradeoff cụ thể. Không hứa resume model call sau process death hoặc exactly-once tool side effects.

Source đối chiếu: Onyx `.tmp/onyx` commit `06aa2b09cc4aa5135fa2627e5235814e996f1514`:

- `backend/onyx/chat/chat_state.py:195` và `chat/process_message.py:1060-1101`: turn setup; frozen top-level không đồng nghĩa mọi object bên trong deep immutable.
- `chat/process_message.py:1468-1627` và `chat/stream_buffer.py`: writer, live reader, batching/cache/replay; cache publication không phải durability barrier của mọi visible delta.
- `server/query_and_chat/chat_backend.py:350-420,1279-1357`, `db/chat.py:963-1012`: history và resume; không suy rộng kết luận sang mọi đường authorization khác.
- `db/models.py:3488` (`SearchDoc`): retrieval metadata phục vụ chat replay, không phải bản sao đầy đủ nội dung tài liệu.
- `chat/chat_utils.py:917-945`, `chat/process_message.py:314-337`: custom assistant/Project precedence.

## Trách nhiệm và luồng chạy

| Thành phần | Trách nhiệm |
| --- | --- |
| Web / assistant-ui | Render server-owned messages/tool parts, branch navigation và commands; adapter cho history/SSE |
| Chat capability | ChatSession, ChatMessage, Persona settings, turn setup, authorization, background execution lifecycle và finalization |
| Concrete Chat persistence repositories | SQL/row mapping, message tree, outcome/citation metadata, constraints và active-run coordination cần thiết |
| API composition | Authenticated endpoints và application-owned background executor; đóng/mở SSE reader không sở hữu model execution |
| Embabel / Spring AI | Một outer tool loop; model/provider, messages/options, native tools và metadata APIs |
| Retrieval public API | Search/context reads có quyền trên index hiện có |
| Stream buffer trong RAM | Replay trong một API process ở Phase 2.3, có TTL/byte/read limits; không thêm Redis client vào API cho Chat |

Luồng: nhận command và kiểm quyền → lưu message identity/quan hệ branch → dựng `ChatTurnSetup` → chạy nền inference/tools → writer phát stream và buffer chunks có sequence → lưu outcome/citations vào DB. Không giữ DB transaction trong lúc chờ model. Provider stream lỗi hoặc kết thúc thiếu terminal metadata cần thiết giữ partial outcome ở trạng thái failed; không tự chạy lại toàn bộ lượt hoặc tool đã thực hiện. Contract Phase 2 dưới đây phục vụ trực tiếp consumer send/history/Stop/reconnect; OpenAPI và migration sẽ được tạo cùng implementation, không phải API/schema đã tồn tại.

Reconnect kiểm quyền conversation, đọc chunks sau sequence đã nhận; duplicate bị bỏ, gap/expiry tải trạng thái message đã lưu. Fallback chỉ phục hồi dữ liệu DB thực sự có, không khôi phục token hoặc execution đã mất. Buffer RAM có giới hạn mỗi lượt và tổng process, TTL và bounded reads; eviction không tự hủy execution. Slow reader bị ngắt subscription khi vượt giới hạn, writer vẫn chạy. Process restart mất replay; không hứa reconnect qua process khác.

Stop là command riêng: kiểm trước inference/tool, truyền cancellation tới subscription/provider khi hỗ trợ, lưu partial outcome phù hợp. Cần phân xử complete/cancel và nhận biết run gián đoạn khi process chết; không biến cơ chế phối hợp active run này thành một queue/worker platform. Browser disconnect không tự cancel.

Phase 2.2 hiện thực thi bằng Spring-managed virtual threads và Reactor cancellation, còn marker Stop trên assistant row PostgreSQL và owner polling. Quyết định phạm vi ngày 2026-09-09 thay phần coordination này: Phase 2.3 bỏ DB Stop signaling/polling, truyền Stop trực tiếp tới active execution trong cùng API process sau kiểm quyền. DB vẫn giữ transcript, conditional terminal winner và deadline để kết thúc stale RUNNING; không bỏ persistence chỉ vì bỏ signaling. Đây là thay đổi đã lên kế hoạch, chưa là runtime đã triển khai. Shutdown vẫn cần bounded drain và giữ partial phù hợp.

### Baseline một provider và phần mở rộng

MEM-11 sở hữu [kiến trúc provider/model xuyên suốt](provider-model-architecture.md): schema, API/UI, quyền, BYOK tổ chức, precedence, native integration và client lifecycle. Triển khai baseline một provider và contract tests ở 2.3; MEM-77 mở rộng trên baseline đó, không quyết lại architecture. Composition tạo native Embabel `LlmService` cùng Spring AI `ChatModel` và options converter phù hợp; executor nhận binding đã resolve và gắn guard theo lượt. Dùng `Ai.withLlmService` và public PromptRunner, giữ native streamer/tool loop/accounting. Provider-specific construction có caller thật; không tạo registry song song hoặc empty future adapters.

Implementation catalog nhiều provider/model, stable configuration IDs, admin CRUD, quyền chọn model, BYOK cấp tổ chức, refresh/credential rotation và provider thứ hai thuộc [MEM-77](https://linear.app/memory-os/issue/MEM-77), assign `phamnhatanh811`, bị chặn bởi MEM-11. Thiết kế các phần này đã thuộc MEM-11 theo tài liệu linked ở trên. Theo Onyx: admin cấu hình provider/key tổ chức, user chọn model được cấp quyền; personal BYOK ngoài phạm vi. Persona/settings trong MEM-11 chỉ dùng binding hiện có; không dựng selector giả hoặc tier model tự đặt. `ChatTurnSetup` tiếp tục giữ cấu hình đã resolve trong RAM.

Đây là giới hạn triển khai có chủ đích so với catalog/shared cache của Onyx. RAM và local Stop giảm vận hành cho một process, nhưng không đáp ứng horizontal replicas. Native binding tạo điểm tích hợp cho MEM-77; catalog động vẫn cần implementation và kiểm chứng riêng, không coi việc thêm một bean là đủ. Xem [reference và framework reuse](onyx-config-and-framework-reuse.md).

## Thiết kế chi tiết Phase 2

### Domain story, glossary và ownership

Actor đã được IAM xác thực mở một ChatSession riêng, gửi câu hỏi dưới nhánh hiện tại và nhận một assistant message có ID ổn định. Backend chuẩn bị context, chạy model nền, phát text và lưu outcome. Actor có thể Stop hoặc kết nối lại; Actor khác không được đọc/điều khiển session trái quyền. Phase 2 giao luồng này với một model thật và Persona mặc định có cấu hình; Search tools/citations nguồn thật thuộc Phase 3, editor Persona và sharing thuộc Phase 5.

| Thuật ngữ | Ý nghĩa và quan hệ |
| --- | --- |
| `ChatSession` | Hội thoại thuộc Tenant và owner Actor, có một cây ChatMessage và Persona được chọn. Không phải HTTP/authentication session; “conversation/hội thoại” trong văn bản chỉ cùng khái niệm này |
| `ChatMessage` | Một node trong cây: root kỹ thuật, câu hỏi USER hoặc câu trả lời ASSISTANT; parent và latest child xác định nhánh. Root không hiển thị hoặc gửi model |
| `Persona` | Cấu hình vai trò AI: instructions và model settings đang dùng. Giữ tên Onyx; không mang nghĩa hồ sơ của người dùng. Chỉ thêm các trường tools/sources/Projects khi consumer tương ứng được triển khai |
| Turn / lượt thực thi | Xử lý một câu hỏi thành một assistant message trong phạm vi một model. `runId = assistantMessageId`; không có aggregate/bảng `ChatRun` riêng ở Phase 2 |
| `ChatTurnSetup` | Context đã resolve trong RAM cho lượt, chứa identity và concrete model/options/history; không là bản ghi cấu hình bền vững |
| Stream buffer | Bản sao tạm của output để đọc tiếp; một chunk chứa nhiều events. Cursor đọc chunk và sequence event tới browser là hai vị trí khác nhau |

```text
Tenant + owner Actor → ChatSession → cây ChatMessage
                              │       root → USER U1 → ASSISTANT A1
                              │                         runId = A1
                              └→ Persona → ChatTurnSetup trong RAM
```

Căn cứ đặt tên: Onyx `db/models.py:3166,3269,4176` có ChatSession/ChatMessage/Persona; `chat/process_message.py:962-974` dành assistant message trước và dùng ID đó làm processing run ID cho single-model. Nhánh multi-model của Onyx dùng user-message ID; không suy rộng mapping Phase 2 sang tính năng đó. Java thêm application services/repositories theo conventions repo, không coi cấu trúc thư mục Onyx là bằng chứng đủ cho bounded context.

Capability `io.memoryos.chat` sở hữu session/message/Persona và invariant của lượt. IAM sở hữu Actor/Tenant/membership; Chat chỉ gọi public IAM contracts. API gọi public Chat application contracts và quản lý executor; Web dùng HTTP. Dependency `chat → retrieval` chỉ thêm ở Phase 3 khi có tool caller; không có cạnh ngược hoặc truy cập persistence của capability khác. Không tạo domain chung `ai` để gom mọi capability dùng model.

### Schema và consistency boundary

Đây là schema mục tiêu cho Phase 2. V18 đã triển khai phần session/Persona/message và được kiểm trên PostgreSQL test; chưa áp dụng lên staging. Các metadata/consumer còn lại được thêm cùng execution. Một migration additive tiếp theo nằm ở `core/src/main/resources/db/migration/`, API chạy Flyway.

| Dữ liệu Chat sở hữu | Nội dung tối thiểu cần lưu |
| --- | --- |
| `persona` | ID, Tenant/scope sử dụng, tên, instructions, model selection/options được phép và dấu nhận biết built-in. Cấu hình mặc định được provision bằng đường khởi tạo bình thường; không tạo profile/endpoint probe. Editor và group sharing chưa thuộc Phase 2 |
| `chat_session` | ID, Tenant, owner Actor, Persona, tên hiển thị và timestamps; session overrides chỉ gồm settings có consumer thật |
| `chat_message` | ID, session, parent/latest-child, role, content, timestamps; assistant outcome/status, safe failure code, model/usage metadata; user command identity để chống gửi trùng |

Theo Onyx, session có một root rỗng và mỗi message có `parent_message_id`/`latest_child_message_id`. Phase 2 nối nhánh hiện tại; giữ cấu trúc cây cho edit/regenerate ở Phase 5 nhưng chưa tạo endpoints/UI cho các hành vi đó. Không có bảng branch hoặc selected branch theo từng Actor.

- DB constraints bảo đảm parent/latest child cùng session, một root/session và tối đa một assistant message `RUNNING`/session. Application kiểm latest child thực sự là con trực tiếp và cập nhật pointers trong transaction; API không nhận quyền tự ghi pointers tùy ý. Tenant/owner được lấy và kiểm phía server.
- Transaction send khóa session, kiểm quyền/parent, chống duplicate, tạo USER và dành ASSISTANT ID rồi cập nhật nhánh. `clientRequestId` unique trong session xác định lần gửi: cùng ID/cùng nội dung command trả lại cùng message IDs; cùng ID/khác command trả conflict. Lưu identity/fingerprint cần thiết để đối chiếu, không lưu full RunConfigSnapshot. Duplicate được kiểm trước active-run conflict để retry mạng không tạo model call thứ hai.
- Admission hữu hạn được kiểm trước khi chấp nhận execution mới; hết tài nguyên không tạo orphan RUNNING. Model chỉ chạy sau commit; submit/setup thất bại phải đóng assistant outcome tương ứng. Không giữ transaction trong lúc chờ provider.
- SQL, row mapping, locks, unique/conditional updates ở concrete persistence repositories; application sở hữu quyền, orchestration và transaction. Group repository theo session/message consistency boundary; không dựng interface một implementation hoặc một repository cho mỗi bảng.
- Người dùng xác nhận giữ `JdbcClient` cho Chat ngày 2026-09-09 sau khi đối chiếu với JPA. Lý do: các thao tác hiện tại tập trung vào truy vấn nhánh đệ quy, khóa session và chuyển trạng thái có điều kiện; SQL thể hiện trực tiếp các invariant đó. Tradeoff là phải bảo trì SQL và mapping thủ công; JPA có lợi cho CRUD/lifecycle nhưng chưa có bằng chứng giảm tổng độ phức tạp của Chat. Đây không phải kết luận benchmark JDBC nhanh hơn JPA hoặc JPA không xử lý được concurrency. Không chuyển JPA hay triển khai thử nghiệm hai persistence chỉ để tiếp tục phase; chỉ xét lại khi workload hoặc bằng chứng bảo trì thay đổi. Quyết định JPA của IAM giữ nguyên.
- Metadata câu trả lời hiện lưu model, token/cost khi biết và safe failure code. Finish reason dùng để kiểm stream nhưng chưa lưu thêm cột khi chưa có consumer. Không lưu secrets, runtime objects hoặc toàn bộ effective config; citations nguồn thật được thêm cùng Retrieval consumer ở Phase 3.

Khác biệt triển khai Java cần kiểm: constraint active message và command identity trong PostgreSQL đáp ứng concurrent send/retry mạng của MemoryOS. Chúng tận dụng rows message đã cần lưu; chi phí là contention theo session và conditional SQL. Đây không phải tuyên bố Onyx thiếu cơ chế concurrency/idempotency; cần tests hai sends đồng thời, retry cùng command và rollback trước khi chốt implementation.

#### Phân biệt Onyx và lựa chọn Phase 2.1

Đối chiếu checkout Onyx `06aa2b09cc4aa5135fa2627e5235814e996f1514`; đây là bằng chứng source ở revision đó, không phải khẳng định cho mọi phiên bản/đường gọi.

| Contract | Onyx đã kiểm | MemoryOS và tradeoff |
| --- | --- | --- |
| Cây message | `db/models.py:3269` có parent/latest-child FK theo ID; `db/chat.py:635,671` có root và reserved assistant | Giữ cấu trúc; thêm composite FK để parent/selected child cùng session và selected child là con trực tiếp. DB bảo vệ invariant nhưng thêm ràng buộc thứ tự ghi/commit; không kết luận application Onyx thiếu validation |
| Quyền hội thoại | `db/chat.py:41` có owner filter, shared path và trường hợp caller truyền user_id=None; `server/query_and_chat/chat_backend.py:398` hỗ trợ nhận session chưa có chủ | Dùng Actor/Tenant/membership IAM hiện có; Phase 2.1 chỉ private, gán chủ khi tạo. Sharing ở Phase 5, quyền admin đọc nội dung chưa được cấp mặc nhiên. Private-only không phải thiết kế cuối hoặc tuyên bố tốt hơn Onyx |
| Retry cùng request | Chưa tìm thấy contract clientRequestId tương đương trong chat/server/db paths đã đọc | Unique request ID và liên kết assistant gốc bảo vệ retry sau mất response. Phải giữ ID khi frontend retry và mở rộng command equality khi có overrides; chưa chứng minh executor không gọi model hai lần |
| Một active reply | `chat/chat_processing_checker.py:24` lưu processing marker/run ID trong cache với TTL; marker này không tự chứng minh atomic admission | Khóa session và unique RUNNING trong DB; thêm contention và trách nhiệm đóng stale RUNNING khi process chết ở Phase 2.2 |
| Terminal/partial | `chat/process_message.py:1205,1225` dùng lock/cờ persisted trong process để phân xử outcome và lưu partial khi Stop | Conditional DB update bảo vệ terminal đã lưu; phải nối cancellation/provider/resource lifecycle ở Phase 2.2. Giữ partial là hành vi bám Onyx; chưa có Stop sản phẩm |

User message lưu `original_assistant_message_id` khi reserve cặp message, cùng transaction với `client_request_id`. Retry dùng liên kết này, không dùng `latest_child_message_id` vì chọn nhánh có thể đổi con trỏ. Không có đường application cập nhật liên kết gốc; composite FK bảo đảm đích là con trực tiếp cùng session. Đây là dữ liệu nội bộ phục vụ request identity, không thêm trường vào history API hay bảng ChatRun.

### Từ message sản phẩm tới model và terminal outcome

1. Persist USER U1 và placeholder ASSISTANT A1; trả identity cho client. `runId` chỉ là tên correlation của A1, không là user identity hoặc provider response ID.
2. Resolve Persona/settings và selected history vào `ChatTurnSetup`. Chuyển nội dung sang native Embabel messages; bỏ root kỹ thuật và placeholder A1 khỏi model input. `.user(text)` của ChatClient chỉ xây user prompt; nó không cấp message ID hay lưu transcript. Ưu tiên public Embabel streaming và SpringAiLlmService; custom SPI bridge Phase 1 là bằng chứng feasibility, chưa là lựa chọn mặc định cho product.
3. Một lượt có thể gọi nhiều inference khi tools được thêm sau này; mỗi inference có provider metadata riêng nhưng vẫn cập nhật cùng assistant message. Browser disconnect chỉ đóng reader, không cancel lượt.
4. Chuyển trạng thái assistant `RUNNING → COMPLETED | CANCELED | FAILED` bằng một conditional transition. Ghi content/partial outcome và metadata trong cùng terminal transaction; terminal SSE chỉ công bố sau khi commit thành công. Quy tắc này dành cho outcome, không yêu cầu commit mỗi delta.
5. Stop là yêu cầu vào đúng A1, được kiểm quyền và idempotent; truyền trực tiếp tới active execution trong cùng process. Phản hồi đã nhận Stop không đồng nghĩa subscription/tool đã dừng. Execution ngừng trước inference/tool tiếp theo, lưu partial outcome rồi xác nhận CANCELED. Complete/Stop tranh chấp chỉ có một terminal winner; request muộn không đổi terminal hoặc ghi late text. Nếu process đã restart, đọc outcome/deadline để xử lý run gián đoạn; không giả định có subscription để cancel.
6. Mỗi lượt có deadline hữu hạn lưu cùng trạng thái execution của assistant message. API process chết không tự resume model; stale RUNNING phải được kết thúc có điều kiện trong thời gian hữu hạn theo deadline, giữ phần DB thực sự đã có. Không kết luận chết chỉ vì mất buffer/reader; execution cũ không được ghi đè terminal khi quay lại. Local cancellation và xử lý deadline phải có integration tests ở Phase 2; không mở rộng thành queue/lease platform hoặc tự retry tools.

Provider lỗi/timeout hoặc EOF thiếu finish metadata hợp lệ tạo FAILED và giữ partial answer. Nếu DB không commit được outcome thì không phát completed giả; phải quan sát được lỗi và đối soát state còn RUNNING theo deadline. Usage chưa nhận được là unknown, không chuyển thành zero. Graceful shutdown ngừng nhận lượt mới và drain/cancel có giới hạn; không hứa giữ mọi token sau process death.

### HTTP/SSE cho consumer Phase 2

Create/list/get session, history, send và cancel đã có trong OpenAPI. Events/SSE thuộc Phase 2.3. DTO/status được kiểm bằng integration tests và generate từ controllers. Reload lấy ID/status assistant cuối nhánh từ history; chưa cần thêm current_run vào session DTO.

| HTTP | Consumer và kết quả |
| --- | --- |
| `POST /api/chat/sessions` | Tạo session riêng với Persona mặc định hợp lệ; trả session/root identity |
| `GET /api/chat/sessions` | Sidebar: list có pagination và giới hạn, chỉ sessions Actor được đọc |
| `GET /api/chat/sessions/{sessionId}` | Metadata session và identity/trạng thái lượt đang chạy nếu có |
| `GET /api/chat/sessions/{sessionId}/messages` | History của nhánh được chọn, có giới hạn/cursor và parent/latest-child identity; trả cả partial/status đã lưu |
| `POST /api/chat/sessions/{sessionId}/messages` | Nhận text, parentMessageId, clientRequestId; trả userMessageId và assistantMessageId/runId với HTTP 202. Đọc history để biết outcome; model overrides cùng consumer settings ở Phase 5 |
| `GET /api/chat/sessions/{sessionId}/messages/{assistantMessageId}/events` | Live/replay SSE cho đúng assistant message; kiểm session/message ownership trước subscribe |
| `POST /api/chat/sessions/{sessionId}/messages/{assistantMessageId}/cancel` | Stop idempotent; trả trạng thái hoặc xác nhận yêu cầu đang chờ xử lý, UI theo dõi outcome thật |

- Mọi route dùng IAM/session hiện có; mutations dùng CSRF theo security contract. Actor/Tenant không tin từ request body. ID trái quyền không lộ nội dung hoặc khả năng stream/cancel; lỗi dùng stable Chat codes theo conventions.
- SSE text delta và terminal outcome mang assistant-message identity cùng event sequence tăng dần. Client tiếp tục từ event ID cuối đã áp dụng (`Last-Event-ID` hoặc cursor query tương đương); duplicate bị bỏ. Heartbeat không tăng content sequence. Tool/citation events được thêm cùng consumer ở Phase 3/4.
- `StreamBufferWriter` gom output thành bounded chunks; `readChunks(...)` trả blocks/next chunk cursor/done/gap. Event IDs được giữ trong payload để lọc event đã nhận khi một chunk chứa cả phần cũ và mới; không dùng chunk index thay event sequence. Handoff replay sang live phải không mất/nhân đôi event.
- Buffer RAM có TTL/byte limits mỗi lượt và tổng process, bounded reads; không chặn live delivery vô hạn. Khi reader chậm vượt giới hạn thì đóng subscription có khả năng reconnect; writer vẫn chạy. `done` của buffer không cho phép bỏ các chunks chưa đọc.
- Buffer missing/gap trả tín hiệu tải lại history; không nối các đoạn thiếu. Khi DB vẫn báo RUNNING, UI tiếp tục theo dõi trạng thái qua read API có giới hạn rồi hydrate outcome, không hiển thị completed hoặc tự gọi model lại. Restart/expiry không bảo đảm tiếp tục live sau reconnect thiếu prefix; replay qua replica khác ngoài phạm vi.
- Proxy giữ SSE không buffering; cấu hình heartbeat/timeouts, disconnect cleanup và same-origin credentials được kiểm xuyên Nginx. UI giữ stable assistant ID qua stream, Stop, reload và fallback.

### Contract triển khai 2.3 đã chốt

Provider baseline và handoff MEM-77 theo [provider/model architecture](provider-model-architecture.md); không chỉ dời converter. Runtime đã triển khai theo contract dưới đây; xem evidence riêng của Phase 2.3 trong [verification](verification.md).

**Local lifecycle:** send reservation/registration và cancel của cùng session phải serialize qua một critical section ngắn; dùng bounded striped locks thay lock map tăng theo số session. Không giữ lock/DB transaction trong model call hoặc network SSE delivery. Chống race: retry đã thấy assistant row commit nhưng original send chưa đăng ký local Active, rồi Stop bị bỏ qua. Stop sau kiểm quyền tìm đúng local Active, chọn stop reason/outcome trong cùng cơ chế phân xử với complete/fail; Stop đã được chấp nhận trước terminal decision thắng completion. Sau terminal decision, Stop không thay outcome. Không còn ghi/poll cancel_requested_at; migration xử lý column theo chính sách applied migrations, không sửa V19 checksum trên retained DB.

DB finalization vẫn conditional; khi deadline reconciliation đã thắng, trả outcome thực lưu trong DB, không phát local COMPLETED đã thua. DB outage giữ immutable pending outcome và admission slot để retry, không gọi lại model; chỉ phát terminal sau commit/read authoritative outcome. Local deadlines, periodic stale-run reconciliation và bounded shutdown giữ nguyên; bỏ per-run DB control polling. Process restart không có Active cho run cũ: trả saved status và chờ deadline reconciliation, không báo cancel thành công giả hoặc tự resume model.

**Wire protocol 2.3:** SSE `text-delta` có `{assistantMessageId, sequence, text}`; `outcome` có `{assistantMessageId, sequence, status, failureCode}` và trỏ tới persisted history cho nội dung/usage đầy đủ. SSE `id` là `<assistantMessageId>:<sequence>`; sequence bắt đầu từ 1, monotonic theo run, giữ nguyên khi replay. Client reconnect gửi `Last-Event-ID` hoặc query `after` với cùng dạng cursor; nếu cả hai có thì phải bằng nhau. Sai format, ID thuộc run khác hoặc cursor vượt sequence hiện tại trả validation error; authorization luôn được kiểm trước khi trả buffer/state. Không trộn event ID với chunk cursor nội bộ.

`reset` có `{assistantMessageId, reason}` với reason `BUFFER_MISSING`, `BUFFER_GAP` hoặc `BUFFER_EXPIRED`, không có SSE id/content sequence. Client đọc history theo ID rồi theo dõi persisted RUNNING bằng bounded polling/retry; reset không gọi model hoặc coi answer completed. Nếu buffer mất kể cả DB đã terminal, dùng reset/history, không tạo sequence giả. Heartbeat là SSE comment, không tăng sequence. Không expose tool/citation events rỗng; thêm typed variants khi Phase 3/4 có consumer.

**Replay/live:** buffer giữ bounded chunks và event sequences. Khi subscribe, dưới cùng per-run synchronization: kiểm earliest/latest cursor, chụp replay prefix tới high-water mark và đăng ký live reader sau mark đó. Gửi replay trước rồi drain live events mới hơn mark; không gọi network dưới lock. Nếu replay prefix đã mất hoặc reader queue overflow trước handoff, kết thúc subscription/reset phù hợp, không nối thiếu prefix. Writer tiếp tục độc lập; `done` không bỏ unread chunks. Flush pending chunk trước outcome; outcome chỉ enqueue sau DB commit.

**Giới hạn cấu hình ban đầu:** buffer 4 MiB/run, 64 MiB/process, TTL chunks 10 phút; chunk flush tối đa 16 KiB hoặc 25 ms; event payload vượt byte cap được chia ở ranh giới Unicode hợp lệ. Reader queue 128 KiB, tối đa 4 readers/run và 64/process; read batch tối đa 256 KiB. Heartbeat 15 giây, proxy read timeout lớn hơn heartbeat, SSE connection timeout hữu hạn. Đây là defaults để đo trong integration tests, không phải số liệu capacity đã benchmark. Validate các giới hạn dương và quan hệ hợp lệ; eviction/expiry vẫn giữ live execution. Replay storage, reader queues và partial-answer buffer đều phải bounded, không chỉ ring buffer. Slow reader bị đóng subscription để reconnect; provider writer không chờ socket.

**HTTP delivery:** controller trả `ResponseEntity<Flux<ServerSentEvent<Object>>>` như cách compose HTTP của OrgMemory (`AssistantController.chat`, `UiMessageStream`). Spring MVC quản lý SSE writes/subscription trên application executor; `ChatEventStream` chuyển bounded reader thành Flux trên virtual-thread scheduler, prefetch một batch và đóng reader qua `Flux.using`. Không tự quản lý SseEmitter/FutureTask/send loop. MVC vẫn blocking khi ghi socket; đây là lựa chọn giảm code lifecycle, không phải chứng minh hiệu năng hơn hoặc đổi sang WebFlux. Disconnect/timeout chỉ đóng reader; native execution chạy độc lập theo baseline Onyx đã chốt. Không nối thẳng model Flux vào HTTP như đường OrgMemory đã đọc. Browser-session/bearer auth, existing IAM và CSRF mutation conventions được giữ.

Implementation giới hạn replay bằng cách dành trước budget mỗi slot: tối đa `min(maxStreams, totalBytes / runBytes)`, mặc định 16 buffers. Đơn giản hóa accounting và bảo đảm upper bound, đổi lại retention theo số lượt thấp hơn dynamic allocation cho nhiều câu trả lời ngắn. Mỗi event tính UTF-8 payload cộng overhead cố định; không coi con số đó là số byte JVM heap hay JSON wire. Batch thực dùng `min(readBytes, readerBytes)`, mặc định 128 KiB. Reader theo cursor và high-water trong cùng retained deque, không copy toàn bộ prefix hoặc tạo queue không giới hạn.

**Exit tests:** Stop trong reservation/registration gap; cancel/complete/fail race; stale DB terminal thắng local outcome; DB outage không terminal giả; concurrent subscribe với flush/finalize; reconnect không missing/duplicate; expiry/eviction/future cursor; reader overflow và cleanup; unauthorized Actor/Tenant; restart/history fallback; native provider thật qua HTTP/proxy. Generated OpenAPI/client phản ánh route/protocol thực; UI sản phẩm thuộc 2.4. QA/raw receipts ở ignored scratch.

### Vị trí triển khai

Đây là boundary trách nhiệm, không phải yêu cầu tạo một lớp riêng cho mỗi dòng. Không chốt custom executor/streamer trước khi kiểm API cấp cao và điểm mở rộng framework dưới đây.

| Thư mục | Phần việc Phase 2 |
| --- | --- |
| `core/src/main/java/io/memoryos/chat/` | Narrow public contracts/glossary types và module boundary; `application/` cho ChatSession/turn operations, `execution/` cho setup/native model bridge, `streaming/` cho buffer, `persistence/` cho SQL |
| `core/src/main/resources/db/migration/` | Migration additive Chat; không chỉnh checksum migrations đã áp dụng |
| `api/src/main/java/io/memoryos/api/chat/` | ChatSession/turn controllers, `contract/`, runtime configuration/properties và executor wiring |
| `api/src/main/resources/application*.yaml` | Config được validate cho runtime thật; credential lấy từ cơ chế hiện có |
| `web/src/routes/_authenticated.chat*.tsx` | Chat routes trong session boundary hiện có |
| `web/src/features/chat/` | Sidebar/thread/composer, runtime adapter, stream/reconnect và focused tests |
| `openapi.yml`, `web/src/lib/hey-api/` | Generated contract/client; không sửa generated files thủ công |
| `core/src/test/java/io/memoryos/chat/`, `api/src/test/java/io/memoryos/api/chat/`, `web/tests/e2e/chat.spec.ts` | DB/contract/HTTP/browser acceptance của runtime sản phẩm |

Tên lớp bám glossary: ChatSessionService/ChatTurnService khi có use case, không dùng Conversation như entity thứ hai; StreamBufferWriter giữ nghĩa Onyx. Chỉ tạo lớp/package có caller thực. Thư mục worker hiện có tiếp tục thuộc ingestion; không thêm deployable hoặc framework abstraction chung.

## Framework, tools và cấu hình

Ưu tiên Embabel `ModelProvider`, `ModelSelectionCriteria`, `LlmOptions`, `Ai/PromptRunner`, native `Tool`/`ToolCallContext` và Spring AI `ChatModel`, `Prompt`, `Message`, `ChatOptions`, `ChatResponse`. Resolve model roles cho lượt vào setup trong bộ nhớ. Không tự tạo framework LlmClient/ToolDefinition; existing tool adapters được ưu tiên. [Reuse map](onyx-config-and-framework-reuse.md) ghi gap và giới hạn kiểm chứng.

Candidate đã probe: Embabel 1.5.1, Spring AI 2.0.1, Boot BOM 4.1.1, assistant-ui 0.15.18, React 19.2.8. `StreamingToolLoop` qua supported SPI `LlmMessageStreamer` là đường đã thử, không chứng minh custom streamer cần thiết. [Phase 1 verification](phase-1-verification.md) ghi bằng chứng boundary cô lập; OpenAI Chat Completions/gpt-5-mini là baseline đầu tiên. Không suy rộng sang public high-level entry point hoặc product IAM/DB/Retrieval acceptance.

### Framework reuse audit

Rà soát 2026-09-09: kế hoạch trước đi từ custom SPI probe tới danh sách lớp tự viết mà chưa kiểm public entry point tương ứng. [Probe bổ sung](framework-entrypoint-verification.md) đã đóng khoảng trống này trước implementation execution: chọn public PromptRunner và native streamer/loop, chỉ thêm public-interface composition cho gap đã tái hiện. Không chọn theo tên hoặc giữ code chỉ vì đã viết.

| Phần | Embabel 1.5.1 đã cung cấp | Hướng chọn và giới hạn |
| --- | --- | --- |
| Model/settings | ModelProvider, LlmService, LlmOptions, PromptRunner.withLlmService | Dùng native, giữ model đã resolve cho lượt. ChatTurnSetup chỉ gom context sản phẩm, không dựng model registry/options riêng |
| Streaming/loop | StreamingPromptRunnerBuilder, withMessages, generateStream; StreamingLlmOperationsImpl tự dựng DefaultStreamingToolLoop | Public PromptRunner streaming đã được chọn và kiểm; không tự tạo loop/streamer để có text/tools |
| Spring AI bridge | SpringAiLlmService.createMessageStreamer và SpringAiLlmMessageStreamer | Giữ native streamer; public LlmService delegate và ChatModel decorator xử lý các gap đã kiểm, vẫn giữ high-level caller |
| Conversation/session | Conversation, ConversationFactory, Chatbot, AgentProcessChatbot, OutputChannel | Factory nối được vào native Chatbot trong probe. Chọn theo consumers/lifecycle của toàn scope; không đánh giá bằng khả năng thay JDBC. Branch/reservation/terminal mapping vẫn cần adapter |
| Conversation store | Tài liệu bundled mô tả embabel-chat-store: StoredConversationFactory dùng Neo4j, StoredConversation async persistence | Không gọi là thiếu abstraction. Chưa kiểm store tương đương PostgreSQL/JDBC, cây nhánh, request identity, atomic terminal. Không thêm Neo4j/transcript thứ hai chỉ để dùng store |
| Persistence/ownership hiện có | Framework conversation/identity/process contracts | Giữ JDBC Phase 2.1: schema/quyền có trách nhiệm sản phẩm thật. Thêm Conversation adapter/factory cùng action/tool/Chatbot cần native conversation lifecycle; withMessages cho turn hiện tại không cần factory |
| Background/Stop | AgentProcess lifecycle/kill, OutputChannel và reactive subscription | Kiểm trước khi tự viết phần tương đương. Kill process không tự chứng minh subscription/provider đã dừng hoặc DB outcome commit; application vẫn cần nối phần admission/quyền/terminal còn thiếu |

Đường inference đã chọn sau probe: application command → native per-turn ExecutingOperationContext/Ai → public PromptRunner streaming → public LlmService delegate → native SpringAiLlmService/streamer/tool loop → public ChatModel composition → Spring AI provider. Đây là lựa chọn inference, không phải chứng nhận toàn bộ lifecycle Chat. LlmService delegate khai báo capability đã kiểm chứng. Composition nối stream metadata/usage vào accounting native và áp dụng policy tại boundary còn thiếu; không tạo CostCalculator/BudgetEngine riêng. Native tool callbacks/message conversion được giữ nguyên. Không cần custom LlmMessageStreamer cho baseline OpenAI đã kiểm. Quyết định conversation/workflow và cách mở rộng xuyên sản phẩm nằm ở mục dưới.

Native streaming bỏ qua ToolLoopInspector/Transformer, chấp nhận một số EOF thiếu finish reason, và public runner có cap mặc định 20 không trả final tools-off. Extension đã kiểm cap trong trần đó, với baseline sáu cycles; không coi giới hạn tùy ý hoặc provider khác đã được chứng nhận. Vòng cuối OpenAI bỏ cả tools và tool_choice. Mỗi lượt giữ context/guard state riêng, cleanup subscription và native process reference; provider HTTP client được quản lý ở composition root. Các yêu cầu này phải được chuyển thành tests của product runtime ở 2.2.

Nếu native implementation thiếu contract: dùng configuration/hook trước; tiếp theo decorate public Spring AI ChatModel hoặc implement public Embabel LlmService/LlmMessageStreamer, delegate phần đã có. Đặt extension tại tầng nơi dữ liệu còn tồn tại; wrapper đặt sau khi usage/metadata bị mất không thể tự khôi phục chúng. Không tạo interface LlmClient/AgentEngine riêng hoặc copy tool loop. SpringAiLlmService ở 1.5.1 là Kotlin data class và SpringAiLlmMessageStreamer là internal; không lên kế hoạch subclass hai implementation đó. Mỗi extension phải có repro gap, regression qua high-level caller và giới hạn provider/version rõ ràng.

Source checkout Embabel v1.5.1 SHA `bd25d07d2c34e4d6f06dea54a39732ae1bdcfb52`:

- `embabel-agent-api/src/main/kotlin/com/embabel/agent/api/common/streaming/StreamingPromptRunner.kt`: withMessages/generateStream.
- `embabel-agent-api/src/main/kotlin/com/embabel/agent/spi/support/streaming/StreamingLlmOperationsImpl.kt:291`: tự dựng loop, truyền maxIterations/toolCallInspectors/toolCallContext; phải kiểm propagation từng hook thay vì suy từ sự tồn tại của API.
- `embabel-agent-api/src/main/kotlin/com/embabel/agent/spi/LlmService.kt` và `spi/support/springai/SpringAiLlmService.kt:99`: public factory/native streamer.
- `embabel-agent-api/src/main/kotlin/com/embabel/chat/Conversation.kt`, `ConversationFactory.kt`, `ChatSession.kt`, `agent/AgentProcessChatbot.kt`: chronological messages/factory/session/process ownership.
- `embabel-agent-docs/src/main/asciidoc/reference/chatbots/page.adoc:671`: store Neo4j/async theo tài liệu; chưa phải runtime verification của embabel-chat-store.

`Persona` cung cấp instructions/starter prompts, sources, enabled tools và model settings theo quyền sản phẩm. Assistant là cách gọi trên UI/tài liệu sản phẩm; tên model nghiệp vụ là `Persona`, không thêm entity `Assistant` hoặc đổi thành `UserPersona`. Model capabilities, retrieval limits, context window, tool timeouts, max cycles và run deadline phải có cấu hình được validate. Quyền/hạn mức deployment và Tenant là trần; override chỉ chọn trong phần được phép. Không lưu secrets vào messages/context metadata và không tạo một schema/config framework tổng quát.

Hai tool đầu: `searchKnowledge` và `readDocumentSection`, dùng server-side Actor/Tenant context ngoài model arguments. Reuse public Retrieval, không import persistence internals, không thêm index thứ hai. Search giữ baseline Onyx: query rewrite/multi-query → bounded retrieval → weighted RRF → section merge/selection → validated IDs và bounded neighbor read. Baseline `k=50`, weights semantic 1.3, keyword 1.0, non-custom 0.7, original 0.5 cần đối chiếu trên corpus thật; không sao chép Vespa semantics sang OpenSearch bằng tên tham số.

Một outer loop; kiểm policy đúng loại trước inference và tools, thêm tool results vào model context rồi lặp. Baseline sáu cycles và vòng cuối tắt tools để trả lời; iteration-limit exception không tự đáp ứng behavior đó. Không thêm một evidence-assessor inference bắt buộc sau mỗi vòng. Context window, cycle cap, native cost/token budget, deadline và concurrency là các giới hạn khác nhau theo mục sau.

### End-to-end framework integration

Audit 2026-09-09 xét toàn bộ parity lần giao đầu và đường mở rộng sau; [verification bổ sung](framework-entrypoint-verification.md#end-to-end-audit--2026-09-09) gồm native accounting/budget và factory/Chatbot wiring. JDBC là persistence mechanism, không phải lý do bác bỏ framework adapter. PromptRunner là public inference entry point, không tự quyết định ai sở hữu conversation lifecycle.

| Use case end-to-end | Reuse và lựa chọn hiện tại | Extension và điều kiện nghiệm thu |
| --- | --- | --- |
| Send/follow-up/history | Selected branch → native Messages → per-turn Embabel context/PromptRunner; JDBC transcript là authoritative | Actor/Tenant, request identity và reserved assistant ID đi xuyên send/stream/DB/UI; root/placeholder bị loại |
| Edit/regenerate/branch | Cùng inference entry point, context mới từ nhánh được chọn | Branch A → B không mang history/assets/blackboard A sang B; request retry không append lại USER hoặc đổi reserved reply |
| Persona/Projects/attachments | Native prompt/messages/options; dùng Conversation/AssetView khi một action/tool thực sự tiêu thụ chúng | Precedence, ownership/readiness, settings chỉ đổi lượt sau; hydrated asset reference không tự cấp quyền đọc nguồn |
| Retrieval/typed selection | Public Retrieval và native Tool/PromptRunner typed operations | Query/selection/answer cùng phạm vi accounting của lượt; typed calls native đã record không được tính trùng; IDs/citations do MemoryOS validate |
| Stop/replay/reload | Local subscription và native callbacks; PostgreSQL outcome + buffer RAM trong một API process | Cancel bảo vệ cả tool boundary; reconnect không tạo execution mới; terminal UI sau commit; process ID framework không thay runId |
| Sharing | Current caller authorization trước history/replay/send; framework identity được map ở execution | Reader không kế thừa quyền executor/owner; source reader kiểm ACL hiện tại; không dùng global factory load(id) như đường bỏ qua quyền |
| Observability/cost | Native Usage, LlmInvocation, PricingModel, process totals và native events khi cần | Ghi nhận một lần mỗi inference; đủ model/run correlation và trạng thái unknown; không ghi prompt/secret. recordLlmInvocation không mặc nhiên phát listener event |
| Web search sau này | Thêm native Tool, provider client, evidence mapping và UI renderer trên cùng execution/stream contract | Auth/timeouts/cost của provider và citations có tests; không cần thay transcript hoặc inference engine, nhưng vẫn cần code tích hợp |
| Deep research sau này | Ưu tiên native agent/actions/process/ConversationFactory cho workflow có state/triggers | Chọn lifetime theo research operation/branch; checkpoints, background ownership, cancel và UI progress cần acceptance riêng. Không hứa factory tự giải quyết resume hoặc không cần migration |

**Conversation/Factory decision.** Giữ per-turn execution với native messages cho toàn bộ Chat parity hiện tại. Không thêm long-lived AgentProcessChatbot chỉ để giữ history. Factory là option tích hợp được hỗ trợ, không bị loại khỏi kiến trúc: native Chatbot đã load factory và dùng Conversation trong probe. Thêm factory cùng consumer đầu tiên cần native Conversation/assets/triggered actions, thay vì tạo một interface chưa ai gọi. PromptRunner vẫn có thể được dùng bên trong Chatbot/action; hai API không loại trừ nhau.

Lợi ích của factory/Chatbot: common conversation/assets API và framework-managed blackboard/actions/triggers, đặc biệt khi workflow sống qua nhiều tương tác. Tradeoff: phải map authorized selected branch, stable message IDs và terminal writes; native onUserMessage append có thể trùng reservation, saveAndSend gửi sau addMessage chứ không hiểu transaction MemoryOS, conversation/process cũ không tự refresh khi external branch đổi. Per-turn mapping ít state cần đồng bộ hơn cho edit/regenerate và settings-per-turn. Đây là đánh giá phù hợp của toàn scope, không kết luận factory không có giá trị vì JDBC còn tồn tại.

Nếu consumer đó được triển khai: factory/load phải bound caller/Tenant và branch/run context rõ ràng; create/load gọi public Chat application contract, SQL giữ ở persistence; Conversation adapter ánh xạ selected chain và mutations vào reservation/finalization hiện có. Không append user hai lần, không thêm assistant ID ngoài reserved ID, không coi intermediate tool messages là final transcript. Khi branch/settings đổi phải reload hoặc tạo process context phù hợp; không lặng lẽ tái sử dụng blackboard cũ. Factory có thể dùng chính JDBC/PostgreSQL; Neo4j store trong tài liệu framework không là yêu cầu.

**Accounting và từng loại giới hạn.** Native streaming v1.5.1 đã được probe có usage nhưng process invocation history vẫn trống. Public ChatModel composition capture usage trước aggregation, chuyển thành native Usage/LlmInvocation và gọi recordLlmInvocation; native PricingModel/process thực hiện tính/cộng cost. Áp dụng native Budget/EarlyTerminationPolicy tại inference và tool boundary khi cấu hình hạn mức đó; không tự viết thuật toán budget tương đương. Typed/synchronous paths đã accounting native thì không record lần hai. Query/selection calls trong tools phải chung accounting scope; đây là product regression bắt buộc khi nối Phase 3.

| Giới hạn | Ý nghĩa / owner | Không được đánh đồng |
| --- | --- | --- |
| Context window | Chọn/truncate model input trong token capacity; native tokenizer/options, Onyx history behavior | Tổng token đã chi tiêu nhiều inference |
| Output token cap | Native provider/model options giới hạn một response | Cost cap cả lượt |
| Cycle cap | Sáu inference cycles, final tools-off qua request policy; native loop vẫn sở hữu continuation | Budget.actions hoặc vòng lặp riêng của MemoryOS |
| Token/cost cap của lượt | Native Budget/policy trên native recorded invocations; kiểm trước inference/tool | Hạn mức tuyệt đối không bao giờ vượt: một call đang chạy vẫn có thể vượt cap trước khi usage được biết |
| Deadline/Stop | Runtime cancellation và guard tại inference/tool, conditional DB outcome | Cost policy hoặc xóa framework process reference |
| Concurrency | Bounded background admission của API process | Budget.actions hay provider rate limit |
| Hạn mức theo user/Tenant/time window | Onyx có persistent usage limits; cần scope/admin/storage riêng nếu triển khai parity quản trị này | Native per-process Budget; không tự thêm billing dashboard hoặc window ledger vào MEM-11 |

Native process cost trả 0 khi không có pricing; product phải giữ pricing/usage-unknown, không báo đó là miễn phí hoặc budget được bảo đảm. Cached-token pricing/admin overrides của Onyx vượt đường input/output cơ bản của LlmInvocation.cost() đã đọc; giữ native usage metadata để mở rộng pricing khi có consumer, không claim pricing parity toàn bộ. Khi money/token cap đã hết, dừng với partial/error theo outcome contract; không gọi thêm model chỉ để ép có final answer. Vòng cuối tools-off chỉ áp dụng khi còn được phép inference.

EOF/finish validation và last-cycle request policy là extension có repro; usage accounting reuse native types/calculations; Stop/quyền thuộc application/tool boundary. Không bắt buộc gom tất cả vào một class decorator. Tên/class count được quyết định khi port consumer thật, không predeclare một engine hay policy registry mới.

Extensibility dùng native model/tool APIs, typed outputs và renderer cho consumer thật. Web tool mới vẫn cần provider/auth/rendering; deep research có thể cần workflow/checkpoints riêng khi triển khai. Không predeclare execution strategies, plugin registries hoặc parent/subrun schemas chỉ để chuẩn bị tương lai.

## History, nguồn và trải nghiệm

DB sở hữu message tree; edit tạo nhánh, regenerate dưới user message đã chọn. Theo semantics selected/latest branch của Onyx, không thêm per-Actor view table mặc định. Model context lấy selected history; compaction nếu cần không thay full transcript hoặc tạo conversation store thứ hai.

Quyền đọc conversation áp dụng cả history và reconnect. Quyền search/source/file áp dụng cho lần đọc mới; chia sẻ conversation không cấp quyền mở nguồn. Người được đọc transcript có thể đọc nội dung đã được lưu trong đó, kể cả khi sau đó mất quyền nguồn. Đây là hệ quả cần nghiệm thu cùng chia sẻ, không phải cam kết thu hồi mọi bản sao nội dung. Reindex, cập nhật nội dung, xóa nguồn và revoke quyền phải có scenarios riêng; generation đổi không là lý do ẩn lịch sử.

Attachments reuse ObjectStorage và ingestion phù hợp, có ownership, readiness và retention thật; private attachment không tự thành Source chung. Projects/custom assistant theo precedence đã chốt. Chia sẻ có xác thực, read-only cho reader trong phạm vi hiện tại; owner quản lý cuộc hội thoại. Anonymous public sharing và các chức năng khác ngoài bảng parity chưa thuộc lần giao này.

Sharing là khác biệt có chủ đích: đường Onyx đã đọc cho phép PUBLIC link anonymous (`chat_backend.py:355-390`); MemoryOS giữ authenticated/Tenant boundary theo phạm vi được chọn. Lợi ích là xác định được người đọc; tradeoff là không có UX gửi link công khai. Grant model cụ thể phải chốt trước Phase 5, không tự suy thành Group grants. Edit/regenerate cũng cần migration và assistant-only reservation riêng; schema send-at-tip hiện tại không được xem là contract đủ cho các thao tác đó.

## Bài học của lần thiết kế này

Bản nháp trước đã suy từ yêu cầu production sang các cơ chế cụ thể như lưu effective config, worker dispatch/lease, event journal và dependency ACL hồi tố mà chưa chứng minh cần chúng để đáp ứng phạm vi đã chọn. Sai ở bước suy luận và đánh giá tradeoff; yêu cầu production vẫn giữ nguyên. Những cơ chế đó kéo theo nhau, làm phạm vi phình ra và thay đổi semantics Onyx. Quyết định sửa là giữ baseline ở bảng trên và làm đầy đủ hardening cần thiết; mọi khác biệt sau này phải qua tradeoff ngắn theo conventions. Probe framework không chứng minh các phần bổ sung ấy cần thiết hoặc tốt hơn.
