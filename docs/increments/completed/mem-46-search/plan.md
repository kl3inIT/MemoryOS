# MEM-46 — Kế hoạch Search làm trước

[Design](design.md) là scope hiện hành; [MEM-11](../mem-11-production-chat/plan.md) làm Chat sau. Pipeline Search đã deploy; MEM-46 đang In Progress sau khi PR #83 được mở và phần hoàn thiện UI/UX, nghiệm thu cùng Dashboards được giao cho `phamnhatanh811` trên nhánh Linear hiện hành. Không merge `main` trước quyết định của người dùng. i18n thuộc MEM-74. Permission-specific design để rà riêng. [Verification](verification.md) ghi rõ phần đã chạy và phần còn phụ thuộc runtime/model.

Review PR #79 bổ sung trong cùng scope: validation null filters/HTTPS credentials, không che programming errors, response schemas required, TLS từ NPM đến Dashboards, backend saved-object read-only và certificate renewal có rollback. Kiểm unit/protocol/OpenAPI, operator tests với certificate thật được tạo trong test, `clean check`, frontend, CI đúng head và staging sau sửa; giữ V17 đã áp dụng và không merge main.

## 1. Chốt model/index và quality contract

- [x] Đọc current Document/Ingestion/Connector specs và tests; xác nhận artifact-read contract, current chunks và worker ownership.
- [x] Resolve Spring AI 2.0.1 với Boot 4.1.1/OTLP; OpenAI `text-embedding-3-large`, 3072 dimensions, API key từ Infisical; pin input convention/tokenizer và batch/time/concurrency limits, ghi rõ giới hạn provider model alias.
- [ ] Chọn OpenSearch server/client/transport; kiểm TLS/Infisical, JSON-P/Jackson và dependency convergence bằng integration smoke.
- [x] Chốt native OpenSearch hybrid theo reference implementation: BM25 title/content + vector k-NN, min_max + arithmetic_mean, weights 0.5/0.5. Không triển khai A/B thuật toán hoặc RRF trong một query.
- [x] Tái hiện top-K semantic false positives bằng exact/semantic/nonsense queries trên corpus local; đối chiếu reference implementation, OpenSearch, Typesense, Meilisearch và Elastic. Chốt raw semantic radial threshold thay vì combined min-max threshold hoặc browser filtering.
- [x] Thay semantic top-K bằng configurable radial `min_score=0.70`, giữ `candidate-limit` làm `ef_search`/response budget; validate cấu hình và chứng minh bằng unit + real-OpenSearch integration tests.
- [ ] Corpus tiếng Việt/mã định danh/bảng, exact-ID/semantic/table quality assertions và latency evidence cho duy nhất đường hybrid đã chọn.

## 2. JSON -> chunks -> index thật

- [x] Bounded artifact read/checksum/reader protection; heading-aware prose, row/header-aware tables, source cell values/true provenance; oversized span, merged header, repeated row-header and wide-table golden cases.
- [x] Current chunk persistence và transactional publication + durable intent; deterministic generation/input identity, reader/cleanup protection, không version-history ledger.
- [x] Embedding response-bearing batches: count/order/index association/dimension/finite-value/model checks; record synthetic exact inputs, không inject metadata ngoài intended text.
- [x] Native bulk precomputed vectors chỉ lưu OpenSearch, không embed lần hai. Commit intent trước IO, fence late writes tại index/generation, kiểm per-item success và searchable completeness trước PostgreSQL completion.
- [ ] Prove retry/cancel/restart/duplicate/stale claims/partial bulk/uncertain writes; reconcile surviving output hoặc regenerate batch thiếu. Preserve real worker relay/claim/renewal/ack semantics.
- [ ] Real replace/delete/cleanup/reconciliation; truthful extraction/chunk/search readiness. Model/input change re-embed; metadata-only change không tự re-embed.

## 3. Search API/UI

