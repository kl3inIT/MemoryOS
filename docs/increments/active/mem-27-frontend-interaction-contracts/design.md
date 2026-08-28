# MEM-27 design: frontend interaction contracts

## Outcome

MemoryOS replaces feature-local interaction styling with one enforced component contract for actions and native form controls. Product code chooses semantic tone, prominence, and size; shared components own rest, hover, active, focus-visible, disabled, and pending presentation in both themes.

This increment learns the contract discipline of Onyx/Opal at revision `ec08b5f94`. It keeps MemoryOS typography, role-based semantic tokens, Tailwind 4 stack, Radix composition, and existing behavior. It does not copy Opal source, numeric palettes, or product-specific states.

## Existing defects

The semantic token layer currently exposes primary and secondary action colors plus ghost surfaces, but not a complete interaction matrix. `Button` compensates with shadcn-style variants, raw opacity for disabled state, `/80` hover modifiers, and unrelated size aliases. Feature code then fills the gaps:

- invitation filters duplicate a 40px input/select class and implement `Clear` as a raw quiet button;
- invitation table sorting and pagination use raw buttons/selects with independent state and height rules;
- invitation and identity dialogs duplicate 40px and 44px field classes;
- Sources duplicates 40px text/file inputs;
- shell and session icon-only controls use labeled-button size aliases;
- `MenuItem` and `SidebarTab` maintain separate hover, focus, and disabled maps.

The result is not only visual drift. A consumer must understand container styling, icon sizing, focus treatment, native button defaults, and theme behavior to add one action safely.

## Reference boundary

The local Onyx/Opal checkout establishes these reusable contracts:

- `web/lib/opal/src/components/buttons/button`: tone is independent from prominence; secondary prominence owns its border; icon-only controls are square;
- `web/lib/opal/src/components/buttons/text-button`: quiet actions have no background, border, padding, or rounding and change foreground only;
- `web/lib/opal/src/components/buttons/icon-wrapper`: icons inherit action foreground and size from the owning control;
- `web/lib/opal/src/components/buttons/sidebar-tab` and `line-item-button`: embedded navigation controls use an internal prominence rather than ad hoc ghost styling;
- shared interaction styles use explicit state tokens and a short 150ms transition rather than arbitrary opacity modifiers.

MemoryOS adopts those boundaries, not their implementation names wholesale. Existing `surface-*`, `content-*`, `border-*`, `action-*`, `status-*`, and `focus-ring` roles remain canonical.

## Semantic model

Every action has three independent dimensions:

```text
tone       = default | danger
prominence = primary | secondary | tertiary | internal
size       = sm | md | lg
```

`primary` is the principal container action. `secondary` is a bordered supporting container action. `tertiary` is a transparent container action for low-prominence controls. `internal` is a transparent action embedded in another component, such as a menu row or navigation surface. `danger` changes semantic color and is valid only where the action can cause destructive or irreversible effects.

The token layer owns foreground, background, and border values for rest, hover, active, focus-visible, and disabled states. Disabled presentation uses explicit semantic values; components do not reduce opacity. Focus-visible uses the existing global focus-ring role and remains independently visible in light and dark themes.

## Control sizes

Shared control heights are:

```text
sm = 32px
md = 40px
lg = 44px
```

`Button`, `TextButton`, `IconButton`, `Input`, and `Select` use the same size vocabulary. Adjacent controls at the same size have identical outer height and an optically aligned text baseline. Icon buttons are square at every size. Text buttons have no container dimensions beyond their content and hit-area padding; they never recreate a ghost container.

`Button` defaults native `type` to `button` unless the caller explicitly requests `submit` or `reset`. Pending state disables repeated activation without replacing accessible names. `asChild` preserves the child element's navigation semantics.

## Component boundary

### Button

Container action with `tone`, `prominence`, `size`, optional leading/trailing icon, pending state, and Radix-compatible `asChild` composition. Feature code does not provide raw state colors, borders, heights, radii, or disabled opacity.

### TextButton

Quiet textual action. It uses foreground transitions only and keeps a visible focus indicator. It is the canonical replacement for clear, reset, and inline retry actions that should not render as a container.

### IconButton

Square icon-only action. It requires an accessible name through `aria-label` or equivalent visible labelling and owns icon sizing for `sm`, `md`, and `lg`.

### Input and Select

Thin native wrappers that own semantic background, foreground, border, placeholder, focus, disabled, and shared-height behavior. They do not introduce a form framework, validation model, or composite select implementation.

### Embedded controls

`MenuItem` and `SidebarTab` consume the internal action state contract while retaining their navigation-specific layout, active-route semantics, and keyboard behavior. They do not duplicate color-state maps.

## Migration

All current application call sites migrate in one clean cutover:

- application shell, account menu, administration sidebar, and new-session controls;
- generic loading, empty, access-denied, error, and not-found states;
- invitation filters, table headers, pagination, create/rotate/revoke flows, and recipient surfaces;
- identity/session dialogs and actions;
- existing Sources form, upload, item, and source actions.

Old shadcn-style variant names and feature-local standard interaction classes are removed. Product-specific layout classes remain when they do not encode shared state or size behavior.

No compatibility aliases remain after migration because they would preserve two interaction vocabularies.

## Accessibility and behavior

- Keyboard focus is visible only on `:focus-visible` and is not replaced by hover styling.
- Disabled native controls are non-interactive and remain legible without opacity-only treatment.
- Icon-only actions have an accessible name.
- Controls preserve current link, form-submit, menu, dialog, and routing behavior.
- Motion uses color, border-color, background-color, and transform-safe transitions with a 150ms duration; reduced-motion preferences remain respected by the global motion policy.
- Light and dark themes expose the same semantic hierarchy and state distinguishability.

## Ownership and files

- `web/src/styles/tokens.css` owns semantic interaction values and shared control dimensions.
- `web/src/styles/theme.css` exposes only reusable utilities needed by components.
- `web/src/components/ui/` owns Button, TextButton, IconButton, Input, and Select contracts.
- feature and shell files choose semantics and compose layout; they do not recreate interaction matrices.

## Verification

Focused component tests defend native button type, `asChild`, accessible icon buttons, disabled and pending behavior, and shared size attributes. Browser verification measures computed rest, hover, active, focus-visible, and disabled styles in light and dark themes, plus exact adjacent control heights. Existing invitation, identity, shell, and Sources browser behaviors remain unchanged.

## Explicit exclusions

- Sources information architecture or visual redesign; MEM-39 owns it after MEM-27 and MEM-38.
- Shared destructive confirmation and mutation feedback; MEM-38 owns those semantics.
- Multi-brand or tenant-custom themes.
- Copying Onyx numeric token ramps.
- Generic data-table behavior, form frameworks, motion-system redesign, z-index redesign, or Storybook adoption.
- New product actions, routes, backend fields, or authorization behavior.