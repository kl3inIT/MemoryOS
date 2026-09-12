# MEM-81 — Ma trận và bằng chứng nghiệm thu

[Design](design.md) sở hữu scope/invariants/decisions; [plan](plan.md) sở hữu thứ tự và tiến độ. Tài liệu này chỉ ghi cách kiểm và kết quả có bằng chứng, không tự tạo thêm scope hay SLO.

## Pre-publication — 2026-09-12

- Người dùng đã duyệt nhiều commit và một PR review. Branch `feat/mem-81-chat-attachments` được tạo trong checkout hiện có từ `origin/main` `00a42f78b575b0bae8b0990e416a1d828ade2fca`; không tạo worktree. Không merge/deploy hoặc cập nhật Linear. Bảo toàn bằng hash sáu file OCR đang dirty/untracked, loại hoàn toàn chúng khỏi staged scope.
- Chạy lại `MEMORYOS_OPENAPI_WRITE=false; gradlew.bat clean check --no-daemon --console=plain`: **PASS 9s**, 24 tasks / 11 executed / 12 from cache / 1 up-to-date. Cả bốn test tasks FROM-CACHE của gate trước trên cùng nội dung code; không nhận đây là uncached rerun. Kết quả 609 tests = 601 passed + 8 opt-in skipped; live vision/resource đã có lượt thực chạy riêng được ghi bên dưới.
- `corepack pnpm check` từ `web`: **PASS**, 22 suites / 124 tests, generated stability, lint/format/typecheck, production build/font/routes. Cảnh báo runtime-config/bundle size vẫn được ghi nhận, không phải warning-free claim.
- `corepack pnpm exec playwright test tests/e2e/chat.spec.ts tests/e2e/chat-workspace.spec.ts --workers=2`: **38/38 PASS, 2.4m**. Lần này chạy sau helper retry lost-upload-response, không chỉ dùng kết quả browser cũ. Test dùng HTTP fixture, không nhận là deployed attachments end-to-end.
- Static-analysis evidence và các inspection timeouts giữ đúng boundary đã ghi ở từng checkpoint. Trong bước publication không sửa Java/config/runtime/frontend; chỉ bổ sung records và cập nhật roadmap/architecture theo implementation hiện có. `git diff --check` PASS; staged diffs được quét Gitleaks 8.30.1 với redaction trước mỗi commit. Không có credential hoặc file local runtime được đưa vào scope.
- Thứ tự commit: storage/schema → backend/API/worker/native execution → frontend/client → measured probes/evidence → canonical docs/reuse convention. GitHub PR head/checks được xác nhận sau push; CI/review chưa phải bằng chứng merge hay release. Giữ increment trong `active/` và không tự đóng MEM-81.

## Live vision và đo tài nguyên — 2026-09-12

Phạm vi mới được người dùng chọn: chỉ hai phép kiểm này, OCR để sau. HEAD `00a42f78b575b0bae8b0990e416a1d828ade2fca` cộng diff local chưa commit. Không sửa runtime/OCR manifests, deploy, PR, worktree, commit hoặc Linear trong lượt này. Bằng chứng số gốc được giữ trong [measurements-2026-09-12.json](measurements-2026-09-12.json), không phụ thuộc thư mục build bị `clean` xóa.

### Live vision: PASS ở boundary API/native provider

`ChatSessionApiIntegrationTest.realVisionReadsPixelsThroughAuthenticatedHttpAndPersistedHistory` gọi `gpt-5-mini` tại `https://api.openai.com/v1` qua API bearer-authenticated, catalog adapter, native runner và PostgreSQL thật. Credential lấy từ Infisical dev của project, chỉ truyền trong process; không dùng ambient `OPENAI_API_KEY` hoặc lưu key trong evidence. Storage là double và READY được seed bằng helper hiện có: đây **không phải** upload → MinIO → worker → model end-to-end.

Hai PNG 900×420 có cùng filename trung tính, mã ngẫu nhiên tám ký tự chỉ nằm trong pixels, lần lượt 3/5 hình tròn đỏ và một hình vuông xanh. Prompt không chứa đáp án. Cả hai trả đúng cả mã và số hình; history reload giữ fileId và citation; input usage được lưu từ provider, không lấy heuristic 4096 làm billing.

| Ảnh | Bytes | Đáp án thật | Input tokens | HTTP send → persisted completion |
| --- | ---: | --- | ---: | ---: |
| 1 | 6975 | `86B46D11`, 3 hình tròn đỏ, 1 hình vuông xanh | 1563 | 6.057 s |
| 2 | 7828 | `8F39FFD8`, 5 hình tròn đỏ, 1 hình vuông xanh | 3211 | 5.559 s |

Lệnh focused với `MEMORYOS_CHAT_VISION_LIVE_TEST=true` và managed `SPRING_AI_OPENAI_API_KEY`: `gradlew.bat :api:test --tests '*ChatSessionApiIntegrationTest.realVisionReadsPixelsThroughAuthenticatedHttpAndPersistedHistory' --no-daemon --console=plain` — PASS, 1 test / 2 actual vision turns, 1m11s; test task thực chạy. Khi lặp lại phép kiểm live dùng thêm `--rerun-tasks` để không nhận cache làm provider evidence. Hai ảnh tổng hợp không chứng minh mọi loại ảnh/provider, OCR chất lượng, hay full browser/worker release acceptance.