- [x] Ghép retrieved chunks liên tiếp theo reference implementation trước giới hạn ba sections/document; giữ ranking, ordinal range, best-match anchor và từng provenance. Gaps/document/generation khác nhau không ghép; không fetch neighbors.
- [x] Đồng bộ Search API/OpenAPI/Hey API và UI sections; preview vẫn đọc chunks hiện tại. Kiểm ranking/content order, giới hạn sau merge, duplicate/obsolete hits, nguồn từng chunk và browser mở từng section.

- [x] Direct-query hybrid retrieval: query embedding đúng model/index space, BM25 title/content và vector candidates, chosen fusion, deterministic ties/dedup/current result identity.
- [ ] Bounded query/filters/result count/pagination và source metadata; document grouping có supporting snippets và locators thật. Không trả raw SDK response. UI behavior đã kiểm; corpus/latency và deployed API acceptance vẫn mở.
- [x] Search workspace riêng theo reference implementation: empty state căn giữa brand/câu dẫn/query, FLIP dock transition có reduced-motion fallback, compact Radix time/file-type menus, rồi result feed và desktop file-type facets; loading/empty/unavailable/retry responses, escaped snippets/highlights, filter/pagination và source opening. Khi pending, composer chỉ giữ spinner cố định, không có Cancel. Default Search không gọi generative ChatModel.
- [x] Polish visual hierarchy theo content-first enterprise Search: copy/empty state phân biệt Search với Chat, toolbar có label `Filters`, result card theo title → friendly metadata → context, refined desktop facet card và các state empty/error đồng nhất; giữ focus ring, keyboard flow và reduced-motion.
- [x] Hoàn thiện Search composer không có `+`/attachment/Source action: voice input dùng browser Web Speech API, nối transcript vào query mà không tự submit hoặc gửi audio lên server; có recording/unsupported/permission/error feedback, cleanup và accessible pressed/disabled state. Kính lúp là submit Search duy nhất; attachment action để trong phạm vi Chat.
- [x] Thay full section content trong result card bằng snippet ngắn quanh exact full-query/token match; highlight literal/case-insensitive an toàn bằng React nodes, không dùng `dangerouslySetInnerHTML` và không tạo highlight giả cho semantic-only hit.
- [x] Chuẩn hóa metadata hiển thị: friendly file type, bỏ MIME thô và exact generated `Title: <filename>` prefix; giảm thuật ngữ kỹ thuật `Passage`, không suy diễn source/time ngoài response.
- [x] Chuyển current passage reader thành responsive Radix Dialog; focus trap/visible focus, Escape/overlay close, explicit focus return với search-input fallback, giữ list scroll và chuyển đúng section/matching ordinal.
- [x] Giữ preview là faithful plain text khi API chưa có block kind/heading hierarchy/table cells; không dựng bảng/list từ whitespace. Ghi rõ contract gap nếu structured renderer được yêu cầu sau này.
- [x] Rà apply/reset filters, loading/cancel/retry/empty, paging, result/section switching, keyboard và mobile overflow; giới hạn `aria-live` ở status text ngắn.
- [x] Search API contract qua backend OpenAPI/Hey API và browser thật; Chat không được hiển thị như tính năng đã chạy trước MEM-11.
- [ ] Golden search cases: exact IDs/abbreviations, Vietnamese paraphrases, multiple matching chunks, table headers, no match, changed artifact và query/model/index mismatch/outage.
- [ ] Chạy lại authenticated local API acceptance ở ngưỡng `0.70`: ba exact unique phrases chỉ trả document đúng, semantic paraphrase giữ document liên quan và nonsense query trả empty; không suy rộng corpus ba tài liệu thành production quality benchmark.

## 4. Recovery và nghiệm thu riêng Search

