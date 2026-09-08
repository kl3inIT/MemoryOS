# MEM-11 — Kế hoạch Chat sau Search

Todo, blocked by MEM-46. [Design](design.md) giữ current scope; requirements multi-query/weighted RRF/context được chuyển từ MEM-71/72, không để rơi phần đã nhận. Permission chi tiết để rà ở integration sau.

## 1. Tiêu thụ Search đã có

- [ ] Đọc actual MEM-46 contract/evaluation/recovery evidence; dùng shared retrieval/current evidence identities, không clone native index/backend.
- [ ] Resolve approved ChatModel/gateway/structured output/stream protocol từ MEM-66, explicit role clients và shared timeout/retry/cost budgets.
- [ ] Define actual bounded context-read consumer/contracts khi Chat cần neighbor reads; không đưa speculative surfaces vào Search trước.

## 2. Multi-query và context

- [ ] Follow-up query rewrite từ history, semantic/keyword/subqueries + original; schema/domain validation, dedup/fan-out/concurrency limits và original-query fallback.
- [ ] Shared hybrid retrieval cho từng query; weighted RRF với rank/k/weights/ties/duplicate semantics đã đo. Partial branches, empty/no-evidence và cancellation có outcome rõ.
- [ ] Merge adjacent/overlap, structured LLM selection IDs/ranges, bounded neighbor reads/extent decisions, final token/citation budgets và preserved provenance.
- [ ] Test valid JSON nhưng IDs/ranges ngoài candidates, duplicates, malformed/empty outputs, exhausted retry/timeout, changed artifact và no-evidence; không coi schema là validation đủ.
- [ ] So sánh direct-query với multi-query/RRF, fixed context với selection/expansion trên corpus tiếng Việt; đo retrieval quality, answer groundedness/citation support và calls/tokens/latency/cost.

## 3. Answer và conversation production

- [ ] PostgreSQL conversation/message/generation/citation persistence: Tenant/Actor ownership, duplicate-send key, linked retries, retention/delete, checkpoints và abandoned-run convergence.
- [ ] Evidence-only answer prompt và server citation map; typed citation validation, real source navigation/stale artifact behavior; insufficient evidence không bịa câu trả lời/nguồn.
- [ ] API/proxy/browser streaming: progress/deltas, heartbeat/backpressure/timeouts, stop/disconnect, partial/failed/canceled outcomes, final commit trước completed.
- [ ] Real UI conversation list/history/composer, retry/reload/concurrent sends; refresh đọc durable state, không tự generate lại hoặc phát duplicate completions.
- [ ] Search-to-Chat handoff và mode switching dùng actual shared retrieval/IDs; model context window tách khỏi full transcript.
- [ ] Integrate MEM-25 audit và safe observations; content capture tắt. Rà history/source permission semantics ở integration riêng trên nền IAM/source hiện có.

## 4. Nghiệm thu Chat riêng

- [ ] Real approved model + current indexed FILE/native examples + API/browser; answer có supporting passages và đúng citation.
- [ ] PostgreSQL/index/model fault, concurrent request/crash/retry/timeout/stop/reload; prompt-injection evidence không mở tools hoặc đổi contract.
- [ ] Quality, concurrency/p50/p95/cost, chat backup/restore, monitoring/runbooks và rollback được đo/test; native input reuse scope từ Search.
- [ ] Khi có code: IDE/static, Gradle wrapper `clean check`, relevant frontend/OpenAPI/browser/CI/review; exact-SHA deployment và production evidence.
- [ ] Consolidate implemented docs/spec/test/runbooks rồi đóng MEM-11 theo gate Chat; completion của MEM-46 Search không bị chờ các bước này.

Lần lập kế hoạch chỉ kiểm Markdown/Mermaid và Linear scope; không có runtime verification mới.
