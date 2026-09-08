# MEM-46 — Kế hoạch Search làm trước

[Design](design.md) là scope hiện hành; [MEM-11](../mem-11-production-chat/plan.md) làm Chat sau. Implementation được duyệt ngày 2026-09-08; pipeline, API và UI đã có code và kiểm thử, production acceptance còn mở. Permission-specific design để rà riêng, theo yêu cầu mới. [Verification](verification.md) ghi rõ phần đã chạy và phần còn phụ thuộc runtime/model.

Review PR #79 bổ sung trong cùng scope: validation null filters/HTTPS credentials, không che programming errors, response schemas required, TLS từ NPM đến Dashboards, backend saved-object read-only và certificate renewal có rollback. Kiểm unit/protocol/OpenAPI, operator tests với certificate thật được tạo trong test, `clean check`, frontend, CI đúng head và staging sau sửa; giữ V17 đã áp dụng và không merge main.

## 1. Chốt model/index và quality contract

- [x] Đọc current Document/Ingestion/Connector specs và tests; xác nhận artifact-read contract, current chunks và worker ownership.
- [x] Resolve Spring AI 2.0.1 với Boot 4.1.1/OTLP; OpenAI `text-embedding-3-large`, 3072 dimensions, API key từ Infisical; pin input convention/tokenizer và batch/time/concurrency limits, ghi rõ giới hạn provider model alias.
- [ ] Chọn OpenSearch server/client/transport; kiểm TLS/Infisical, JSON-P/Jackson và dependency convergence bằng integration smoke.
- [x] Chốt native OpenSearch hybrid theo Onyx: BM25 title/content + vector k-NN, min_max + arithmetic_mean, weights 0.5/0.5. Không triển khai A/B thuật toán hoặc RRF trong một query.
- [ ] Corpus tiếng Việt/mã định danh/bảng, exact-ID/semantic/table quality assertions và latency evidence cho duy nhất đường hybrid đã chọn.

## 2. JSON -> chunks -> index thật

- [ ] Bounded artifact read/checksum/reader protection; heading-aware prose, row/header-aware tables, typed values/true provenance; oversized/merged/repeated/wide-table golden cases.
- [x] Current chunk persistence và transactional publication + durable intent; deterministic generation/input identity, reader/cleanup protection, không version-history ledger.
- [x] Embedding response-bearing batches: count/order/index association/dimension/finite-value/model checks; record synthetic exact inputs, không inject metadata ngoài intended text.
- [x] Native bulk precomputed vectors chỉ lưu OpenSearch, không embed lần hai. Commit intent trước IO, fence late writes tại index/generation, kiểm per-item success và searchable completeness trước PostgreSQL completion.
- [ ] Prove retry/cancel/restart/duplicate/stale claims/partial bulk/uncertain writes; reconcile surviving output hoặc regenerate batch thiếu. Preserve real worker relay/claim/renewal/ack semantics.
- [ ] Real replace/delete/cleanup/reconciliation; truthful extraction/chunk/search readiness. Model/input change re-embed; metadata-only change không tự re-embed.

## 3. Search API/UI

- [x] Ghép retrieved chunks liên tiếp theo Onyx trước giới hạn ba sections/document; giữ ranking, ordinal range, best-match anchor và từng provenance. Gaps/document/generation khác nhau không ghép; không fetch neighbors.
- [x] Đồng bộ Search API/OpenAPI/Hey API và UI sections; preview vẫn đọc chunks hiện tại. Kiểm ranking/content order, giới hạn sau merge, duplicate/obsolete hits, nguồn từng chunk và browser mở từng section.

- [x] Direct-query hybrid retrieval: query embedding đúng model/index space, BM25 title/content và vector candidates, chosen fusion, deterministic ties/dedup/current result identity.
- [ ] Bounded query/filters/result count/pagination và source metadata; document grouping có supporting snippets và locators thật. Không trả raw SDK response.
- [ ] Search page/navigation, loading/empty/unavailable/cancel/out-of-order responses; escaped snippets/highlights, filter/pagination và source opening. Default Search không gọi generative ChatModel.
- [x] Search API contract qua backend OpenAPI/Hey API và browser thật; Chat không được hiển thị như tính năng đã chạy trước MEM-11.
- [ ] Golden search cases: exact IDs/abbreviations, Vietnamese paraphrases, multiple matching chunks, table headers, no match, changed artifact và query/model/index mismatch/outage.

## 4. Recovery và nghiệm thu riêng Search

- [ ] Tạo PR, một lần trigger/triage CodeRabbit và CI theo đúng head; deploy PR SHA lên staging, chưa merge main.
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
