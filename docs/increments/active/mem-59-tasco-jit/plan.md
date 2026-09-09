# MEM-59 — Tasco browser JIT plan

## Implementation sequence

1. Add empty-default deployment allowlist configuration and strict validated ID-token claim selection at browser admission; preserve existing active-member, invitation, profile, and ActorId-only session behavior.
2. Implement the IAM-owned JIT transaction using concrete persistence and Tenant-before-Actor lock order. Create/reuse only the exact `STANDARD` identity and active MEMBER/non-manager Basic authority; reject inactive Tenant and incompatible membership, preserve invitations, and make repeat/concurrent admission idempotent.
3. Reconcile the `memoryos-web` User Session Note mapper through the existing idempotent upsert, without changing upstream providers or other client configuration.
4. Integrate the changes, exercise the observable contracts below, and record evidence before declaring verification complete. Keep the increment active until merge.

## Verification status

Implementation steps 1–3 are complete. Targeted tests and repository `clean check` passed; the actual Spring HTTP callback and PostgreSQL session path were exercised with a signed synthetic OIDC provider. See [verification](verification.md) for commands, counts, boundary details and remaining live Keycloak/Tasco acceptance. The table below retains the full acceptance contract; it is not a claim that every external-provider gate has passed.

| Gate | Required observation |
| --- | --- |
| Mapper reconciliation | In an isolated Keycloak realm, first reconcile creates the mapper on `memoryos-web`; replay reports unchanged with the same UUID/count; drift correction restores the exact contract. Other clients and upstream provider configuration remain unchanged by this addition. |
| Broker claim boundary | A brokered login emits `memoryos_identity_provider` as String in the `memoryos-web` ID token only. Local login with no broker note has no qualifying claim; access token/UserInfo do not expose it. Record safe claim metadata only, never token bytes. |
| Default and trust failures | Empty allowlist, unlisted alias, absent/blank/non-String claim, and wrong issuer confer no JIT authority. A qualifying UserInfo/access-token claim without the ID-token claim also confers none. Existing eligible invitation behavior remains available when JIT is not selected. |
| First admission | Trusted browser login with no binding creates one STANDARD Actor, exact binding, active MEMBER, and non-manager Basic for the configured Tenant; no Admin/ordinary Group grant and no invitation write. Missing/unverified email does not block JIT. |
| Exact identity | A bound Actor without membership is reused; same email on a distinct `(issuer, sub)` never links Actors. |
| Replay and contention | Repeat login and concurrent same-identity admission produce one Actor/binding/membership/Basic edge, no elevation, and no partial state. |
| Fail-closed transaction | Inactive Tenant, inactive/incompatible membership, and failed Basic provisioning deny admission; failed provisioning rolls back new Actor/binding/membership state and never consumes an invitation. |
| Existing flows | Active owner/member login remains unchanged; ordinary local invited users retain exact verified-email acceptance. A trusted JIT login with a pending invitation leaves invitation persistence untouched. |
| Session and bearer | Browser callback rotates and retains only ActorId plus existing profile observations, no provider token markers. Bearer with an unbound identity and even a qualifying provider claim remains unauthorized and creates no Actor, membership, or session. |
| Authority convergence | After application deactivation, a new trusted browser login does not reactivate membership and an existing session observes current denied authority. |
| Repository integration | Main runs the checked-in repository gates once after integration and records precise outcomes; no successful gate is presumed from compilation or mapper JSON inspection alone. |
| Broker simulator | Exercise a real Authorization Code + PKCE browser flow through an isolated broker simulator and the actual application surface. Record this as simulator evidence only. |
| Actual Tasco | Use the real approved Tasco provider/account flow to confirm alias, broker claim, first/repeat admission, and local deactivation behavior. Real-provider access is a separate acceptance prerequisite, not covered by simulator success. |

## Rollout and recovery

Reconcile the browser mapper first and verify its token boundaries. Keep `MEMORYOS_JIT_ALLOWED_PROVIDER_ALIASES` absent/empty until the environment is approved; explicitly set `tasco` only for the intended deployment and restart/redeploy the API through its managed configuration path. Do not change `MEMORYOS_IDENTITY_ISSUER` or `MEMORYOS_TENANT_ID`.

Removing the allowlist opt-in disables future JIT admission after API restart, but intentionally does not remove admitted Actors, deactivate memberships, or invalidate existing sessions. Use existing authorized membership management for application revocation. Provider-side revocation propagation remains outside MEM-59. Do not roll back by deleting identity or invitation rows.
