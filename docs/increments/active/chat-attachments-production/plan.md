# MEM-81 — Kế hoạch attachments production

## CI, merge và staging được duyệt — 2026-09-12

Yêu cầu tiếp theo đã mở quyền sửa/tối ưu CI, merge PR #99 khi đủ gate và triển khai đúng release main lên staging. Quyền này thay thế giới hạn chỉ publication bên dưới; không bao gồm sửa OCR checkout hoặc cập nhật Linear.

1. Sửa hai fixture MinIO bị Docker Hub từ chối pull: chuyển sang Quay chính thức với cùng release/digest; đồng bộ Compose default và env example, không nâng MinIO.
2. Chia browser CI thành hai shard độc lập, mỗi shard một worker; frontend check chạy ở shard 1. Giữ `fail-fast: false`, artifacts riêng, toàn bộ tests và aggregate `CI Gate`; không song song hóa JUnit hoặc tách API/worker image build đang dùng chung cache.
3. Kiểm IDE, workflow lint, focused integration và full gates; push fix rồi đo thời gian CI thực. CodeRabbit đã từ chối 135 files vượt quota 100, không có review/inline/thread finding trong một lần thu evidence; chỉ dùng fallback đã ghi khi latest-head CI xanh và base/head còn đúng.
4. Merge với exact-head guard, chờ CI main đúng merge SHA, triển khai release đã xác minh qua đường delivery hiện có và kiểm có xác thực. Giữ OCR và bằng chứng parser/live vision đã ghi ở checkpoint trước.

Người dùng yêu cầu sửa tiếp CI/CD trong cùng turn. Run PR `34667847443` đã xanh trên `8cee98b`: aggregate khoảng 7m19s thay 11m14s. CD cũ `34609144624` rollout thành công nhưng login smoke lỗi cả candidate lẫn runtime đã rollback, nên giữ reservation. Bổ sung preflight identity/quyền trước mutation, diagnostic theo phase không lộ secrets, và input recovery thủ công có exact release: full smoke trước `finish` hiện có; không xóa khóa trực tiếp hoặc restore DB. Cấu hình identity smoke cần kiểm lại live; chưa coi credential, membership hay quyền admin là đã được cấp đúng.

## Publication được duyệt — 2026-09-12

Người dùng đã duyệt chia commit và mở một PR review vào main. Dùng branch trong checkout hiện tại, không tạo worktree. Chỉ publish MEM-81 và quy tắc component/library reuse đã được yêu cầu; loại `infrastructure/deployment/ocr/` và `docs/increments/active/mem-79-rancher-ocr/`. Không merge, deploy hoặc cập nhật/đóng Linear trong phạm vi này.

Thứ tự commit theo dependency thực: (1) storage purpose/bounds và migration nền UserFile; (2) core/API/worker attachment flow, parser reuse, context/vision/citations và regression liên quan; (3) frontend attachment components, generated client và UI tests; (4) opt-in resource harness và bằng chứng live vision/resource; (5) canonical specs/test matrices, reuse convention và increment records. Core/API/worker thay constructor/contract cùng nhau nên không chia theo thư mục thành các commit thiếu wiring.

Chạy lại backend `clean check`, frontend `check`, browser Chat/workspace regression trước push. Bằng chứng live/large-file của checkpoint trước được giữ nguyên với boundary thực; không chạy model trả phí thêm chỉ để publish. CI/review sau PR và nghiệm thu runtime là trạng thái riêng; OCR đã được hoãn không cản mở PR.