### Tài nguyên: PASS ở boundary production parser trong JVM riêng

`ChatFileResourceMeasurementTest` reuse fixture writer của `LargeChatSpreadsheetExtractionTest`; tạo XLSX hợp lệ trước khi đo. Mỗi scenario chạy JVM mới `-Xmx512m`, gọi production `BoundedChatFileExtractor` → POI file-backed → canonical → chunker. Không fork bản parser riêng, không nới cap hoặc gọi OCR. `scripts/measure-chat-file-process.ps1` lấy mẫu Windows process working set/private commit và thư mục temp mỗi khoảng 50 ms (có overhead lấy mẫu); Java heap lấy mẫu 10 ms. Đây là **sampled peaks**, không bảo đảm bắt mọi spike. OS timing gồm JVM startup; bảng dưới dùng thời gian công việc không gồm tạo fixture/startup. Không so sánh số RAM này như memory toàn Spring worker/API.

Máy: Windows 11 Pro, AMD Ryzen 7 6800HS, 16 logical processors, khoảng 13.69 GiB RAM usable; lúc bắt đầu còn khoảng 1.27 GiB available. Java 25; chạy lần lượt, không chạy vision đồng thời. Raw fixture nằm ngoài thư mục temp đo; OS page cache không được flush. Corpus nhiều PNG có reference trong drawing, một sheet/hai ô dữ liệu, một chunk đầu ra — kiểm file-byte/IO/concurrency, **không đại diện workbook dày ô hoặc nhiều chuỗi/shared strings**. Mỗi scenario một lượt, không tính p95/SLO.

| Corpus / callers chung một extractor | File MiB | Working set đỉnh MiB | Private commit đỉnh MiB | Heap đỉnh MiB | Temp đỉnh MiB | Hoàn tất cả nhóm |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| Sát cap 100 / 1 | 98.44 | 240.49 | 388.20 | 121.55 | 196.88 | 2.447 s |
| Sát cap 250 / 1 | 248.73 | 271.21 | 449.92 | 120.43 | 497.46 | 3.278 s |
| Sát cap 250 / 3 đồng thời | 248.73 mỗi lượt | 268.08 | 437.34 | 112.48 | 497.46 | 6.374 s |

Ba caller hoàn thành 3/3; thời gian chờ tới lần đọc đầu là 0.041 / 3.020 / 4.679 s, thời gian spool+parse+chunk tương ứng 3.146 / 1.788 / 1.680 s. Chunks có thể tiếp tục sau khi permit extraction được nhả, nên các khoảng này không phải trace của scheduler. Peak active input copy = 1, phù hợp semaphore một extraction của production; không suy thành ba parser chạy song song. Tất cả scenario giữ tối đa hai temp files và không còn entry sau process thoát.

Phát hiện thực: `Tika.detect(InputStream, filename)` tạo thêm `apache-tika-*.xlsx` ngoài spool `memoryos-chat-file-*.bin`, khiến temp gần **2× raw**; không phải memory leak. Đã đối chiếu Tika 4.0.0 source và docs: có thể reuse `TikaInputStream.get(Path)` để tránh bản sao này. Lượt này chỉ đo/ghi nhận, **chưa đổi runtime**; tối ưu đó không trở thành requirement mới hay điều kiện đóng ticket.

Hai lượt đầu của harness fail vì assertion cleanup đếm cả JAR `mockitoboot*.jar` 1933 bytes do Mockito tạo trong JVM đo. Đã bỏ Mockito khỏi child, dùng Docling collaborator fail-fast không gọi mạng; không xóa/che file parser hoặc nới giới hạn production để lấy PASS. Assertion sau đó kiểm toàn thư mục temp trống và số bản sao đã đo. Lượt sạch: `MEMORYOS_CHAT_RESOURCE_TEST=true; gradlew.bat :connector:test --tests '*ChatFileResourceMeasurementTest' --no-daemon --console=plain` — PASS 39s, 1 test / 3 isolated scenarios / 5 extractions. Dùng `--rerun-tasks` khi đo lại; runner opt-in Windows không thay normal runtime profile.

### Gate và ranh giới

- Gate-1: compile API test PASS; JetBrains kiểm ba file Java connector đã thêm/sửa, không errors/unresolved references. Giữ warning `public main` cho child JVM entrypoint và `XSSFWorkbook` không try-with-resources vì fixture dùng `OPCPackage.revert()` để tránh autosave bản lớn. API test file inspection timeout cả 10s/30s: không nhận IDE-clean cho file đó; compile/live behavior là evidence riêng. PowerShell sampler đã parse và chạy thật qua ba scenario.
- Full backend `MEMORYOS_OPENAPI_WRITE=false; gradlew.bat clean check --no-daemon --console=plain`: **PASS 2m26s**, 24 tasks / 14 executed / 10 from cache. XML tổng **609 tests = 601 passed + 8 skipped, 0 failures/errors**: core 390/0 skip, connector 63/4 skip, API 129/4 skip, worker 27/0 skip. Core/worker test results FROM-CACHE; API/connector test tasks thực chạy. Normal gate không bật paid/live/resource opt-ins, nên hai test mới được skip ở gate này nhưng đã chạy PASS riêng ở trên; ba Docling và ba provider opt-ins cũ vẫn skip. Không cộng focused results vào số test full gate. Warnings JDK native/Unsafe còn; không nhận warning-free. `git diff --check` PASS; JSON evidence parse thành công, không còn process `ChatFileResourceProbe` sau khi kết thúc.
- Không có thay đổi frontend nên không chạy lại browser/frontend từ lượt trước. Full upload/worker/model large corpus, RAM toàn worker/API, production concurrency/SLO và OCR vẫn chưa được chứng nhận. OCR được người dùng hoãn, không dùng nó để chặn hai phép kiểm đã hoàn thành ở boundary nêu trên; không tự đóng MEM-81/release AT.

