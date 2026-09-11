# MEM-11 UI verification — 2026-09-11

Scope: the [approved Onyx UI plan](ui-onyx-alignment-plan.md), implemented in the existing checkout on `feat/mem-11-onyx-ui`. Custom assistant redesign and attachments are excluded. No backend schema or persistence change is required: the existing create-session contract accepts `projectId`.

## Component installation and runtime

The assistant-ui CLI installed EditMessage, MessageActions, MessageBranches, FeedbackDialog and ThreadList. Their source is adapted under the existing assistant-ui component directory, using MemoryOS tokens and controls. The application supplies server command callbacks and branch state. Conversation dropdowns use `ThreadListItemMorePrimitive`; no second chat store, cloud runtime or fake local branch mutation was introduced. The CLI-added demo surfaces helper and its unused shimmer dependency were removed after adaptation; pinned dependencies remain unchanged. Source license and the exact installation command are retained alongside the components and plan.

Project drafts share the pathless Chat route. First send creates a session with its project ID, promotes the URL and retains the original runtime/composer/SSE reader. Metadata updates have their own query, so renaming/moving does not reload the active transcript. A later send preserves the updated title rather than replacing it with the transport's creation-time metadata.

Streaming regression exposed a fenced-code rendering defect: a completed answer could retain only an intermediate code prefix while later paragraphs were complete. The installed code-fence adapter supplies synthetic `pre` props through a callback ref; the node-based memo comparator could skip new children against lagging node metadata. The local `pre` presentation now renders its current children without that comparator. Other markdown components retain memoization and deferred parsing remains enabled. The existing browser code-content/copy/reload assertion reproduces the failure before the fix and verifies complete visible/copied code afterward.

## Local verification

- `pnpm format` and `pnpm check` passed from `web`: generated API stability, CI-image compatibility, lint with warnings denied, `format:check`, TypeScript, 106 unit tests, route generation, production build and emitted fonts.
- `gradlew.bat clean check --no-daemon` passed in 9 seconds. Java sources are unchanged; compile/test tasks were restored from the Gradle cache (24 tasks: 12 executed, 12 from cache). This is not a new live provider or database acceptance run. No Java/Kotlin/YAML/properties/XML file belongs to this UI change; unrelated OCR files remain excluded from the commit.
- Focused browser workspace verification passed all nine initial scenarios: versions/sharing/deletion; existing assistant form; shared access/revoke; revision refetch; malformed sources; Project draft/send/deletion; keyboard move/remove and desktop drag/drop; rename/edit error recovery; clipboard-denied sharing.
- Final `pnpm test:e2e --workers=2 --max-failures=2`: **79 passed in 4.5 minutes**, including all ten workspace scenarios, the renamed-title follow-up guard, feedback failure/reload/removal, the complete code-content/copy assertion, and existing Chat/Search/admin/source flows. The earlier full run had two failures: one untranslated test selector and the reproduced fenced-code defect above; both are corrected. Final formatting, TypeScript and the changed renderer's lint check pass as well.
- Tests use the existing synthetic OAuth/HTTP fixture with real incremental SSE. They establish browser behavior, not deployed IAM, inference quality or Project instruction execution. Existing authenticated Java/PostgreSQL tests in the [matrix](../../../tests/chat.md) own those backend contracts.

## Visual inspection

Browser screenshots were inspected for the new empty Chat, saved conversation and overflow menu, inline editor, Project workspace with and without conversations, and sharing on mobile. Desktop captures include the dark theme; mobile captures use 390 × 844. Project and sharing controls wrap within the viewport, the composer is available directly in an empty Project, destructive actions are in confirmed menus, and there is one conversation header. Inline editing retains its draft and exposes cancellation after command failure; the action bar can wrap on a narrow screen. Synthetic captures remain under ignored `.tmp/mem11-ui-*.png`.

PR/head CI, CodeRabbit findings, deployment and production acceptance are separate evidence. MEM-11 remains active; these local checks do not close its remaining runtime/operations scope.

## PR #93 review follow-up

CodeRabbit submitted five actionable findings against `66639606137a3d1e7173c919cae947957d470bb4`. The user requested a fresh review check after the original watch had expired. All five are valid and addressed in the same PR: explicit header mode, validation before closing Project creation, loaded-data guards for unavailable choices, Vietnamese navigation labels, and method-specific Project-session fixture handling matching `ChatProjectController`.

Five new browser scenarios first failed against the original source at the expected boundaries: two saved-title cases, hidden creation error, premature unavailable option and a POST returning the GET response. Post-fix validation also corrected two new test locators to use the combobox's accessible name and the conversation link including its timestamp. The bot's separate docstring-coverage warning is informational: the repository's review policy excludes style-only advice, and named React presentation functions do not need boilerplate comments restating their bodies. The renderer's non-obvious memoization rationale and canonical behavior/verification documents remain recorded.

Final follow-up gates: `pnpm format` and `pnpm check` pass, including 106 unit tests; the affected Chat/workspace/identity-shell Chromium suite passes **53 tests in 2.9 minutes** with two workers. `gradlew.bat clean check --no-daemon` passes in 14 seconds, with unchanged Java compilation/tests restored from cache (24 tasks: 11 executed, 12 cached, one up-to-date). No backend/configuration file belongs to this review fix. Latest-head CI and GitHub thread resolutions remain separate PR evidence.
