# MEM-11 — Kiểm chứng Phase 1

Ngày kiểm chứng: 2026-09-09. **Phase 1 hoàn tất ở boundary kiểm chứng framework/transport; sẵn sàng triển khai phase 2.** Các kết quả dưới đây là feasibility của framework/transport trong harness cô lập; chưa có product Chat runtime. [Plan](plan.md) ghi ranh giới với các phase triển khai. QA, source probe và raw receipts ở ignored `.tmp/mem11-phase1/`.

Làm rõ sau framework audit 2026-09-09: kết quả custom LlmMessageStreamer/StreamingToolLoop không chứng minh public PromptRunner/Chatbot path đã qua cùng contracts hoặc custom bridge là cần thiết. [Probe bổ sung đã hoàn tất](framework-entrypoint-verification.md): chốt public PromptRunner với native streamer/loop, public LlmService delegate và ChatModel composition cho gap đã kiểm. Bằng chứng probe bên dưới giữ nguyên phạm vi; không dùng nó thay thế bằng chứng mới hoặc product acceptance.

## Dependency và model baseline cho triển khai

| Thành phần | Lựa chọn đã resolve/compile/chạy |
| --- | --- |
| JVM | JDK 25, Boot BOM 4.1.1, Spring AI 2.0.1 theo repo hiện có |
| Embabel | `embabel-agent-api:1.5.1`, source tag v1.5.1 tại `bd25d07d2c34e4d6f06dea54a39732ae1bdcfb52` |
| Browser | React 19.2.8, assistant-ui 0.15.18; ExternalStoreRuntime |
| Model/provider | OpenAI Chat Completions, `gpt-5-mini`; response model quan sát được `gpt-5-mini-2025-08-07` |
| Transport harness | Spring MVC/Security session + CSRF, Nginx từ image MemoryOS Web local, Redis 8.8 |

Dùng model/provider này làm baseline tích hợp đầu tiên, với model name/options cấu hình được và credential chỉ ở server. Probe dùng reasoning effort minimal, output cap 700, timeout 45 giây, retry 0 và stream usage; đó là giới hạn kiểm chứng, không phải tuning production đã nghiệm thu. Answer/query/selection roles resolve vào setup trong bộ nhớ qua native ModelProvider. Danh sách model được phép và validation settings thuộc write path sản phẩm ở phase 2/5; không coi model khác đã được hỗ trợ chỉ vì chung API.