## Reuse Source / Spring AI / Docling — local 2026-09-12

### Checkpoint tiếp tục: native image, citations, recovery và isolated parsers

- API/native SDK HTTP regression (vision true/false) + 7 setup tests PASS. Test đầu bắt được lỗi DTO history bỏ `fileId`; đã sửa `ChatSourceResponse`, nullable Source IDs và regenerate OpenAPI/Hey API. HTTP capture xác nhận đúng `data:image/png;base64,...`, bytes không được mở khi non-vision, file identity/citation được lưu và đọc lại. Provider là local HTTP fixture; không nhận đây là live model hiểu ảnh.
- `FileReaderToolTest` + 12 `ChatFileLifecycleIntegrationTest` + OpenAPI contract PASS (1m15s): search outage hướng về cached reader nhưng không nuốt quyền/Stop; expired lease takeover chặn worker cũ publish/fail; upload expiry idempotent, retry yêu cầu upload mới. Hai lỗi test-authoring đã sửa trước kết quả pass: estimator không phải functional interface; Mockito restub throwing method cần `doThrow`, không thay behavior để nới assertion.
- `TikaSourceContentExtractorTest` PASS (7 cases, focused command 49s): Source TXT/Markdown/PDF/DOCX regression; Chat HTML giữ canonical/inert text; timeout chấm dứt child; PNG/JPEG 3000×2 và WebP 1×1 giải mã được; PNG chỉ có header bị từ chối. ImageIO WebP dùng TwelveMonkeys 3.15.0 thay parser header tự viết. PNG/JPEG dùng JDK ImageIO. Child-process heap 512 MiB, deadline 90s; ảnh có pixel bound 144 triệu, sample decode dài tối đa 2048, raw không bị thay. Đây không phải đo peak RSS hoặc mọi format/corpus đã nghiệm thu.
- Browser CLI kiểm qua UI thật/HTTP fixtures: file text và image có citation; mở text inert và đúng original-download identity; image load trong reader; reload giữ citation; owner endpoint chuyển 404 thì reader báo unavailable và không có link tải. Snapshots: `web/.playwright-cli/page-2026-09-11T21-07-06-133Z.yml`, `page-2026-09-11T21-11-30-525Z.yml`, `page-2026-09-11T21-13-50-687Z.yml`, `page-2026-09-11T21-17-51-527Z.yml`. Browser bắt lỗi user files nằm trong native `attachments` nhưng UI chỉ render `parts`; đã dùng `MessagePrimitive.Attachments` và giữ file/image IDs khi edit. Regression trong `chat-attachments.test.tsx` PASS 4/4 sau bổ sung gửi → file button. Browser fixture trước khi ổn định có lỗi URL/Buffer do CLI sandbox và port chưa khởi động; không tính các lượt đó là product failure. Console còn runtime-config 404 của dev fixture; 404 private file cuối là deliberate denial.
- `corepack pnpm check` PASS sau regenerate: 22 suites / 122 tests, generated stability, lint/format/typecheck/build; build còn warning runtime-config/bundle size.
- Full browser regression `playwright test tests/e2e/chat.spec.ts tests/e2e/chat-workspace.spec.ts --workers=2`: **38/38 PASS (2.8m)** sau sửa sent-attachment rendering/edit và regenerate contract. Đây là regression qua HTTP fixtures, không cộng với manual citation scenarios thành một E2E service suite.
- `WorkerFileProcessingIntegrationTest` mở rộng PASS (1m18s): test-owned PostgreSQL, Redis, MinIO thật; initiate replay → signed HTTP PUT → finalize replay → mất USER_FILE stream delivery → topology/rediscovery → restart consumer → ImageIO child → READY plaintext/canonical artifact trên S3 → DELETE → raw trả S3 404. Processing attempts = 1, dispatch attempts >= 2. Tái dùng fixture Source đã có; không thêm runtime mode/endpoint. Không có live OpenSearch/model/OCR claim; restart này là consumer lifecycle, không giả là kill/restart toàn pod.
- Gate-1 kiểm toàn bộ Java/Kotlin DSL sửa trong checkpoint. Không còn errors; giữ warning public main (child JVM entrypoint), tool methods được native reflection gọi, headers CSRF/SSE và local HTTP fixtures, mock `open`/assertThrows không phải resource leak. OpenAPI IDE inspection timeout; contract regeneration/test và frontend generation stability là fallback, không coi YAML là IDE-clean.
- Full backend gate đầu checkpoint `MEMORYOS_OPENAPI_WRITE=false; gradlew.bat clean check --no-daemon`: **PASS (12m14s)**, 24 tasks / 20 executed / 4 from cache. Cả bốn test tasks đã chạy; core lâu do tạo/chạy PostgreSQL fixtures, không timeout. Worker private-image recovery được bổ sung/chạy riêng trong thời gian gate này; response canonical bound sau đó được đồng bộ 32 MiB với StructuredContent (các string Source vẫn 8 MiB). Gate bản cuối được ghi riêng bên dưới, không gộp các lần chạy thành một kết quả.
- **Gate backend bản cuối PASS (2m46s)**: cùng `clean check`, 24 tasks / 16 executed / 8 from cache. Core 390 tests lấy từ cache của full run vừa pass; connector 62, API 128, worker 27 chạy lại. Tổng **607 tests = 601 passed + 6 opt-in skipped, 0 failures/errors**. Ba skip Docling live và ba skip provider live giữ nguyên; không đổi test conditions để lấy pass. Corpus XLSX 100/250 MiB có trong connector gate. Warnings SDK generics/JDK và OTLP receiver shutdown còn, không coi warning-free.
- Browser upload bổ sung một lần retry **chỉ khi fetch TypeError** ở initiate/finalize, giữ nguyên requestId/fileId, không PUT bytes lần hai và không retry HTTP denial/validation/cancellation. `chat-attachments.test.tsx` hiện 6/6 PASS; toàn `corepack pnpm check` chạy lại **PASS, 22 suites / 124 tests**, cùng generated stability/lint/format/typecheck/build. Browser 38/38 ở trên chạy trước helper retry này; không nhận là đã rerun browser sau helper. Reload mất draft không được mô tả thành resumable/chunked upload; records đã tạo vẫn qua recent files/expiry hiện có.
- Cuối checkpoint HEAD vẫn `00a42f78b575b0bae8b0990e416a1d828ade2fca`, dirty checkout không commit. `git diff --check` PASS (có thông báo CRLF→LF cho các file đã dirty từ trước). Browser/fixture server do lượt này mở đã đóng. Không sửa các file OCR hay tạo worktree/PR/commit/deploy/Linear mutation.

