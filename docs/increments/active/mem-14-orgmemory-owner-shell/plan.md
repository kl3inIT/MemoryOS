# MEM-14 plan: OrgMemory and Onyx-aligned application shell

## Implementation

1. Replace the owner-debug dashboard with the `New Session` application shell.
2. Add the separate administration route and `Knowledge` → `Sources` shell.
3. Implement Onyx-style fold behavior: 15rem/4rem widths and logo-to-expand hover/focus swap.
4. Separate sidebar header, body/sections, and pinned footer.
5. Introduce forwarded-ref `SidebarTab`, `SidebarSection`, and `MenuItem` primitives.
6. Add the pinned account popover with only real appearance and admin actions.
7. Add a system-aware, persisted light/dark theme.
8. Move the interface to Hanken Grotesk and the Onyx typography scale.
9. Remove actor UUIDs, debug/session subtitles, raw colors, arbitrary font sizes, and handwritten row styling.
10. Update focused unit and browser assertions for the observable shell contract.

## Verification

1. Run LSP diagnostics on changed TypeScript and TSX.
2. Run `pnpm check`.
3. Run `pnpm test:e2e`.
4. Exercise desktop expanded/folded/hover, account popover, light/dark, admin navigation, and mobile drawer against the local runtime.
5. Reconcile durable architecture, roadmap, and verification evidence.