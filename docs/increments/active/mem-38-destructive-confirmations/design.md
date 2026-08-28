# MEM-38 design: destructive confirmations and async feedback

## Outcome

MemoryOS routes destructive source actions through one accessible confirmation contract. A user sees the named entity, the impact of the action, and separate cancel and danger actions before any mutation starts. The dialog owns pending and failure feedback, prevents duplicate activation, and closes only after the requested operation completes successfully.

This increment standardizes behavior without redesigning Sources or changing connector cleanup APIs.

## Existing defects

The FILE Sources page currently performs item removal and source deletion immediately. Both actions share page-level mutation state and errors with unrelated create, upload, and reindex work. Deletion polls asynchronous cleanup, but the initiating control provides no confirmation or local progress context. Source and item failure states can also expose backend error-code tokens directly.

These defects create four risks:

- a destructive click cannot be reviewed or cancelled;
- duplicate activation is prevented only indirectly through feature-global busy state;
- failed mutations lose the action-local context needed for a safe retry;
- transport and ingestion codes leak through as product copy.

## Reference boundary

Radix AlertDialog provides the destructive-dialog semantics, keyboard containment, accessible title/description relationships, and controlled-open contract. The official async pattern keeps `open` controlled and closes after the asynchronous work resolves. MemoryOS explicitly focuses the least-destructive Cancel action when the dialog opens.

The local Onyx reference at revision `ec08b5f94` confirms the product contract: name the entity and action, separate cancel from submit, use danger treatment for confirmation, and summarize impact before destructive work. MemoryOS adopts that contract, not Onyx component code or styling.

## ConfirmDialog contract

`ConfirmDialog` is a controlled interaction boundary implemented with Radix AlertDialog and the existing semantic `Button` component. Callers provide:

- a trigger element;
- required title and description content;
- settled and pending confirmation labels;
- an async `onConfirm` operation;
- optional error presentation for domain-specific failures.

The component owns open, pending, and action-local error state. It does not own server calls, query invalidation, or feature selection state.

### State transitions

```text
closed --trigger--> open/idle
open/idle --cancel|Escape--> closed
open/idle --confirm--> open/pending
open/pending --resolve--> closed
open/pending --reject--> open/failed
open/failed --confirm--> open/pending
open/failed --cancel|Escape--> closed
```

While pending, close requests are ignored, Cancel and Confirm are disabled, the Confirm control retains its accessible name through the pending label, and `aria-busy` exposes progress. A synchronous ref guard runs before awaiting so rapid pointer or keyboard activation cannot submit twice before React commits pending state.

A rejection remains inside the dialog as `role="alert"`. Opening a new confirmation clears stale errors. Successful completion clears error state and closes the dialog. The component never assumes that a rejected `Error.message` is safe product copy.

## Sources integration

### Remove file

Each item row wraps its existing Remove trigger in `ConfirmDialog`. The title names the file. The description explains that its indexed document becomes unavailable and cleanup continues asynchronously. Confirmation invokes the existing remove-item mutation and refreshes the selected source. Failure remains in the open dialog for retry.

### Delete source

The source header wraps Delete source in `ConfirmDialog`. The title names the source. The description explains that every indexed document from the source becomes unavailable and cleanup continues asynchronously. Confirmation invokes the existing delete operation, polls the operation to a terminal state, refreshes query state, and selects no source after success. A failed or timed-out cleanup rejects to the dialog instead of converting the operation into a page-global message.

The existing abort controller still cancels cleanup polling on unmount. Aborted teardown does not display an error in a detached component.

## Error presentation

Source error presentation is centralized by operation context:

- authorization and missing-resource responses identify the denied or stale action;
- conflict responses explain that source state changed or work is already in progress;
- unavailable/network responses ask the user to retry;
- known extraction and cleanup codes map to stable human-readable messages;
- unknown safe uppercase codes are converted to readable diagnostic references rather than rendered as raw tokens;
- arbitrary exception messages are never shown directly.

Non-destructive create, upload, and reindex failures continue to use the page-level error region. Destructive remove and delete failures use their owning dialog.

## Accessibility

- Every dialog has a required visible title and description.
- Cancel receives initial focus; destructive confirmation is never the default focused action.
- Escape and Cancel close only while idle or failed and never invoke a mutation.
- Pending controls remain labelled, expose busy state, and cannot be activated twice.
- Failure feedback is announced with `role="alert"` while focus remains trapped.
- Closing returns focus to the trigger through Radix behavior.

## Ownership

- `web/src/components/ui/confirm-dialog.tsx` owns reusable destructive-confirmation behavior and layout.
- `web/src/features/knowledge/source-errors.ts` owns source-specific safe error presentation.
- `web/src/features/knowledge/sources-page.tsx` owns entity copy, mutation orchestration, query refresh, and selection.
- Focused component and error tests defend the reusable contracts; Playwright defends the complete Sources behavior.

## Non-goals

- No Sources information-architecture or visual redesign.
- No batch file staging.
- No copy of Onyx components.
- No connector cleanup API or generated-client change.
- No generic notification framework or speculative confirmation abstraction for unrelated capabilities.

## Verification

Verification covers least-destructive initial focus, Escape and Cancel without requests, entity-specific impact copy, pending state, duplicate-submit prevention, failure retention and safe feedback, retry success, successful source cleanup, and focus restoration. The frontend quality gate, complete Playwright suite, actual Chromium interaction, and repository-wide Gradle gate must pass before completion.