Giới hạn hiện tại: manifest private OCR vẫn `DOCLING_SERVE_MAX_FILE_SIZE=104857600`, ingress 150m; không có `DOCLING_TEST_ENDPOINT`/live-provider opt-in trong môi trường lượt này. Không sửa OCR checkout, deploy, commit/PR/worktree hay Linear. Còn cần nghiệm thu service OCR 250 MiB, live vision, full upload/worker/model corpus lớn và đo RAM/temp/concurrency trước khi đóng MEM-81. Các kết quả local ở trên không thay thế AT release. Heuristic 4096/image hiện tại không phải công thức patch-token reference implementation hay bảo đảm tokenizer của mọi model.

Scope: sửa native-table chunking, first-publication private identity, dùng lại spreadsheet parser Source cho Chat, dùng chung SDK options/auth/canonical Docling và chặn publication khi mất lease. Không sửa OCR manifests, tạo commit/PR/worktree, deploy hoặc cập nhật Linear. Spring AI tokenizer/embedding, artifact lifecycle và Search projection được giữ; không thêm ETL framework, generic coordinator hoặc artifact-reader framework.

- Focused core/chunker/private-label + connector compilation: `gradlew.bat :core:test --tests '*DocumentChunkServiceTest' --tests '*StructuredDocumentChunkerTest' :connector:compileTestJava :api:compileTestJava :worker:compileTestJava --no-daemon` PASS (34s).
- Reuse/recovery tests: `gradlew.bat :core:test --tests '*DocumentChunkServiceTest' --tests '*StructuredDocumentChunkerTest' --tests '*UserFileIngestionCoordinatorTest' :connector:test --tests '*SpreadsheetSourceContentExtractorTest' --tests '*BoundedDoclingClientTest' --tests '*DoclingSourceContentExtractorTest' --no-daemon` PASS (32s). Bao gồm Source/Chat cùng CSV/XLSX canonical, date1904/cached-value/merge/hidden-sheet/provenance; record overflow/malformed ZIP; HTTP JSON/multipart cùng SDK options và API key; renewal false/exception không stage/publish.
- `gradlew.bat :core:test --tests '*ExtractionArtifactLifecycleTest' :connector:test --no-daemon` PASS (54s). PostgreSQL thật chứng minh first publication/reload/convention rebuild giữ UserFile ID, artifact ID không đổi và reader lease được giải phóng. Google Sheets/Docs extraction → chunk assertions chứng minh tìm được giá trị bảng, không chỉ tên sheet. Provider HTTP/Docling trong test này là fixture hoặc opt-in, không phải live OCR acceptance.
- `gradlew.bat :connector:test --tests '*LargeChatSpreadsheetExtractionTest' --no-daemon` PASS (35s), 2/2 cases. File OOXML hợp lệ có PNG được tham chiếu qua drawing, kích thước trong 2 MiB dưới mỗi cap 100/250 MiB; đi qua `BoundedChatFileExtractor` → POI file-backed → canonical → chunker. Source từ chối cùng file do cap 10 MiB. Fixture được JUnit TempDir thu hồi. Hai lượt trước thất bại ở việc đóng **fixture writer** tự save lại package lớn vào memory; đã sửa ownership dùng `OPCPackage.revert()` sau khi lưu output, không nới cap/runtime để né lỗi. Đây không phải benchmark peak RSS, full upload/worker/model E2E hay bảo đảm mọi workbook lớn đều nằm trong structural bounds.
- Gate-1 kiểm 18 file Java của lượt reuse, có warnings: không có IDE errors/unresolved references. Các cảnh báo giữ có lý do: `X-Api-Key` là header private thực; URI SAX là feature identifiers, không phải fetch HTTP; `toBuilder` dùng generic signature của Docling SDK 0.6.5; kiểm null ở boundary JSON giữ defensive validation dù SDK annotation nói non-null; `Mockito.verify(open)` không mở tài nguyên; fixture workbook dùng `revert()` để đóng không autosave. Inspection `openapi.yml` timeout cả 10s/30s, không coi kết quả rỗng là IDE-clean; contract được kiểm bằng API snapshot test và generation stability. Compile và behavioral tests vẫn là bằng chứng riêng.
- Full `clean check` đầu tiên kết thúc sau 11m23s, fail duy nhất snapshot OpenAPI còn cũ từ phần MEM-81 đang có; core 384 tests và worker 27 tests không lỗi. Đã regenerate snapshot bằng `MEMORYOS_OPENAPI_WRITE=true` + `:api:test --tests '*OpenApiContractTest*'`, đồng thời chạy `DoclingPropertiesTest` chứng minh Spring binding API key/Tesseract/languages, giữ defaults và từ chối header injection: PASS (1m02s). Đã regenerate Hey API client, không sửa schema bằng tay.
- `corepack pnpm check` PASS sau khi format đúng hai file MEM-81 hiện có (`chat-file-picker.tsx`, `chat-source-panel.tsx`): generation stability, lint, formatting, typecheck, 22 suites / 122 tests và production build/font/route checks. Không thay component/runtime UI trong lượt reuse. Build còn cảnh báo runtime-config script và bundle size, không phải warning-free claim.
- Gate cuối: `MEMORYOS_OPENAPI_WRITE=false; gradlew.bat clean check --no-daemon` **PASS (2m10s)**, 24 tasks (14 executed, 10 from cache). JUnit tổng **598 tests: 592 passed, 6 skipped, 0 failures/errors** — core 384/0 skip; connector 60/3 skip; API 127/3 skip; worker 27/0 skip. Core/worker test results được phục hồi từ cache của lượt vừa chạy; API/connector chạy lại sau cập nhật contract và binding test, không nhận là full uncached run. Skips là ba `DoclingServeIntegrationTest` và ba opt-in trong `ChatSessionApiIntegrationTest`, không tính thành live provider evidence. Corpus XLSX lớn chạy lại PASS (100-cap case 8.24s, 250-cap case 12.946s; thời gian gồm tạo fixture và parse, không phải latency worker). JVM/SDK và OTLP receiver-shutdown warnings vẫn có. HEAD giữ `00a42f78b575b0bae8b0990e416a1d828ade2fca`, kết quả dành cho dirty checkout đã sửa, không phải release/commit mới. `git diff --check` PASS.

