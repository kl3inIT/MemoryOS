# MEM-77 implementation plan

## Delivered foundation — PR #88

- [x] Add the provider adapter contract and verify native binding/options/resource ownership with tests.
- [x] Add JDBC catalog migration, model management capability, secret handling and Tenant/Group/Persona access tests.
- [x] Add backend administration, allowed-model/default/Persona APIs and safe validation responses.
- [x] Resolve send selections once in RAM; preserve idempotency and acquire/release clients through all terminal paths.
- [x] Exercise multiple model configurations, updates during a turn, denied access, fallback, Stop and provider failures through the runtime.
- [x] Inspect changed files, regenerate OpenAPI/client, run focused tests and `clean check`; document verification and adapter handoff.

These checkboxes record the original backend delivery, not completion of the remaining issue. The initial implementation started from main `287ca9c4bb79878bf49855934bddd4781f1c931a`, and PR#88 reached `anhnd` at `4ecd916bb2f18b896a0224858abdc681fd9d7146`. The user subsequently requested latest-main integration and completion of Phase1; HEAD fast-forwarded to `0310a24c03b162a34673b1e5c407aa6745bfc48e`. The pre-refresh uncommitted work is preserved in safety stash `410cbf4dad276b7fc264366394a42d3bc7e0b534` while semantic integration is verified. See [verification](verification.md). Keep main's JPA catalog lifecycle and MEM-11 workflows; do not replace the resolver, lease cache or native execution loop.

## Preserved review decisions

PR #88 owns the historical review/CI receipts. Preserve its internal HTTP/provider-manager trust policy, distinct OpenAPI validation schema and mutation headers, null-option validation, explicit deployment capabilities/pricing fail-fast, provider-association batching and client construction/cleanup outside the shared cache monitor.

## Delivery contract

The [design](design.md) owns architecture, research evidence and scope. The user has authorized repository implementation and then committing/pushing the MEM-77 changes to `anhnd`, with unrelated MEM-65/MEM-66 work retained outside that commit. Unchecked items may include implemented source with unverified acceptance clauses; the checkpoints below identify that distinction. Branch publication does not authorize a PR, main release, target deployment, Linear mutation or MEM-77 closure.

Target flow: provision and operate a private SmolLM2-135M-Instruct service on the approved target → model manager configures its provider/model through Models administration → validates the connection → sets an eligible Tenant/Persona default or supplies the model UUID → deployed Chat streams, stops and persists correctly under the accepted small-model workload. MEM-66 supplies feasibility evidence and the chosen model baseline; MEM-77 owns the operational runtime and its acceptance, not just a connection to the research stack. A larger model or stronger answer quality is not required for closure.

Access controls/association management are deferred at the user's request; existing enforcement and stored fields are not. Persona default/inherit is included. Chat model-selector UI and general Persona editing belong to MEM-11. New providers from this UI are explicitly manager-only, so local-model acceptance must not silently promote them to public or claim ordinary-member access.

Current implementation ownership: managed provisioning/runtime; serving lifecycle/release/observability; native tokenizer/binding policy; catalog/Persona/source-owned HTTP contracts; Models/default browser administration. The integration owner serializes generated artifacts, cross-slice contract integration and all validation after concurrent edits settle. Fixed shared fields are required `ModelSettings.tokenizerProfile` and adapter `tokenizerProfiles: [{id, displayName}]`; the Persona projection retains the contract below. Existing MEM-65/MEM-66 work and services are preserved. Target access/headroom/connectivity remain blocked independently; no serving acceptance is inferred from the Phase 1 asset probes.

### Pre-refresh implementation checkpoint — 2026-09-11

| Workstream | Implemented / controlled evidence | Still open |
| --- | --- | --- |
| Managed runtime and operations (2) | Pinned bounded provisioning, private Compose, readiness/fingerprint probes, preflight, maintenance/drain/rotation guards and reserved serving recovery source. Cold provision **37.70 s**, `ASSETS_PUBLISHED_AND_VERIFIED`; offline readonly UID1654 verify-only `ASSETS_VERIFIED`; **17/17** POSIX operation and **3/3** fingerprint tests pass without model import. Actual production NGINX launcher/configuration passes controlled transport/one-slot/maintenance/slow-body settlement smoke in **1.63 s**, no model loaded. | Real engine launch, real-model gateway qualification, cold/warm/offline generation, concurrency/load/co-load, native metrics and real rotation/rollback. |
| Native/catalog/API (3–4) | Required installed profile/adapter metadata, V34 legacy backfill, shared CPU tokenizer/policy, pre-reservation/history/every-call/Validate framing, raw HTTP cancellation, bounded Persona list and source-generated contract. Eight-corpus IDs match pinned HF on Windows Java25.0.2 and corrected Linux runtime **base** Java25.0.4; 1,000 shared encodes each. Real-socket cancellation **2/2**. Full `clean check` passes: **528 passed, 4 opt-in skipped**, after bounding API test-context retention without increasing heap or changing runtime behavior. | Final API candidate image/native-load gate is blocked by unavailable Docker Desktop Linux engine; the base probe does not replace it. Actual API→model prompt/usage and upstream capacity release remain unverified. |
| Models/defaults (5–6) | Models-only navigation and provider/model/Tenant/Persona forms, secret/revision/Access/race guards. Actual Vite fixture surfaces observed at1280×900 and390×844 without overflow. Full frontend static/generated/type/unit/build gates pass with110/110 unit tests; complete Chromium fixture suite passes60/60, including Models and existing application/admin flows. | Real API/model-backed normal-login workflow and selected UUID/no-fallback target evidence. |
| Integration and rollout (7–8) | Durable implementation/evidence docs, complete backend/frontend/browser receipts, workflow/shell/Compose and monitoring-rule checks, and implemented staging Chat smoke with passing secret-free collection; owned throwaway resources removed, no publication/deployment. JetBrains is configured but connection-refused, with no IDE-clean claim. | The normal image-build retry verified pinned Infisical and downloaded Gradle, then ended without an exit code; Docker's Linux engine pipe is absent and Desktop status is unavailable. No final image identity/native receipt. Staging smoke remains **unrun**; all real-model/target gates and operator/scope decisions remain open. |

