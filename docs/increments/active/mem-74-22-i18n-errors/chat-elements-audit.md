# Chat Elements reuse checkpoint — 2026-09-12

This is a source/catalog audit and a bounded implementation checkpoint, not a claim that every upstream example was run or every Chat surface is translated.

## Sources inspected

- assistant-ui repository at `063b9ec8c92098c51b6924d49b1a6c3cc80eec45`: `examples/with-artifacts/app/{page.tsx,artifact-surface.tsx,artifact-state.ts,toolkit.tsx}`; the examples directory inventory; `apps/docs/components/pages/examples/{artifacts.tsx,artifacts-view.tsx}` and `apps/docs/runtimes/artifacts.tsx`.
- Official registry: `elements-composer`, `elements-artifact-card`, `elements-canvas-split`, `elements-error-state`, `elements-connection-state`; existing installed Elements and current assistant-ui/Context7 documentation.

## Decisions by surface

| Surface | Reuse / current decision | Boundary |
| --- | --- | --- |
| Composer attachments | Native `ComposerPrimitive.Attachments` / `AttachmentPrimitive`, upstream preview hook and Zustand selector; horizontal ComposerAttachmentChip layout | Fixed bounded card, one-line name, full tooltip, known size, adjacent remove; upload readiness/cancel remain native. Reused-file size is display-only namespaced part metadata from the existing API; only file IDs are submitted. |
| Message file / sources | Existing `File`, `Sources`, `InlineCitation`, source workspace and authorized readers | Both open one right-hand panel on desktop and Radix modal drawer on mobile. Selection is local context/state; no new persistent artifact store. Shared transcript descriptors remain non-opening. |
| Artifacts example | Explicit trigger, selected item, bounded title and split layout | The docs demo automatically selects the latest `render_html` call; that behavior is not copied. The richer example's HTML execution, unstable interactable/edit/version APIs and storage semantics are not introduced. |
| CanvasSplit / ArtifactCard | Visual reference, not a replacement for the existing Thread or reader | Registry components are props-driven; CanvasSplit includes demo-sized layout, and ArtifactCard's root is a div. Application keyboard/focus, full-height layout and backend lifecycle do not come from installing those components. |
| Errors / connection | Adapted ErrorState and narrow ConnectionNotice | Safe translated descriptors; retain a pending recovery action's DOM; no automatic replay or invented attempts/token counters. A denied reader offers no retry that bypasses authority. |
| Feedback | Existing FeedbackDialog plus shared ChatDialog | Rounded reason chips, translated labels, stable server IDs, retained note/reason on failure or locale switch; same single-reason backend contract. |
| Message actions / branches | Keep existing native action/branch composition | Copy and Sources remain below the answer; do not replace branch/idempotency handling with standalone demo callbacks. Remaining action localization is not complete. |
| Thinking / streaming / scroll | Keep existing runtime-driven ThinkingIndicator, native viewport and existing replay adapter | No invented chain of thought, confidence, progress percentage, queued send or second stream store. Existing regression tests cover Stop, replay and session promotion. |
| Markdown / highlighters | Existing deferred MarkdownText; Shiki runtime-aware highlighter is the selected follow-up candidate | Shiki tokenizes after streaming; do not install Prism and Shiki together. No highlighter dependency was installed at this checkpoint. |
| Mermaid / Generative UI | Not enabled at this checkpoint | Require bounded safe rendering and actual tool/data contracts; showing a catalog example does not add a supported execution surface. |
| Tool groups / retrieval / document references | Existing actual search progress and source descriptors | Richer tool rendering can use real emitted data; no demo tool output or invented tool statuses. |
| Agent plans / approvals / code runner / terminal / memory / voice / cost / quota | Not introduced | No matching approved backend capability in this UI slice; installing the catalog does not establish authority, execution, persistence, or metrics. |

## UX and verification boundaries

Completion update: full application locale wiring and renderers are implemented. Shiki uses `react-shiki`'s JavaScript engine under unchanged CSP; Mermaid uses `beautiful-mermaid` as inert image data. Generated read-only artifacts have a real bounded backend tool/message-persistence path and reuse `GenerativeUIRender` in the same panel. The original no-backend/no-new-dependency statement below describes the earlier file-panel batch only. Current evidence is in `verification.md`.

- No second sidebar competing with Sources. Opening a file does not recreate the Chat runtime or composer. Closing returns focus to its trigger, with a mounted workspace fallback if the trigger disappears.
- The existing reader continues to validate authority, release private query data on close, render extracted text inertly, and use the canonical document generation/offset for citations.
- Mobile uses the existing Radix Dialog for focus containment and Escape; no bare full-screen aside copied from the demo. Long filenames are visually truncated without losing their full title or accessible label.
- No OCR/backend changes or new package dependencies in this UI follow-up. Full-app i18n, renderer integration and generated-artifact features remain distinct from the completed panel/card work. Execution evidence belongs in `verification.md`.