Giới hạn còn mở: private OCR service đang giới hạn 100 MiB, ingress 150m; binding API key/Tesseract mới chưa được đưa vào runtime. Không kết luận PDF/DOCX/PPTX 250 MiB đã được parse ở service thật. Native image/citation/recovery E2E, đo RAM/temp/concurrency và các AT release chưa được đóng. Chunk convention v2 đổi Search identity, cần backfill/embedding lại khi rollout; artifact hiện có được reuse, không OCR lại.

## Trạng thái tại lần chi tiết hóa — 2026-09-11

- Đã kiểm source MemoryOS tại `0310a24c03b162a34673b1e5c407aa6745bfc48e` và reference implementation tại `40eb240df370688ed6eeedc1272116e7829554ac` qua checkout the retired reference checkout.
- Đã đối chiếu Linear trong phiên trao đổi: MEM-11 Done theo xác nhận nghiệm thu của người dùng; MEM-81 Backlog, related MEM-11. Đây là trạng thái ticket, không phải xác nhận runtime attachments.
- Tại checkpoint lập kế hoạch, thay đổi chỉ là tài liệu increment và mọi AT còn chưa thực hiện. Sau yêu cầu bắt đầu code, kết quả implementation được ghi riêng bên dưới; không đổi bằng chứng lịch sử thành runtime acceptance.
- Không chạy migration/build/provider/browser/deployment trong lần lập kế hoạch. Các thay đổi OCR khác trong worktree thuộc công việc đang có và được giữ nguyên.

## Ma trận kiểm chứng

### Checkpoint backend local sau yêu cầu bắt đầu code

