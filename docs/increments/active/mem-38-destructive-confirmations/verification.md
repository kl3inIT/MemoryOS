# MEM-38 verification matrix

| Requirement | Evidence |
| --- | --- |
| Destructive actions require an accessible title, description, explicit Cancel, and danger confirmation | `ConfirmDialog` composes Radix AlertDialog with required title/description props and semantic Buttons. Focused tests assert the accessible description; Chromium observed one alert dialog titled `Remove knowledge.txt?` with the complete impact statement. |
| Cancel is initially focused and Cancel or Escape performs no mutation | Focused component tests verify initial Cancel focus, Escape closure, Cancel closure, zero confirmations, and focus restoration. Focused Playwright verifies both Escape and Cancel before any remove request. Chromium observed `Cancel` as the active element on open. |
| Async confirmation cannot close or submit twice while pending | A synchronous ref guard precedes the async boundary; pending blocks close requests and disables both actions. Focused component tests assert one callback after pointer and keyboard reactivation attempts. Playwright and Chromium observed `Removing file`, `disabled`, `aria-busy="true"`, disabled Cancel, and exactly one retry request. |
| Success closes only after the operation and restores product state | Focused component tests keep the dialog open until a deferred promise resolves and then restore focus to the trigger. Playwright completes item removal and source cleanup before asserting `No files yet` and `No sources connected`. Chromium observed the dialog close only after the delayed remove response and refreshed empty state. |
| Failure retains the dialog with safe action-local feedback and supports retry | Focused component tests reject once, retain `role="alert"`, suppress the exception message, and succeed on retry. Playwright injects an initial 409 and asserts the contextual conflict message before a successful retry. Chromium observed the same retained dialog and message. |
| FILE item removal and source deletion name the affected entity and explain access invalidation plus asynchronous cleanup | Playwright asserts `Remove knowledge.txt?`, `Delete Product documentation?`, and their full impact descriptions before allowing either mutation. |
| Source transport, extraction, and cleanup failures do not render bare backend codes or arbitrary exception messages | `source-errors.test.ts` covers known extraction/cleanup codes, mutation-specific 404/409 copy, safe unknown-code references, unsafe status detail, and typed cleanup failures. Sources renders `sourceStatusMessage` for persisted source and item failures. |
| Existing Source create, upload, reindex, remove, delete, CSRF, refresh, and cleanup behavior remains intact | Focused Sources Playwright passed. The complete 15-scenario Playwright suite passed, including six expected same-origin mutation headers across the failed/retried remove sequence. |
| Frontend contracts, generated clients/routes, formatting, types, build, and unit behavior remain healthy | `pnpm check` passed: generated API stability, Playwright image assertion, zero-warning oxlint, oxfmt, TypeScript, 10 unit files with 41 tests, generated route stability, Vite production build, and font-asset verification. |
| Repository remains healthy | `gradlew.bat clean check --no-daemon` completed successfully with 23 actionable tasks: 11 executed and 12 from cache. |

## Browser evidence

- Headless Chromium at 1440×900 exercised the actual `/admin` Sources surface with intercepted production API contracts.
- Initial state: one alert dialog, entity-specific title and impact description, Cancel focused.
- Failure state: dialog retained with `This file is already changing. Refresh the source and try again.` and balanced danger/cancel presentation.
- Pending state: `Removing file` exposed `aria-busy`, both actions were disabled, and the second request count remained one for that attempt.
- Completion state: the dialog closed and the source detail refreshed to `No files yet`.
- No Java, Kotlin DSL, YAML, properties, or XML files changed; JetBrains per-file inspection was not applicable. LSP diagnostics reported no issues in changed production and focused unit-test TypeScript files.