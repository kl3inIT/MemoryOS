# MEM-65 design: MemoryOS Keycloak authentication theme

> **Replaced on 2026-09-23.** The meaning-network composition described below is no longer in
> the repository. The theme the deployments serve is the centred brand card on a flat page,
> added in `001f3632`. What this document still holds is the part that did not change: the
> theme restyles keycloak.v2 without owning a template, and every byte it serves is local.

## Outcome

The `memoryos` realm renders its browser authentication and required-action pages with the approved MemoryOS meaning-network composition: a full-viewport dark knowledge-map surface, a foreground MemoryOS identity on the left, and a compact light authentication card on the right. The page remains usable without vertical document scrolling at supported desktop and mobile viewport sizes.

The theme covers the existing Keycloak-owned browser flow, including sign-in, password recovery, required password update, email verification, informational success, and error or expired-action states. Keycloak continues to own form actions, CSRF/session state, validation, password visibility, localization hooks, accessibility semantics, and action-token behavior.

## Keycloak extension boundary

The implementation follows Keycloak 26.7's supported theme contract:

- `infrastructure/keycloak/themes/memoryos/login/theme.properties` extends `keycloak.v2`;
- the theme supplies only versioned CSS, images, and message bundles;
- no FreeMarker template is copied or overridden, so every page continues to inherit the exact templates and security behavior from the pinned Keycloak runtime;
- the repository-owned theme directory is mounted read-only at `/opt/keycloak/themes/memoryos` in the shared Keycloak container;
- MemoryOS realm reconciliation sets only the `memoryos` realm's `loginTheme` to `memoryos` and verifies the resulting value.

This does not customize the Admin Console, Account Console, master realm, or OrgMemory realm. Production theme caching remains enabled. Theme changes take effect when the shared Keycloak container is recreated; no temporary runtime mode or cache-disabled production profile is introduced.

## Visual and responsive contract

The existing `keycloak.v2` semantic structure is restyled rather than replaced. The login container becomes a two-area composition: the realm header is the visible MemoryOS identity and the Keycloak main element is the floating form card. A repository-owned SVG supplies the ambient meaning network and labels (`Sources`, `Structure`, `Evidence`, `Context`, `Private storage`, `Recall`, and `Citations`). Decorative graphics do not enter the accessibility tree.

At desktop widths the identity and compact card form one centered two-column composition rather than anchoring either element to a screen edge. The identity receives a small inward optical offset so the logo and name sit closer to the center without moving the form or narrowing its working space. At narrower widths the offset reduces, the left identity contracts, and the layout eventually becomes a centered single-card mobile composition. The document itself does not scroll; the card may scroll internally only when a provider or future authenticator adds more controls than the viewport can contain. Focus indication, error alerts, labels, password visibility, and reduced-motion behavior remain visible.

The approved design intentionally excludes the four principle pills and the `auth.kl3in.tech` badge. The Keycloak attribution remains a quiet card footer. The theme uses local system font fallbacks and repository-owned assets only; it makes no browser request to a font CDN.

## Runtime and ownership

The shared Keycloak runtime remains image-pinned and continues serving both MemoryOS and OrgMemory. The read-only mount adds one theme without changing the image, database, issuer, clients, subjects, or network aliases. Realm reconciliation remains the single source of truth for selecting the theme. A missing mount causes reconciliation to fail its theme-selection verification instead of silently accepting an unbranded realm.

## Verification

Verification covers:

- theme structure, inheritance, local assets, absence of copied FreeMarker templates, and required screen selectors;
- read-only Compose mounting and MemoryOS-only realm reconciliation;
- shell syntax and rendered staging/production Compose configuration;
- representative inherited Keycloak login, update-password, verify-email, info, and error markup rendered against the custom CSS at desktop and mobile viewports;
- no document overflow, visible focus, and absence of the removed pills and hostname badge;
- a live Keycloak 26.7 runtime and real browser flow when the container runtime is available;
- the repository-wide `clean check` and frontend checks.

## References

- [Keycloak: Working with themes](https://www.keycloak.org/ui-customization/themes)
- [Keycloak 26.7 release notes](https://www.keycloak.org/docs/26.7.0/release_notes/)
- [Keycloak: Configuring themes](https://www.keycloak.org/docs/latest/server_admin/#proc-configuring-themes_server_administration_guide)
- [Keycloak: Directory structure](https://www.keycloak.org/server/directory-structure)
