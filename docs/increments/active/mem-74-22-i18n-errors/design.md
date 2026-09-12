# MEM-74 / MEM-22 — Account language and problem presentation

Status: scoped implementation complete locally; repository/frontend gates passed. Release, live acceptance and Linear closure have not been performed. See verification for browser results and opt-in test boundaries.

## Accepted scope

Publication follow-up (2026-09-12): the user subsequently authorized scoped commits and a pull request. Use the current checkout without a new worktree; preserve unrelated documentation/OCR changes. This does not authorize merge, deploy or Linear mutations.

One combined increment covers Vietnamese/English UI localization and consistent problem presentation across the current authenticated application, invitation/session screens, Chat, attachments, Search/reader and Sources/Users/Groups administration. It includes the user-approved account-language hint for Chat. OCR, landing, Keycloak themes/emails, document translation and dependency backlog are excluded. No commit, PR, deployment or Linear mutation is authorized by this implementation request.

## Reference and deliberate differences

Store the UI language on the account, expose it in personal General settings, localize shared component labels, keep catalog keys/placeholders in parity, and derive a bounded server-side reply-language hint. This React/Vite application uses i18next/react-i18next and the existing identity query. No parallel Zustand locale store, preference table or locale-based remount. Vietnamese is the product default; English is the missing-translation fallback. The picker waits for server confirmation rather than optimistically committing an account preference.

## Contracts

- IAM owns one `actors.ui_language` column constrained to `vi`/`en`, default `vi`. IdP profile observations never overwrite it. GET `/api/identity/me` returns `uiLanguage`; PUT `/api/identity/me/language` changes only the authenticated active member's preference with existing CSRF and membership locking. No client-supplied actor ID, admin capability or authorization revision change.
- Confirmed preference lives in the identity Query cache; i18next renders that preference. Existing focus refetch converges other tabs/devices. Late mutation responses must not update a different account. Lost responses reconcile authoritative identity; never silently claim the change persisted.
- Bundled typed catalogs translate at render time. Stored notification/error state contains safe descriptors, not frozen translated text. Locale controls HTML language and Intl formatting, never identifiers, codes, content or user input. No application subtree keyed by locale.
- Keep RFC 9457 status/business codes stable. Add stable field-validation codes and allowlisted safe parameters while retaining compatible safe API messages. Never expose arbitrary provider/server error text in the UI or infer behavior from it.
- Shared presentation distinguishes session 401, page/action 403, field validation, conflict, throttling, transport/unavailable and unexpected failures. Features own business-code mappings; no duplicate raw-status switches or HTTP-interceptor toasts. Background transient failures preserve data; authorization revocation still clears private state. Mutations retain their actual idempotency/recovery contracts and drafts.
- Resolve reply language server-side once per running Chat turn. `vi` prefers Vietnamese but an explicit request for another language wins; `en`/absent/unknown follows the user's message language. Do not force English, translate retrieval inputs/documents, override Persona instructions or add an inference service. A settings change affects subsequent turns only.

## Verification and trade-offs

### Renderer completion follow-up

Completion implementation: Shiki uses the cached JavaScript regex engine, because the default WebAssembly engine failed the production CSP check. CSP stays unchanged. The optional Generative UI scope question received no answer; use the announced read-only scope. `render_gui` uses the native Java/Embabel tool loop and V42 stores at most three bounded specs in the assistant message's terminal transaction. SSE advertises only `hasArtifacts`, then one authorized history read loads the saved specs. No new endpoint, job, form, HTML/script execution or application action. Reuse public `GenerativeUIRender` in the sidebar: the part-bound `MessagePrimitive.GenerativeUI` throws outside a message part even with an explicit spec. The canonical contract is [Chat renderers](../../../specs/chat.md#message-renderers-and-read-only-presentations).

The user requested completion, including renderers. Add the upstream `react-shiki` and `beautiful-mermaid` engines with pinned versions, lazily loaded after the native message part stops streaming. Mermaid output is displayed as an SVG image rather than inserted as active HTML; unsupported or oversized diagrams retain readable source with a localized explanation. Reuse the application's Radix Dialog and controls for zoom/focus instead of the registry's hand-written modal trap. Keep existing CSP and raw HTML inert. The announced completion scope is read-only; no application mutation authority is granted.

### Accepted Chat Elements/example follow-up (2026-09-12)

Reuse assistant-ui `examples/with-artifacts` at `063b9ec8c92098c51b6924d49b1a6c3cc80eec45` for explicit card-to-panel navigation, message-local origin and responsive layout. Keep authority-scoped readers and mobile Radix Dialog focus containment. Selection stays local React state, not a duplicate persisted content store. Closing restores the originating trigger without remounting Chat or its composer. The earlier file-panel batch did not add artifact persistence; this completion batch adds the bounded message contract above. Do not copy demo HTML iframe execution or unstable interactable/version APIs.

Composer attachments follow the user's latest horizontal-card reference and the registry ComposerAttachmentChip composition: bounded icon/name/known-size/remove, one-line filename with full tooltip, and no permanent Ready label. Retain native attachment lifecycle and the existing preview hook/Zustand store. Reuse ErrorState, connection notice, feedback and Sources elements with translated safe presentation; no automatic mutation retries or fabricated progress. Full-app and renderer completion evidence is recorded in verification.md.

Persistence follows ADR 0010: the increment uses aggregate-level Spring Data `JpaActorRepository`, not a handwritten repository for the language attribute. Routine reads use `findById`; service-owned transactions lock active membership before updating the managed Actor. Only `refreshForUpdate` is a custom fragment, because acquiring an ORM lock alone does not reload an already-managed entity. Existing IAM repositories assigned separately and JDBC authority/worker mechanics are unchanged. No schema or HTTP contract changes are required by this refactor.

Account-authoritative saves need a network round trip; disabling the picker during save avoids local write races. Bundled catalogs increase bundle size modestly but avoid locale-loading failures and infrastructure. Safe generic errors sacrifice raw detail for privacy; operator diagnostics remain separate. UI translation does not prove model output language, and prompt fixture tests are not live-model acceptance.

Required checks include PostgreSQL constraint/persistence, active-member self-only writes, CSRF, unchanged authorization revision, login preservation, stale-account responses, lost responses, locale changes during stream/form/error display, key/placeholder parity, localized validation and unknown errors, preserved background data and auth revocation, both-locale browser flows, static analysis and repository gates. Final business acceptance remains separate from deployment readiness.
