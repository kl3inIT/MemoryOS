# Managed local inference and application integration handoff

The remaining MEM-77 work is defined by [design](design.md) and the [phased plan](plan.md). MEM-66 supplies feasibility evidence and the small SmolLM2 model chosen for initial delivery; MEM-77 must complete an operational self-hosted serving deployment and its application integration, not select a larger model. The backend extension point is already delivered. Reuse `OpenAiChatProviderAdapter`/type `openai` unless a reproduced protocol/native-integration difference requires otherwise; endpoint/model/tokenizer/hardware differences alone do not justify another transport class.

## Reuse the delivered boundary

- Configure endpoint and real gateway credential on the provider; configure model API name, visibility, explicit limits/options/capabilities/pricing and the planned tokenizer profile on the model.
- Select the stable model configuration UUID, never resolve a model globally by its API name. Keep catalog authorization, revision conflicts, idempotency and fallback semantics.
- Reuse `ChatModelResolver`, bounded `ChatModelClients` leases, native `SpringAiLlmService`/`OptionsConverter`, the existing executor and HTTP/SSE transport.
- Keep `maxRetries(0)`, the supplied timeout, native observations, provider-reported usage and exclusively owned HTTP-client cleanup. A retired binding does not close while a turn still leases it.
- New provider creation and existing Access-field preservation follow [the deferred-Access contract](design.md#safe-administration-while-access-is-deferred). A Persona default is not an access grant.

## Remaining implementation

1. Pin the SmolLM2-135M-Instruct assets and a provisional 1,024/128 CPU envelope; record host fit, candidate topology, operational owner and measurement thresholds. Stronger answer quality is not a closure gate. Native framing is qualified before final limits are frozen, not assumed at this step.
2. Implement the [managed runtime](design.md#managed-local-inference-runtime): verified offline assets, real non-root/file permissions, private routing, independent model/gateway health, one shared generation slot/zero queue with 429, bounded output/fan-out and native absolute deadlines. Rotate inference API credentials with revision-checked catalog BYOK; retain the catalog AES key.
3. In parallel with serving, qualify CPU tokenizer/JNI packaging in Windows and the actual API container, including empty-cache denied-egress startup and true-last-use ownership. Verify the [implemented profile/request contract](design.md#implemented-tokenizer-and-budgeting-contract); no artificial dependency on future post-merge acceptance.
4. Cut over settings, legacy JSON, all native/platform binding callers and generated schemas. Use the same full-prompt policy before reservation, in history and on every native call/Validate; keep last-cycle tools-off separate and reject unsupported tool execution. The executor remains provider-independent.
5. Complete Models through truthful required/nullable generated APIs, transient-secret direct SDK writes and safe session/revision handling. Validate needs post-response provider/model revision reconciliation; it currently returns no revision metadata. Do not invent another endpoint or store BYOK in mutation-cache variables.
6. Implement the bounded authorized Persona list and exact Tenant/Persona default rules. Keep hosted deployment bootstrap/default; create local manager-only Smol through normal catalog writes, choose the builtin Persona by UUID and exercise ordinary Chat without a selector or new Access grant. Unknown local pricing uses the existing unlimited-USD sentinel, not zero prices.
7. Run integrated candidate repository/browser/native/lifecycle gates, confirm actual served alias plus weight/tokenizer/template manifest, finalize limits and obtain an approved successful-main release. This is not target acceptance. Preserve historical receipts; record precisely which native/fixture/live boundaries were exercised.
8. Deploy that exact release through the existing reservation/provenance path, preserving Search smoke and adding real Models/Chat checks. Re-run target workload/rotation/outage evidence and compatible serving-only rollback. A schema-changing application failure retains the existing operator-recovery guard; no automatic old-application/DB restore. Close only after authorized target acceptance and scope reconciliation.

The API must reach the gateway through the selected environment's private networking; host/container loopback and cross-host routing are different. The operational deployment uses a persistent authenticated gateway and unpublished inference backend with the required protected transport, not a public inference API. No gateway-control platform, Chat worker path or application smoke mode is required. Small-model host fit and operational rollout are MEM-77 work; selecting a better model or procuring a GPU is not.

Later model upgrades follow the [controlled replacement boundary](design.md#replacing-the-model-later): weights/serving identity, compatible tokenizer/profile/template, explicit limits/capabilities, measured resources and re-verification. Reuse the integration rather than rewrite it, but do not promise that every upgrade is only a UI model-name edit; a new installed profile or demonstrated protocol difference can require a targeted application change.

The [implementation plan](plan.md#fixed-implementation-decisions) owns fixed IDs/DTOs, phase dependencies, source ownership and A1–A20. Native metrics, gateway probes/logs and operator resource measurements have different sources; do not claim absent exporters, log ingestion or paging. Candidate checks precede approved main publication; final deployment evidence follows it.

## When a new adapter is justified

Only introduce another `ChatProviderAdapter` after reproducing a protocol, native option/response mapping or lifecycle requirement that cannot be represented cleanly by the current verified integration. The decision must name that difference and its runtime evidence.

If that condition is met, retain the existing extension contract: stable type and truthful credential requirement; local-only validation; native Spring AI model and Embabel service/converter; explicit token/context/output policy; exclusively owned cleanup. Do not substitute dummy credentials, copy an inference loop or branch the executor on a provider/model name.

Use `OpenAiChatProviderAdapterTest`, `ChatModelClientsTest`, `ChatTurnSetupTest` and catalog cases in `ChatSessionApiIntegrationTest` as the existing regression boundaries. `LocalAdapterFixture` proves the extension seam only; it does not certify a shipped local provider. Keep new evidence in [verification](verification.md) with its exact boundary, and do not replace the historical backend receipts.