- Migration V37 và các lớp UserFile/upload/worker đã được triển khai; chưa commit/PR/deploy/Linear mutation. OCR checkout không bị sửa bởi thay đổi này.
- HEAD đọc tại bàn giao: `00a42f78b575b0bae8b0990e416a1d828ade2fca`, cộng diff local MEM-81 chưa commit. SHA này là nền checkout, không phải commit chứa implementation attachments.
- Gate compile `:core:compileJava :api:compileJava :worker:compileJava --no-daemon`: PASS.
- Chạy hẹp API/core/worker trước full gate: PASS, gồm API file ownership/CSRF/cap/finalize và OpenAPI, lifecycle PostgreSQL, storage regression, architecture, relay/topology và OpenSearch private exclusion. Storage trong file lifecycle/API test là test double; OpenSearch exclusion dùng container thật. Không có claim live OCR hoặc browser upload từ các test này.
- Generated `openapi.yml` và Hey API client bằng scripts repository; `corepack pnpm check` từ thư mục `web`: PASS, 20 test files / 113 unit tests, lint/format/typecheck/build và generated stability. Gọi pnpm từ repo root ban đầu gặp mismatch Corepack 11.24.0/11.22.0; chuyển đúng cwd dùng bản pin 11.22.0, không sửa packageManager.
- IDE đã inspect các Java/YAML thay đổi với warnings. Java compile thành công. SQL inspection trên hai Document repositories không có data source IDE: áp dụng suppression giống concrete repositories hiện có; PostgreSQL tests là bằng chứng SQL thực. OpenAPI/worker YAML có lượt trả `timedOut=true`, không coi đó là chứng nhận IDE-clean đầy đủ; generation/binding/startup được kiểm riêng.
- Full `clean check` lần đầu phát hiện lỗi test Nginx Chat tại HTTP đầu tiên (`HTTP/1.1 header parser received no bytes`). Fixture chỉ thay biến storage trong CSP và bỏ sót biến Sentry hiện có của Dockerfile. Đã sửa fixture thay cả hai biến, không sửa Nginx sản phẩm.
- Sau sửa fixture, `./gradlew.bat check --no-daemon`: PASS lúc 23:35 ngày 2026-09-11 (+07), 2m04s. Các task core/connector/worker dùng kết quả thành công của lượt clean vừa trước; API suite được chạy lại. Tổng XML: core 372/372, connector 50 pass + 3 skipped, worker 27/27, API 124 pass + 3 skipped; **573 pass, 6 skipped, 0 failures**. Nginx disconnect/reconnect/Stop pass. `ChatFileLifecycleIntegrationTest` có 7/7 cases pass, gồm giữ reservation đủ lâu khi re-sign URL. Không gọi lần `clean check` đầu tiên là PASS.
- `git diff --check`: PASS. Những test/provider opt-in bị skip, browser upload và large-file/OCR live vẫn chưa được nghiệm thu; số test trên không thay thế các AT còn mở.
- Còn thiếu: message `files JSONB`/branch/context/read_file/vision, Persona/Project links, private retrieval/readiness, UI/preview/download, expiry recovery, format parity và parser streaming/100–250 MiB. Các phase và AT tương ứng vẫn mở; chưa đủ điều kiện release MEM-81.

### Checkpoint tiếp tục — 2026-09-12, reader và reuse frontend

- Vẫn là diff local trên nền `00a42f78b575b0bae8b0990e416a1d828ade2fca`; không commit/worktree/PR/deploy hoặc cập nhật Linear, không sửa các file OCR đang có.
- Dùng component Attachment/File upstream và hook `useAttachmentSrc` với Zustand `useShallow`; `useFileSrc` được export để reader ảnh đã lưu dùng cùng lifecycle. `ChatDialog`/`ConfirmDialog` là component hiện có của project. Không có message store/Zustand store mới, không overwrite Button hoặc nâng phiên bản các dependencies khác.
- API owner-private text/raw đã nối reader lịch sử. Test mới bao gồm cursor Unicode, content inert, no-store/attachment/nosniff, đóng stream khi metadata sai hoặc owner bị thu hồi quyền/xóa file trong storage IO, không tải content khi file unavailable, cleanup object URL/query cache và không mất selected IDs unavailable.
- Hẹp backend: 10 file lifecycle + 4 context tests + 1 API file scenario PASS. Lượt đầu bắt được làm tròn nanosecond xuống microsecond của PostgreSQL ở thời hạn giữ object; đã sửa bằng làm tròn lên và thêm expiry fixture xác định, rồi chạy lại cả nhóm thành công. Không phải nới assertion.
- `./gradlew.bat clean check --no-daemon`: PASS, 12m33s. XML: core 376/376, connector 50 pass + 3 skipped, API 124 pass + 3 skipped, worker 27/27 — **577 pass, 6 skipped, 0 failures**. Sau đó bổ sung hai test message-file transactions/Persona precedence và chạy riêng cả hai: PASS, compile tests thành công. Không gọi đây là 579 tests trong một lần full gate; main runtime không đổi sau full gate.
- Test native composer bổ sung phát hiện thư viện không tự disable send khi attachment đang xử lý. Đã dùng wrapper mỏng quanh Root/Send, dựa trực tiếp vào runtime state, chặn cả Enter và nút Gửi; adapter kiểm lại status/ID. Chạy nhóm attachment/reader mới: **9/9 PASS** (4 composer/adapter/edit + 5 reader/picker). Giữ input editable, không dựng lại composer engine hoặc thêm store riêng.
- Frontend `corepack pnpm check`: PASS, 22 files / 121 unit tests, generated stability, lint, format, typecheck và build. Sau sửa bố cục bên dưới, lint/format/build/typecheck được chạy lại. Build còn cảnh báo chunk lớn và script runtime-config không phải module; không coi warning là runtime failure.
- Sau thêm readiness guard, chạy lại toàn bộ `corepack pnpm check`: **PASS, 22 files / 122 unit tests**, cùng generated stability/lint/format/typecheck/build. Chạy lại 3 browser cases chịu ảnh hưởng trực tiếp (new-session promotion, composer spacing/model keyboard, Enter/IME/multiple turns): **3/3 PASS**; 38-case regression ở lượt trước vẫn được ghi riêng, không cộng thành 41 tests khác nhau.
- Browser `playwright test tests/e2e/chat.spec.ts tests/e2e/chat-workspace.spec.ts --workers=2`: lượt đầu 37 PASS/1 FAIL do hàng attachment đẩy input cách heading 111 px (contract <90 px). Đã xem screenshot, chuyển hàng attachment xuống dưới input, giữ nguyên assertion. Chạy lại toàn bộ hai suite: **38/38 PASS**. Đây là browser regression trên HTTP fixture, không phải upload→worker→model thật.
- IDE kiểm changed Java gồm warnings; đã sửa nullable disposition/context assertions và collection warning. Cảnh báo tài nguyên ở mock/`assertThrows` đã review: các trường hợp này chủ động kiểm `open` ném lỗi và `close` được gọi, không phải đường stream thành công bỏ quên close. Không có chứng nhận live OCR/native vision từ IDE hoặc compile.
- Còn mở: native image vào provider/accounting, file citations và Stop/deadline của private file tools, đầy đủ readiness/recovery, parser bounds/format parity và corpus thật 100/250 MiB. Preview ảnh trong UI không chứng minh model có vision; `READY` vẫn chỉ chứng minh content publication. Các AT end-to-end bên dưới chưa được đóng.

