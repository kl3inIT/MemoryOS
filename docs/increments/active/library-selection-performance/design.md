# Library selection performance

## Accepted scope

Fix the pause when selecting every file on `/library`, preserving page-scoped selection, day selection, mixed state, keyboard operation and the floating bulk-action bar. No API or deployment change.

## Evidence and reuse

On staging, toggling a 50-file page took about 0.18–1.08 s in diagnostic runs. A selection mounted 62 Radix checkbox indicators (page, files and day headings). Profiling traced synchronous style reads to Radix Presence; instrumentation counted 62 `animationName` reads. Removing all `:has()` rules in a reversible browser experiment reduced style cost but did not eliminate the pause; this does not justify removing shared styling globally.

The installed `@radix-ui/react-checkbox` Indicator exposes `forceMount`, and its Presence uses `present: forceMount || isIndeterminate(checked) || checked === true`. Use that public prop in the existing registry wrapper and hide an unchecked indicator with its Radix `data-state`. Radix continues to own state, ARIA, keyboard, disabled behavior and form integration. This is a deliberate primitive defect fix: stable indicator nodes avoid repeated Presence measurements during bulk selection. Keep day-heading checkboxes mounted too, hidden and outside the accessibility tree before selection starts.

## Verification

Exercise 50 synthetic files through the real Library route, including select all, partial selection, day selection, clear and keyboard operation. Assert indicator visibility and that toggles do not remount indicators/read their computed style. Record before/after browser measurements with the same fixture and host rather than imposing a machine-dependent timing assertion. Run the web gate and document any unrelated failures; deployed acceptance remains separate.
