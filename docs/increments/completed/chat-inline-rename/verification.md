# Verification

## Unit

`src/features/chat/session/chat-session-row.test.tsx` (Vitest, jsdom): no action buttons and no `required`; Enter then blur writes once with the trimmed name; blur saves and leaves focus where the person moved it; Enter returns focus to the row; empty and unchanged names close without a request; Escape discards the draft and focuses the row; `isComposing` Enter is prevented; a failed save keeps the draft and the alert, and a second Enter retries. All 8 tests pass, together with the other `features/chat/session` tests. `tsc -b`, Oxlint (warnings denied), oxfmt and the i18n audit pass on the changed files.

## Browser

Playwright `chat-workspace.spec.ts` (local, Chromium, fresh fixture server): all 18 scenarios pass, including "saves a conversation name on blur without action buttons or stealing focus" (blur write once, Enter write, focus destinations, empty and unchanged names make no write) and the existing rename scenario that now confirms with Enter. The full browser suite runs on CI.

## Web gate

The i18n audit, Oxlint (warnings denied), oxfmt, `tsc -b` and the Vitest suite of `src/features/chat` (272 tests) pass locally. Knip and the route check run on CI.

## Not covered

No backend or deployment change; no deployed acceptance is claimed.
