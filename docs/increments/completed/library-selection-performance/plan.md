# Plan

- [x] Inspect the selection path and the installed Radix Indicator/Presence behavior.
- [x] Keep indicators and day checkboxes mounted while preserving visibility and accessibility.
- [x] Verify the 50-file runtime flow and record comparable before/after performance evidence.
- [x] Run the web gate and consolidate the contract/evidence in canonical documentation.

Keep this increment active until merge and deployed acceptance.

## Staging acceptance (2026-10-19)

A page of 50 files: no checkbox indicator is remounted across 15 toggles; select-all or clear takes 128–176 ms to the next paint (672–840 ms at 4× CPU throttling), against 0.18–1.08 s before. One row toggle costs about as much as all 50, which points at a whole-list render.
