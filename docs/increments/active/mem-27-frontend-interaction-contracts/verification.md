# MEM-27 verification matrix

| Requirement | Evidence |
| --- | --- |
| One semantic action matrix owns default/danger primary, secondary, tertiary, and internal states in both themes | `tokens.css` defines the tone/prominence matrix; `action-styles.ts` is its only standard container-state consumer. Browser-computed primary rest, hover, and active backgrounds resolved distinctly, and the dark primary surface resolved differently from light. |
| Quiet text actions have no container background or border and retain visible keyboard focus | `interaction-controls.test.tsx` asserts the `TextButton` contract. Keyboard traversal reached Apply with `:focus-visible`; the settled browser style contained the 2px offset and 5px semantic ring. |
| Icon-only actions are square, inherit semantic state, and expose an accessible name | Focused tests query `IconButton` by its accessible name. Browser measurement observed the disabled `lg` Send action at exactly 44×44px. |
| Native Button defaults to `type="button"`; explicit submit and Radix `asChild` behavior remain correct | Focused tests cover default type, explicit submit, composed anchor href/type, pending busy state, disabled activation, and semantic data attributes. |
| Inputs, selects, and adjacent actions at one size share exact outer height and optical alignment | Focused tests cover `sm`, `md`, and `lg`; browser measurement observed invitation email input, status select, Apply button, and Clear text action all at exactly 40px. |
| Disabled and pending controls prevent repeated activation without opacity-only presentation | Focused tests prevent native and `asChild` mouse/Enter/Space activation across Button, TextButton, and IconButton while preserving `aria-busy`; browser-computed disabled Send opacity was `1` with semantic disabled surface/content values. |
| Shell, shared states, identity, invitations, and Sources use shared contracts without behavior regressions | Unit suite passed 8 files and 34 tests. Chromium Playwright passed all 15 existing product scenarios, including the complete FILE Sources flow. |
| Migrated product files contain no obsolete variant names, duplicated standard control classes, or raw standard Tailwind interaction palette classes | Repository search found no old Button variants/icon sizes; `oxlint --deny-warnings`, `oxfmt --check`, and TypeScript build passed through `pnpm check`. |
| Generated routes and API client remain stable | `pnpm check:api` and `pnpm check:routes` passed through `pnpm check`; the production Vite build and font-asset assertion passed. |
| Repository remains healthy after the frontend cutover | `pnpm check` passed. `gradlew.bat clean check --no-daemon` completed successfully with 23 actionable tasks. |

## Browser evidence

- Desktop Chromium at 1440×900 verified the invitation filters, action hierarchy, account menu, and empty state in both themes.
- Keyboard traversal verified visible focus independently from hover.
- Mobile Chromium at 390×844 measured the navigation `IconButton` at 40×40px, found no horizontal overflow, and visually preserved the New Session composition.
- No Java, Kotlin DSL, YAML, properties, or XML files changed; JetBrains per-file inspection was not applicable.