| ID | Scenario / contract | Boundary nhỏ nhất và evidence cần có | Phase |
| --- | --- | --- | --- |
| AT-01 | Migration, ownership và dependency direction | Flyway/PostgreSQL thật; message có một cột files JSONB, không có join table; application transaction từ chối foreign file IDs trong JSONB, relational FK giữ Tenant; hai composition roots khởi động; Modulith/ArchUnit không cycle/internal imports | 1 |
| AT-02 | Upload/adopt một lần, lỗi checksum/bytes và lost response | MinIO + API + PostgreSQL; gửi lại finalize đồng thời trả cùng file/job; lỗi verification/transaction không adopt sai hoặc mất cleanup owner | 1–2 |
| AT-03 | Chính sách kích thước | 100 MiB default, 250 MiB cấu hình, cap+1, config vượt ceiling, giảm cap khi chưa finalize; Source FILE/Google giữ admission cũ. Kiểm upload boundary ở đây, xử lý file lớn ở AT-15 | 1–2 |
| AT-04 | Private list/status/read/link/delete | API có xác thực; owner dùng được, actor khác/inactive/anonymous/foreign IDs bị từ chối; cursor/filter/batch bounds, stale revisions và browser mutation security | 2 |
| AT-05 | Processing/readiness đúng loại nội dung | Worker + storage + extractor/index thật; format table accepted/rejected đã chốt, errors/password/malformed; không báo READY trước representation/projection cần thiết | 3 |
| AT-06 | Retry/restart/rediscovery và late claim | PostgreSQL + Redis + worker; mất tín hiệu, redelivery, worker chết sau IO trước publish, lease mất; một current generation, không publish hồi sinh DELETING | 3 |
| AT-07 | Isolation ở projection/query/reader | OpenSearch thật có Source public và private file cùng keyword; broad lexical/vector/hybrid không lấy private, file tool chỉ lấy scope được phép; reindex/reconciliation và stale generation không mở quyền | 3–4 |
| AT-08 | Send identity, edit/regenerate/branch và settings | `ChatPersistenceIntegrationTest`/API; mảng JSONB giữ thứ tự, cùng request cùng files replay, đổi files conflict; EDIT giữ nguyên JSONB message cũ, REGENERATE đúng user files; deleted file descriptor render unavailable; settings/link thay đổi chỉ áp dụng lượt sau | 4 |
| AT-09 | Direct context và đọc tiếp | Native setup/tool boundary rồi live model; plaintext hit không parse lại, miss/empty/raw binary xử lý đúng policy đã chọn; fact ở đầu/giữa/cuối file dài, metadata còn khi bị loại khỏi context, đọc tối đa 16000 ký tự/lần có vị trí/provenance; model thiếu tools có kết quả rõ | 4 |
| AT-10 | Bảng và ảnh thực | Reader + provider binding và live model; sheet/range đúng, formula cached không giả recalculation; request chứa media thật, model thiếu vision được báo rõ, không suy ảnh từ filename | 3–4 |
| AT-11 | Persona/Project và quyền sharing | API/PostgreSQL + browser; custom Persona có files/rỗng không lấy Project files; builtin xử lý theo D5; shared transcript không cấp quyền reader/download; thay revision/race không bỏ kiểm quyền | 4–5 |
| AT-12 | Browser upload/retry/history và continuity | Browser trước với fixtures cho faults, sau với runtime thật; multi-file partial failure giữ draft; recent pagination, upload trước session, session URL promotion không mất files/SSE, EDIT/reload/mobile/keyboard | 5 |
| AT-13 | Delete và reference cleanup | PostgreSQL + MinIO + index + worker; file còn Project/Persona link được báo đang dùng; conversation delete không xóa raw còn dùng; storage delete lỗi retry; historical answer vẫn đọc khi file unavailable | 1–5 |
| AT-14 | Stop/deadline và lỗi provider/tool | Native execution + real HTTP stream; Stop/failure giữ partial outcome đúng, không late completion; read/search dùng cùng budget, không tự hủy processing độc lập | 4–6 |
| AT-15 | Luồng lớn và tài nguyên | File hợp lệ sát 100/250 MiB trong structural bounds, upload → xử lý → hỏi/đọc nguồn; ghi peak API/worker RAM, temp disk, concurrency và timings từng chặng; follow-up READY không parse/embed lại | 6 |
| AT-16 | Release acceptance và regression | SHA/digests/migration/settings thật; authenticated browser upload → READY → hỏi có nguồn → follow-up/reload → cleanup; Source Search/Google admission không bị đổi ngoài scope | 6–7 |