- [x] Tạo PR, một lần trigger/triage CodeRabbit và CI theo đúng head; giữ review history, không dismiss hoặc override manual-review requirement.
- [ ] Deploy PR SHA lên staging theo release path được duyệt, chưa merge main.
- [ ] Provision OpenSearch staging TLS/service role/volume, cấu hình Infisical và CA mount; backup trước V17, rollout API/worker/web và kiểm SHA/health/Search runtime. Credential embedding hợp lệ là điều kiện để nghiệm thu Search chạy thật.

- [ ] Versioned physical indices/mappings/aliases; readable-index reuse, snapshot restore/catch-up và full index+snapshot-loss regeneration. Verify completeness/current generation trước cutover/cleanup.
- [ ] Đo corpus/index/replica/snapshot bytes, CPU regeneration, RPO/RTO, refresh/bulk/disk/retention; production snapshot/restore drill và rollback thực.
- [ ] FILE -> worker -> approved model -> OpenSearch -> API -> browser/source navigation; native Google fixture/runtime acceptance khi provider dependency đã sẵn sàng, không âm thầm bỏ scope này.
- [ ] Preserve existing identity/source lifecycle contracts trong integration; detailed permission expansion tách khỏi phần thiết kế chức năng đang chốt. Không tạo bypass runtime để chạy demo.
- [ ] Kiểm quality thresholds, concurrency/latency/cost và safe logs/metrics; search-ready/index success không thay quality evidence.
- [ ] Khi có code: applicable IDE static gates, checked-in Gradle `clean check`, frontend/OpenAPI/browser, CI/review và exact-SHA runtime evidence. Consolidate architecture/spec/test/runbooks trong substantive change.
- [ ] Đóng MEM-46 khi Search đạt acceptance riêng; không chờ LLM query plan/context expansion/conversation/stream của MEM-11. Bàn giao actual search contract và evaluation/recovery evidence cho Chat.

## Verification hiện hành

- [x] Sửa diagnostics Java/configuration; chuẩn hóa imports, shared config metadata; chuyển observability resources vào `core`. Shared YAML import diagnostics được đối chiếu với lỗi IntelliJ IDEA-308405 và JAR/runtime evidence; giữ conventional Spring Boot launcher/Testcontainers lifecycle, không suppress inspections.
- [x] Chuyển chunk bulk persistence sang `JdbcClient`; kiểm multi-statement publication và rollback khi lô sau thất bại trên PostgreSQL thật.
- [x] Chạy lại inspections cho toàn bộ file thay đổi, compile/build và behavioral checks liên quan; cập nhật evidence và phân biệt rõ ngoại lệ IDE đã xác minh.

`clean check` toàn repository đã qua; frontend `pnpm check` (52 unit tests), 17 browser tests và real PostgreSQL/OpenSearch/Spring AI HTTP fixtures đã qua. Xem [verification](verification.md) để biết giới hạn evidence. Các checkbox acceptance còn mở được giữ nguyên: synthetic embeddings không thay thế approved-model quality, browser mock không thay thế full deployed flow, passage preview không thay thế original/native source opening, và chưa có production snapshot/cutover/capacity drill.

- [ ] Verify the deployed Dashboards custom Nginx host, normal Keycloak SSO and Certbot renewal. Preserve unrelated NPM configuration/cron and existing user roles.
- [x] Implement an idempotent global-tenant index pattern/saved view for `memoryos-chunks*` through the supported Saved Objects API; keep bootstrap credentials in a mounted curl config and cover create/no-op/drift/fail-closed behavior with operator tests.
- [ ] Run the operator provisioner on staging; verify Discover opens the four sample chunks while `memoryos-inspector` remains read-only and cannot create or mutate saved objects/documents.

- [ ] Scope alias inspection to its actual read-alias target and permit service bulk coordination; preserve the multi-index alias guard, index-prefix and human-role boundaries. Validate ordinary authenticated sample FILE ingestion through Search and passage reads on the secured staging runtime, and retain the sample source for feature review in MEM-46.
