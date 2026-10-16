# Plan

## Steps

1. `components/composites/search-field.tsx`, `hooks/use-pointer-opened-trigger.ts`, `tooltip` on `TooltipIconButton`.
2. Library toolbar, rows, entry list, tabs, picker, settings, uploads, notices, preview modal and ask composer use them;
   `source-list.tsx` takes the field and the icon buttons.
3. Drop the page description and its catalog entry; tests for the icon-only controls, the filter count, the clear
   button and the collapse icon.
4. Conventions: the page description is optional; `SearchField` and `TooltipIconButton` join the composites list.

## Verification

| What | Result |
| --- | --- |
| Probe against the e2e fixtures (Chromium): `:focus-visible` after closing the display menu and the filter popover by pointer and by Escape | Before: the trigger rang. After: no ring; keyboard arrows in the tab row still ring; a click on the keyboard-focused tab removes the ring; the tab list has `overflow: visible` on desktop |
| `check:i18n`, `tsc -b`, `oxlint --deny-warnings`, `oxfmt --check`, Vitest for the library, sources and component folders | See the pull request |
