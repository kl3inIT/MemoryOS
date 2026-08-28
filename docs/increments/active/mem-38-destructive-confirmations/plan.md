# MEM-38 implementation plan

## Foundation

- [x] Align Linear issue MEM-38 with the accepted Sources boundary and MEM-39 dependency.
- [x] Audit destructive mutations, async cleanup polling, error presentation, shared interaction contracts, tests, Radix guidance, and the local Onyx reference.
- [x] Record the confirmation state machine, ownership, accessibility requirements, error policy, and exclusions.
- [x] Add MEM-38 to the active increment maps and reconcile merged MEM-27 lifecycle records.

## Shared interaction contract

- [x] Add the Radix AlertDialog-backed `ConfirmDialog` with required title, description, labels, and async confirmation callback.
- [x] Focus Cancel on open and preserve Escape, focus containment, and trigger-focus restoration.
- [x] Add synchronous duplicate-submit protection, pending labels, busy state, disabled actions, and pending close prevention.
- [x] Retain failed confirmations with an announced safe error and close only after success.

## Sources migration

- [x] Centralize source transport, extraction, and cleanup failure presentation.
- [x] Move FILE item removal behind entity-specific confirmation and dialog-local feedback.
- [x] Move FILE source deletion and cleanup polling behind entity-specific confirmation and dialog-local feedback.
- [x] Remove raw source and item error-code presentation without changing Sources layout or connector APIs.

## Verification

- [x] Add focused component tests for focus, cancel, success, pending duplicate prevention, and failure retention.
- [x] Add focused error-presentation tests for known transport/domain codes and safe unknown-code references.
- [x] Extend Playwright Sources coverage for cancel, Escape, impact copy, pending state, failure retry, and successful completion.
- [x] Inspect changed files, run focused tests, and browser-exercise the confirmation flow.
- [x] Run `pnpm check`, the complete Playwright suite, and `gradlew.bat clean check --no-daemon`.
- [x] Record exact evidence in `verification.md` and consolidate durable frontend interaction rules.