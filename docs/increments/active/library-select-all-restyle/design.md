# Library select-all restyle

## Accepted scope

Selecting every file of a 50-file page on `/library` still pauses after [library selection performance](../../completed/library-selection-performance/design.md). Remove the remaining pause without changing what the list shows or how it is operated: page, day and file selection, mixed state, keyboard use and the bulk-action bar stay as they are. No API or deployment change.

## Evidence

Measured on the production build (`MEMORYOS_E2E_PREVIEW=1`) with the 50-file fixture of `library-hub.spec.ts`, as Event Timing interaction latency from the click to the next paint, median of ten clicks; Chrome's `Performance.getMetrics` and a timeline trace split the cost.

| | Select all | One row | Select all, 4× CPU | One row, 4× CPU |
| --- | --- | --- | --- | --- |
| Before | 128 ms | 72 ms | 656 ms | 296 ms |
| After | 24 ms | 16 ms | 136 ms | 56 ms |

Before, a select-all spent about 100 ms on style and 55 ms on script:

- **First frame.** Chrome restyled 4,784 elements, about 95 per changed row, which is each row's whole subtree. The row's checkbox sat inside the shared `Item`, a `group/item` that non-subject `:has()` rules read (`group-has-data-[slot=item-description]/item` from `ItemMedia`). Chrome invalidates such an anchor's whole subtree whenever a `data-state` inside it changes, because some `:has([data-state…])` rule exists in the stylesheet. Moving the checkboxes out of the Item in the browser cut the frame to 1,084 elements; removing every `:has()` rule cut it to 1,683, which is not an option for a shared kit.
- **Following frames.** For about ten frames Chrome restyled 718 to 888 elements each, the `transition-colors` fades of fifty row backgrounds and fifty checkboxes.
- **Script.** Every selection rendered all fifty rows with their tooltips and menus, because no row was memoised and the row commands were a new object on each render of the page.

## Design

- `LibraryRow` puts its checkbox beside the `Item` instead of inside it. A negative left margin on the Item keeps the picture and the name where they were (checkbox 13 px and picture 39 px from the row's edge, as before), and the checkbox stays above the Item so its pointer area is unchanged. The registry `Item` is untouched.
- The row background changes at once instead of fading, and `Checkbox` with `forceMountIndicator` (used only by the library's bulk lists) also changes colour at once.
- `LibraryRow`, its body and `LibraryCard` are memoised. `LibraryList` hands rows commands that keep one identity and call the page's latest handlers; the selection handlers update from the current selection, so they keep theirs. A select-all renders fifty checkboxes and row frames, not fifty bodies.

The grid card keeps its checkbox inside the card, whose hover and focus reveal depend on it; the card is memoised, so one toggle renders one card.

## Verification

`library-hub.spec.ts` now also asserts that each row's checkbox is outside the `Item` and has no transition, the two measured causes; it fails on the previous code. A timing threshold would depend on the host, so the numbers above are recorded evidence, not an assertion.
