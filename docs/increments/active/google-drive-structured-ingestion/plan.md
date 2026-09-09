# FILE–Drive integration plan

## Publication and main refresh — 2026-09-09

The user approved scoped commits, merging current `origin/main` into this feature branch, verification, push and Linear linkage. This is not authorization to open or merge a PR, deploy, or mark MEM-76 Done.

- [x] Separate source-page contracts, shared Users/Groups pagination, Source presentation/pagination, and stale-Source synchronization guards into conventional commits; preserve existing Docling commit `819c54e`.
- [ ] Merge main `287ca9c`, retaining Chat/JIT and Drive behavior, regenerating the combined API/client, and reconciling migration versions.
- [ ] Verify the combined tree with fresh build outputs and disposable test databases, without modifying the running review database or its Flyway history.
- [ ] Push the feature branch, prove the remote SHA, and link the commit chain and integration evidence to MEM-76.

Main V1–V20 is authoritative. Branch-only Drive V18–V29 will move to V21–V32 with unchanged SQL bodies. Existing `memoryos_main_review` and `memoryos_drive_review` databases retain their historical layouts and are not upgrade targets for this integration; do not start the new schema layout against either database. Publication and repository integration do not constitute a runtime database cutover.

## Source detail consistency — 2026-09-09

Pre-publication verification: all requested UI and counted-page changes were implemented and verified on the integrated branch. Focused backend tests, the serial repository gate, `pnpm check` (70 unit tests), all 39 selected browser tests and final read-only browser smoke passed. See the [Source detail consistency evidence](../../../tests/connector.md#source-detail-consistency--2026-09-09). These results precede the subsequent main refresh above; they are not proof of that newer integrated tree. The increment remains active.

- [x] Files: required authorized `totalItems`, shared Users/Groups-style pagination and current/total pages.
- [x] Indexing histories: required filtered Source-run `totalItems`; shared pagination for Source runs and existing counted file attempts.
- [x] Source header: larger Drive logo without changing other provider marks globally.
- [x] Major section headings: consistent icon-backed presentation, including Upload content, Synchronization, Selected content, Files and indexing histories.
- [x] Credentials: full section heading with key icon; remove its redundant Drive logo and preserve connection actions.
- [x] Group associations: match the upper sections' surfaces, borders and controls without changing grants or dirty-state behavior.
- [x] Verification: compile/test authorized totals, regenerate OpenAPI/client, pass frontend and selected browser gates, visually inspect desktop/mobile/light/dark Source surfaces, and consolidate architecture/spec/test evidence. No IDE was connected, so no IDE-clean claim. Read-only Chromium smoke used controlled HTTP responses and made no user-data writes; it is not live Google or Orca embedded-browser acceptance.

Main owns pagination wiring, generated artifacts, documentation and final verification. Independent backend-total and section-style owners skip build/lint/tests/formatting while edits are concurrent. No reindex, extraction retry or alteration of user Sources/credentials is authorized by this UI task.


## Isolated main integration — 2026-09-09

Status: the combined backend, generated-contract, frontend and browser gates pass; isolated FILE/Google ingestion, Search eligibility, UI and lifecycle cleanup were exercised successfully at the boundaries below. Conflict resolution is complete; this is local integration, not a PR merge or deployment. This section supersedes earlier branch-only authorization, migration-number and no-local-merge instructions without widening the Google ingestion delivery.

- The user explicitly chose the separate `C:/Users/adim/orca/workspaces/MemoryOS/google-drive-main-integration` worktree and `nhuxuanviet/google-drive-main-integration` branch. Checkpoint `290357aaf617a8cd607f2d213fe9a8993be97b51` (`290357a`) preserves the complete Google branch and 145 local files (about 14,000 added lines). Merge input `origin/main`/`MERGE_HEAD` is `fb835f9`.
- The original `google-drive-structured-ingestion` checkout, running services, user-selected roots/approvals, credential material and review database remain untouched. Do not migrate, reset, restore or write the existing review database; any final runtime proof needs an isolated disposable database/runtime, never a reused user dataset. No shared deployment or Google content mutation is authorized by this integration section.
- Recovery exception: the user subsequently authorized restarting Docker Desktop after its backend lost the route to the WSL VM. Normal Desktop restart timed out; terminating only `docker-desktop` and starting Desktop restored the engine without unregistering a distro or resetting volumes. Restore MemoryOS dependencies with their existing configuration/volumes. The user explicitly shut down Oracle during recovery; leave that unrelated container stopped.
- Preserve main's six current capabilities: `iam`, `connector`, `document`, `ingestion`, `objectstorage`, and `retrieval`. Public `ActorId`/`TenantId` and authorization belong to `io.memoryos.iam`. Google credential and configuration/selection/discovery/sync/schedule management requires global `SOURCES_MANAGE`; generic Source/item/run/operation reads retain global/scoped `SOURCES_READ`. Credentials remain Tenant-owned and reusable, not newly Actor-owned; OAuth consent remains bound to its initiating Actor. Google-created Sources attach the protected Admin Source association and remain RESTRICTED. Source management never grants Google document-read access.
- Preserve branch credential reuse, General/Specific selection, linked discovery and reversible explicit approvals, lazy selection tree, durable asynchronous verification, storage-backed acquisition/extraction, independent schedules, exact run/item histories and Files pagination. Source detail remains direct `SourceSummary`; item reads remain bounded pages. Each item retains `latestAttempt` and `lastIndexedAt`, adds main's separate `searchStatus`, and does not restore `latestOperationId`.
- Keep main's JPA IAM/Group scope, current chunks, native OpenSearch normalized hybrid Search, staging, CI/testing and selected dependency upgrades. The worker workload union is `INGESTION`, `CLEANUP`, `SEARCH`, `SOURCE_SYNC`, and `GOOGLE_DRIVE_SELECTION_VALIDATION`. Search projection consumes current extraction artifacts without changing restricted Google reader eligibility. Search and Chat remain separate increments.
- Retain main V1–V17 unchanged. Rename branch V13–V24 to V18–V29 (+5) while retaining every descriptive suffix. Canonical architecture/specs use integrated numbers. Historical evidence below retains original branch numbers and commands: branch V22/V23/V24 means integrated V27/V28/V29; do not replay those old migration commands against user data.

Integration sequence: reconcile capability/API/worker/web contracts and migrations; regenerate OpenAPI and the browser client from the integrated API; then let Main run the final consolidated gates and isolated runtime/UI proof. No builds, tests, linters or formatters run while concurrent file owners are editing. Main alone stages, finalizes the merge and performs final verification; there is no new push/PR/deployment authorization in this record.

| Required integrated gate | Required evidence |
| --- | --- |
| Source/authority and migration integration | No unresolved markers or obsolete imports; main IAM/Group rules retained; fresh V1–V29 plus main-V17-to-integrated upgrade exercised only on disposable data; migration preservation and restrictive ownership verified |
| Backend and generated contract | Available semantic inspection, compile and complete repository gate; API-generated OpenAPI and regenerated client with drift checks |
| Browser contracts | Frontend gate and existing browser scenarios; actual desktop/mobile/light/dark Source tree, explicit approval/deselect, pending receipt recovery, bounded Files pages and separate Search status |
| Runtime behavior | Isolated real PostgreSQL/Redis/storage/API/worker proof for FILE lifecycle, Google selection → acquisition → owned indexing/history, cleanup/fencing and Search projection/read eligibility; distinguish fixtures from live provider/model proof |
| Release state | Record exact integrated SHA, command/results and remaining provider/model/Tasco gaps; keep this increment active until PR merge and do not mark wider MEM-10/MEM-60 ACL delivery complete |

### Current integrated evidence

- Backend and migrations: the complete repository gate passes; exact command, module counts, cache/warning boundaries and disposable V17 → V29 preservation evidence are canonical in the [Ingestion matrix](../../../tests/ingestion.md).
- Frontend and browser: the final frontend gate and all 44 Chromium scenarios pass; exact command, retained paging regression and inspected light desktop/mobile tree captures are canonical in the [Connector matrix](../../../tests/connector.md). Routed browser evidence is separate from the actual service-backed runtime below.
- The isolated runtime used database `memoryos_main_integration` and Tenant `2ad8bbda-b628-4643-bb2b-7383766ae4fe`, separate Redis on 16381, API/worker on 18082/18083, and its own OpenSearch index prefix. Normal Keycloak authorization-code/PKCE login established a genuine Spring owner session. No fake session, bearer-audience bypass or out-of-band reader membership was used; the second temporary Keycloak user was not admitted to this Tenant.
- Actual FILE acceptance passed: Source `07dcaf06-8982-4d58-80c7-48d9670f9212`, signed MinIO PUT, finalize, worker extraction and current indexing. Item `0bdec09a-87ac-4d3d-9e54-730b0b5f9a3c` reached `INDEXED`; attempt `3a6c004c-82f1-4760-9566-bcf9827ca9b3` succeeded at `2026-09-09T09:53:16.816650Z` with no error and a populated `lastIndexedAt`. Its separate Search projection reached `READY` using the configured real embedding service and OpenSearch. Actual `POST /api/search` and generation-pinned document read returned 200 with the original UTF-8/Vietnamese content of Document `80784938-3d04-49e6-accd-5c28183c8b0a`.
- Controlled Google OAuth connected reusable Tenant credential `70606e4a-bc01-426c-8360-e8fed440285d` through the actual callback. Initial operation `e8652caf-4bc8-44af-b9c8-2b6a5c148c76` correctly rejected a cycle in the throwaway fixture without activating a Source. After correcting its My Drive ancestry and normally reauthorizing the credential, receipt `24d623e6-6ae3-4506-b5a0-7b17fea04b15` activated Source `60c1a056-68e7-4067-9e88-990ef51ff767`. The API was stopped while the real worker validated, acquired and indexed the native Docs and TXT inputs. Run `75bf9c88-348b-49c0-93c4-da58ea329f92` completed acquisition and indexing successfully: scanned/acquired/published 2, unchanged 0, failures 0, and no pending indexing; actual processing ran `10:42:46.012164Z` → `10:42:55.423683Z`. This is a signed-token/Drive/Docs HTTP fixture, not live Google or Tasco data.
- Google remained RESTRICTED with the Admin Source association. Even after the native Google Document had a searchable generation, owner Search returned only the FILE result; its generation-pinned Google document read returned 404 `SEARCH_DOCUMENT_UNAVAILABLE`, while the FILE read returned 200. Credential revocation returned 204, advanced revision 2 → 3, invalidated discovery revision 1 → 2 and made the retained tree root non-expandable. A later child request was interrupted by Docker failure and is not claimed as a successful authorization check.
- Actual desktop/mobile/light/dark UI and run-history evidence is canonical in the [Connector matrix](../../../tests/connector.md). Discovery recorded one linked candidate and a safe `SOURCE_GOOGLE_NOT_FOUND` warning because the controlled fixture lacked its outside-parent metadata; no live approval was performed. Routed tests, not this limited provider fixture, establish approval/deselect and receipt-recovery coverage.
- Cleanup used the real `DELETE_SOURCE` runtime path: operations `5321b5e8-bb74-4a82-a6d6-8db332c2f4ae` and `ed26fcb7-2f46-4228-982b-d785a6b05fb1` were admitted by the API and executed by the worker with the API stopped. Both Sources disappeared; Documents, stored objects and extraction artifacts each reached 0, and the isolated OpenSearch index returned count 0. Both temporary Keycloak users were deleted and verified absent. The runtime database was dropped; the SQL-upgrade database was already absent, and no `memoryos_main_%` database remained. Owned Redis, OpenSearch project/volume, API/worker, preview, provider fixture and headless browser were stopped/removed; only ignored sanitized screenshots are retained.
- Docker Desktop repeatedly lost the backend route (`192.168.65.7:2376: no route to host`); Docker CLI and in-guest WSL diagnostics timed out. A host probe after stopping the integration API/worker found 597 MB available physical memory and approximately 37.6 GB committed against a 41.7 GB limit: memory pressure, not a proven OOM root cause. Authorized Docker-only WSL termination and forced Desktop restart restored access; API and worker smoke phases were then run separately to reduce the test footprint. The failure recurred during acceptance, so no permanent host fix is claimed. After retiring the isolated runtime, the original API and worker both returned readiness HTTP 200/`UP`; original Redis/Docling readiness was also observed. Oracle remains stopped by user choice, without an Oracle restart or configuration change.

No IDE-clean result, capacity/SLO claim, live Google/Tasco acceptance, staging rollout or PR merge is asserted here. All earlier successful commands and screenshots in the sections below are **pre-integration branch evidence**, including subsequent dated UI revisions; they must not be reused as proof of this merged tree.

## Wider upstream delivery boundaries retained

The `fb835f9` planning notes tracked MEM-9/MEM-10/MEM-63/MEM-76 with `nhuxuanviet27102004` under MEM-60. Their unchecked work packages described wider integration and acceptance, not absence of the implementation preserved at `290357a`. The user-approved ingestion-only slice remains narrower than full MEM-10/MEM-60: Google ACL collection/enforcement, verified reader binding and authorized Google document reads are not implemented. Do not infer provider principal identity from email or equate a Drive permission ID with an OIDC subject. Future USER/DOMAIN/ANYONE support requires explicit supported-principal evidence; unresolved groups, unbound principals and incomplete/stale ACLs deny. Remote revocation freshness and permission-only updates without reparsing remain separately designed acceptance, not promises of instantaneous revocation.

Retain the full format scope: native Sheets and Docs snapshots, Slides export to PPTX, downloaded PDF/DOCX/PPTX through Docling, bounded Java XLSX/CSV readers, and the existing UTF-8 TXT/Markdown route. Native Workspace content is not an original binary download. Detect mixed provider revisions rather than publishing known inconsistent multi-request snapshots; retain provider identity, acquisition time, input evidence/checksum and provenance. Unsupported objects remain explicit outcomes, never empty successes. General stays within the credential's actual My Drive; Specific supports explicit shared-with-me files/folders and explicit files/folders inside Shared Drives, not account-wide/whole-Shared-Drive discovery. Shortcuts, group-directory expansion, service accounts, write-back, push notifications, image-only input, audio/video and archives are not added.

MEM-61 supplies the current Document/artifact lifecycle. MEM-46 owns bounded current chunk text/provenance in PostgreSQL and embedding vectors only in OpenSearch; former MEM-62/47/48/49/50 are absorbed there. Chat remains MEM-11 (including MEM-71/72). The current extraction schema embeds image data; no separate image-object store, Document-version ledger, parser-profile uniqueness or PostgreSQL vector store is introduced.

Actual Tasco data remains unavailable. Recorded-response fixtures and synthetic provider documents do not establish customer-data acceptance. Wider release evidence still requires cross-format provenance/limits, sanitized real-account acquisition/resync/revoke, allowed/denied/stale ACL cases in their separate delivery, and exact-SHA runtime acceptance. No claim of SDK compatibility, resource sufficiency, OCR quality or production readiness follows from planning or from fixture-only proof. Onyx/OrgMemory references inform lifecycle/checkpoint and parser contracts, not wholesale runtime or authority copying; pinned inspected reference commits remain in the branch evidence.

## Pre-integration branch implementation and evidence

Status: **MEM-76 selection policy, durable asynchronous validation, paged selection/drafts, acquisition → indexing run history and the approved Files pagination extension are implemented and locally verified.** Final uncached backend, frontend and all 39 browser scenarios passed. Root/history capacity, controlled 100k-file no-change traversal and real desktop/mobile/light/dark UI over 100k item rows are consolidated below. Google OAuth/Drive/Docs used controlled fixtures, not live Google; metadata seeding and no-change traversal are not initial acquisition/indexing proof. Private runtime/database and throwaway scaffolds were removed after verification; sanitized evidence/screenshots remain ignored. The increment and broader issues stay active; no push/PR/merge/deploy.

User-approved scope: acquisition, synchronization, extraction and current Document only. No source ACL collection/enforcement, reader identity linking or document-read API/UI. Preserve Tenant/Owner authorization and exclude Drive from FILE PUBLIC. This is not all MEM-10/MEM-60 acceptance. The user authorized selective port, a backed-up fresh local database, isolated local MinIO, reuse of the existing Drive corpus and UI verification in Orca's embedded browser. No PR, push, merge or shared deployment is authorized here.

User-approved UI revision — 2026-09-09: remove the Source Run history component, its list/detail/error requests and dedicated browser scenario. Replace the verbose item-processing list with a compact Onyx-informed Indexing attempts table and server-side page sizes 5 (default), 10, 25 and 50. Keep backend run contracts/data/retention, synchronization controls, selection and Files unchanged. Verify desktop/mobile rendering, page-size changes and cursor reset; the earlier 39-scenario evidence below describes the pre-removal UI.

## Enterprise source operations plan

Issue thực hiện: [MEM-76](https://linear.app/memory-os/issue/MEM-76/mo-rong-google-drive-selection-va-bo-sung-lich-su-djong-bo-co-ket-qua), In Progress, assign Nhữ Xuân Việt (`nhuxuanviet27102004`); parent MEM-60, related MEM-9/MEM-10/MEM-39/MEM-64. Chỉ tạo một issue phát sinh có scope/rationale rõ, không chia nhỏ thành ticket cho từng chỉnh sửa.

**Implementation approved — 2026-09-08; integrated and locally verified.** Selection/backend history/web implementation and additive V22/V23/V24 are present. Final backend/frontend, actual rendered UI, isolated runtime and separate root/history/corpus measurements are consolidated in canonical verification matrices below. No user Source, network or shared deployment changes were made. [Design and trade-offs](design.md#enterprise-source-operations-proposal) remain the implementation boundary; this is not live-provider or broader ACL/Tasco acceptance.

### Kết quả cần đạt

1. Folders/Files phân nhóm trong cùng một selection; thao tác số lượng lớn không làm mất lựa chọn ngoài trang hoặc biến selection đang xác minh thành phạm vi đang chạy.
2. Loại bỏ cap 20 hardcode bằng backend policy và asynchronous durable verification có quota/budget/fencing, không thay bằng unlimited hoặc HTTP timeout lớn hơn.
3. Run history phản ánh đầy đủ acquisition → indexing, thành công không thay đổi, lỗi từng file/toàn run và phục hồi; không nhầm current Source count với số tài liệu của một run.

### Approved implementation sequence and acceptance criteria

| Bước | Thay đổi và phạm vi file | Điều kiện hoàn thành |
| --- | --- | --- |
| 1. Chốt contract và sizing | `DefaultGoogleDriveSourceService`, request/response contracts, `GoogleDriveProvider` budgets, increment design. Chốt policy nguồn duy nhất, operation activation, counter dictionary và API cursor. Benchmark ma trận 20/21/100/1.000 roots, depth/overlap/shared ancestors và approvals; mục tiêu corpus 100.000 file bằng controlled fixtures. | Có số request, thời gian, DB/heap và quota rejection rõ. Chọn quota phát hành từ kết quả đạt, không coi mục tiêu 1.000 là năng lực đã đo. Chốt byte budget độc lập đủ cho max roots và link-length hiện có. |
| 2. Durable schema và query foundation | Migration mới trong `core/.../db/migration`; concrete connector persistence cho proposed selection, run attribution/outcomes và query history. Reuse current sync/index attempts, không thêm generic audit store. Bổ sung composite tenant/source/time/ID indexes, snapshot metadata, retention/compaction và deletion dependencies. | Migration giữ credential/Source/roots/interval/input/current Documents. Legacy facts không có thì nullable/Unknown. Không backfill fabricated counters; live claims và stored-object references không bị retention xóa. |
| 3. Async selection và quota | `DefaultGoogleDriveSourceService`, capability selection operation/repositories, existing relay/lease/worker composition, create/replace request contracts. Admission nhanh → durable candidate → checkpointed provider verification → atomic activation; receipt chống submit trùng, pending cancellation/fencing, limits dùng policy. | Create/Save trả `202` không chờ Google. Reload/restart không mất operation; retries không tạo hai Sources hoặc tăng revision hai lần. Invalid/overlap/quota/permission/revoke/stale candidate giữ nguyên active selection và lịch; không có small-sync/large-async fallback. |
| 4. Run lifecycle, attribution và lỗi | `DefaultConnectorSyncService`, `JdbcSourceSyncRepository`, `JdbcIndexAttemptRepository`, item intake/publication and cleanup paths; error mapping tại provider/storage/extraction boundary. Ghi attribution cùng adoption, chốt counters bằng fenced transitions, giữ acquisition terminal độc lập với run-level indexing. | Zero-change, partial acquisition, storage failure, extraction failure, reused pending attempt, retry/duplicate delivery, supersession và credential loss có kết quả đúng, không double-count hoặc sửa lại completed history. Current run không che Last completed/Last successful. |
| 5. Authorized read API và clean client cutover | `api/.../source`, query repositories, typed selection receipt và dedicated `SourceRunResponse`, `openapi.yml`, generated Hey API client. Policy endpoint; paged selection/provenance; source run list/detail/errors; paged item-attempt history. `/api/sources/{sourceId}/runs` và `/api/sources/{sourceId}/runs/{runId}` là runs; `/index-attempts` vẫn là item-processing history, không alias. Generic operation polling không nhận run-only fields. | Owner/Tenant/CSRF/If-Match giữ nguyên; foreign IDs và cursor không thành oracle. Keyset pages ổn định khi run mới vào; pinned selection revision không bị trộn. Status polling không tải full trees; mọi caller được migrate trong cùng cutover. |
| 6. UI selection và Run history | `google-drive-selection.ts`, `google-drive-links.tsx`, `google-drive-panel.tsx`, `create-google-drive-source-page.tsx`, `source-detail-page.tsx`, `source-errors.ts` và existing shared primitives. Nhóm Folders/Files, provenance labels, tìm/lọc/phân trang, bulk draft, pending-validation receipt, history table/detail/errors và summary trạng thái đúng. | Keyboard/mobile/light/dark dùng được. Draft không mất do paging/polling; Save toàn selection, Cancel không ghi; approved IDs vẫn đồng bộ ở mọi nhánh. Unknown khác 0; loading khác empty; lỗi hiển thị một đúng nơi và không bị run mới xóa. |
| 6a. Files pagination — approved extension | Tách summary khỏi items; `GET /sources/{id}` và FILE create trả `SourceSummary`, reuse `/items` cho keyset `SourceItemPage`, default 25/max 100; V24 page index, generated clean cutover, UI Previous/Next và mutation/polling ownership. | Không tải/join/render toàn corpus cho status hoặc page. Tenant/Owner/cursor giữ đúng; upload/finalize/reindex/remove hoạt động qua page changes. Nghiệm thu real UI với 100.000 seeded rows, không dùng số đo traversal để thay UI. |
| 7. Regression và capacity gates | Retained Google authority/sync/PostgreSQL lifecycle/HTTP tests, focused browser cases, `gradlew.bat clean check`, generated-contract drift và `pnpm check`. Kiểm tra changed IDE-supported files khi JetBrains khả dụng; không nhận IDE-clean nếu tool vắng. Controlled load dùng service/provider boundaries thật với fixture server, không chỉ test parser URLs. | Unique attribution/counters giữ đúng qua restart/Redis replay; không prune incomplete scope; query có bounded rows/heap và không N+1. Mục tiêu history page p95 ≤500ms với 100.000 terminal summaries và page 25/max 100 trên môi trường benchmark được ghi; thời gian Google không được ngụy trang thành SLO đã đạt. |
| 8. Nghiệm thu runtime được cấp quyền | Source mô phỏng riêng qua owner/browser bình thường: mixed folder/file → selection >20 → activation → scheduled/manual sync → index → thay nội dung → no-change → provider/storage failure → recovery. Exercise FILE upload/reindex/history để bảo toàn provider đang có. | Có run IDs, counters, phase errors và ảnh UI desktop/mobile đối chiếu PostgreSQL; storage/network thật phải hoạt động. Giữ Source gốc do user chọn; simulated Google proof không phải dữ liệu Tasco. Không coi các gate cũ là nghiệm thu cho thay đổi mới. |

### Phụ thuộc và ownership

- Main giữ contract, schema/migration numbering, API/client integration và docs. Sau bước 1–2, selection worker và run-attribution có thể làm độc lập trên vùng sở hữu riêng; file enum/composition/schema chung chỉ một integration owner sửa. UI tích hợp trên contract đã chốt, không tự tạo mock-only controls hoặc tự thương lượng contract giữa các worker.
- MEM-9: selection/quotas và UI; MEM-10: sync/history/typed outcomes; MEM-60: nghiệm thu end-to-end. MEM-63 giữ reader contract, chỉ thay đổi nếu regression chứng minh cần; phối hợp vocabulary với MEM-64 nhưng không chuyển durable history sang metrics. Theo chỉ đạo mới ngày 2026-09-08, tạo issue phát sinh riêng liên kết các việc hiện tại, assign Nhữ Xuân Việt và lưu bối cảnh/lý do/alternatives/acceptance để tra cứu; không đóng hoặc chuyển assignee các issue nền.
- Scope/credential authority, publication fences, incomplete-generation pruning và FILE behavior là release blockers. ACL/document-read, account-wide browsing, new provider formats, search và shared deployment không tự được đưa vào vì nhãn enterprise.
- Canonical docs distinguish final local gates and actual UI/runtime proof from controlled root/history/corpus measurements and unverified live Google/production capacity. The approved Files extension and receipt reload/activation are complete at that local boundary. Linear changes use the authorized notification/coordination flow; broader linked issues remain open.

### Rủi ro và điểm duyệt

- Async validation thay đổi create/save từ phản hồi tức thời sang accepted operation; UI phải hiển thị bản đang áp dụng và bản pending riêng. Đổi quota nhưng giữ synchronous Google validation bị loại vì không giải quyết request amplification/timeouts.
- Per-run/per-file attribution and retention are backend contracts, not only UI tables. Defaults are implemented at 90 days for terminal summaries and 14 days for detailed outcomes/errors, with live/current-reference/unresolved-error protection. These are not compliance/audit guarantees; full retention and runtime gates remain separate.
- Phê duyệt plan đồng nghĩa chọn mục tiêu ingest vận hành được và có số đo; không đồng nghĩa mọi người dùng đã được đọc tài liệu. ACL và search readiness là các điều kiện riêng trước khi gọi sản phẩm đầy đủ enterprise-ready.
- The previously observed review-network DNS/storage fault remains outside this delivery; the isolated fixture runtime does not prove that shared endpoint has recovered. No hosts/DNS or shared-runtime changes are part of this verification.

### MEM-76 verification — 2026-09-08

| Evidence field | Current result and boundary |
| --- | --- |
| Implemented contract | V22 selection receipts/intents/checkpoints, V23 exact run attribution/counters/errors and V24 bounded Files-read indexes. Default selection policy remains 1,000 explicit roots, 3,145,728 request bytes and 500 linked approvals; configured bounds are not live Google capacity. |
| Targeted backend | Selection/history/lifecycle/coordinator/authorized HTTP checks passed. Post-pagination lifecycle, Source API/OpenAPI and real-Docling FILE worker checks passed in 1m57s. The final clock-fixture correction was followed by a passing 1m22s sync/history regression run and then the complete gate below. |
| Selection and history capacity | Completed 20/21/100/1,000 mixed-root CREATE/APPROVE, depth/shared-ancestor/checkpoint continuation, 1,001-root rejection and overlap scenarios. The separate 100,000-summary fixture has zero owned children. Exact timings/calls/checkpoints/database/heap and provider-port/JDBC limitations are canonical in the [Connector matrix](../../../tests/connector.md#measured-selection-and-history-capacity--2026-09-08), not duplicated here. Neither proves a 100,000-file corpus. |
| Frontend and generated contract | Final `pnpm check` passed generated drift, lint/format/TypeScript, 55 tests/15 files, routes, production build and font assets. All **39** routed browser scenarios passed in 3.2 minutes with one worker and zero retries, including Files paging regressions. [Canonical evidence](../../../tests/connector.md#frontend-and-browser-acceptance--2026-09-08). |
| Rendered browser | Actual headless desktop 1440/mobile 390 light/dark surfaces visually inspected, including 100k-item Files pages; no page-level overflow and mobile touch Next reached Page 2. This is not Orca embedded-browser proof. [Canonical UI evidence](../../../tests/connector.md#real-ui-files-pagination-over-100k-items). |
| Isolated runtime | Owner Keycloak login plus real PostgreSQL/Redis/MinIO/API/worker with controlled Google HTTP fixtures. Change/no-change, provider/storage recovery, linked approval/scheduling and separate FILE history were exercised. Final pending selection survived hard reload, then activated revision 3 and a successful 23-file run. Real Files upload/reindex/removal survived page changes. [Canonical run IDs/counters](../../../tests/ingestion.md#mem-76-isolated-runtime--2026-09-08). |
| Final repository backend gate | Post-pagination `clean check --rerun-tasks` passed in **4m28s**, 24/24 tasks executed; all **367** backend tests passed with zero skips, including endpoint-enabled real Docling and owned Redis fixtures. `CI=true`; Arconia Dev Services disabled. [Exact command, counts and warning boundary](../../../tests/ingestion.md). |
| Corpus-capacity evidence | Completed 100,000 known unchanged files through the real sync processor/JDBC/HTTP adapter: full convergence in 45m20s, durable continuation/duplicate skips and abandoned-claim recovery. Incomplete listing retained all existing items/Documents. [Canonical counters/resources/provenance](../../../tests/ingestion.md#controlled-100k-file-traversal). V23 fixture, disabled fsync, controlled HTTP and one physical JDBC connection; not new-file acquisition/indexing, live Google, Redis/JVM crash recovery or a production SLO. |
| Approved Files extension | Complete: direct SourceSummary, bounded keyset pages and migrated API/client/UI. Real UI with 100,000 item rows loaded/rendered 25 rows per page; Previous/Next had no overlap, upload reset to Page 1 and exact reindex/removal observers survived paging. [Canonical 100k-item fixture and operation IDs](../../../tests/connector.md#real-ui-files-pagination-over-100k-items). |
| IDE inspection | No JetBrains MCP or LSP available; no IDE-clean claim. Compiler/project gates are the available fallback. |
| Data/authorization | No real user Source/provider content, shared runtime or DNS/hosts changed by MEM-76 verification. No push/PR/merge/deploy. Actual Tasco data is unavailable; increment and broader issues remain active. |
| Cleanup | Stopped owned API/worker/web/provider/dependency processes, removed private Compose containers/network and only database `memoryos_mem76_smoke`. Removed 44 throwaway probe/SQL/launch/fixture-secret files; retained ten sanitized reports and ten screenshots outside version control. Shared PostgreSQL, Docling and user data were preserved. |
| Documentation | Checked 160 relative file/heading links across ten touched documents; zero missing files or headings. |
| Linear | Final acceptance and approved Files scope recorded on MEM-76 in comment `710c257f-aa8e-42e6-a7b7-986478ee2d09`; issue/parent statuses and assignees were not changed. |

Contracts are canonical in [Connector](../../../specs/connector.md), [Ingestion](../../../specs/ingestion.md) and [architecture](../../../../ARCHITECTURE.md); exact measured/runtime evidence lives in their verification matrices. No final local MEM-76 gate or approved UI scenario remains pending. Historical sections below retain their original live-Google/Orca boundaries and do not establish current MEM-76 live-provider acceptance. Keep the increment active until the authorized PR/merge lifecycle and broader issue acceptance are addressed.

## Owner-credential and explicit-link cutover

- [x] Inspect local Onyx reference and its OAuth/service-account setup docs: OAuth JSON belongs to a credential; explicit URL selections disable account-wide retrieval.
- [x] Replace shared server OAuth client settings with encrypted owner-supplied app credentials across consent, callback and worker refresh; migrate legacy grants to require explicit reauthorization.
- [x] Replace browser candidate listing with pasted file/folder links and server-side URL/metadata validation; remove candidate API and generated callers.
- [x] Restrict sync enumeration to saved roots and descendants, removing account-wide Changes replay while retaining resumable traversal, publication fencing and incomplete-generation safety.
- [x] Run credential/selection/recovery regressions, regenerate and drift-check OpenAPI/client, and run the full backend/frontend/browser gates.
- [x] Complete post-cutover owner-app consent and explicit-link ingestion in the actual shared-identity review session: delete the old Source at the user's request, create its replacement and index the three retained Sheets links.

Ownership: Main integrates root validation, scoped synchronization, docs and final verification. AuthBackend owns OAuth/core credential persistence/provider protocol and API contracts. DriveUi owns frontend setup/selection and affected browser tests. API request contracts are `oauthClientJson` (optional only when reusing a stored app) and root `links`; source configuration adds safe `oauthClientConfigured`. No plaintext client secret may enter persisted session state or returned configuration. All work stays in the target; no donor edit, PR, push or shared application deployment.

## Onyx interface-only alignment

- [x] Inspect the user-supplied read-only `E:/Project/onyx` checkout and clarify scope: all existing MemoryOS screens, not new Chat/Search/Agents capabilities.
- [x] Trace actual source scheduling: refresh intervals, separate pruning intervals, and historical modified-time cutoff; none is a wall-clock worker start time.
- [x] Align shared tokens/primitives, responsive shell, session/access and invitation screens with Onyx, retaining existing authorization and functionality.
- [x] Port source catalog, setup progression, detail/configuration and operational status presentation; retain owner JSON secrecy and explicit-link ingestion.
- [x] Remove the mistakenly introduced scheduling API/schema/editor and schedule-only tests, keeping fixed five-minute synchronization and manual commands.
- [x] Verify authorization/selection/processing boundaries and inspect desktop/mobile and light/dark browser evidence with controlled API responses. Real-provider evidence is recorded separately below.
- [x] Widen the shared wide-page token so Sources and Add a source align; allocate readable table columns and contain horizontal scrolling, including off-screen accessible column labels.
- [x] Port the actual Onyx setup sidebar, credential card/table, OAuth modal and compact upload/paste component, replacing the custom full-page form while retaining Source-owned OAuth semantics.
- [x] Verify modal dismissal/secret clearing, credential continuation, callback-to-Connector setup and desktop/mobile behavior; move redirect/app configuration into collapsed Setup instructions rather than an extra Source field.
- [x] Initial simplification removed UUIDs, timestamps, duplicate email and horizontal scrolling while disclosing lifecycle actions; the metadata removal was superseded by the four-field review below.
  - Verified the retained real credential in Orca: full account-label click selects it, Continue opens Connector setup, Manage reveals the one-Source count and disabled attached-credential Delete, and closing Manage restores the compact row. The live picker had no horizontal overflow; dark-theme screenshot was inspected. No Source or credential mutation was submitted.
  - Frontend formatting, lint and TypeScript passed. All four focused Google setup browser scenarios passed with one worker and zero retries, including keyboard Space selection and existing shared lifecycle/secret-clearing guards. Removed the obsolete UUID-display assertion rather than preserving metadata as a presentation requirement. Backend and dependency contracts are unchanged; no new throwaway script remains.
- [x] Restore the four Onyx metadata fields (ID, Name, Created, Last Updated), replacing the overly reduced picker; retain compact responsive rendering and disclosed lifecycle actions.
  - Inspected the populated dark-theme table in the actual Orca session: exactly four column headers, short ID, one account name and compact dates, with no horizontal overflow. Continue opened Connector setup with the same credential; returned to the table without submitting any mutation.
  - Inspected the 390px-wide browser capture: name/selection first, short ID and two labelled dates remain readable without forcing narrow desktop columns. Formatting, lint and TypeScript passed; all four focused Google setup browser scenarios passed with one worker and zero retries, including keyboard selection and shared lifecycle guards. No backend, dependency or credential data changes.
- [x] Separate credential selection from Name; reduce setup subtitles and move synchronization/selection guidance into keyboard- and touch-accessible help popovers. Verify the table, help disclosure and existing selection/lifecycle behavior.
- [x] Remove non-interactive file-row hover that masks the Reindex button in dark mode; verify Reindex/Remove hover without triggering either action and retain shared Button focus styles.
- [x] Place Save selection directly below the links editor, right-aligned without a separate footer; keep conditional Reload before it and preserve save/conflict guards.
  - Final frontend verification: formatting, lint and TypeScript passed; 55 unit tests and all 23 browser scenarios passed with one worker and zero retries. Both help popovers passed keyboard opening, Escape dismissal and focus restoration in the existing Google setup scenario.
  - Inspected the mobile selection-column/help captures and the live dark-theme Source. Following the user's alignment correction, Save and the editor share the same right edge, with no action-footer border; formatting, lint and TypeScript passed again. Live pointer hover gives Reindex `rgb(38, 38, 43)` and Remove `rgb(51, 11, 9)` while their row stays transparent; no real reindex, remove or save action was triggered.
  - These presentation changes did not alter the backend, credentials or saved Source selection. Existing save, refresh and revision-conflict coverage remains passing.
- [x] Correct XLSX admission for small streaming ZIP64 workbooks; retain CRC/size integrity and archive/XML bounds, add a generated regression fixture and verify the actual failed upload through worker reindex.
  - [x] Runtime recovery exposed historical FAILED attempts leaking into the Source error after INDEXED. Aggregate only current unresolved item failures; preserve another item's real failure while clearing recovered ones.
  - The synthetic ZIP64 regression and current-failure recovery regression both failed before their fixes. Final `clean check :api:bootJar :worker:bootJar --no-daemon` passed; the spreadsheet suite has 7 passing cases and the source lifecycle suite has 14, with no skips or failures.
  - Authorized reindex of the existing 3,899-byte XLSX retained its item, upload timestamp and SHA-256. Final operation `f6c4dc80-0610-42bf-b0cc-9c1e82934104` reached INDEXED / ACTIVE with one Document, no pending work and null item/Source errors.
  - The original Google Source still has three INDEXED items and three Documents. Its shared credential remains ACTIVE at revision 1 with one attached Source. No Google selection, credential, upload bytes or scheduling interval was changed.
  - Local API/worker artifacts were rebuilt and restarted; both readiness checks passed. JetBrains inspection and LSP were unavailable; compilation and the repository Gradle gate supplied verification, not an IDE-inspection claim.
- [x] Keep a visible credential disclosure chevron and Close label when expanded; verify pointer opening and keyboard collapse without credential mutations.
  - Frontend formatting, lint, TypeScript and 55 unit tests passed. All four Google setup browser scenarios passed with one worker and zero retries; the settled mobile capture visibly retains Close and the upward chevron. Pointer opening and Enter collapse passed.

- [x] Persist per-Source Google synchronization intervals with a data-preserving V19 migration, independent schedule revision, owner/CSRF-protected update and all scheduling branches using the saved value.
- [x] Replace the fixed Automatic interval label with persisted display and an accessible inline editor; preserve interval/link drafts and require explicit recovery from stale schedule edits.
- [x] Regenerate the Spring OpenAPI snapshot and browser client, run backend/frontend gates and verify save/reload/conflict plus a real automatic due cycle without changing Google roots or credentials.
  - OpenAPI/client generation, complete backend/frontend gates and browser save/reload/conflict scenarios pass. After owner login was restored, a live save retained the user's interval of one minute, schedule revision 3 and three roots across reload. Without a manual sync during observation, `lastSyncedAt` advanced from `2026-09-07T15:41:58.974239Z` to `2026-09-07T15:43:18.775658Z`; the Source returned to no pending work with three Documents. This verifies the configured interval and normal due-scan scheduling, not an exact sixty-second worker-start guarantee. No interval was changed through SQL.

The user has approved reusable Google Drive credentials for multiple Sources. The prior Source-owned UI adaptation is superseded by the independent credential lifecycle in the updated design. Read-only Onyx inspection confirmed independent credential creation, provider/owner filtering and guarded deletion. MemoryOS keeps its existing tables while replacing the Google singleton constraints, atomic credential-plus-Source OAuth creation and orphan-credential cleanup.

The earlier UI-only baseline passed `pnpm check` (54 unit tests) and all 21 browser tests. Current reusable-credential evidence is recorded below. The separate dependency audit reported four high and two moderate advisories, including transitive `fast-uri` paths under development dependency `shadcn` (patched in 3.1.6). Dependency remediation was not part of this change; no security-clean claim is made.

Integration ownership: Main owns design/docs, generated API/client integration and final verification. Core owns credential persistence, shared authority and atomic Source creation; API owns HTTP/session contracts and HTTP tests; web owns the Onyx credential workflow and browser coverage. All share explicit contracts and skip validation while sibling edits are in flight. Per-Source intervals are now approved; ACL and other advanced Onyx backend capabilities remain excluded.

## Reusable credential cutover

- [x] Separate credential identity/catalog and migrate Google singleton constraints without changing existing IDs or encrypted grants.
- [x] Implement shared reconnect/revoke/auth-failure fencing, guarded credential deletion and Source-only cleanup.
- [x] Create Source with an existing credential and validated links atomically; keep remote calls outside SQL transactions.
- [x] Cut over owner HTTP endpoints, encrypted consent state and callback routing to credential identity.
- [x] Bind Onyx Select credential/Create New/Connector steps to real metadata and independent Source creation; migrate all callers.
- [x] Verify migration preservation, tenant isolation, shared invalidation, concurrency boundaries and actual browser reuse; run backend/frontend gates after integration.

Local runtime preservation: retain Source `45a91ebf-c73d-47ba-95f6-04e96f7cf808` and its three indexed Sheets. Do not edit the frozen `D:/Capstone Project/MemoryOS` donor or read-only `E:/Project/onyx` reference. No PR, push, merge or deployment.

### Reusable credential verification

- Backed up the existing review database to ignored `.tmp/drive-review-private/before-reusable-credentials.dump` and verified its restore catalog before V18. The migrated Source ID, Connector ID, credential ID, ACTIVE state, authority revision, encrypted OAuth-app fingerprint, three root IDs, three item IDs and three Document IDs matched the private baseline exactly.
- Final backend gate: `gradlew.bat clean check :api:bootJar :worker:bootJar --continue --no-daemon --max-workers=2` passed with JDK 25 and real Docling at `http://127.0.0.1:15062`. Core/connector test results were reused from the preceding successful tasks with unchanged inputs; API/worker tests executed. The API fixture was corrected to include the required email scope. Existing JDK/dependency and fixture-shutdown JDBC/OTLP warnings remain.
- Final frontend `pnpm check` passed: generated API/route stability, lint, formatting, TypeScript, production build and 55 tests in 14 unit files. `pnpm test:e2e --workers=1 --retries=0` passed all 23 scenarios after the modal-guidance correction. Desktop modal/card captures were visually inspected; the setup URI is hidden until instructions are expanded.
- In the real authenticated Orca browser, select the retained credential → Continue → submit a separately named Source with one retained Sheet link. Source `dd6a698c-3ba3-42b7-b8ec-c5f5d1dd0e6d` reached ACTIVE with one INDEXED item/current Document, no pending work and no error. Both Sources reported credential `76bf3016-c10b-4672-a597-8419296169a9`; catalog attachment count was two and authority revision remained one. No JSON upload or Google consent occurred.
- Delete the temporary Source through its real confirmation dialog. Its API then returned 404; the credential remained ACTIVE at revision one with one attachment. Original Source remained ACTIVE with three Documents, the same three item IDs, no pending work and no error. Provider files were neither edited nor deleted. The built API/worker and local web remain available for review.
- No JetBrains MCP or LSP was configured; compilation and repository gates are the available static evidence. Live shared revoke/reconnect was not exercised against the retained grant to avoid interrupting the user's original Source; deterministic PostgreSQL/HTTP tests cover those transitions. Broader format/provider-outage/ACL acceptance remains outside this bounded reuse verification.

## Approved sequence and delivery state

- [x] Read the Linear FILE architecture and MEM-9/10/60/63 requirements against current main/V12; trace upload, storage, Redis, Document and access boundaries.
- [x] Freeze the donor and create the dedicated target worktree. Preserve existing local databases and operator configuration privately before creating the isolated database.
- [x] Fix public input/write/sync contracts and numeric admission bounds without a new Gradle module, provider registry or ACL schema.
- [x] Implement owner-bound Google consent, encrypted grants, revision/CAS semantics, reconnect/disconnect and revision-checked selected roots.
- [x] Implement tracked raw writes, offline native snapshots/readers, Java XLSX/CSV routing and existing Docling/Tika reuse.
- [x] Implement durable SOURCE_SYNC, page/frontier checkpointing, incomplete-run recovery, input adoption and publication fencing through the existing worker topology.
- [x] Implement Google source management and generated API/browser contracts while retaining FILE and the closed document-read boundary.
- [x] Run controlled regression suites, real FILE/MinIO/Docling scenarios, and embedded-browser setup/upload/indexing checks; correct failures rather than suppressing them.
- [x] Finish the final repository gate and FILE cleanup/restart scenario after the last pending-work correction.
- [x] Complete fresh Google OAuth → real root picker → native snapshots → worker → current Documents/private artifacts and durable sync recovery after worker restart.
- [ ] Complete remaining live format, provider-change/outage and Drive disconnect/removal acceptance.

## File-level implementation map

Target: `C:/Users/adim/orca/workspaces/MemoryOS/google-drive-structured-ingestion`. Frozen donor: `D:/Capstone Project/MemoryOS`. All edits belong to the target. This is the actual implemented map, replacing the earlier candidate filenames.

| Owner/path | Implemented entry points and responsibility | Reuse/cutover |
| --- | --- | --- |
| `api/.../source` | `GoogleDriveCredentialController`, `GoogleDriveSourceController`, `GoogleDriveOAuthCallbackController`, `GoogleDriveAuthorizationSessionState`, `GoogleDriveAccountClient`, `GoogleDriveOAuthProperties`, request/response records | Independent credential catalog/consent/lifecycle and explicit Source creation; owner-bound state/PKCE/nonce and dedicated acquisition callback |
| `api/.../security` | `GoogleDriveCallbackSecurityConfiguration` | Exact callback chain; main Keycloak/invitation login and Actor-only session remain intact |
| `core/.../connector/application` | `DefaultGoogleDriveAuthorizationService`, `DefaultGoogleDriveConnectionService`, `DefaultGoogleDriveSourceService`, `GoogleDriveSelectionPolicy`, `DefaultGoogleDriveSelectionProcessor` | Owner/account pinning, shared authority versus payload CAS, bounded async admission/receipt recovery, checkpointed provider verification and fenced activation; independent scheduling |
| `core/.../connector/persistence` | Credential cipher/configuration/repository, `JdbcGoogleDriveSourceRepository`, `JdbcGoogleDriveSelectionRepository` | Encrypted credentials, immutable candidate/receipt/policy/revision snapshots, metadata/ancestor checkpoints, source-scoped selection pages/drafts and atomic activation; no ACL/Groups tables |
| `core/.../connector` | `GoogleDriveProvider`, `ConnectorSyncPort`, `SourceInputFormat`, `SourceInputDescriptor`, extended `IndexWork` | Provider-neutral network/session and stored-input contracts; no SDK/Jackson type or live fetch instruction crosses into ingestion work |
| `core/.../connector/application` and `persistence` | `DefaultConnectorSyncService`, `JdbcSourceSyncRepository` | Resumable selected-root reconciliation, complete-generation pruning, confirmed-input release, raw adoption and shared credential fencing; no Changes feed |
| `core/.../connector/application` and `persistence` | `DefaultSourceRunHistoryService`, `DefaultSourceRunHistoryMaintenance`, `JdbcSourceRunHistoryRepository`, `JdbcSourceRunRetentionRepository` | Exact run/owned-child counters and safe errors, bounded indexed queries, independent activity/completion/success summaries and protected retention |
| `api/.../source` and `web/src/features/sources` | `SourceRunController`, typed run responses, `source-item-history.tsx`, `google-drive-selection-panel.tsx`, selection-operation recovery hook | Backend run list/detail/errors; compact paged Indexing attempts UI; pending selection receipts, full drafts and revision-pinned page rendering. Run history UI removed by the 2026-09-09 revision. |
| Existing Connector repositories/services | `JdbcSourceItemRepository`, `JdbcIndexAttemptRepository`, `JdbcSourceQueryRepository`, `JdbcSourceDocumentRepository`, source/cleanup services and repositories | Keep FILE hash dedup; add Drive remote identity, current-input publication and reconciliation deferral; include cleanup in pending-work projection; explicitly keep Drive outside FILE PUBLIC |
| `core/.../objectstorage` | `ObjectWriteService`, `DefaultObjectWriteService`, `JdbcObjectWriteRepository`, extended stored-object persistence | Reservation before PUT, verified completion, transaction-bound adoption/release, uncertain-write tombstones; reuse S3 IO without browser receipt or artifact-lifecycle substitution |
| `connector/.../provider/google` | `RestGoogleDriveProvider`, properties/auto-configuration, `NativeSnapshot`, `GoogleSheetsSourceContentExtractor`, `GoogleDocsSourceContentExtractor` | Bounded native acquisition and offline structural extraction; replace donor aggregate Changes and manifest control flow |
| `connector/.../provider` and `provider/file` | `SourceContentExtractorRouter`, `SourceContentExtractorAutoConfiguration`, `StructuredContent`, `SpreadsheetSourceContentExtractor` | Native descriptor first, detected binary format second; XLSX/CSV Java tables; retained Docling PDF/DOCX/PPTX and Tika UTF-8 text leaves |
| `core/.../ingestion` | `SourceSyncProcessor`, `SelectionValidationProcessor`, `DefaultIngestionCoordinator`, workload/dispatch/metrics integration | Selection verification and SOURCE_SYNC use the existing PostgreSQL → Redis → fenced processing path; INGESTION reads adopted MinIO input without Google |
| `worker` | Existing composition, control-plane, topology, relay and consumer classes | Four workloads, due-source enqueue, bounded history maintenance and abandoned raw-write cleanup; no Google-specific executor or alternate polling mode |
| `core/.../db/migration` | V13–V21 retained; V22 durable selection operations; V23 source run history | Additive policy/receipt/checkpoint and exact attribution/counter/error/index/retention schema; no edits to previously applied migrations |
| Gradle/runtime/MinIO composition | Shared `connector` dependency in API, provider configuration, explicit worker extractor composition, worker `raw/*` PUT policy | Four modules remain; private storage and existing secret delivery remain; no staging rollout performed |
| `web/src/features/sources` and routes | Google credential catalog, OAuth modal, Connector explicit-link form and management panel; FILE CSV/XLSX admission; generated route/API clients | Real owner management only, visible RESTRICTED boundary, no document viewer or donor approval/manifest UI |

### Runtime call chain

```text
Reusable credential + separately named Source with explicit roots
  → GoogleDriveSourceController / DefaultGoogleDriveSourceService
  → durable selection receipt / JdbcGoogleDriveSelectionRepository
  → existing relay / GOOGLE_DRIVE_SELECTION_VALIDATION / checkpointed verification
  → fenced atomic Source + roots + initial-sync activation
  → durable SOURCE_SYNC / JdbcSourceSyncRepository
  → existing relay / RedisStreamWorker / SourceSyncProcessor
  → DefaultConnectorSyncService / GoogleDriveProvider
  → tracked ObjectWriteService raw snapshot
  → atomic input/version/index-attempt adoption
  → existing INGESTION / SourceContentExtractorRouter
  → offline native, spreadsheet, Docling or Tika reader
  → extraction-artifact staging
  → fenced current Document + index completion transaction
```

## Correctness decisions confirmed during implementation

- Normal refresh rotation increments `payload_revision`, not `credential_revision`. A losing stale authentication failure cannot revoke the winning rotation; reauthorization/revoke still advance authority and fence old publication.
- Synchronization uses selected-root reconciliation, not account-wide Changes. Unrelated account items are neither enumerated nor imported; only complete traversal of accepted roots permits pruning.
- A failed enumeration releases successfully adopted, confirmed current-generation inputs but withholds final cursor advancement and pruning. Failed files remain retryable.
- Reconciliation can temporarily clear eligibility while an index is running. An otherwise-current authorized input is deferred and later redispatched rather than permanently superseded; `deferred_attempts` does not consume its extraction retry budget. Changed/revoked/excluded inputs cannot use this path.
- Raw reservation/adoption/cleanup and extracted-artifact ownership remain distinct. Uncertain PUT completion retains a durable late-writer tombstone; referenced objects are never generic-cleanup candidates.
- The real FILE UI exposed two gaps: its old extension allowlist rejected supported CSV/XLSX, and source `pendingWork` omitted nonterminal item cleanup. Both were corrected at their owning admission/read-model boundary, retaining the existing browser polling mechanism.
- Current-schema H2 fixtures could not execute the PostgreSQL-specific new migrations. They now use the shared fresh-PostgreSQL fixture. Historical bounded migration tests remain separate. Removed the migration-count-only `SchedulerSchemaMigrationTest`; current-data and real control-plane behavior tests remain.
- Worker integration contexts used the same fixed Redis host port. They now request `arconia.dev.services.redis.port=0`; normal development port 56379 remains unchanged. The isolated manual E2E Redis uses 16379.

## Verification ledger — 2026-09-07

The checks and live scenarios below predate the owner-app/explicit-link/interface cutover unless explicitly identified in [Owner-app and interface verification](#owner-app-and-interface-verification). They are retained as historical evidence, not a substitute for current acceptance.

### Environment and data safety

- Existing local database dumps and original operator/configuration data were preserved under ignored private `.tmp/drive-e2e-private/`; no staging reset or deployment was performed.
- Fresh local database `memoryos_drive_structured` runs in `memoryos-postgres-local` on loopback 15555. API applied V1–V15 and became healthy.
- Isolated MinIO bucket `memoryos-drive-e2e` on 19000/19001; Redis on 16379; local Keycloak realm `memoryos-drive-test` on 18180; API 18080, worker 8081, browser origin 8080, pinned real Docling 15062.
- The exact Google callback `http://127.0.0.1:8080/login/oauth2/code/google-drive` was already registered in the actual Google Cloud client. Docs API was observed enabled. No credentials, grants or callback secrets are recorded here.
- After the clean gate, API and worker were restarted and both health endpoints returned `UP`. Orca's fresh Google setup tab is prepared with source name `Google Drive structured E2E`; authorization has not been restarted while the user is unavailable. The expired Google tab is preserved separately. The local runtime remains available for the human approval step.

### Executed checks

| Check | Observed result |
| --- | --- |
| Main compilation and focused authority/sync/storage/OpenAPI checks | Passed; committed OpenAPI and browser clients generated from actual controllers |
| Full backend gate before final runtime correction | Core, connector and API passed; fixed worker Redis context collision, then the complete `:worker:test` passed with real Docling configured |
| FILE pending-work regression | Extended `SourceApiIntegrationTest.indexesAndCleansUpOneFileThroughTheAuthorizedApi` failed before the SQL fix at the pending-work assertion, then passed after it; tests pending=true before cleanup and false after terminal cleanup |
| Final frontend `format` + `check` | Passed: generated API/route stability, lint without warnings, formatting, TypeScript, 14 files / 53 unit tests, production build, four emitted WOFF2 assets and no inline font URLs |
| Affected routed browser regressions | `file-source-setup.spec.ts` and `identity-shell.spec.ts`: all 19 Chromium scenarios passed, including updated FILE format admission and existing source/authentication behavior |
| Final full backend `clean check --no-daemon` | Passed in 3m12s with JDK 25 and real Docling configured. All four module test tasks executed; eight compile tasks reused cache. Dependency/JDK warnings and test-fixture shutdown JDBC/OTLP log noise remain; this is not a warning-free claim |
| IDE/static inspection | No LSP or JetBrains MCP configured; no IDE-clean claim. Compilation, ArchUnit/Modulith and repository gates are the available evidence |
| Documentation references | All 155 local Markdown file links across the 14 updated canonical/increment documents resolve |

### Actual embedded-browser and storage proof

- Orca owner login through local Keycloak reached the authenticated MemoryOS shell, source catalog and Google setup. Screenshots were saved and visually inspected; this is real UI, not a component fixture.
- Real FILE CSV creation used browser-direct MinIO PUT, API finalize and Redis/worker indexing. UI reached INDEXED. Private artifact read showed a 4×3 table retaining the quoted comma, quoted multiline value and `Việt Nam`. CSV reindex returned to INDEXED.
- Source-detail XLSX upload reached INDEXED. Its artifact retained the `Metrics` sheet, 4×2 sparse grid, `A1:B1` merge, Unicode title, and inert `SUM(B2:B3)` formula with cached numeric value 30.
- CSV removal deleted its Document/raw ownership. The stale DELETING UI exposed the pending-work defect described above. After the fix, XLSX removal while the worker was stopped reported source ACTIVE, item DELETING, `pendingWork=true`. Restarting the worker completed the durable cleanup, and the existing page automatically changed to “No files yet” without navigation or reload.
- A final real TXT upload indexed successfully through the retained text path. Deleting that nonempty source via its actual confirmation dialog navigated to the empty `/admin` source list. PostgreSQL reached zero source, item, Document, stored-object, upload, source-upload and extraction-artifact rows; both item removals and source deletion were SUCCEEDED. Authenticated GETs for the recorded XLSX and TXT raw keys returned 404. This combines real UI, worker restart, relational convergence and targeted storage evidence; it is not a full bucket inventory.
- The three temporary FILE fixtures were removed after verification. Ignored backups, runtime configuration and screenshots remain for continued acceptance work.
- An isolated worker-credential IAM probe returned raw PUT 200, exact-byte GET 200, anonymous GET 403, DELETE 204 and subsequent GET 404. Its probe object was removed. This proves the new local raw-write policy, not the complete Google acquisition lifecycle or a staging rollout.
- Real PostgreSQL/Redis/MinIO worker integration also exercised DOCX through Docling, Redis loss/redelivery, item/source cleanup and deactivated-Tenant cleanup. Deterministic provider/native/authority/sync tests remain distinct from live Google acceptance.

### Live Google acceptance after fresh authorization

The first MFA session expired. After the user returned, the local stack was restored and fresh OAuth completed through the real owner/browser callback, creating source `8e37ecb5-a8a7-4d09-8546-88de91a10f34`. No old callback, encrypted donor grant or database-injected credential was used. The earlier retained-grant decryption failure is historical and does not imply provider revocation.

- The actual picker listed My Drive content and saved four selected files: the retained VETC, SAVICO and TASCO AUTO KPI Sheets plus `MemoryOS Google Drive Acceptance Document`. Source access remained RESTRICTED; the UI says document access is not configured.
- All four files acquired into tracked private raw objects and reached INDEXED with four current Documents and four active extraction artifacts.
- Every represented Sheet cell was compared by coordinate, complete native cell payload and exact formatted text between the live snapshot and its extraction artifact. VETC has 504 formatted cells over 42×12; SAVICO and TASCO AUTO each have 492 over 41×12: **1,488 exact matches**. All three retain the allocated sparse 1000×26 grid. The historical 1,476-cell expectation is not the current corpus: VETC now has one additional represented row.
- Native Docs paragraph text matched the provider snapshot exactly. This fixture contains a simple paragraph; rich Docs structures are covered by deterministic fixtures, not established by this live document.
- With the real worker stopped, the owner requested synchronization. The API reported INDEXING and `pendingWork=true`, retaining all four documents. After worker restart, it converged to ACTIVE, `pendingWork=false`, four INDEXED items, the same four Document IDs, and still exactly four raw objects/artifacts.
- Removing the direct Docs root through the real selection UI converged to three INDEXED items/Documents. Authenticated GETs of that known Doc's raw object and extraction artifact both returned 404; its cleanup completed. Selecting the existing acceptance folder instead reacquired the Doc through folder traversal and restored ACTIVE/four INDEXED items with no pending work. The provider file was not edited or deleted. Screenshot `.tmp/drive-e2e-private/google-live-four-indexed.png` was visually inspected.
- Private comparison evidence is retained at ignored `.tmp/drive-e2e-private/live-native-evidence.json`. No provider content or credentials are published by that ledger.

Remaining live acceptance: Slides/binary corpus, richer native Docs, provider-side change/outage recovery, and full Drive disconnect/source-deletion cleanup. Current live evidence must not be generalized to those unexercised paths or used to close full MEM-10/MEM-60 scope. MFA is no longer the blocker.

### Requested developer review runtime

The user requested the implemented branch with the shared development Keycloak and existing development MinIO; PostgreSQL, Redis, Docling, API and web remain local. Preserve the isolated E2E data/evidence rather than changing its tenant or copying grants into the development tenant.

Shared realm discovery and authentication of the supplied `memoryos-web` client succeeded. The user supplied the [service directory](https://linear.app/memory-os/document/memoryos-danh-ba-dich-vu-e481f92219a3), which distinguishes server-loopback `127.0.0.1:19000` from the workstation-accessible S3 endpoint `https://memoryos-objects.72-62-193-33.nip.io`. The directory's deployment uses bucket `memoryos`, confirmed from managed staging configuration. Its provisioner secret was retrieved privately from Infisical staging and successfully authenticated; the dev lookup's `*not found*` sentinel is not a usable credential.

After the user authorized SSH inspection, the actual service secrets were read privately from the API/worker secret mounts on the shared server. Both `memoryos-api` and `memoryos-worker` authenticated to the public S3 endpoint and returned HTTP 200 with the exact readiness sentinel. The user need not supply additional credentials. No secret values are recorded here.

Read-only inspection initially found worker raw GET/DELETE without PUT, and CORS restricted to staging. The user then explicitly approved updating the existing shared identities rather than creating local-review accounts. The worker policy now additionally permits `s3:PutObject` on `arn:aws:s3:::memoryos/raw/*`; all other statements were preserved. `.env.staging` now retains the staging origin and adds `http://127.0.0.1:8080`. Before applying, the original policy and environment were backed up under `/apps/memoryos/local-review-backup-20260907T032711Z`. Resolved Compose configuration differed only in the MinIO CORS value; the image was unchanged. Only MinIO was recreated, with `--no-deps` and a successful health wait.

The real shared worker credential subsequently returned raw PUT 200, exact-byte GET 200, DELETE 204 and GET 404 for one uniquely named smoke object, which was removed. Browser preflight returned the matching allowed origin for both localhost and staging. Shared MinIO, API and worker were healthy afterward. This policy change affects every process using the shared worker identity, including the deployed worker and the configured local worker; no deployed API/worker restart or application deployment was performed.

The local review API, worker and web are running against shared Keycloak/MinIO, with fresh local database `memoryos_drive_review`, separate Redis on 16380, and local Docling on 15062. API health and worker readiness returned HTTP 200/UP. Private runtime configuration is under ignored `.tmp/drive-review-private/`; the original E2E database, Redis and object storage remain separate and preserved.

Actual sign-in initially reached shared Keycloak but was rejected with HTTP 400 and `Invalid parameter: redirect_uri`. The user explicitly approved adding `http://127.0.0.1:8080/login/oauth2/code/memoryos` to the shared `memoryos-web` client. The original client representation was backed up as `keycloak-memoryos-web-before.json` in the same private server backup directory. Admin update returned 204; subsequent inspection confirmed only the redirect-URI set changed, with both staging callbacks preserved and no wildcard, client-secret, origin or role change. No Keycloak restart was required.

Afterward, the local sign-in route returned 302 to shared Keycloak, whose login form returned HTTP 200. Orca's actual browser showed the Email/Password/Sign In form. API health, worker readiness and Docling are UP. The review runtime is ready for the user to sign in at `http://127.0.0.1:8080`; authenticated callback/session and Google-source verification under that real user still await the user's sign-in. No authenticated-login completion is claimed yet.

### Owner-app and interface verification

At this earlier review, the user limited Onyx to **presentation only**. The unapproved temporary schedule API and migration were removed before the local review migration; owner OAuth JSON, explicit links, the five-minute cadence, manual commands and server-backed read-only access filtering were retained. This is historical baseline evidence: the later reusable-credential V18 and explicitly approved per-Source interval V19 supersede its scheduling scope.

| Check | Observed result |
| --- | --- |
| Backend `./gradlew.bat clean check :api:bootJar :worker:bootJar --no-daemon` | Passed in 6m29s with `DOCLING_TEST_ENDPOINT=http://127.0.0.1:15062`. API, core, connector and worker tests ran; OpenAPI drift check used normal non-write mode. Both executable artifacts were built |
| Frontend `pnpm check` after integration corrections | Passed: generated API stability, CI-image check, warning-denying lint, formatting, TypeScript, 14 unit files / 54 tests, route stability and production build; four emitted WOFF2 assets, no inline fonts |
| Browser `pnpm test:e2e --workers=1 --retries=0` | Passed all 21 tests, including owner-app upload/paste, secret clearing/non-echo, explicit-link selection, FILE lifecycle, session/access and invitation flows with routed responses |
| Visual inspection | FILE setup desktop/mobile/light/dark and Google credential/saved-link mobile captures inspected. Narrow-layout overflow checks passed. A settled CSS probe confirmed enabled dark secondary-button text is white at 85% opacity over `rgb(25,25,30)`; the initially dim capture was a transition frame, not a token defect |
| Actual review runtime | Verified boot JARs started with retained private environment and bounded local heap; API and worker readiness returned `UP`. V16/V17 applied to the existing backed-up review database |
| Local preservation before requested replacement | Read-only SQL reported schema V17, one Source, three selected roots, five recorded sync attempts and zero unfinished syncs. No new schedule migration was applied |
| Actual Orca browser | User sign-in was renewed and the existing private owner Web OAuth JSON was uploaded through the real setup form. Google consent completed for the same account; callback created a new Source with an ACTIVE credential. No password, callback or grant was fabricated |
| Requested Source replacement and live ingestion | Old Source deletion completed in the UI and its API returned 404. Three retained file links were saved on the new Source; manual synchronization reached ACTIVE with three documents, all three items INDEXED, no pending work and no error code. The last successful index timestamp observed was `2026-09-07T06:05:45.703695Z`. Original Google files remained available and were reacquired |
| Shared page alignment | Sources and Add a source both measured a 1280px outer container at the same desktop viewport, with identical left alignment, 16px horizontal padding and 52px top padding. Statistics and Total docs headings fit on one line. A narrow real-browser frame exposed an absolutely positioned accessible-label overflow; containing it within the table scroll region removed document-level horizontal overflow |
| Inspection limits | No LSP/JetBrains MCP was available. JDK/dependency warnings and fixture-shutdown JDBC/OTLP noise remain; no warning-free or IDE-clean claim |

The UTF-8 byte-limit regression failed before the parser correction and passed afterward, including the exact 16 KiB boundary and over-limit Unicode. Integration also corrected typed invitation navigation and kept malformed JSON errors generic/non-echoing. Obsolete fake-composer and fixed-viewport-height assertions were removed; navigation, authorization, recovery and secret-isolation contracts remain exercised.

Browser captures are ignored outputs under `web/test-results/`. The isolated color probe was closed without touching the user sign-in tab. Private review configuration, owner-client JSON and the pre-cutover database backup remain outside version control for continued acceptance; no credentials or provider contents are published here. A PowerShell startup hit Windows paging-file pressure during parallel verification; running the same built applications directly with bounded heap restored readiness without changing machine settings or unrelated services.

**Remaining acceptance limits:** the fresh owner-app/explicit-link path is now verified against the three existing Sheets, not every provider format or failure mode. The older four-document Google run remains separate evidence. Remaining format/provider-outage/full-cleanup cases listed above are not claimed complete. Do not close the full linked issues or merge this increment on the strength of this bounded corpus and controlled fixtures alone.

## Observable acceptance references

- Owner/OAuth/state/nonce/account/credential/root revision and FILE lifecycle: [Connector matrix](../../../tests/connector.md).
- Reservation-before-PUT, integrity, crash/adoption/late-write cleanup and binary/native admission: [Object storage matrix](../../../tests/object-storage.md).
- Offline native/table routing, bounded acquisition, partial-run retry/pruning, leases, Redis recovery and publication deferral: [Ingestion matrix](../../../tests/ingestion.md).
- Stable current Document, transactional artifact replacement, stale authority and orphan cleanup: [Document matrix](../../../tests/document.md).

Fixtures prove deterministic boundaries; actual service/browser runs prove only the path and corpus exercised. Google live acceptance remains mandatory, not replaced by FILE success or donor history.

## Per-Source interval verification — 2026-09-07

- Preserved a matching PostgreSQL 18 custom-format database backup and both previous runtime jars under ignored `.tmp/drive-review-private/` before V19. No credentials, selected links or private uploaded bytes were copied into tracked files.
- Final backend `./gradlew.bat clean check :api:bootJar :worker:bootJar --no-daemon` passed in 2m34s with JDK 25 and `DOCLING_TEST_ENDPOINT=http://127.0.0.1:15062`: 28 tasks, 17 executed and 11 from cache. The shared core fixture now applies V19; the in-flight regression uses non-invoking Mockito restubbing. The CSRF case exercises the existing non-simple-header guard rather than expecting an Origin-only rejection that the browser contract does not implement.
- Frontend `corepack pnpm check` passed generated API/route stability, CI-image validation, warning-denying lint, formatting, TypeScript, all 60 unit tests in 14 files and the production build. Four emitted WOFF2 assets were verified.
- `corepack pnpm test:e2e tests/e2e/google-drive-source-setup.spec.ts` passed all four Chromium scenarios. Mobile interval save/reload, independent Source values, desktop keyboard cancellation and retained credential controls were exercised. The desktop/mobile `google-automatic-interval-*.png` captures under ignored `web/test-results/` were visually inspected.
- The local API and worker were rebuilt/restarted and both readiness checks passed. V19 exposes the retained Source at interval 5, schedule revision 1 and unchanged scope revision 2. Real automatic operations completed successfully at `2026-09-07T14:09:30.294Z` and `2026-09-07T14:15:35.577Z`; the latter set the next due time to `14:20:35.577Z`, with no error. Read-only checks matched all original root/item IDs, three INDEXED items, three Documents and the ACTIVE credential at authority revision 1.
- The owner session was subsequently restored. The current live Source is saved at one minute and schedule revision 3, retained across reload with all three roots. The successful automatic-cycle timestamps above supersede the earlier login blocker and five-minute-only evidence; stale-conflict recovery remains controlled-browser evidence rather than a conflicting write to the user's live Source.
- JetBrains inspection and LSP were unavailable. Compilation, the repository gate, browser verification and live runtime evidence are the checks performed; this is not an IDE-inspection claim. No PR, push, merge or shared deployment was performed.

## Source action feedback review

- [x] Add visible Reindex acceptance and operation-specific completion/failure feedback; preserve concurrent-item tracking and abort observers when leaving the Source.
- [x] Replace the saved-selection inline acknowledgement with an accessible, transient success notification.
- [x] Replace persistent automatic-interval acknowledgements with transient notifications, retaining validation, conflict recovery and focus restoration.
- [x] Add Synchronize now acceptance and terminal acquisition feedback using the same notification lifecycle, without claiming downstream indexing completion.
- [x] Verify successful, failed, superseded and unavailable operation outcomes plus notification dismissal on the actual browser surface; retain the user's current interval and three selected Sheets.

The user-reported missing feedback and permanently displayed interval acknowledgement are the reproduction evidence. Source detail now uses the existing Radix dependency for page-scoped notifications and a shared abortable observer for the exact returned operation. The live Source announced Synchronization requested and Synchronization complete, explicitly allowing indexing to continue; a same-value interval save announced Automatic interval saved for one minute. Onyx setup remains paused; its General/Specific controls were inspected read-only to answer the user's scope question.

- [x] Apply the user's final timing requirement: every notification, including errors, disappears after five seconds without pausing for hover, keyboard focus or an unfocused Orca pane.
- [x] Verify that expiry regression and consolidate the final frontend and live-browser evidence.

The original Radix timer paused when Orca's visible browser pane lost focus; direct inspection observed `document.hasFocus() = false` with no notification hover. The updated regression also reproduced the old persistent-error behavior: Reindex failed remained after its eight-second removal deadline. Each toast now owns a five-second timer cleaned up on dismissal/unmount; Radix still supplies presentation, announcements and manual dismissal, not expiry timing. No API, backend or dependency contract changed in this feedback pass.

Final feedback verification:

- `corepack pnpm check` passed after the final timer change: generated API/route stability, CI-image check, warning-denying lint, formatting, TypeScript, 60 unit tests in 14 files and the production/font build. `corepack pnpm test:e2e --workers=2 --retries=0` passed all 26 Chromium scenarios. The formerly failing expiry regression now passes with a hovered/focused toast and a window-blur event; it covers success, informational and error expiry plus early keyboard dismissal.
- In the actual Orca Source page, Synchronize now reached Synchronization complete. At `2026-09-07T15:54:26.036Z`, that notice was visible with `document.hasFocus() = false`; at `15:54:31.054Z`, the notification list was empty with focus still false. No reload, focus restoration or manual dismissal was used. The user's one-minute interval and three Sheets were retained.
- Desktop reindex/synchronization and mobile selection/interval captures under ignored `web/test-results/` were visually inspected. The canonical Connector contract and verification matrix now describe operation-specific outcomes and the fixed five-second policy. No new runtime dependency or throwaway script remains.
- The separate dependency audit still reports four high and two moderate advisories, including transitive `fast-uri` under development dependency `shadcn`. This feedback change does not remediate dependencies and makes no security-clean claim.

## Approved General / Specific implementation

- [x] Define General/My Drive and Specific/explicit-link contracts and preserve original Source scope.
- [x] Persist scopeMode with a preserving V20 migration; validate and fence create/scope changes.
- [x] Reuse durable traversal for the verified My Drive root; retain retry, incomplete-generation and pruning safety.
- [x] Update required HTTP request/response mode, generated clients and all affected callers.
- [x] Add General/Specific creation and selection controls while preserving drafts, interval and five-second feedback.
- [x] Verify scope-switching, root identity, incomplete traversal, authorization and migration boundaries.
- [x] Run backend/frontend gates and exercise both modes on the real runtime without expanding the original Source.

Ownership: DriveScopeCore owns core persistence, application and sync changes plus core/connector/worker regression callers. DriveScopeWeb owns frontend source creation/selection and all web fixtures. Main owns API contracts/tests, OpenAPI/client regeneration, integration, live runtime verification and canonical/increment consolidation. During concurrent edits, workers skip builds, tests, linters and formatters; Main runs the final gates after integration.

Scope verification:

- JDK 25 `./gradlew.bat clean check :api:bootJar :worker:bootJar --no-daemon --no-parallel --max-workers=1` passed: 28 tasks, 4m35s. The serialized rerun replaced a transient Windows JDBC socket `BindException` in the preceding concurrent gate; no assertion or implementation change was used to hide that failure.
- `corepack pnpm check` passed: API/route generation checks, Oxlint, Oxfmt, TypeScript, 64 unit tests and production build. `corepack pnpm test:e2e --workers=2 --retries=0` passed all 27 scenarios. Mobile Specific-empty and General-created/saved screenshots were inspected; no empty Saved scope or horizontal overflow.
- Live Flyway applied V20 from V19. Original Source `45a91ebf-c73d-47ba-95f6-04e96f7cf808` retained credential `76bf3016-c10b-4672-a597-8419296169a9`, all three root/item identities and Documents, `SPECIFIC`, scope revision 6, interval one minute and schedule revision 3, matching the saved pre-migration configuration.
- The authenticated UI created temporary Source `d349dd83-7817-4562-bd69-5482fbe3c006` as `GENERAL`, with `roots: []`, scope revision 1 and default interval five minutes. This exercised real credential-bound Google root resolution. Before worker startup, the UI saved `SPECIFIC` with one existing Sheet link, yielding scope revision 2 without changing interval/schedule revision. Worker acquisition/indexing reached one `INDEXED` Item, one Document and `pendingWork: false`.
- No live whole-My-Drive traversal was run: the old General generation was superseded before worker startup. Durable General traversal, root revalidation, pagination, incomplete-generation no-prune and mode-switch publication fences are covered by the backend regressions; the live test deliberately bounded acquired content to one Sheet.
- JetBrains inspection and LSP diagnostics were unavailable: no Java/TypeScript language servers or JetBrains tools were configured. Compilers, static checks and runtime evidence above are the verification used.

## Final action feedback and branch delivery

- [x] Complete accurate notifications for source creation, upload/finalization, sync, reindex, remove/delete, selection/interval saves, credential management and explicit refresh/reload, retaining accessible dialog errors.
- [x] Verify five-second expiry, operation outcomes, partial-success recovery and navigation feedback in the integrated frontend gates and real runtime.
- [x] Delete the temporary scope-verification Source and reconfirm the original Source, three Sheets/Documents and reusable credential remain unchanged.
- [x] Consolidate canonical contracts and verification for the approved branch delivery.

Delivery authorization: create focused commits and push `nhuxuanviet/google-drive-structured-ingestion` to origin. Do not open a PR, merge, close the broader linked issues or deploy the shared runtime. Git history and the remote branch record delivery; the increment remains active until a later authorized merge.

### Final feedback verification

- Final frontend integration passed `corepack pnpm check` (14 unit files, 68 tests, generated API/routes, Oxlint/Oxfmt, TypeScript and production build) and `corepack pnpm test:e2e --workers=2 --retries=0` (35 browser scenarios). Mobile creation/saved-scope captures cover the actual scope controls; credential captions wrap words normally instead of splitting them mid-word.
- In the authenticated runtime, Reindex announced acceptance and the temporary Sheet returned to `INDEXED` with a new successful index timestamp. Synchronize now announced acceptance and completion; its completion explicitly did not claim downstream indexing was complete.
- Live Remove announced acceptance and completion, closed its confirmation dialog, and left zero Items/Documents with no pending work. Live Delete Source announced acceptance, closed its confirmation dialog, then navigated to `/admin` with a named deletion-success notice; a subsequent GET returned 404 for the temporary Source.
- DOM observations measured sync acceptance/completion notices at 5.004/5.007 seconds and the deletion-success notice at 5.004 seconds across navigation. The ordinary deletion-acceptance notice did not leak onto the destination route.
- After cleanup, the original Source retained exactly its saved credential, root and Item identities, `SPECIFIC`, public configuration `revision: 6`, `credentialRevision: 1`, `credentialStatus: ACTIVE`, one-minute interval and `scheduleRevision: 3`. All three Items remained `INDEXED`, all three Documents remained present, and the reusable credential returned to one attached Source.
- Removed the temporary browser observer after verification. Private screenshots/configuration backups remain ignored; no test Source, credential mutation, PR, merge or shared deployment is part of this delivery. Whole-My-Drive live traversal and the broader provider acceptance recorded above remain unclaimed. JetBrains IDE inspection was unavailable; the checked-in Gradle gate, compiler checks and exercised runtime are the verification evidence.

## Creation-only scope correction after Onyx review

Owner approved removing post-creation General/Specific switching. The inspected Onyx reference (`ec08b5f948165d4640f51343e04e77081b33ea32`) renders scope controls in creation and read-only connector configuration in detail. This supersedes the mode-switch acceptance above, not the recorded historical test evidence. Specific link editing remains supported; mode, credentials, existing content and schedules are not migrated.

- [x] Reject mode changes before provider access and preserve the mode in transactional root persistence; cover both directions and unchanged state.
- [x] Keep both creation choices, render saved mode without detail radios, and remove mode drafts/General selection-save controls.
- [x] Update affected regressions and contracts, run gates, then restart and inspect the actual local UI/API without changing the user's scope.

Verification after the correction:

- Refreshed the controller-owned OpenAPI snapshot and generated client; the replacement operation now documents the fixed creation-time mode.
- `./gradlew.bat clean check :api:bootJar :worker:bootJar --no-daemon --no-parallel --max-workers=1` passed with JDK 25 and the real Docling endpoint (8m 41s). No JetBrains inspection tool was available; compiler and repository gates provide the static/build evidence. Existing JVM CDS/Unsafe dependency warnings remain.
- `corepack pnpm check` passed: generated-client stability, lint, formatting, TypeScript, 14 unit-test files/68 tests and production build. `corepack pnpm test:e2e --workers=2 --retries=0` passed all 35 browser tests. Reviewed saved Specific/General mobile screenshots under `web/test-results/`.
- Restarted the local API and worker with their preserved runtime configuration; both readiness endpoints returned UP. During live API smoke, the worker remained stopped: two disposable Sources exercised both mode-change directions, each returning HTTP 400 `SOURCE_INVALID_REQUEST` with identical configuration and ETag. A same-mode Specific link edit succeeded at revision 2 and preserved its independent 17-minute schedule.
- Both disposable Sources were marked for deletion before worker restart, preventing a General crawl. Cleanup operations `029f59b8-d744-4bab-85d2-db4007d859f3` and `0a4c218d-a468-4127-8764-010a61c7a4f2` completed SUCCEEDED.
- The original Source retains SPECIFIC mode, scope revision 6, the same three roots/three Documents, credential authority revision 1, and interval 1 minute/schedule revision 3. Live detail has zero mode radios and retains its Specific links editor. Reviewed `.tmp/drive-review-private/scope-creation-only-live.jpg`; PNG capture failed in the Orca CLI, while JPEG capture succeeded without restarting Orca.

## Selection feedback and root-list polish

- [x] Move selection save/reload errors beneath the links input with accessible field association; preserve independent synchronization/interval feedback and clear the field error when editing or retrying.
- [x] Remove the Saved scope heading and replace root kind suffixes with file/folder MIME-aware icons and accessible type labels. The owner's subsequent reference replaces outline glyphs with filled color blocks and white file-type marks, implemented as local SVG without new dependencies.
- [x] Verify overlap feedback/recovery, creation/detail rendering and the actual local Source without changing its saved selection.

Verification:

- After the final filled-icon revision, `corepack pnpm check` passed API/route generation stability, lint, formatting, TypeScript, all 68 unit tests and the production build. `corepack pnpm test:e2e --workers=2 --retries=0` passed all 35 scenarios.
- The existing overlap regression now checks the error within Selected content, exactly one persistent alert, an invalid textarea described by the overlap message, retention across status refresh, clearing on edit and a successful corrected save.
- Visually inspected the mobile native-Docs selection capture at ignored `web/test-results/google-drive-source-setup--20ddc-ith-separate-explicit-roots-chromium/google-selection-saved-mobile.png`: filled blue document icon, no redundant heading and no horizontal overflow. Live Orca DOM inspection confirmed filled Google Sheets marks and the removed heading; the actual rendered Sheets SVG was exported to ignored `.tmp/drive-review-private/selection-sheet-icon.svg` and visually inspected against the requested green-block/white-mark style.
- Orca viewport screenshots returned `runtime_unavailable`; no successful live viewport capture is claimed. No live Source, credential or schedule mutation was submitted for this polish. Backend contracts and running services were intentionally unchanged; no new test file, runtime dependency or throwaway script remains.

## Explicit selection editing

- [x] Keep Specific detail links read-only until Edit selection; retain direct entry at creation and no General editor.
- [x] Use a revision-pinned draft with local Cancel, success-only return to read-only, editable failure recovery, explicit reload and focus restoration; preserve interval and authority guards.
- [x] Update existing unit/browser workflows, run frontend gates and inspect the actual edit/cancel UI without submitting changes to the user's saved selection.

Verification:

- `corepack pnpm check` passed API/route generation stability, CI-image validation, Oxlint, Oxfmt, TypeScript, all 68 unit tests and the production build. `corepack pnpm test:e2e --workers=2 --retries=0` passed all 35 scenarios.
- Existing browser coverage proves creation remains directly editable, detail starts read-only, keyboard entry focuses the textarea, Cancel restores saved links with zero roots writes, and a successful save returns to read-only and restores Edit focus. Existing unit coverage rejects typing while read-only and retains editable failure/reload recovery and authority, revision, interval and status-refresh guards.
- Visually inspected the mobile editing and saved states in `web/test-results/google-drive-source-setup--20ddc-ith-separate-explicit-roots-chromium/google-selection-editor-mobile.png` and `google-selection-saved-mobile.png`: Edit replaces save controls at rest; editing exposes Save selection, Cancel and conditional Reload without horizontal overflow.
- The actual Source at `http://127.0.0.1:8080/admin/sources/45a91ebf-c73d-47ba-95f6-04e96f7cf808` was observed read-only, then editable with textarea focus and Save/Cancel after Edit. After requesting Cancel, a fresh DOM inspection confirmed read-only with no unsaved changes. A final GET confirmed Specific scope, selection revision 10, three saved roots and schedule revision 3. No live Save was submitted. Orca control calls intermittently timed out; the visual captures above are browser-fixture screenshots, not live viewport captures.
- Canonical connector contract and verification matrix now describe the edit guard. No backend/API contract, running service or saved Source was changed; no new test file or throwaway script was introduced.

## Linked-document selection tree

- [x] Define and implement bounded content-link discovery with named targets, parent/location provenance, per-file outcomes and no automatic approval.
- [x] Persist discovery and approved targets separately; atomically save root links and target choices under current owner, credential, scope and discovery authority.
- [x] Reuse durable SOURCE_SYNC frontier, identity, membership, fencing and complete-generation pruning for approved targets without changing folder traversal.
- [x] Render the expandable tree in Selected content with view-only expansion, explicit Edit/Save/Cancel, Select all, unavailable/already-included states and manual discovery.
- [ ] Regenerate the API/client contracts, run server/frontend/browser gates, exercise actual discovery and verify saved-target synchronization using a disposable Source without modifying the user's existing selection.

Current verification evidence:

- `corepack pnpm check` passed API/client generation stability, lint/format/types, 77 unit tests and production build; `corepack pnpm test:e2e --workers=2 --retries=0` passed 36 scenarios. Desktop/mobile expanded-tree captures were visually inspected. These browser scenarios use routed API fixtures, not live Google acceptance.
- New backend discovery, approval, fencing and frontier regressions passed in earlier runs, but those runs do not establish a successful whole-repository gate. Windows runs encountered intermittent PostgreSQL socket `BindException`; Linux bridge verification rejected non-loopback HTTP upload endpoints as intended, and later host-network runs encountered Docker interruption and Ryuk connectivity failure. No production security guard was weakened and no retry or test exclusion was added. The current recovered native gate is tracked separately.
- JDK 25's XML depth admission originally raised its own malformed-input error before the reader's typed bound. The Office reader now keeps JAXP bounded at 101 while its admission rejects depth above 100 first. Migration fixtures include V21; the historical V20 test asserts its own SQL contract rather than invoking the current repository against an intentionally old schema.
- The built API and worker reached readiness on the real local runtime and Flyway recorded `21:true`. Read-only database checks confirmed the original Source's exact three root IDs, Specific scope revision 10, one-minute interval and schedule revision 3 were preserved; discovery revision remains 0. Backups and prior JARs remain private and retained.
- Live discovery/approval/saved-target synchronization is not yet verified. The prior owner session expired; normal shared Keycloak sign-in is required. Orca navigation/capture works, but snapshot/eval currently close their runtime connection, including on a fresh tab. An independent browser reached the real login page without mocks or an authorization bypass.
- All current Google inputs are self-created simulated documents. Actual Tasco source data has not been provided; no Tasco-data acceptance is claimed. JetBrains MCP/LSP inspection was unavailable; compilation and the recorded project gates are the available fallback, not an IDE-inspection claim.

Recovered native gate: `gradlew.bat clean :api:bootJar :worker:bootJar check --no-parallel --max-workers=1 --no-daemon` completed **BUILD SUCCESSFUL in 5m 6s** on JDK 25 after Docker recovery, with process-local `JAVA_TOOL_OPTIONS=-Djava.net.preferIPv4Stack=true`. Core executed afresh: 199 tests, zero failures/errors/skips. API 68, connector 55 and worker 25 test results were restored from Gradle's successful cache: 347 total, zero failures/errors/skips. Both JARs were rebuilt. This verifies the gate under the recorded environment; it does not establish the root cause or a permanent fix for the earlier socket/Ryuk failures. The temporary XML diagnostic source and owned Linux Gradle cache volume were removed; runtime/database backups remain retained.

Post-gate runtime confirmation: both rebuilt API and worker reached readiness. The original Source's saved invariant and exact root IDs still match the pre-change baseline; Flyway remains `21:true`. An unauthenticated request to its Google Drive configuration returns HTTP 401, so live acceptance remains blocked on normal owner sign-in rather than being simulated or bypassed. Latest proof was synchronized to MEM-9/MEM-10/MEM-63/MEM-60; all remain In Progress.

### Selection presentation and folder-sync diagnosis

- Follow-up screenshots exposed weak Edit affordance and read-only focus without inner spacing. Edit now uses the shared secondary button; read-only links remain copyable, transparent and non-resizable, with Read only text. Both states retain 12px horizontal/8px vertical padding. Google provider errors stay in Synchronization, without the duplicate Source-header message; unrelated Source/file errors remain.
- The final frontend `pnpm check` passed all 77 tests, lint/format/types, stable API/route generation and production build. An isolated browser using existing fixture-shaped responses exercised desktop/mobile focused read-only → Edit → Cancel: both states had 12px padding, Cancel restored read-only, zero mutation requests. A failed-source fixture displayed exactly one persistent synchronization error; screenshots were inspected. This proves presentation, not live provider behavior.
- The user subsequently added folder `1. VETC`, advancing the original Source to revision 11; this is user-owned state, not a failed preservation invariant. The folder FILE/FOLDER nodes completed, while four content files (including the three prior Sheets) exhausted acquisition attempts. Recent object-write reservations remained incomplete/discarded; the failure is after acquisition during storage staging, not folder URL validation.
- Local DNS resolver `10.22.103.10` returned `94.140.14.33` for `memoryos-objects.72-62-193-33.nip.io`, differing from the documented server `72.62.193.33`. The worker JDK's credential-free HEAD failed with TLS `internal_error`. A diagnostic request pinned to the documented IP retained hostname/certificate verification, and the current worker credential read its readiness object with HTTP 200. No secrets or signed URL are recorded. Direct independent DNS timed out; SSH to the documented server also timed out. No application TLS guard, Google selection, system DNS or hosts configuration was changed. End-to-end sync recovery requires correcting the workstation/network DNS route.

## Source history UI revision — 2026-09-09

Completed the user-approved removal of Run history and compact Indexing attempts redesign. Removed the unused run-history component and its dedicated browser scenario/fixtures; kept backend contracts and all synchronization/selection/Files controls. Indexing attempts now uses a compact semantic table, inline help/refresh, and 5/10/25/50-row cursor pages with default 5 and reset on size/Source changes.

Verification: full `pnpm check` (55 unit tests plus build/static/generated checks), all three retained enterprise browser scenarios, and a throwaway rendered-browser pagination smoke passed. Final header refinement passed focused formatting/lint and repeated smoke. Dark 1440px/390px screenshots confirm compact rendering and container-only horizontal scrolling; the smoke sent no writes or run-history requests. [Canonical verification and limitations](../../../tests/connector.md#compact-indexing-attempts-ui--2026-09-09). README, architecture and Connector contract now describe the current surface rather than the removed panel. Historical backend/run verification above remains applicable; historical UI counts are not claims about the current suite. No push, PR, shared deployment or user data change.

Follow-up screenshots — 2026-09-09: place Automatic interval/value/Edit in the Source summary card, not below or beside the Synchronization heading. Share the existing summary rendering between FILE and Google Drive without moving schedule state out of its owner. Preserve edit/cancel/save/conflict behavior and the user's saved interval. Also replace Indexing attempts' Page N indicator with current/total pages backed by an exact server count; verify page-size changes and actual rendering.

The clarified summary-card placement and server-counted current/total pagination are implemented. Shared SourceSummaryCard avoids duplicated summary markup or schedule state. OpenAPI/client generation, the focused PostgreSQL-backed API paging contract, core/API/worker compilation and the full frontend check passed. Independent Chromium smokes confirmed desktop/mobile layout, interval edit/cancel/save behavior in fixtures and 1/7 → 2/7 → 1/4 pagination under size changes. The API was repackaged and restarted with its existing runtime settings; readiness was observed. [Evidence and inspection limits](../../../tests/connector.md#summary-interval-and-exact-history-totals--2026-09-09).

## Selection clarity and discovery verification — 2026-09-09

- [x] Inspect the user's retained discovery and current selected content to distinguish unique target IDs from reference locations; exercise the authorized real discovery path.
- [x] Deduplicate direct-root/linked rows at the backend read boundary, preserving origins and consistent counts/filter/cursor behavior. Compact the UI into one list with grouped reference details and explicit document/reference semantics.
- [x] Keep help adjacent to section titles, including Indexing attempts. Preserve pending validation, whole-draft approval/save/cancel, search/paging and current user configuration.
- [x] Verify regression behavior and actual desktop/mobile UI; distinguish real-provider evidence from controlled fixtures and record access limitations.

Completed evidence is canonical in [Unique selection and real linked discovery](../../../tests/connector.md#unique-selection-and-real-linked-discovery--2026-09-09). The live bounded discovery action succeeded; the updated API exposes four unique selected rows with all eight references retained on their one target. Selection revision and interval were preserved. Two focused PostgreSQL regressions, frontend checks and the final 20-scenario browser run passed; the earlier broad backend timeout, initial browser setup timeout and unavailable IDE/Orca snapshot inspection remain explicitly recorded rather than represented as successful gates. The API was rebuilt/restarted with its existing local configuration; worker and shared services were not restarted.

### Expanded saved-links correction — 2026-09-09

- [x] Remove the duplicated section header/help/scope from the saved-links disclosure and detail editor; retain the creation scope controls and a labelled, copyable read-only field.
- [x] Verify expanded saved links and Edit/Cancel in the real authenticated dark desktop/mobile UI, as well as the existing creation/selection checks. Preserve all saved links and runtime selection state.

The [expanded-state evidence](../../../tests/connector.md#expanded-saved-links-layout--2026-09-09) records one heading/help in both copy and editing states, preserved four-link values and Cancel focus, the full frontend gate and three passing enterprise browser scenarios. The user's screenshot identifies a gap in the earlier collapsed-state verification; it is not treated as a discovery/backend failure.

### Folder-first selection and linked sync affordance — 2026-09-09

- [x] Add owner-authorized, Source-scoped, bounded lazy tree reads with actual folder contents, discovered file links, orphan retention and revision/credential fencing.
- [x] Render expandable folder/file/link branches, retain unique search results and shared draft approval across repeated references, and expose Select for sync without bypassing Save/validation.
- [x] Remove Technical paths, retain useful locations and errors, and verify files without links, paging, shared targets, keyboard operation and Cancel.
- [x] Generate the API/client contract, run backend/frontend gates, and exercise the actual authenticated Google folder/file/link surface without changing the saved selection.

### File and indexing history clarity — 2026-09-09

- [x] Inspect actual Onyx table/counter semantics against MemoryOS Source-run and per-file contracts.
- [x] Surface each current file's real indexing time and clarify its latest per-file processing details.
- [x] Replace Drive's ambiguous per-file history table with actual per-Source execution outcomes and truthful counters, preserving FILE behavior and avoiding the removed run dashboard.
- [x] Verify no-change, unknown/legacy, active indexing, failure and current-file timestamps in focused checks and the actual authenticated UI.

### Selection disclosure refinements — 2026-09-09

- [x] Move Edit selection and its editor into the below-tree disclosure; rename Copy saved root links to File and folder links and reveal Edit only while expanded.
- [x] Remove the persistent selected-folder/file/linked-document count subtitle above the tree.
- [x] Verify collapsed/expanded keyboard behavior, Select for sync opening the disclosure, Cancel focus and unchanged persisted selection on the final desktop/mobile UI.

### Reduce repetitive file and run text — 2026-09-09

- [x] Fold Added and file-attempt details into Last indexed instead of repeating subtitles beneath each filename.
- [x] Remove repeated run trigger/indexing captions and all-zero auxiliary details while retaining meaningful counters, unknown values, retry timing and errors.
- [x] Verify the quieter final desktop/mobile rows, timestamp disclosure and final frontend/browser gates.

### Files pagination bar — 2026-09-09

- [x] Replace the page/file-count/order subtitle with below-table Rows, current page and labelled previous/next icon buttons.
- [x] Preserve bounded keyset navigation, reset on size changes and refresh first-page uploads at the selected size; extend the existing concurrent-operation pagination scenario.
- [x] Verify final row-size transitions, desktop/mobile layout and serialized frontend/browser gates.

Final evidence: `clean check :api:bootJar :worker:bootJar` passed in 8m55s with reachable Docling; final `pnpm check` passed all 61 tests in 16 files plus generated-client/route stability, lint, formatting, TypeScript, build and font checks. The three Source/identity/Drive browser suites passed all 33 scenarios with two workers and no retries in 1.5m, including the extended size-change/concurrent-operation pagination scenario. The final mobile-only filename minimum-width adjustment separately passed formatter/lint and actual 390px visual verification (256px filename column, approximately 77px rows, no document overflow). Gate/client generation and browser suite startup were serialized after an earlier overlapping generation transiently removed an import.

Actual authenticated smoke verified folder → real file → linked TARGET with eight locations; draft-only selection, automatic links-disclosure expansion, keyboard and Cancel focus; actual current-file timestamps and Source run counts 3/0/3; Files sizes 5/100/25 with unchanged content and correctly disabled end navigation. Final saved draft exactly equals the baseline: scope revision 12, discovery revision 9, three roots, zero linked approvals and one-minute interval. No live mutation commands were issued. Desktop/mobile screenshots remain ignored under `.tmp/drive-review-private/`; canonical details are in the [connector verification matrix](../../../tests/connector.md#folder-tree-and-truthful-history--2026-09-09). JetBrains/LSP semantic inspection was unavailable. The increment remains active; this is not a merge or production-capacity claim.

### Plain timestamps and inline duration — 2026-09-09

- [x] Remove the Last indexed disclosure and obsolete component/test, and align its help and canonical documentation.
- [x] Render completed run duration beside the completion time without wrapping.
- [x] Verify the actual desktop/mobile surface and run frontend validation.

`pnpm check` passed 60 tests and all frontend gates. Actual desktop/mobile screenshots and bounding-box checks confirmed plain file timestamps and same-line run durations without document overflow. See [timestamp verification evidence](../../../tests/connector.md#plain-timestamps-and-inline-duration--2026-09-09).

### Independent linked-document approval — 2026-09-09

- [x] Separate root editor visibility from draft approval; preserve selection, reload, save/cancel and focus behavior.
- [x] Extend existing tree browser coverage for closed disclosure, draft-mode transitions and complete saved payload.
- [x] Verify actual desktop/mobile UI and frontend/browser gates, then reconcile canonical guidance.

Frontend check passed 60 tests and all gates; the three browser suites passed 33 scenarios. Live SAVICO approval, explicit Edit and both Cancel paths passed with unchanged saved draft and no live write. README, architecture and connector contract now distinguish approval-only controls from explicit root editing. See [independent approval evidence](../../../tests/connector.md#independent-linked-document-approval--2026-09-09).

### Reversible saved linked approval — 2026-09-09

- [x] Expose persisted approval removal without opening root editing; preserve availability and root-coverage boundaries.
- [x] Verify Cancel and saved removal preserve other approvals/roots, including unavailable approved targets.
- [x] Run browser/frontend gates, exercise the live surface without saving and align canonical guidance.

Frontend check passed 60 tests and all gates; browser suites passed 34 scenarios. Actual desktop/mobile VETC deselection and Cancel preserved the saved draft and never opened root editing. README and connector guidance now describe reversible saved approvals. See [saved-approval verification](../../../tests/connector.md#reversible-saved-linked-approval--2026-09-09).
