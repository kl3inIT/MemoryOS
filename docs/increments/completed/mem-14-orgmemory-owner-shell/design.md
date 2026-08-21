# MEM-14 design: OrgMemory and Onyx-aligned application shell

## Outcome

The authenticated MemoryOS surface adopts the proven OrgMemory composition and current Onyx/Opal sidebar contracts without changing the MEM-13 browser-session boundary. The app opens on a `New Session` shell, exposes a separate administration shell, supports a real persisted light/dark preference, and keeps navigation, account actions, and content in stable layout regions.

## Reference boundary

The implementation is based on fresh source inspection rather than screenshots alone:

- OrgMemory `AppShell`, app/admin sidebars, route layouts, semantic tokens, and feature ownership;
- Onyx `SidebarLayouts.Root/Header/Body/Footer/Section`, `SidebarTab`, `LineItemButton`, `AccountPopover`, `AppChrome`, and shared typography/size token sources.

MemoryOS copies interaction and layout contracts, not OrgMemory session models or Onyx product state. Agents, projects, recents, notifications, help, logout, connector operations, and chat history remain absent until their contracts exist.

## Runtime surfaces

The app shell exposes:

- `New Session` as the selected primary item;
- an assistant-first landing composition with `How can I help?` and a visibly unavailable composer;
- `Admin Panel` as a real route to the separate administration shell;
- an administration `Knowledge` section with `Sources`;
- a pinned account trigger with real appearance and admin actions;
- no raw actor UUID or debug/session subtitle.

The administration page is an honest empty source-management shell. It performs no connection, credential, ingestion, or retrieval operation.

## Component boundary

- `components/app-shell/app-shell.tsx` owns viewport composition, fold state, mobile drawer, pinned regions, navigation composition, and skip navigation.
- `components/app-shell/account-menu.tsx` composes the account trigger and real menu actions.
- `components/ui/sidebar-tab.tsx`, `sidebar-section.tsx`, and `menu-item.tsx` own shared size, alignment, focus, hover, selected, collapsed, anchor/button, and forwarded-ref behavior.
- `features/theme` owns system detection, persisted preference, and the `.dark` class.
- `OwnerShell` and `AdminShell` own only their route content.

Custom Radix triggers forward refs and all `aria`, `data`, pointer, and keyboard props to their DOM element so overlay positioning and accessibility remain correct.

## Sidebar contract

- Expanded desktop width is `15rem`; folded width is `4rem`.
- The folded logo is the expand button. Hover or keyboard focus swaps the MemoryOS mark for the sidebar icon on the same control.
- Expanded topbar shows a compact brand and collapse control.
- Header, body, and footer are independent; footer remains pinned.
- `New Session` is the primary action. Administration uses a titled `Knowledge` section.
- Desktop has no product topbar or decorative horizontal divider. The compact topbar exists only on mobile.
- Mobile uses a modal drawer with backdrop, focus management, explicit close, and Escape behavior.

## Account contract

The footer shows `Admin Panel` and a compact `Workspace owner` trigger. The trigger stays transparent at rest, uses a subtle selected tint when open, and collapses to the avatar. The popover contains only implemented actions: appearance and Admin Panel. It does not fabricate email, notifications, help, version, logout, actor identifiers, or session diagnostics.

## Typography and design system

MemoryOS uses Hanken Grotesk for interface typography and a monochrome semantic palette. The content scale follows Onyx:

- hero `48/64`;
- page heading `24/36`;
- section heading `18/28`;
- main content `16/24`;
- main UI `14/20`;
- secondary `12/16`;
- figure label `10/12`.

Sidebar tabs use the exact Onyx main UI `14/20` preset with 36px rows and 16px icons. Section labels use `12/16`; the brand mark is 28px and the topbar remains 40px.

`tokens.css` owns primitive and semantic values, `theme.css` exposes Tailwind utilities and typography presets, and `base.css` owns global element behavior. Product components use semantic tokens and presets rather than raw palette values, hexadecimal colors, OKLCH values, or arbitrary font sizes.

The token source remains CSS while web is the only consumer. A Style Dictionary JSON source and generated web/mobile outputs become justified only with a second platform consumer.

## Exclusions

No backend, OpenAPI, authentication, authorization, session, assistant execution, conversation storage, connector, source synchronization, ingestion, retrieval, notification, help, or logout contract changes.