[Issue](https://linear.app/memory-os/issue/MEM-81) ở Backlog tại lần kiểm Linear ngày 2026-09-11. Người dùng chọn làm attachments trước Code Interpreter; sau khi chi tiết hóa, đã yêu cầu bắt đầu code. [Design](design.md) giữ baseline, invariants và quyết định; [verification](verification.md) giữ ma trận và kết quả thực tế. Implementation local đang diễn ra; chưa release hoặc cập nhật Linear.

Baseline được đọc: MemoryOS `0310a24c03b162a34673b1e5c407aa6745bfc48e`; Onyx `.tmp/onyx` tại `40eb240df370688ed6eeedc1272116e7829554ac`. Recheck HEAD, migrations và phần thay đổi đồng thời trước khi code. Không sửa các thay đổi OCR đang có trong checkout. Không tự tạo worktree, PR, commit, deploy hoặc cập nhật Linear từ yêu cầu chi tiết hóa kế hoạch này.

## Ranh giới với MEM-11

- MEM-11 đã được người dùng nghiệm thu và đóng ngày 2026-09-11, gồm editor/branch, Persona, Project instructions/conversations và authenticated sharing. Dùng contract thực đã giao; không giữ checklist cũ của MEM-11 làm blocker mới.
- MEM-81 sở hữu mọi file gắn message/Persona/Project, upload/status/readiness, context/read_file/images/tables, private retrieval, preview/citations/history và retention/cleanup.
- Hai issue có quan hệ related; MEM-81 không block PR hoặc completion của phạm vi MEM-11 đã điều chỉnh. Khi bắt đầu MEM-81, dùng contract Persona/Project/branch thực đã giao, không chuẩn bị trước schema hoặc abstraction không có consumer trong MEM-11.
- Không coi việc tách issue là hoàn thành attachments hoặc giảm baseline; cap 100/250 MiB và production acceptance được giữ nguyên tại đây.

## Thứ tự và cách ghi tiến độ

### Phạm vi được chọn tiếp — live vision và tài nguyên, 2026-09-12

Người dùng chỉ yêu cầu hai phần này; OCR để sau. Không sửa OCR, deploy, commit, PR, worktree hoặc Linear.

1. Dùng credential dev đã quản lý, test opt-in qua API có xác thực → native adapter → model thật. Ảnh tổng hợp chứa mã chỉ nằm trong pixels và hình học có đáp án xác định; kiểm câu trả lời, usage và history/file identity. Không coi storage double hoặc READY seed là upload/worker E2E.
2. Reuse corpus XLSX hợp lệ sát 100/250 MiB và production extractor; đo process RAM, heap, temp và thời gian trong process riêng, tách fixture creation. Chạy 1 và nhiều caller trên cùng extractor để kiểm giới hạn concurrency/cleanup; ghi chính xác boundary và không tự đặt SLO/p95.
3. Chạy static/focused/full backend gates; ghi số đo và giới hạn còn lại trong verification/canonical test docs. Không thay runtime chỉ để chạy phép đo.

Kết quả: live vision hai ảnh PASS; resource probe 100/250 MiB và ba caller PASS trong process riêng. Đã ghi chi phí Tika duplicate temp gần 2× raw, không đổi runtime. Full `clean check` PASS 2m26s, 601 passed/8 opt-in skipped; hai opt-in mới có kết quả chạy riêng ở trên, không nhận cache/skip là live evidence. Boundary chi tiết tại [checkpoint measurement](verification.md#live-vision-và-đo-tài-nguyên--2026-09-12); không coi số đo parser local là full worker/API sizing hoặc release acceptance.

### Tiếp tục hoàn thiện ngày 2026-09-12

1. Kiểm payload HTTP ảnh qua native SDK thực; thêm regression đổi model không vision (marker theo Onyx, không tải bytes), history/workspace, quyền và Stop.
2. Kiểm citation mở file private trong browser, reload và unavailable; dùng reader/component hiện có.
3. Bổ sung recovery/readiness ở boundary thật, giữ PostgreSQL authoritative và cached text khi search chưa sẵn sàng.
4. Chạy lại gates và corpus file lớn; tách rõ OCR service/runtime acceptance cần quyền triển khai khỏi kết quả local. Không sửa OCR checkout hoặc tự deploy.

Reference kiểm lại: `llm_step.py::resolve_image_cap` chỉ giới hạn Azure khi ENABLE_AZURE_IMAGE_CAP được bật; không áp cap provider giả định cho adapter OpenAI hiện có. Non-vision replay dùng marker và không gửi image bytes.

Tiến độ checkpoint: (1) native HTTP payload/non-vision/Stop có regression; (2) sửa DTO citation bị mất fileId khi reload, sửa sent attachments/edit theo native collection, browser text/image/unavailable đã kiểm; (3) PostgreSQL stale claims/expiry và MinIO+Redis+worker image recovery đã PASS; (4) parser ảnh dùng ImageIO/WebP plugin trong child Tika, HTML/EML/EPUB reuse cùng isolation. Kết quả và các test-authoring failures ghi riêng ở verification. Chưa tick các AT cần live OCR/model, full corpus lớn xuyên hệ thống và đo tài nguyên; không triển khai OCR để tự vượt ranh giới quyền.

### Checkpoint implementation đầu tiên

- Đã viết migration V37, UserFile owner-private, HTTP upload/finalize/list/status/retry/delete và policy Chat riêng. Giới hạn storage/admission không phải bằng chứng parser xử lý được 100/250 MiB.
- `USER_FILE` dùng relay PostgreSQL/Redis hiện có, public work port của Chat và composition worker hẹp. Worker reuse extractor, artifact và Document publication; plaintext từ cùng kết quả extraction lưu trên UserFile, không dựng artifact-reader framework.
- Có token/lease fencing cho publication/delete; raw đã adopt chuyển về cleanup bền vững qua `retireAdopted`, giữ upload receipt. Document cleanup kiểm thêm UserFile reference.
- Private projection mang `user_file_id`; Search chung loại private trước hybrid ranking. Private retrieval cho lượt Chat chưa nối.
- Còn nguyên phần việc message `files JSONB`, command/branch/context/read_file/images, Persona/Project links, UI, format parity, parser/file lớn và nghiệm thu end-to-end. Không tick trọn phase 1–3 vì các phần đó còn thiếu; checkpoint này chưa được ship.
- Kiểm chứng checkpoint: Gradle `check` PASS sau sửa fixture Nginx cũ (573 pass / 6 opt-in skipped); frontend `check` PASS (113 tests). Kết quả, các lần lỗi và giới hạn bằng chứng nằm trong `verification.md`.

```text
0. Hoàn tất quyết định và contract
   → 1. Ownership, schema, policy và composition
   → 2. Upload/finalize/status/delete qua API thật
   → 3. Worker extraction, private projection và recovery
   → 4. Send/history/context/tools/vision/citations
   → 5. Composer, file library và Persona/Project
   → 6. Nghiệm thu tích hợp, file lớn và giới hạn tài nguyên
   → 7. Review, CI, deployment và bàn giao
```

Hoàn thiện từng bước trên luồng runtime thật và thêm kiểm chứng tại boundary đang thay đổi. Có thể xây UI khi HTTP contract đã ổn định, nhưng không đánh dấu UI hoàn thành bằng fixture thay cho integration. Không ship các bước trung gian dưới profile/mode tạm hoặc nút giả. Tất cả bước dưới đây cùng thuộc MEM-81, không phải đề xuất cắt phạm vi thành nhiều release.

Checkbox chỉ được tick khi có bằng chứng tương ứng trong `verification.md`; kiểm source hoặc viết kế hoạch không tick implementation. Mỗi phase ghi: commit, contract thay đổi, test/scenario, kết quả và phần còn thiếu.

## 0. Hoàn tất quyết định trước implementation

- [x] Xác nhận baseline repository và reference checkout; đọc design/plan, command/context/transport, upload cap, worker composition, Document cleanup và source-bound retrieval.
- [x] Đối chiếu Linear trong phiên trao đổi: MEM-11 Done theo nghiệm thu người dùng; MEM-81 Backlog và related, không bị MEM-11 chặn.
- [x] Ghi format gaps, data/transaction boundaries, API consumers và D1–D6 trong design.
- [x] Theo phản hồi người dùng, chốt một cột `files JSONB` trên message theo Onyx; bỏ yêu cầu bảng nối message–file. Đọc lại loader/read_file/context helper và ghi chính xác cache/fallback trong design; lựa chọn physical plaintext storage/fallback của MemoryOS chưa coi là đã chốt.
- [ ] Hoàn tất D1/D2/D3: đường upload, ownership/composition file work và Document reference protection; chọn dependency direction không có cycle.
- [ ] Hoàn tất D4/D5: format parity, gửi chỉ có file, file đang processing của workspace, file builtin Persona và giới hạn sharing hiện có.
- [ ] Hoàn tất D6 bằng bounds có căn cứ và kế hoạch đo; không đổi cap 100/250 MiB đã chốt hoặc gán trước SLO chưa đo.
- [ ] Đọc tài liệu hiện hành qua Context7 khi chọn API cụ thể của native runtime, attachment adapter, storage/extractor client; kiểm đúng phiên bản đang pin. Nếu native public API chưa đáp ứng, ghi gap thực và lựa chọn hẹp trước khi thêm adapter.

Đầu ra: design đủ quyết định để viết schema/HTTP contracts; mỗi khác biệt reference có lý do, chi phí và cách kiểm. Không yêu cầu người dùng xác nhận lại lựa chọn thường lệ đã nằm trong scope.

## 1. Ownership, schema, upload policy và composition

Điểm vào: `core/src/main/java/io/memoryos/{chat,objectstorage,document,ingestion}`, migration dưới `core/src/main/resources/db/migration`, và `worker/WorkerConfiguration`/`MemoryOsWorkerApplication` trong source tree.

- [ ] Thêm UserFile, một cột `files JSONB` cho message, liên kết Persona/Project và processing/delete receipts theo ownership đã chọn. Không tạo bảng nối message–file; migration dùng số tiếp theo còn trống, giữ nguyên migration đã chạy.
- [ ] Định nghĩa identity và generation: raw immutable; retry cùng file không tạo raw mới; file khác bytes có identity mới; liên kết message có thứ tự.
- [ ] Thêm policy Chat phía server vào initiate/verify/adopt. Đồng bộ stored-object constraints; giữ admission của Source FILE và Google/native như trước.
- [ ] Bảo vệ Document/artifact references trước khi tích hợp publication. Kiểm cả release từ Source lẫn từ UserFile; cleanup không bỏ sót hoặc xóa nhầm artifact đang dùng.
- [ ] Tạo public contracts tối thiểu có caller thực, composition riêng cho API/worker và allowedDependencies tương ứng. Worker không nạp Chat inference/SSE/model catalog chỉ để xử lý file.
- [ ] Giữ SQL/locks/claims trong concrete persistence repositories; authorization và transaction cross-repository ở application services.

Kiểm chứng: migration/PostgreSQL, ownership constraints ở quan hệ relational, validation JSONB IDs tại application transaction, rollback adoption, reference cleanup và bean/module boundaries. Tái sử dụng `ObjectUploadLifecycleIntegrationTest`, `ExtractionArtifactLifecycleTest`, `ChatPersistenceIntegrationTest` khi cùng boundary; thêm case mới cho invariant chưa được kiểm.

Điều kiện ra: migration chạy trên nền main thật; hai composition roots hợp lệ; consumer Source/Google không bị nới policy và Document đang được tham chiếu được bảo vệ.

## 2. Upload, quản lý file và API

Điểm vào: ObjectStorage service/persistence, `api/src/main/java/io/memoryos/api/chat`, Chat file application/persistence mới có consumer thật.

- [ ] Expose policy và format đã xác nhận cho composer; xác định request/response bounds, mã lỗi nghiệp vụ và quyền từ Actor/Tenant hiện tại.
- [ ] Implement initiate → PUT → finalize/adopt theo D1. Finalize trả receipt/file status bền vững; mất response ở PUT/finalize có đường kiểm tra và retry đúng chặng.
- [ ] Lặp initiate/finalize theo identity đã chốt không tạo UserFile/job thứ hai. Check quyền lại sau provider IO; mismatch kích thước/checksum không adopt.
- [ ] List recent có pagination/filter; batch statuses có bound và quyền từng file. Status tách upload, content và private search readiness đủ cho client phục hồi.
- [ ] Read/preview/download kiểm ownership trước resolve storage; tránh lưu signed URLs trong DB/history/log. File active content được xem theo cách không thực thi cùng origin ứng dụng.
- [ ] Delete kiểm references, chặn use mới và tạo durable cleanup. Retry processing/delete chỉ áp dụng trạng thái phù hợp; receipt vẫn đọc được khi raw/project thay đổi theo contract.
- [ ] HTTP API dùng request/response top-level trong `contract`, stable operationId/tags và RFC 9457. Processing bất đồng bộ trả acceptance, không báo hoàn tất.
- [ ] Generate `openapi.yml` bằng backend contract test và Hey API client trong cùng thay đổi; không chỉnh file generated thủ công.

Kiểm chứng: API authorization/CSRF, pagination, cap/cap+1, mismatch, lost response, concurrent finalize, rollback và cross-owner IDs. Dùng MinIO thật để kiểm signed PUT/verification; mock HTTP chỉ chứng minh mapping/error path.

Điều kiện ra: có thể upload riêng tư trước session, nhận đúng file ID sau retry và đọc status bằng API sản phẩm. File processing chưa hoàn tất không được báo READY.

## 3. Worker xử lý, private projection và cleanup

Điểm vào: `ingestion/application`, `ingestion/persistence`, `OperationWorkload`, worker relay/topology/consumers; `connector/src/main/java/io/memoryos/provider/file`; Document/artifact và OpenSearch adapters.

- [ ] Nối operation user-file với relay/claim/lease/renew/finish theo D2. Enqueue intent commit ở PostgreSQL, Redis rediscovery không nhân đôi publication.
- [ ] Nối raw storage input với extractor phù hợp. Hoàn tất readers/format table; nguồn gốc private không giả thành Source hoặc URL Google.
- [ ] Sửa đường Docling bytes/base64 và reader bảng đang giữ toàn file theo D1/D6. Bound temp files, expanded archives, response/artifact, memory và cleanup khi timeout/lỗi.
- [ ] Tài liệu có canonical artifact/chunks/provenance; bảng có sheet/range và cached values; ảnh có representation/capability metadata đủ dùng cho native vision.
- [ ] Thêm private document scope xuyên chunk/projection/query/reader. Search chung loại file private trước lexical/vector ranking; migration/reindex/reconciliation không làm mất scope hoặc biến private thành public.
- [ ] READY xác nhận đúng content/search generation/index identity theo đường sử dụng. Index lỗi có status/retry thực; không chỉ dựa vào extraction success.
- [ ] Delete loại private projection, releases references và raw/artifacts đúng thứ tự; cleanup failure giữ đủ state để chạy lại. Claim cũ sau lease loss/delete không publish lại.
- [ ] Thêm timings và outcomes tại chặng thực theo observability conventions; không log nội dung file, signed URLs hoặc dùng file/actor IDs làm metric labels.

Kiểm chứng: worker execution với PostgreSQL/Redis/MinIO/OpenSearch thật; lỗi extractor/index, Redis mất tín hiệu, worker restart, lease loss/redelivery, cleanup storage failure. Mở rộng `SearchIndexWorkIntegrationTest`, `RedisExecutionTopologyIntegrationTest`, `OpenSearchRetrievalIntegrationTest` đúng regression. Docling live vẫn cần đúng endpoint/image, không lấy fixture làm bằng chứng OCR.

Điều kiện ra: từ finalized UserFile đạt trạng thái sử dụng đúng trên worker thật; có thể phục hồi lỗi/xóa mà không rò scope, nhân đôi hoặc hồi sinh kết quả cũ.

## 4. Chat admission, context, tools, vision và history

Điểm vào: `ChatCommand`, `ChatTurnService`, `application/ChatTurnPersistence`, `persistence/JdbcChatRepository`, `execution/ChatTurnSetup`, `execution/ChatModelBinding`, `tools/SearchTool`, `ChatSource` và API response contracts.

- [ ] Bổ sung ordered attachment IDs vào SEND/EDIT command và request fingerprint; REGENERATE dùng files của đúng user message. Kiểm malformed/foreign/duplicate IDs và bounds trước reserve.
- [ ] Admission revalidate membership, ownership, readiness, model capability, settings revision và selected branch. File IDs/model outputs không thay thế quyền đọc.
- [ ] Resolve effective workspace file set theo Persona/Project precedence đã chốt; settings/link edit sau admission không tự đổi tập file trong lượt đã nhận.
- [ ] Lưu mảng descriptors trong một cột `files JSONB`, cùng transaction với message/command, sau validation ownership/readiness. EDIT tạo nhánh mới và giữ nguyên JSONB files nhánh cũ; replay cùng request không tạo inference lần hai hoặc đổi branch selection.
- [ ] Nạp text tại vị trí message; giữ metadata cho file ngoài budget. Tính native context gồm instructions/history/files/tool reserves/media; không nhân đôi budget engine hoặc tăng context để ép file vào.
- [ ] Nối `read_file` có giới hạn và private retrieval với allowlist của lượt, revalidation generation/quyền và provenance. Đọc bảng có range/continuation, không diễn giải một phần bảng thành kết quả toàn bộ dữ liệu.
- [ ] Ưu tiên đường plaintext đơn giản theo Onyx như design; chốt cách dùng text output hiện có và cache-miss behavior thực. Không bắt buộc artifact-reader framework/tool bảng riêng khi read_file hiện tại đủ phục vụ consumer; phân biệt loader fallback, tool đọc và extraction chuẩn bị context.
- [ ] Đưa image input xuyên native message → provider request thật và accounting; model thiếu vision/tools báo rõ theo D4, không tự âm thầm đổi model.
- [ ] Dùng chung Stop/deadline/tool cycle/cost của Chat hiện tại. Stop kết thúc lượt mà không xóa hoặc hủy processing độc lập của file.
- [ ] History trả file metadata và unavailable state của nhánh đang chọn; citation mở đúng file với quyền hiện tại, theo Onyx. Không bắt buộc nhảy/highlight trang/range. Shared transcript không cấp download authority.

Kiểm chứng: mở rộng `ChatPersistenceIntegrationTest`, `ChatTurnSetupTest`, `ChatTurnServiceTest`, `SearchToolTest`, `ChatSessionApiIntegrationTest` và binding tests theo boundary. Capture provider request ở test để chứng minh bytes/media tồn tại; sau đó kiểm image answer qua model thật.

Điều kiện ra: API Chat trả lời trên file mới và file dùng lại; follow-up/edit/regenerate/reload giữ file, provenance và một execution đúng; broad Search không thấy file private.

## 5. Composer, recent files, Persona/Project và reader

Điểm vào: `web/src/features/chat/chat-thread.tsx`, `chat-transport.ts`, `chat-api.ts`, `chat-page.tsx`, `chat-message-actions.tsx`, `chat-projects-page.tsx`, `chat-personas-page.tsx`, `chat-source-panel.tsx`, `chat-shared-page.tsx` và generated client.

- [x] Tích hợp Attachment/File và hook preview upstream theo quyết định reuse ngày 2026-09-12; giữ Zustand `useShallow` được người dùng chấp nhận, không tạo store Chat song song. Chọn/gỡ/preview, cleanup object URL và reader được kiểm qua production component/runtime trong unit tests; authority được kiểm riêng qua PostgreSQL/API. Chưa coi đây là end-to-end upload/worker/model acceptance.

- [ ] Dùng native assistant-ui attachment adapter/primitives phù hợp phiên bản để nối server lifecycle; kiểm API trước khi dùng. App giữ file IDs/status nghiệp vụ, không dựng một message store song song.
- [ ] Composer có chọn/kéo thả, tên/loại/size, tiến trình từng file, remove/retry và lỗi action-local. Client kiểm policy để phản hồi sớm; backend vẫn kiểm độc lập.
- [ ] Xử lý nhiều file có kết quả khác nhau: giữ file thành công và câu hỏi khi một file lỗi; disable/send theo readiness đã chốt, không bỏ file lỗi âm thầm.
- [ ] Recent files phân trang và lấy status mới khi chọn; retry finalize không upload lại bytes; request UUID/draft được giữ khi send thất bại.
- [ ] New chat và Project draft upload trước session; lần gửi đầu tạo session và đổi URL mà không remount composer, mất selection hoặc mở SSE reader thứ hai.
- [ ] EDIT hiển thị files của message gốc, cho thay tập file ở nhánh mới; regenerate, branch picker và reload dùng metadata server.
- [ ] Project/custom Persona có quản lý links và trạng thái theo revision; giữ UI hiện có, không gộp redesign custom agent. Gỡ link khác với xóa file.
- [ ] Preview/citation/history hiển thị unavailable rõ; shared page read-only không có upload/edit và không dùng quyền owner để mở file.
- [ ] Keyboard/file input/drop, focus, live status announcement, mobile và light/dark theo control/token hiện có; kiểm định dạng tên tiếng Việt và tên dài.

Kiểm chứng: `chat-transport.test.ts`, `web/tests/e2e/chat.spec.ts`, `chat-workspace.spec.ts`; thêm browser cases khi bắt được routing/network/focus regression mới. Fixtures kiểm UI; ít nhất một vòng upload → worker → model qua browser thật nằm ở bước 6.

Điều kiện ra: người dùng thực hiện đủ luồng attachment trên Chat/Project/Persona, phục hồi lỗi và đọc lại lịch sử; các flow MEM-11 đang hoạt động không mất continuity.

## 6. Nghiệm thu tích hợp và file lớn

- [ ] Thực hiện AT-01–AT-16 trong [ma trận](verification.md), ghi boundary và kết quả từng case; tái sử dụng case đã chứng minh trong phase trước nếu HEAD/contract không đổi.
- [ ] Corpus có văn bản tiếng Việt/Anh, PDF scan và text, tài liệu dài có fact ở đầu/giữa/cuối, bảng nhiều sheet, ảnh có nội dung đọc được và file theo format table cuối cùng.
- [ ] Kiểm cap 100 MiB mặc định, cấu hình 250 MiB, cap+1, config vượt ceiling và giảm ceiling khi upload còn dang dở. Dùng file parse được trong structural bounds; file padding hoặc chỉ PUT không chứng minh xử lý file lớn.
- [ ] Đo riêng upload/queue/extraction/OCR/embedding/index/first-token/total-turn, peak RAM/temp disk và concurrency đã cấu hình. So lần đầu với follow-up READY, xác minh không parse/embed lại cùng generation.
- [ ] Kiểm network loss, worker restart, redelivery, model/tool timeout/Stop, concurrent delete/use và storage cleanup retry; phản hồi phải đúng trạng thái và không lộ private data.
- [ ] Chạy source/Google regression do shared storage/extractor/projection đổi; không mở rộng nghiệm thu thành giao toàn bộ các issue Google/OCR đang riêng.
- [ ] Kiểm integration Docling endpoint/auth từ môi trường thực. Nếu OCR hoặc provider chưa sẵn sàng, ghi chính xác case nào chưa kiểm; không chuyển fixture thành pass và không tự gộp việc triển khai OCR vào MEM-81.
- [ ] Consolidate contract đã triển khai vào specs/tests/guidelines tương ứng trong cùng code change; các quyết định chưa thực hiện vẫn ở increment.

Điều kiện ra: các scenario trong scope có evidence thật, limitations/rejected formats được công bố đúng quyết định, không còn claim chỉ dựa HTTP 200. Không đặt benchmark pass/fail theo số latency chưa được chốt.

## 7. Review, release và bàn giao

- [ ] Inspect changed Java/Kotlin DSL/YAML/properties/XML với IDE, gồm warnings, và compile theo skill Gate-1 khi implementation bắt đầu.
- [ ] Chạy Gradle wrapper `clean check --no-daemon`; backend-generated OpenAPI contract và `pnpm --dir web check`, browser gate theo thay đổi/repository. Không chạy lại vô hạn khi không có thay đổi hoặc concern mới.
- [ ] Khi thực hiện lifecycle đã được giao: PR code/config có phạm vi MEM-81, latest-head CI và review findings được xử lý; không mở PR chỉ để lưu kế hoạch hoặc evidence post-merge.
- [ ] Trước rollout kiểm image SHA/digests, migration compatibility, upload CORS/proxy, API/worker credentials, extractor bounds và temp resource settings đúng consumer. Không cần deployable/sandbox mới.
- [ ] Giao release bằng workflow deployment hiện có với backup/rollback phù hợp schema; xác minh release thật và chạy authenticated attachment smoke.
- [ ] Cleanup chỉ dữ liệu test của lượt nghiệm thu; kiểm operation xóa kết thúc. Ghi post-merge runtime evidence và closure tại Linear khi scope được nghiệm thu.
- [ ] Sau merge, move increment/completion và roadmap reconciliation theo policy trong code PR phù hợp; không tạo documentation-only PR chỉ để đổi lifecycle.

Điều kiện hoàn thành MEM-81 nằm ở [design](design.md#điều-kiện-hoàn-thành). Checkbox code/review/health không thay thế nghiệm thu file và Chat thật.

## Handoff tài liệu khi code làm contract trở thành sự thật

| Tài liệu canonical | Nội dung cần cập nhật trong code change |
| --- | --- |
| `docs/specs/chat.md`, `docs/tests/chat.md` | UserFile APIs, branch/files, native context/tools/media, workspace precedence, sharing và actual tests |
| `docs/specs/object-storage.md`, `docs/tests/object-storage.md` | Consumer-specific upload policy, adoption/cleanup và large-file bounds |
| `docs/specs/document.md`, `docs/tests/document.md` | UserFile provenance/reference protection, artifacts/generation/lifecycle |
| `docs/specs/ingestion.md`, `docs/tests/ingestion.md` | File workloads, claims/retry/readiness, dispatch/composition và observability |
| `docs/specs/search.md` và verification Search hiện có | Private projection/query/reader/reconciliation và không rò vào broad Search |
| `ARCHITECTURE.md`, runtime runbooks | Dependency/composition được triển khai, deployment settings và cách kiểm; không ghi đề xuất thành hiện trạng |

## Bản ghi cập nhật kế hoạch

- 2026-09-12, reuse implementation: đã thay Chat table parser riêng bằng Source Commons CSV/POI file-backed có cùng contract và ZIP/XML admission; Source cap giữ 10 MiB. Đã sửa consumer native cells/nested Docs, nhãn private first publication/reload, lost-lease boundary, SDK options/auth/parser diagnostics dùng chung. Corpus XLSX hợp lệ sát 100/250 MiB đã qua parser local, không suy thành OCR/provider/E2E acceptance. Chunk v2 có chi phí Search backfill/embedding khi deploy; xem verification để phân biệt gate mới và checkpoint cũ.

- 2026-09-12, user chốt reuse sau audit Spring AI ETL/Source: sửa native table chunking và nhãn private ở first publication; hợp nhất parser/options Docling; giữ quyền/claim riêng và Source cap 10 MiB. Kiểm corpus extractor → chunker, metadata parity, replay không parse lại, rồi mới đóng readiness/large-file acceptance. Không thay OCR manifests hoặc triển khai từ bước này.

- 2026-09-12, tiếp tục implementation: nối reader lịch sử với API plaintext phân trang Unicode và raw download owner-private; reuse `File`, `ChatDialog`, `ConfirmDialog` và `useFileSrc` thay cho reader UI/URL lifecycle mới. Sửa picker giữ ID unavailable, gỡ đúng một ID, khóa action trùng và hỗ trợ finalize lại; sửa context reload transaction và so sánh Unicode. Test PostgreSQL/API chứng minh owner/deletion/revocation/metadata checks và no-store/download disposition; frontend có 8 tests attachment/reader mới. Native vision, citation file, workload/index readiness, upload-expiry/recovery, format parity và parser 100/250 MiB vẫn là việc mở, không bị bỏ khỏi scope. Xem bằng chứng mới trong `verification.md`.
- Cùng lượt, test readiness thứ 9 phát hiện native send gating không tự chờ worker. Đã thêm điều kiện nghiệp vụ mỏng vào Root/Send, giữ engine/state native; unit chứng minh chặn cả Enter và nút Gửi. Thêm 2 tests PostgreSQL chứng minh file-only/order/replay/edit/regenerate/shared descriptors, custom Persona rỗng override Project và reload context có lock. Backend full gate 577 pass/6 opt-in skipped, hai tests DB mới chạy riêng pass; frontend full gate cuối 122 pass; browser regression 38 pass rồi 3 cases liên quan composer chạy lại pass sau readiness guard.
- 2026-09-11: chi tiết hóa theo yêu cầu người dùng; đọc source baseline và Onyx, thêm phases 0–7, D1–D6 và AT-01–AT-16. Chỉ sửa tài liệu increment; chưa chạy build, migration, provider, browser hoặc deployment. Kết quả kiểm tài liệu được ghi riêng trong `verification.md`.
- Phản hồi tiếp theo: người dùng chọn một cột JSONB cho message files và yêu cầu giải thích read_file theo Onyx. Đã sửa design/plan/matrix tương ứng, bỏ mặc định message-file join table và usage registry; chưa triển khai runtime hoặc chốt thay người dùng toàn bộ cache-miss policy.