Đã đọc lại [MEM-66](https://linear.app/memory-os/issue/MEM-66/nghien-cuu-ai-gateway-va-vllm-cpu-voi-model-nho): đây là nghiên cứu gateway/vLLM CPU với model nhỏ, chưa xác nhận production. Nó không phải bắt buộc để dùng provider hiện có cho Chat; không kéo việc xây gateway vào Phase 1. Khi chọn thêm model/provider, kiểm lại capabilities, chất lượng, metadata và cancellation của profile đó.

Dependencies mới chỉ được thêm vào product khi có caller thật ở phase 2. Không import cả community starter bundle hoặc tạo runtime profile/endpoint tạm trong MemoryOS. Source review của [framework reuse](onyx-config-and-framework-reuse.md) vẫn phân biệt native API đã chạy với API mới chỉ được đọc.

## Kết quả kiểm chứng

| Boundary | Kết quả | Điều thực sự chứng minh / giới hạn |
| --- | --- | --- |
| JVM contracts | 16 assertions PASS | Native ModelProvider binding và tool adapter; malformed/invalid candidate arguments; blocked-tool cancellation; real-model last-cycle answer, usage, continuation và JSON schema; cancel trước token đầu |
| Metadata contracts | 6 assertions PASS | Giữ opaque assistant metadata qua Spring AI → Embabel → Spring AI; giữ native usage; tổng usage mỗi inference một lần. Có negative check chứng minh MessageAggregator không ghép raw tool fragments |
| Browser stream | 10 checks PASS | assistant-ui → authenticated Spring MVC/SSE → Nginx → model thật; live tool result; disconnect/reconnect; ordered replay; cache gap/history fallback; late cancel |
| Browser Stop và authorization | 6 checks PASS | Stop qua runtime callback giữ message/partial answer; 8 cancel requests đồng thời cho một run giữ một terminal; thiếu session/CSRF bị chặn; actor khác không đọc/replay/cancel được |
| Cache faults | 2 scenarios PASS | Pause và kill Redis giữa live stream qua Nginx; live tiếp tục đến completed, replay degraded/fallback, bounded cache copy và resource release |
| Provider faults | 6 scenarios PASS | Native Spring AI HTTP client gặp 429/503, 503 sau tool, timeout trước token, EOF và timeout sau partial text; một failed terminal, không retry/lặp tool |
| Browser provider failure | 6 checks PASS | assistant-ui giữ partial text/tool result, incomplete-error, hết running và hydrate giữ identity/outcome |
| Complete/cancel race | 64 races PASS | Gọi finalization thật của scratch Run đồng thời, kiểm một winner, một Redis terminal event, state/event khớp và không nhận late text. Đây chưa là kiểm transaction PostgreSQL sản phẩm |
| Onyx precedence | 7 cases PASS | Chạy AST của chính hàm upstream với synthetic objects: custom assistant thắng Project kể cả rỗng; default assistant dùng Project. Không phải Onyx full application/DB test |
| UI adapter regression | 5/5 Vitest/jsdom PASS | Recorded events, same message identity, branch hydrate, callback capabilities và reducer duplicate/gap |
| Build | Gradle compile/probes PASS; UI TypeScript và Vite build PASS | Isolated build; không phải toàn bộ product Boot autoconfiguration hoặc repository-wide clean check |

Các số trên là assertions/checks ở những boundary khác nhau; không cộng thành phần trăm hoàn tất hoặc mức tương đương Onyx. Probe cũ có thêm 8 assertions về real-model loop/tool extension/local cancel; kết quả đó vẫn là bằng chứng lịch sử, không tính lặp vào bảng mới.

## Các phát hiện phải đưa vào implementation

1. **Một outer loop:** Embabel `StreamingToolLoop.create` sở hữu inference/tool continuation. Spring AI direct `ChatModel.stream` cung cấp từng inference; không bật thêm một auto tool loop. Native `SpringToolCallbackAdapter` truyền được server-side context; bỏ adapter tự viết chỉ sao chép tool schema.
2. **Vòng cuối:** bridge đếm inference của lượt và gửi `tool_choice=none` khi đến cap, vẫn giữ tool definitions phù hợp. Khi không có tools thì bỏ hẳn tool_choice; provider đã trả HTTP 400 khi gửi none mà không có tools. Provider trả tool calls trái policy vòng cuối phải bị chặn trước execution. Probe dùng cap 2 để buộc nhánh cuối; product giữ baseline Onyx 6 cycles có cấu hình.
3. **Tool arguments:** Embabel có thể đưa `Tool.Result.Error` trở lại model, nhưng không tự thực hiện domain allowlist/authorization. Validate JSON và candidate IDs trước read/side effect; lỗi có nội dung an toàn. Hai negative cases không chứng minh ACL Retrieval sản phẩm.
4. **Cancellation:** dispose subscription khi tool đang block chưa đủ. Lượt chạy cần cancel guard trước mọi inference; tool kiểm trước tác dụng phụ và có timeout/cooperative cancellation. Probe ban đầu không có guard đã thất bại; bản có guard ngăn side effect, inference tiếp theo và output. Không hứa hủy được mọi blocking tool hoặc provider ngừng tính phí.
5. **Metadata:** MessageAggregator thu thập text/metadata/tool calls đã được provider chuẩn hóa; nó không ghép JSON fragments theo tool-call ID và làm mất native usage trong aggregated response. Giữ usage gốc từ provider response trước aggregation; giữ assistant metadata khi chuyển message sang Embabel và ngược lại. OpenAI adapter có xử lý tool-call chunks; không tự xây universal assembler cho mọi provider.
6. **Streaming:** execution/writer sống độc lập với SSE reader; endpoint đặt `X-Accel-Buffering: no`, `Cache-Control: no-store`, sequence và heartbeat. Nginx đã truyền text trước terminal. Shared Redis buffer phục vụ replay; gap yêu cầu load outcome đã lưu. Không cần PostgreSQL journal để chứng minh contract này.
7. **Finalization:** cancel và complete tranh một terminal transition; sau terminal không nhận delta mới. Scratch dùng lock trong process. Product phải giữ invariant này với persistence/active-run coordination thực và kiểm lại ở phase 2/4.
8. **Provider failure:** Flux hoàn tất không đủ để kết luận inference thành công. EOF sau partial text nhưng thiếu terminal finish reason từng bị harness đánh dấu completed; bridge đã kiểm finish metadata và chuyển thành failed, giữ partial output. Với baseline Chat Completions, kiểm lỗi/finish reason theo provider; không áp quy tắc wire này cho mọi provider mà chưa kiểm. Không tự retry toàn bộ run hoặc chạy lại tool đã hoàn tất.
9. **Config:** native ModelProvider đổi role map tác động setup sau; current setup giữ concrete binding. Không resolve mutable role alias lại giữa các inference, không cần durable config snapshot.

Một lượt thử model ban đầu không dùng đúng fixture fact dù tool result đã có; prompt rõ về việc dùng returned evidence đã đạt. Đây là lý do không suy từ tool-call success sang groundedness/chất lượng production; corpus evaluation vẫn cần ở phase 6.

## Bổ sung fault probes sau review Phase 1

Review follow-up phát hiện probe transport trước đó vẫn để live reader phụ thuộc Redis. Bản scratch đã tách live delivery khỏi cache copy: reader dùng queue có giới hạn trong process; bản sao replay ghi bất đồng bộ với queue có giới hạn và timeout. Cache copy lỗi thì ngừng cho run đó, không ném lỗi vào writer/live delivery. Đây là chi tiết harness chứng minh baseline đã chọn, không phải một Chat worker/service mới hay tuyên bố cấu trúc nội bộ giống hệt Onyx.

- **Redis pause/kill:** cả hai lần chạy cuối gặp command timeout khoảng 501 ms với cấu hình probe 500 ms. Live reader đã kết nối vẫn nhận sequence liên tục và toàn bộ 187 ký tự đến completed; replay báo gap, REST fallback giữ outcome. Cache queue tối đa quan sát 6/512 entries; sau run, model subscriptions/cache writers/readers đều về 0 và admission trở lại 4/4. Không dùng số này làm production sizing hoặc latency SLO.
- **Provider faults:** fake HTTP provider trên loopback điều khiển lỗi, dùng native Spring AI OpenAI client và Embabel loop thật qua Spring/Nginx. HTTP 429, 503 và timeout trước token đầu chỉ tạo một request, không chạy tool; 503/EOF/timeout sau tool tạo đúng hai inference requests và một tool execution. Cả sáu ca có một failed terminal, không bị late cancel ghi đè, resources được trả lại. Hai ca ngắt partial giữ nguyên 31 ký tự. Retry được cấu hình 0; không suy rộng thành bảo đảm exactly-once cho mọi tool/provider.
- **EOF regression:** lần đầu socket đóng mà thiếu finish reason đã tạo completed sai. Guard terminal metadata sửa được ca đó; browser xác nhận partial answer có trạng thái incomplete/error, không còn running và hydrate giữ nguyên message/tool result. Lỗi EOF không bị che thành câu trả lời hoàn chỉnh.
- **Regression sau sửa:** 16 JVM contracts với model thật, 6 metadata assertions, 64 complete/cancel races, 10 browser stream checks, 6 browser Stop/auth checks và 5 UI tests đều đạt; TypeScript/Vite build đạt.

Fault provider là fixture có chủ đích; các ca lỗi không phải bằng chứng OpenAI production đã phát sinh đúng những lỗi ấy. Cache probe kiểm outage giữa một live connection đang hoạt động; không chứng nhận bootstrap khi Redis đã down, multi-replica hoặc tiếp tục xem live sau reconnect thiếu replay prefix. RAM fallback và giới hạn product bên dưới vẫn giữ nguyên.

## Giới hạn và gate chuyển sang product

- Harness dùng tài khoản Spring Security giả lập và CSRF thật, không phải Keycloak/IAM của MemoryOS. Phải kiểm lại session/Actor/Tenant và nguồn thật trong product.
- Redis là server thật, nhưng transcript/outcome fallback của harness nằm trong RAM. Không tuyên bố DB durability, cross-process recovery, multi-replica production acceptance hoặc phục hồi sau restart đã đạt.
- Tool facts là synthetic; chưa kiểm real Retrieval/index/citations. Source revoke, source update/delete và conversation sharing phải theo design và acceptance riêng.
- OpenAI Chat Completions continuation đã chạy; opaque metadata round-trip là synthetic. Anthropic/Gemini reasoning signatures, multimodal và OpenAI Responses API chưa được chứng nhận. Profile khác cần verification trước khi bật.
- Usage đầy đủ được quan sát khi inference kết thúc bình thường. Run bị hủy trước usage chunk có usage chưa biết; không ghi thành zero hoặc coi là bằng chứng provider đã ngừng billing.
- IntelliJ đã được gọi cho các Java/build files nhưng scratch Gradle project không được import, nên dependencies/JDK bị báo unresolved. Không có kết luận IDE semantic-clean; focused Gradle compile là bằng chứng type compatibility hiện tại.
- Nginx dùng cấu hình repo với upstream/ports đổi sang probe; thêm route login phục vụ fixture. Không chứng minh mọi proxy/lớp mạng staging. Temporary endpoints/config chỉ có ở ignored scratch.

Production là yêu cầu xuyên suốt implementation. Các giới hạn trên thuộc đúng boundary chưa triển khai; không lấy probe thay nghiệm thu MEM-11.

## Tái kiểm tại workspace này

Source và QA không đưa vào Git theo yêu cầu cleanup. Fresh clone không chứa harness; khi implement Chat, chuyển các observable contracts cần giữ thành tests của product, không copy temporary server hoặc fake auth vào application.

```powershell
.\gradlew.bat -p .tmp/mem11-phase1/jvm compileJava --no-daemon --console=plain
infisical run --env=dev --silent -- .\gradlew.bat -p .tmp/mem11-phase1/jvm metadataProbe raceProbe contracts --no-daemon --console=plain
```

Fault probes chạy bằng `py -3 -u .tmp/mem11-phase1/fault_checks.py cache` và `provider`, cần loopback fault provider cùng các dịch vụ scratch; các lệnh này pause/kill đúng Redis container có label của probe.

Race probe cần Redis scratch đang chạy; browser probes cần scratch Spring server, Redis, Nginx và UI build. Raw logs/summaries ghi source paths và kết quả local; repo chỉ giữ kết luận có giới hạn ở đây. Không chạy repository-wide clean check vì không sửa runtime/dependencies sản phẩm trong phase nghiên cứu này.
