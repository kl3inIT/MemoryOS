# Verification

## Browser test

A 50-file selection scenario in `library-hub.spec.ts` asserts that:
- Selecting all 50 files does not remount checkbox indicators (they remain connected to the DOM)
- No synchronous `getComputedStyle` reads occur on checkbox indicators during selection
- Day-heading checkboxes remain mounted and hidden before selection starts
- Keyboard and pointer selection behavior is preserved (select all, partial selection, day selection, clear)

The test passed against the synthetic fixture with 0 style reads and all indicators remaining connected.

## Implementation

- Added `forceMountIndicator` prop to the `Checkbox` wrapper component in `web/src/components/ui/checkbox.tsx`
- Used `forceMountIndicator` on all Library file checkboxes in `web/src/components/library/library-rows.tsx` (row, card, and day-heading checkboxes)
- Day-heading checkboxes are mounted once and hidden with the `hidden` attribute and CSS `data-[state=unchecked]:invisible` on the indicator
- Radix continues to own state, ARIA, keyboard, disabled behavior and form integration

## Web gate

API generation, lint, formatting and TypeScript passed. The full web gate (including unit tests and Knip) reports pre-existing failures unrelated to this change; the increment scope is verified by the browser test and type check.