These receipts precede main `0310a24`; they do not certify the merged tree. Current merge/Phase1 checks and host snapshots belong in [verification](verification.md). Docker is reachable again, but its earlier candidate build is recorded canceled and no final API-image receipt exists. Outer-host memory still fails the model-launch floor; staging SSH remains denied. Re-measure host/guest capacity before any later workload. Preserve every mixed implementation/acceptance item and A1–A20 below until its full observable boundary is proven.

### Latest-main integration — 2026-09-11

- [x] Fetch `origin/main` and fast-forward `anhnd` from `4ecd916` to `0310a24`; preserve all tracked/untracked local work in the named safety stash before applying it.
- [x] Resolve catalog/JPA and native execution conflicts without dropping main's context revisions, search tools, editor/regenerate behavior or MEM-77's profile/admission/cancellation policies.
- [x] Keep main's assistant CRUD separate from the manager projection (`/api/chat/model-personas`); migrate Models consumers and preserve private Persona ownership.
- [x] Move only the unshipped tokenizer backfill to V37; preserve main V1–V36. Regenerate OpenAPI, SDK and routes from their source owners.
- [x] Run merged-tree OpenAPI generation and backend/browser gates. Java25 `clean check` passes (12m33s); generated SDK/static/build checks, 123 frontend unit tests and 90 Chromium scenarios pass. Actual Models desktop/mobile rendering is verified. [Receipt](verification.md#merged-tree-verification); controlled UI/HTTP evidence is not real-model/target acceptance.

### Phase 2 continuation — 2026-09-11

The user requested continuing Phase2 with the described private, single-generation SmolLM2 design. Continue repository implementation and bounded isolated qualification; do not stop unrelated workloads, change SSH permissions, publish a release or bypass target acceptance. SSH is an execution mechanism, not a feature requirement.

- [x] Repair interrupted asset provisioning. The actual killed CLI now retries successfully using process-lifetime locking, while abandoned/current/previous assets remain untouched.
- [x] Preserve the accepted custom client network and selected monitoring backend during application rollback; reject incompatible client/backend networks during serving-only recovery before changing retained configuration or draining the active runtime.
- [x] Exercise pinned-image CLI, real provisioning/recovery and offline asset reuse without importing a model;18 deployment and5 managed regressions plus release/Compose/Bash/canonical ShellCheck checks pass.
- [x] Reconcile source/controlled/live evidence in the [Phase2 continuation receipt](verification.md#phase-2-continuation--2026-09-11), without converting model-free checks into engine acceptance.

The first outer-host snapshot is3,854,811,136 available bytes; the later10:48Z snapshot is2,879,692,800. Both are below5,469,372,416 required for model startup. Guest available memory was6,353,043,456 bytes with zero swap use. Real-model startup/generation and deployed lifecycle/monitoring acceptance remain blocked, not completed by the source fixes.

### Fixed implementation decisions

| Boundary | Decision before coding |
| --- | --- |
| Initial model | Pinned SmolLM2-135M-Instruct CPU deployment; provisional 1,024 total context / 128 output; larger-model quality is not a gate |
| Catalog bootstrap | Keep existing hosted deployment/Tenant default; create local manager-only provider/model through normal API/UI and encrypted BYOK; no local environment-import shortcut |
| Native/profile contract | Existing `openai` adapter; required `tokenizerProfile`; `openai-o200k-v1` and `smollm2-135m-12fd25f-v1`; adapter descriptors expose required `tokenizerProfiles: [{id, displayName}]` |
| Admission | One shared gateway generation slot, queue/wait zero, excess `429`; existing global Chat/Validate admission unchanged; no queue service |
| Prompt safety | Same immutable policy for mandatory-prompt admission before reservation, bounded history and every native request; separate last-cycle tools-off |
| Pricing | Local pricing null; existing `MEMORYOS_CHAT_COST_BUDGET_USD=1.7976931348623157E308`; token/deadline/output/resource limits remain finite |
| Rotation | Inference API key + revision-checked local BYOK replacement; retain catalog AES key; no new re-encryption system |
| Publication | Candidate verification → approved merge/main CI bundle → reserved target rollout/smoke → acceptance; no PR-build deployment or health-only closure |

Numeric host capacity, latency/startup/Stop thresholds, final context/body limits, native packaging and cancellation behavior are **implementation measurements**, not missing product decisions or already-proven facts. Record the chosen target/operator/access and limits before target workloads. Repository work can begin now without publishing/deploying or changing external issue state.

### Source ownership

| Area | Existing integration targets; new work |
| --- | --- |
| Settings/persistence | `core/.../chat/catalog/ModelSettings.java`, `ModelCatalogService.java`, `ChatProviderAdapters.java`, `ChatProviderAdapter.java`, `chat/persistence/ModelCatalogRepository.java` and JPA lifecycle repositories; unshipped tokenizer migration V37 after main V36 |
| Native composition | `api/.../chat/OpenAiChatProviderAdapter.java`, `OpenAiChatProviderConfiguration.java`, `ChatModelCatalogConfiguration.java`; tokenizer/profile owner in API composition; `gradle/libs.versions.toml`, `api/build.gradle.kts`, `Dockerfile`/API assets as measured |
| Budget/lifetime | `core/.../chat/execution/ChatModelBinding.java`, `ChatTurnSetup.java`, `ChatModelGuard.java`; `chat/application/ChatTurnPersistence.java`, `chat/ChatTurnService.java`, catalog resolver/client leases; no replacement executor |
| API | `api/.../chat/ChatModelCatalogController.java`, `ChatModelValidation.java`, `api/.../OpenApiConfiguration.java`; profile metadata, Persona selector and truthful existing catalog schemas |
| Browser | Proposed `web/src/features/models/` and `/admin/models`; `routes/_authenticated.admin.tsx`, app shell/account menu, identity access/session boundary and existing generated SDK/query conventions |
| Generated contract | Controller/type annotations → `openapi.yml` → `web/src/lib/hey-api/`; never hand-edit the generated snapshot/client as a substitute for source changes |
| Managed inference | Proposed `infrastructure/deployment/compose.inference.yaml`, environment-owned SmolLM2/serving manifest and normal secret provisioning; reuse reviewed mechanisms under `infrastructure/inference/` without copying its temporary credentials or research-only lifecycle |
| Operations/release | Existing `infrastructure/observability/` Prometheus/Grafana/alerts, `infrastructure/deployment/deploy-staging.sh`, CI/release configuration and applicable environment overlays; existing `docs/runbooks/ci-cd.md#managed-inference-operations` |

`core/...` abbreviates `core/src/main/java/io/memoryos`; `api/...` abbreviates `api/src/main/java/io/memoryos/api`. The table preserves planning ownership; managed Compose/source and Models paths now exist. Operational documentation is consolidated in the existing [CI/CD runbook](../../../runbooks/ci-cd.md#managed-inference-operations), not a new runbook. Existing tests are mapped in [Chat verification](../../../tests/chat.md); source existence does not replace an executed receipt.

## Prerequisites and scope reconciliation

- [ ] Record the user-approved Access deferral and its authorization owner in the issue execution record before requesting full issue closure. Replace any unconditional “new local adapter” wording with verified OpenAI-compatible integration/profile support; do not claim the original unamended scope is complete.
- [ ] Confirm integration ownership with MEM-11 for model UUID selection, actual-selection/fallback responses, supported capabilities and Persona default versus editor work. Changes to shared Chat files have one integration owner.
- [ ] Preserve unrelated dirty MEM-65/MEM-66 work and existing review databases. Use isolated verification databases; do not run the new migration layout against `memoryos_main_review` or `memoryos_drive_review`. Reuse only reviewed, explicitly owned committed research mechanisms, or extract minimal managed mechanisms into owned changes; every release-mounted path must exist in the selected commit. Do not broadly stage infrastructure or modify another increment's historical evidence.
- [ ] Verify environment-owned credentials without printing them: hosted provider, local gateway/vLLM and existing catalog AES key. Keep hosted bootstrap unchanged; provision local BYOK through normal catalog writes. Apply the explicit unknown-pricing/unlimited-USD policy above; a finite USD budget with unknown prices is a blocker, not a reason to fabricate zero.
- [ ] Use Java 25, Node 24, the checked-in wrapper and pinned package manager. Name the candidate environment separately from the authorized target/operator. Read actual CPU features, resource/disk headroom and competing workload before starting inference; the research 5-GiB-free prerequisite is not a production sizing formula. Do not stop unrelated workloads to manufacture a pass.

Exit: the work has an explicit scope, owner/dependency contract and safe verification environment. Missing live capacity/credentials are named blockers, not fixture-based completion.

## Phase 1 — Confirm the small-model baseline and serving host

- [x] Adopt `HuggingFaceTB/SmolLM2-135M-Instruct` at `12fd25f77366fa6b3b4b768ec3050bf629380bac` as the initial model. Record license and known language/reasoning limitations; no larger-model search or language-quality benchmark is a prerequisite.
- [ ] Inspect actual target CPU/RAM, disk/cache, network and competing application/Docling load for the small CPU deployment. Confirm safe host fit; do not assume GPU procurement, extra hardware or target headroom from the laptop trial.
- [x] Pin model weights, tokenizer, chat template, serving image, dtype/quantization and served model identity. Declare text streaming supported and tools/vision/reasoning disabled; retain existing capable provider behavior.
- [x] Predeclare the measurement matrix: cold/warm startup, one generation, burst beyond one, Chat/Validate collision, near-context Unicode/history, Stop/deadline/outage and sustained low load alongside existing application work. Record numeric first-content/e2e, startup, Stop-release, rejection-response, duration and peak-resource thresholds before acceptance runs; source cannot establish honest target latency numbers.
- [x] Treat 1,024/128 and the research CPU envelope as provisional. Pin model/tokenizer/template assets and record candidate context/body/output limits; final native framing and any context adjustment are resolved through Phases 3–4, not falsely completed before the profile exists.
- [ ] Record the candidate manifest and operational owner, CPU instruction/thread/cgroup requirements, actual Docker/cache disk budget, private topology and secret references. Confirm application and narrowly scoped monitoring connectivity without assigning public model ingress or assuming a GPU.

Exit: pinned small-model assets, candidate host/topology, a safe provisional envelope and measurement protocol exist. This is permission for isolated qualification, not final full-prompt/capacity acceptance. Hardware/access blockers do not block unrelated contract/UI work.

Phase 1 execution: [candidate manifest](candidate-manifest.json), [measurement protocol](design.md#phase-1-measurement-protocol) and [latest-main/host recheck](verification.md#latest-main-integration-and-phase-1-recheck--2026-09-11). Assets and the offline serving-image tokenizer loader are verified. The user selected existing staging `72.62.193.33`, but the available SSH identity is authentication-denied. Local resource/disk observations are recorded and Docker is reachable again; conservative Windows memory headroom still fails the declared model-start floor. Target workload/headroom, named operational owner and private application/Prometheus connectivity remain **blocked**. Source/UI and controlled qualification are verified independently; they do not close the unchecked host/connectivity items or claim serving activation.

## Phase 2 — Implement the managed inference deployment

- [ ] Add the normal managed composition and versioned serving manifest described in [design](design.md#release-and-target-environment-acceptance). Exercise it first in an isolated candidate environment. Keep research Compose/credentials/evidence separate; no runtime depends on `.tmp`, uncommitted launchers or a one-shot application profile.
- [ ] Implement bounded, checksum/size-verified provisioning of the required pinned files only, atomically publishing readonly model assets. Use explicit local model/tokenizer paths and served alias, separate writable compiler/runtime caches, current/previous retention and actual filesystem free-space checks. Prove cold provisioning, offline restart with empty/warm runtime caches against provisioned assets, and explicit corrupt/missing-asset failure without silent downloads.
- [ ] Enforce private API→gateway→unpublished engine topology, exact authenticated GET-models/POST-generation routes and transport appropriate to the network. Ordinary serving has no download/usage-telemetry egress; only provisioning gets controlled egress. Freeze numeric UIDs/GIDs, secret/volume permissions, writable paths, restart/log/stop settings and justified capabilities; prove startup under the actual identities.
- [ ] Implement the bounded inference API-key rotation procedure: block new local generations, drain or cancel/settle, replace files, recreate/remount both services, revision-check BYOK replacement, verify new-key success/old-key rejection and resume. Keep catalog AES key unchanged and backed up. Do not claim dual-key rotation, automatic secret reference discovery or AES re-encryption.
- [ ] Implement OSS gateway one-slot/zero-queue admission with immediate 429 shared by Chat/Validate; exclude authenticated model-list probes. Qualify vLLM scheduling, `n=1` fan-out and server-side output limits, including omitted/raised token fields. Bound body/header/connections and inactivity; preserve absolute native deadlines and no upstream retry. Do not lower all hosted Chat concurrency or add an application queue.
- [ ] Separate engine health, expected model/manifest, authenticated gateway readiness and generation smoke. Give cold inference its own measured startup allowance. Do not add an application health dependency on inference; verify dynamic upstream resolution after recreation and continued identity/admin/hosted operation during outage.
- [ ] Add Prometheus-only private native scrape and dashboards/rules using the [signal-source contract](design.md#observability-and-operations). Verify real series/units/labels and alert evaluation; no assumed host/container exporter, gateway-log ingestion or paging. Keep operator peak cgroup/disk/restart checks, safe bounded logs and telemetry-failure independence.
- [ ] Extend release validation and the existing reserved deploy/finish/recovery transaction for the serving manifest, pinned third-party digests, asset readiness and accepted/previous receipts. Preserve API/worker/web provenance loops; do not require MemoryOS SHA labels on third-party images. Include an explicit apply/check receipt for the separate monitoring stack or separate serving host.
- [ ] Implement bounded drain and compatible serving-only rollback, retaining required assets/profile support and valid secret references. Preserve the Flyway-history guard; the first schema-changing application release requires explicit operator forward-repair/recovery on failure, not automatic old-binary/DB restore.
- [ ] Document install/provision/start/stop, cold/warm/offline readiness, rotation, overload, diagnostics, cache/disk recovery and upgrade/rollback. Extend owning Compose/shell/Python/configuration CI checks without running real model workloads in generic CI.

Exit: the managed runtime and lifecycle are reproducible in the candidate environment. Native integration may proceed as soon as that endpoint is usable; release/monitoring work need not block isolated tokenizer/API work. Final target evidence is Phase 8.

## Phase 3 — Qualify native assets and freeze request-policy contracts

Work alongside Phase 2 after model/assets are pinned. Use isolated native/tokenizer probes to choose the dependency and request policy; they are not product acceptance. Phases 3–4 form one native implementation workstream: contract preparation does not wait for a future live exit, and final integrated verification happens after the binding is wired in Phase 7.

- [ ] Exercise the current OpenAI native client/options path against the managed candidate gateway and pinned small model using its real credential and candidate URL. Record actual output-limit field, sampling fields, stream options, terminal finish and usage mapping; this does not require a future published target release.
- [ ] Qualify DJL with explicit CPU flavor on Java 25/Windows and in the final API-image environment: actual UID 1654, readonly root, bounded writable extraction and native-memory budget. Package native JNI/companion libraries and tokenizer-only assets; an empty runtime cache with egress denied must not fetch from Hub, DJL/CDN or CUDA sources. Keep weights and inference engines out of API/worker.
- [ ] Compare Java counts with pinned HF for English, Vietnamese/non-ASCII, long text, role markers and histories. Disable raw-count truncation/padding/implicit special tokens. Compare the actual rendered native prompt with the deployed template and provider prompt usage where available; raw text counts alone are insufficient.
- [ ] Freeze one immutable binding policy covering mandatory prompt before `insertPair`, newest-first history and every resulting native Prompt after Embabel contributions/tool conversion. If required contributions are not safely predictable, prepare/freeze them before reservation. Preserve idempotent replay and separate last-cycle tools-off; Validate must explicitly use the shared policy.
- [ ] Prove shared-tokenizer concurrency/native memory and ownership across construction failure, profile retirement and shutdown exceeding the current drain timeout. No vocabulary allocation per turn/revision; do not close native assets while any binding/turn still uses them.
- [ ] Check Stop before response headers/first token and during streaming. Source shows a possible pre-header SDK-close gap; record actual upstream work/slot release. If it fails, fix the narrow native cancellation path and keep the reproduction, rather than adding a differently named adapter with the same defect.

Exit: dependency/asset ownership and minimal request-policy contracts have evidence sufficient to implement the binding. Full product prompt fit, final serving limits, native cancellation and target capacity remain integration gates—not reasons to invent another adapter or claim the research run completed them.

## Phase 4 — Minimal backend and generated-contract cutover

- [ ] Add required `tokenizerProfile` and required nested adapter `tokenizerProfiles: [{id, displayName}]` using the fixed IDs above. API composition owns immutable compatibility metadata/native loading; core has no DJL dependency. Reject missing/unknown/incompatible profiles locally without initializing assets in a catalog transaction.
- [ ] Append the next free migration after V33, backfilling legacy settings only to O200K while retaining IDs/revisions/associations/history. Inventory all settings/binding constructors, deployment import, `OpenAiChatProviderConfiguration` platform metadata, convenience paths and fixtures; cut them over together, without implicit local O200K fallback or permanent missing-field aliases.
- [ ] Load native assets outside DB transactions/cache monitor; use the existing revision-based leases and true-last-use asset ownership. Initialization failure must release reserved client capacity/resources; active turns retain old bindings through edits, deletion and bounded shutdown.
- [ ] Apply the qualified policy at pre-reservation admission/history and every native request, including Validate. Remove unsupported callbacks/tool names/choice/related options and reject unsupported media/tool responses before execution/continuation. Preserve capable hosted bindings, independent last-cycle policy, output reservation and native accounting; no provider-name executor branch.
- [ ] Add the Tenant-bounded, `MODELS_MANAGE` Persona projection with default 25/max 100, ascending UUID keyset, validated Tenant-owned cursor anchor, `limit+1` and null termination. Provision builtin after authorization without resetting catalog selection/revision. Keep SQL/mapping in the repository; return only ID/name.
- [ ] Correct source-owned required/nullable catalog response schemas and document actual request requirements through existing OpenAPI composition conventions. Preserve nullable pricing/Persona selection/failureCode and required IDs/revisions/Access fields; do not handwrite UI types, guess revisions or impose unrelated new request strictness.
- [ ] Generate OpenAPI/Hey API and freeze the profile/settings/Persona shapes before dependent UI work. Preserve `X-MemoryOS-CSRF: 1`, existing query-revision mutation signatures and saved selection identity.
- [ ] Extend owning regressions only for credible risks: real legacy JSON migration/readback, invalid profile writes, raw-fits/framing-overflows rejection before tree mutation, exact-fit Unicode/history, every-call capability enforcement, foreign/invalid Persona cursors, lease/asset shutdown races and required/nullable generated contracts. Inspect available symbol references before exported-contract changes.

Exit: real catalog writes/readback and a normal send can select the local profile without changing the executor or bypassing IAM. The existing hosted profile still works. No speculative discovery endpoint, extra inference endpoint or local-only runtime mode is introduced.

## Phase 5 — Models navigation and provider/model administration

- [ ] Add Models explicitly to `AdminPage`, route classification/title/sidebar and shared `useAdminAccess`. Preserve Sources → Groups → Users precedence and use Models as the new fallback. Both sidebar/account-menu entries work for Models-only managers; denied deep links render no protected query. Same-actor authority removal clears private state.
- [ ] Use management provider/configured-model reads, not `/api/chat/models`, for administration/defaults. Existing caps are 64 providers and 256 total Tenant models; do not retrofit pagination or fetch every provider on every render. Keep hidden/disabled configurations editable and disambiguate equal names by provider UUID.
- [ ] Provider forms cover name, installed readonly-on-edit adapter type, endpoint, enabled state and credential status; preserve accepted internal HTTP. `credentialConfigured` is presence, not proof of decryption or connectivity.
- [ ] Implement KEEP/REPLACE/REMOVE with transient secret refs and direct generated SDK calls, following the existing Google credential form. Keep keys out of React Query variables/data/errors and persistent state. Clear on consumption/settlement/close/session-authority/page changes; ignore stale completions and route direct 401/403 to session refresh/purge. KEEP/REMOVE omit value; REMOVE requires explicit disable for REQUIRED credentials and cannot bypass Tenant-default protection.
- [ ] New providers are explicitly manager-only. Editing retains one coherent baseline of revision and exact Access arrays/flags. Background refetch must not attach a new revision to old Access/draft values. On 409 retain only the non-secret draft; reconcile the whole baseline before manual retry, never auto-retry or classify every conflict as duplicate name.
- [ ] Model forms enforce context 256–10,000,000, output >=1 and strictly below context, streaming enabled, installed profile and complete nullable pricing pair. Distinguish maxOutputTokens from boolean maxCompletionTokens. Optional blank values are omitted, never null scalars; explicit zero prices are known zero, not Unknown.
- [ ] Offer only existing options: temperature 0–2, topP 0–1, frequency/presence penalties −2–2, completion-token family and reasoning effort minimal/low/medium/high. Switching to completion-token mode removes sampling keys; disabling reasoning removes effort. Local text-only policy must not overwrite capable hosted settings; no arbitrary JSON editor or model-name inference.
- [ ] Validate only clean saved state. Capture provider/model IDs and revisions plus local/session generation; after the response, refetch both existing reads and show a result only if all still match. Discard late/stale/unreconciled results after edits/switch/close/privilege change. Handle HTTP 200/false separately from HTTP 400/403/503; do not expose provider payloads or assert an encryption-specific diagnosis from generic 503.
- [ ] Use generated mutation signatures: entity update/delete takes its query revision; Tenant/Persona default uses its own selection revision; Inherit omits model ID; all unsafe requests include the common header. Refresh affected provider/model/default/validation and all cached affected Persona selections after mutations/deletion; retain transcript and acquire fresh selection revisions.
- [ ] Verify desktop/narrow surfaces, keyboard/focus, loading/empty/error states and real denial. Keep consumer regressions for secret-cache isolation, same-actor revoke/late response, stale validation/Access, option transitions and dependent cache invalidation—not hook wiring or copied fixture defaults.

Exit: model management is usable through the real generated API, not only HTTP scripts. Existing Users/Groups/Sources entry rules and session-change cache clearing remain intact.

## Phase 6 — Tenant and Persona model defaults

- [ ] Tenant candidates use the design's actual server predicate: visible model; installed/enabled/credential-usable/public provider; empty Persona allowlist. A public provider with Group associations remains eligible. Explain why manager-only local providers are excluded; no Access promotion.
- [ ] Use the bounded Persona selector and existing get/set selection endpoints. Respect the selected Persona's allowlist even for managers; preserve/label a saved hidden selection, with explicit Inherit. No general Persona prompt/tool/Access editor.
- [ ] Prove explicit model → Persona default → Tenant default with actual selected/fallback IDs. Selection grants no access. Distinguish provider/model revision from Tenant/Persona selection revision; Inherit omits the query model ID.
- [ ] Preserve Tenant-default hide/delete/provider-disable/key-remove protection. Deleting another model/provider clears all affected Persona defaults and advances their revisions; refresh cached selections and retain transcript rather than blocking deletion because of Persona references.
- [ ] Real browser route: create/Validate manager-only local provider/model → select builtin Persona returned by the new list → set local UUID → send in ordinary Chat without adding a model selector. Inspect 202 selected UUID/no fallback and native manifest identity. An ordinary non-manager falls back to the authorized hosted default with `SELECTION_UNAVAILABLE`; Inherit restores normal hosted selection. Match builtin by UUID/session personaId, not a globally unique display name.

Exit: both default workflows operate from administration, use current revisions and produce the selected runtime binding without a manual database edit.

## Phase 7 — Integrated candidate verification and release preparation

Run normal API/isolated DB/identity/browser plus the managed candidate composition after Phases 3–4 are wired and UI/defaults are ready. This is pre-merge product evidence, not a staging release. Freeze the final context/body/output/profile manifest only after full native request qualification, then use it for workload checks.

- [ ] Exercise two real paths: existing hosted provider and managed SmolLM2, with genuine credentials and the fixed unknown-pricing policy. A fixture adapter, historical research run or two records at one endpoint is not this gate; a larger local model is not required. Missing hosted credentials block that regression, not independent local implementation.
- [ ] Trace UUID and provider/model revisions → authorized binding → Embabel/Spring AI/SDK → authenticated gateway → expected served alias/release manifest → SSE/transcript. Capture actual wire model and manifest hashes; persisted configured model_name alone does not attest upstream weights. Preserve idempotency and do not add transcript configuration snapshots.
- [ ] Send short and boundary-size English/Vietnamese inputs, then a follow-up using prior context; reload history. Prove encoding, prompt/history preservation and real generation. Record weak answers as a known model limitation, not a reason to replace the small baseline; this is not language/reasoning-quality certification.
- [ ] Prove prompt plus effective output fits the target's explicit context limit, history uses the bound profile, and an oversized new question is rejected without advancing the tree. Include framework-added content. Use the 1,024/128 baseline if the full-request gate accepts it, otherwise use and explain the measured manifest/catalog adjustment.
- [ ] Verify content arrives incrementally; stop/length finish and trailing usage-only frames are handled; EOF without a valid finish is not success. Missing usage stays unknown and pricing is not invented.
- [ ] Stop before headers/first token, mid-stream and near completion; observe the authoritative DB winner and retained partial text. Verify upstream work stops and capacity can serve the next request. Browser/SSE disconnect alone must not cancel execution.
- [ ] Exercise wrong credentials, unsupported model, context overflow and gateway unavailability against the real environment. Use controlled transport faults for precise 429/503/timeout/partial-EOF races and label that evidence as fixture-based; never retry a partially streamed completion automatically.
- [ ] Verify same API model name under distinct endpoints and in-flight endpoint/key/options/profile edits. Old turns retain old leases; later turns use new revisions; repeats retain accepted IDs; retired assets close after real last use, including shutdown timeout. Use labeled fixtures for deterministic races; drain before replacing weights behind a stable endpoint.
- [ ] Execute the declared workload matrix: cold/warm readiness, single stream, burst >1, Chat/Validate contention, boundary Unicode/history, Stop/deadline and sustained low load with other services running. Observe one slot/zero queue, correct rejected-turn outcome, peak CPU/cgroup memory/swap/throttling/disk growth and recovery. Probe GET-models while busy; test omitted/raised output limits and `n>1`.
- [ ] Confirm native metric units/rules, safe logs, operator resource receipts and inference-outage independence from aggregate application health and identity/admin/hosted Chat. Rehearse restart/cache reuse/API-key rotation and compatible serving recovery on the candidate; do not rotate AES or infer a paging system.
- [ ] Inspect all changed IDE-supported files with JetBrains warnings enabled, then compile; if unavailable, record the limitation and use wrapper/config parsers without claiming IDE-clean. Run focused owning regressions, generated-contract/route checks, `clean check` and frontend gates on the integrated candidate. Validate release archive completeness and every supported Compose/launcher/manifest contract. Run normal actual browser/native smoke; fixture OAuth/model checks remain labeled.
- [ ] After smoke succeeds, update durable specs/test matrices/architecture/runbooks with delivered facts and record exact candidate identities/results/limits in verification. When publication is authorized, use latest-head review and CI, merge approved changes and obtain the successful main release bundle. Do not deploy a PR build or pre-label target acceptance as passed.

Exit: repository gates and candidate product smoke pass, final manifest is qualified and an approved successful-main artifact is available for target rollout. Post-merge target evidence is deliberately not a pre-merge PR requirement.

## Phase 8 — Authorized target rollout and operational acceptance

- [ ] Obtain the explicit rollout/target/secret/operator prerequisites; verify selected CI SHA, bundle checksums, three application digests and serving manifest. Preflight target capacity, retained previous assets/profile, secret readability, backup and monitoring apply path before disturbing traffic. No automatic activation of staging deployment.
- [ ] Use the existing lock/reservation: bounded local drain/cancel and terminal settlement → serving/model readiness → applicable API migration/readiness then worker/web rollout. Preserve unrelated hosted/admin availability for serving-only changes. Verify actual image/model/profile/configuration identities, not just container running.
- [ ] Run existing authenticated upload/indexing/Search/reader smoke **and** Models/default/Chat smoke on the deployed release. Add the owning staging Chat scenario to the existing tooling (`web/tests/staging/`, `pnpm --dir web test:staging`) without granting its actor new production permissions implicitly; a separate authorized model manager may perform the admin acceptance.
- [ ] Re-run the critical Phase 7 workload and lifecycle matrix on the actual target; record pass/fail against predeclared thresholds, real native cancellation/slot release, cold/warm/offline readiness, API credential rotation, safe metrics and app-outage isolation. Candidate/laptop evidence cannot stand in for target resources or latency.
- [ ] Rehearse compatible serving-only rollback with unchanged schema and an application supporting the prior profile; re-run real Chat and existing smoke before finish releases pending. A schema-changing first rollout failure instead retains the guard/reservation/stopped writers for approved forward repair/operator recovery. Do not promise automatic old-application or DB rollback.
- [ ] Append exact release/model/target receipts and remaining limits to verification and reconcile Access deferral/operational ownership in Linear when authorized. Accept the runtime only after all required target smoke passes. Keep increment active after code merge until target acceptance and scope reconciliation complete; then archive and reconcile roadmap.

Exit: the exact approved release is accepted on the target with A1–A20 evidence, compatible serving rollback/recovery receipts and reconciled scope/ownership. Missing target access or live evidence leaves this phase open, not the plan ambiguous or a larger model required.

Schedule concrete cleanup only after the runtime smoke has succeeded, using the actual probes/users/catalog records/processes created during that run. Preserve unrelated local work, existing credentials, containers and model cache, and record the cleanup boundary with the evidence.

Commands for the integrated candidate/final repository gates in Phase 7 (not executed by this planning change; focused tests run while implementing):

```powershell
.\gradlew.bat :core:compileJava :api:compileJava :worker:compileJava
.\gradlew.bat clean check --no-daemon
pnpm --dir web check
pnpm --dir web test:e2e
```

Regenerate the backend snapshot using `MEMORYOS_OPENAPI_WRITE=true` and `:api:test --tests "*OpenApiContractTest*"`, unset the write flag, run the contract test again, then `pnpm --dir web generate:api`; use the exact procedure in the [README](../../../../README.md#refresh-the-generated-api-contract).

## Acceptance checklist

| ID | Required observable result | Owning phase / evidence boundary |
| --- | --- | --- |
| A1 | Models-only manager reaches both admin entries; denied/revoked route mounts no private queries | 5, 7–8; browser/server authority boundary |
| A2 | Provider/model forms preserve options, limits and coherent revision/Access baselines through errors and conflicts | 4–5, 7; real API/DB/browser |
| A3 | BYOK actions/redaction work with no secret in query/mutation cache or retained session/draft state | 5, 7; consumer secret/authority-race regression |
| A4 | Deferred Access survives edits; manager-only creation/defaults never grant Tenant-wide use | 5–6, 7–8; server and selected/fallback identity |
| A5 | Tenant/Persona eligibility, hidden selection, Inherit, revisions and deletion cleanup match server semantics | 6, 7–8; API/DB/browser |
| A6 | Validate reconciles both saved revisions and handles HTTP 200/false versus HTTP failure without stale success | 5, 7–8; race regression and real connection |
| A7 | Existing OpenAI-compatible adapter serves hosted and managed small-local paths | 3–4, 7–8; two actual provider paths |
| A8 | Profiles/assets match; legacy JSON migrates; native CPU loading is offline and safe under the actual API image/lease lifetime | 3–4, 7; migration/native/container boundaries |
| A9 | Full mandatory prompt overflow leaves tree unchanged; exact-fit Unicode/history and every-call capabilities/output are correct | 3–4, 7–8; native request and DB evidence |
| A10 | Stream finish/usage/EOF/error/partial persistence is correct; unknown pricing/usage stay unknown | 7–8; native paths plus labeled fault fixtures |
| A11 | Early/midstream Stop and deadline release real upstream work; SSE disconnect alone does not | 3–4, 7–8; upstream observation and next admission |
| A12 | UUID/revision identity, idempotency, active edits and true-last-use cleanup hold; manifest attests served weights | 4, 7–8; deterministic races and live release identity |
| A13 | Source-generated required/nullable DTOs, headers, bounded Persona projection and repository/config gates are proven | 4, 7; generated consumers, scoped paging and exact-tree receipts |
| A14 | MEM-11/IAM/MEM-66 and deferred Access ownership are reconciled without false issue closure | Prerequisites, 8; authorized scope/execution record |
| A15 | Licensed pinned SmolLM2 completes real text Chat on measured hardware; quality limitations are explicit | 1, 7–8; small-model scenarios, no larger-model gate |
| A16 | Exact private serving assets and model/gateway readiness survive cold/warm/offline restart without coupling app health | 2, 7–8; actual identities/filesystem/network |
| A17 | Inference API-key rotation and revision-checked BYOK accept new/reject old while catalog AES key remains intact | 2, 7–8; deployed maintenance/recovery |
| A18 | Shared one-slot/zero-queue overload, output/fan-out, absolute deadlines and measured resource ceilings hold | 1–2, 7–8; declared workload and existing-app impact |
| A19 | Real bounded native metrics/dashboards/rules and separate operator resource/log receipts match stated sources | 2, 7–8; no invented exporter, log ingestion or paging |
| A20 | Verified-main rollout preserves existing smoke and passes Chat plus schema-compatible serving rollback | 2, 7–8; release/target receipts and guarded recovery |

## Dependency order and stop conditions

Phase 1 pins assets and a provisional environment envelope. Managed runtime/release work in Phase 2 and isolated native qualification in Phase 3 proceed independently; Phase 4 wires the measured policy and freezes generated DTOs. UI/default work in Phases 5–6 can proceed once its DTO slice is stable, without waiting for every operational task. Phase 7 combines those streams, finalizes manifest/limits, runs repository and candidate-product gates and obtains an approved main release. Phase 8 deploys that exact release and records target acceptance before closure. No phase requires evidence that can only exist after its own publication.

| Workstream | Exclusive mutation ownership and handoff |
| --- | --- |
| Serving/release | Inference/deployment/observability and CI owners; hand off candidate endpoint, manifest, limits, secret references and lifecycle procedures—not secret values |
| Native/catalog | One integration owner for settings, binding, guard, admission/persistence, profiles, migration, API schemas and generated contract; coordinate API/Gradle/Docker native packaging with serving owner |
| Browser/defaults | Models UI and existing shell/session integration; consume the frozen generated contract; no hand-edited OpenAPI/client, Access workflow or Chat selector |
| Integration/release | One owner serializes shared contracts/files, final validation and approved rollout; each mergeable delivery slice must remain usable and independently checked, not ship dormant scaffolding |

Stop only the gate requiring unavailable native packaging, target access/headroom, credentials or authorization; continue independent owned work. Record pending evidence with its exact boundary. Repository implementation can start with Phase 1 and the independent contract work; do not substitute fixtures/research receipts for acceptance, silently choose a larger model, or create another adapter to conceal token/cancellation errors.

### Source-backed readiness review

This planning review inspected source and existing test definitions, not a newly executed runtime. Its critical anchors are [bootstrap/pricing](../../../../api/src/main/java/io/memoryos/api/chat/ChatModelCatalogConfiguration.java), [pre-reservation admission](../../../../core/src/main/java/io/memoryos/chat/application/ChatTurnPersistence.java), [native guard](../../../../core/src/main/java/io/memoryos/chat/execution/ChatModelGuard.java), [validation](../../../../api/src/main/java/io/memoryos/api/chat/ChatModelValidation.java), [generated catalog shapes](../../../../web/src/lib/hey-api/types.gen.ts), [secret-form precedent](../../../../web/src/features/sources/google-drive-panel.tsx), [deployment transaction](../../../../infrastructure/deployment/deploy-staging.sh) and [staging release/smoke](../../../../.github/workflows/deploy-staging.yml).

The planning anchors above are historical source review, not new runtime evidence. Native Java/DJL token IDs and concurrency, corrected runtime-base loading, real-socket cancellation and fixture Models surfaces now have [controlled receipts](verification.md#controlled-local-receipts); final API-image verification is blocked by the unavailable Docker engine, while complete native/provider framing, target resource/latency and exact-release operational acceptance remain pending. The public Linear URL exposes only its application shell here; issue metadata was not revalidated or changed. Accepted user scope and repository contracts guide implementation; authorized issue reconciliation remains required before closure.
