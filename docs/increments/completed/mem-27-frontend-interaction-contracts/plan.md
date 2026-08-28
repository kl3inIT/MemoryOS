# MEM-27 implementation plan

## Foundation

- [x] Align Linear issue MEM-27 with the Onyx/Opal reference boundary, accepted scope, and MEM-39 dependency.
- [x] Audit semantic tokens, shared controls, feature-local overrides, and every current Button consumer.
- [x] Record the interaction model, size contract, migration boundary, accessibility requirements, and exclusions.
- [x] Add MEM-27 to the active increment maps.

## Interaction foundation

- [x] Add explicit default/danger action tokens for primary, secondary, tertiary, and internal rest, hover, active, focus-visible, and disabled states in both themes.
- [x] Add shared `sm`, `md`, and `lg` control-height and icon-size tokens.
- [x] Replace shadcn variant vocabulary with semantic `tone`, `prominence`, and `size` contracts.
- [x] Add `TextButton`, `IconButton`, `Input`, and `Select` primitives beside the migrated `Button`.
- [x] Default native buttons to `type="button"`, preserve `asChild`, and enforce accessible icon-button names.

## Clean migration

- [x] Migrate shell, navigation, account, session, and shared state actions.
- [x] Migrate invitation filters, sortable headers, pagination, dialogs, and recipient actions.
- [x] Migrate identity/session controls and current Sources controls without changing behavior or information architecture.
- [x] Move `MenuItem` and `SidebarTab` onto the internal action state contract while preserving active-route semantics.
- [x] Remove obsolete variants, aliases, duplicated form-control classes, raw standard palette interaction classes, and opacity-disabled styling.

## Verification

- [x] Add focused component tests for button type, composition, pending/disabled behavior, icon accessibility, text-button presentation, and shared control sizes.
- [x] Run existing unit and Playwright behavior covering shell, identity, invitations, and Sources.
- [x] Browser-inspect rest, hover, active, focus-visible, and disabled states in both themes and measure adjacent control heights.
- [x] Run `pnpm check` and the repository-wide `gradlew.bat clean check --no-daemon` gate.
- [x] Record exact evidence in `verification.md` and consolidate durable interaction rules into frontend conventions.