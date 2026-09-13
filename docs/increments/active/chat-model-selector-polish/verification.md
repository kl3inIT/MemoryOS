# Verification — 2026-09-13

- `ModelCatalogSelectionTest` passed (4): Tenant default marked when the Persona inherits; authorized Persona default marked instead of the Tenant default; a Persona model on a revoked/restricted provider falls back without listing it; a hidden inherited model marks nothing rather than falsely naming the Tenant default.
- `chat-model-picker.test.tsx` passed (3) within the 197-test web unit run: the real inherited model name is shown without Auto, deployment headings or context subtitle; an explicit choice is kept; an unavailable explicit choice never displays the inherited model.
- Chromium `chat.spec.ts` passed 28/28 with one worker (4.1 min), including the concrete inherited Luna model, keyboard model selection per turn, catalog error/empty/authorized-fallback states and the mobile picker staying on screen with focus restoration.
- A default-parallel local run (7 workers) timed out 21 tests; the same tests pass individually and in the one-worker run that matches CI. This was local resource contention, not a product failure.
- Boundary: no send-precedence change; Persona model editing already exists in the Assistant editor. No live staging model call in this slice.