Các scenario bao quát cùng invariant được kiểm ở boundary khác nhau chỉ khi phát hiện regression khác nhau: ví dụ AT-03 kiểm admission HTTP/storage, AT-15 kiểm khả năng xử lý file lớn thực. Không nhân đôi tests chỉ để tick nhiều ô.

## Corpus và cách đo

| Nhóm dữ liệu kiểm | Cần chứng minh |
| --- | --- |
| Text tiếng Việt/Anh nhỏ và dài | Direct context, đọc tiếp, Unicode offsets/continuation và nguồn đúng ở giữa/cuối; filename khác nhau không thay thế quyền |
| PDF text, PDF scan, DOCX, PPTX | Parser/OCR và provenance/page, dùng đúng Docling endpoint/image/auth; tài liệu hỏng/password báo đúng lý do |
| CSV/TSV, XLSX/XLSM theo format table cuối cùng | Metadata nhiều sheet/range, cached formula, limits cell/ZIP; không chạy macro hoặc tính toàn bảng qua sample |
| PNG/JPEG/WebP | Nội dung hình thực, orientation/dimensions/decompression limits và multimodal request; model capability mismatch |
| EML/EPUB/HTML/text extensions và ảnh nhúng | Theo D4 sau khi khép format/behavior parity; không đánh dấu pass hoặc excluded từ khi mới đọc tên extension |
| File lớn, cap+1 và input vượt structural limit | Tách byte admission khỏi parse success; fixture phải thật sự hợp lệ, không dùng zero padding/PUT-only để chứng minh processing |

Ghi environment/CPU/RAM/disk budget, worker concurrency, model/provider, extractor image, loại/kích thước/cấu trúc fixture và số lượt đo. Không ghi bytes private, credentials, signed URLs hoặc raw model payload có dữ liệu người dùng. Chỉ dùng dữ liệu tổng hợp hoặc corpus được phép.

Mỗi đo latency tách upload, queue, extraction/OCR, embedding/index, first token và total turn. So sánh lần đầu với hỏi tiếp trên cùng raw/generation; kiểm trace/job count cùng với thời gian để chứng minh không reprocess. Không công bố p95 từ mẫu quá ít hoặc đặt pass/fail theo SLO chưa được thống nhất.

## Mẫu ghi bằng chứng implementation

Khi thực hiện, thay mẫu bằng kết quả thật; không điền PASS trước.

| Trường | Nội dung cần ghi |
| --- | --- |
| AT / commit / thời điểm | ID scenario, exact HEAD hoặc deployed release, thời điểm |
| Boundary / môi trường | Unit, PostgreSQL, API HTTP, browser fixture, provider thật hoặc staging; dependencies/versions cần thiết |
| Thao tác | Command/test name hoặc chuỗi thao tác có thể tái hiện |
| Quan sát | Expected/actual, assertion quan trọng, kết quả passed/failed/skipped/unverified |
| Dọn dữ liệu | Tài nguyên test đã tạo, owner cleanup, kết quả kiểm lại; không tác động dữ liệu có sẵn |
| Giới hạn | Phần chưa kiểm và lý do; fixture/provider/runtime evidence phân biệt rõ |

## Kiểm tài liệu của lần lập kế hoạch

- `git diff --check`: đạt, không có whitespace errors trong diff.
- Kiểm đường dẫn của 10 liên kết local trong ba tài liệu: đều tồn tại. Không coi đây là kiểm mạng hoặc kiểm mọi anchor của tài liệu ngoài increment.
- Kiểm code fences cân bằng và trailing whitespace trong cả ba file, gồm `verification.md` mới: đạt.
- Ma trận có đủ 16 ID AT-01–AT-16, không trùng ID. Đã rà scope/phase/dependency và đối chiếu điểm vào với source đã đọc.
- `git status --short` xác nhận phần sửa của lượt này giới hạn ở `design.md`, `plan.md`, `verification.md` trong increment; các thay đổi OCR đang có được giữ nguyên.

Việc kiểm Markdown không thay thế bất kỳ AT runtime nào ở trên. Chưa tạo commit/PR hoặc ghi thay đổi vào Linear.
