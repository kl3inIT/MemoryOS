# Colour and design tokens

Tokens live in `web/src/styles/tokens.css` (values, light in `:root`, dark in `.dark`) and are exposed to Tailwind in `web/src/styles/theme.css` (`--color-*`). The system follows Onyx Opal (`web/lib/shared/tokens`) and the practice shared by Atlassian Design Tokens, GitHub Primer, IBM Carbon and Radix Colors: primitive values, then semantic roles named by what they do, never by their hue.

## Rules

1. **Components use semantic roles only.** No hex, `rgb()`, `oklch()` or Tailwind palette classes (`bg-green-500`, `text-white`, `bg-black/10`) in components or feature CSS. A missing role is added to `tokens.css` with a light and a dark value, then mapped in `theme.css`.
2. **Every role has both themes.** Dark values are chosen for the dark surfaces, not inverted.
3. **Brand artwork is exempt:** logos, the splash and brand loader, and third-party brand marks (Google, Microsoft, provider logos) keep their published colours.
4. **APIs that need a literal colour** (canvas, generated SVG such as Mermaid) read the token at runtime with `cssToken()` from `web/src/lib/css-token.ts`, with the token's value as fallback.

## Roles

| Role | Tokens | Use |
|---|---|---|
| Surfaces | `surface-canvas`, `-base`, `-raised`, `-subtle`, `-sunken`, `-strong`, `-overlay`, `-scrim` | Page, cards, menus; `surface-scrim` behind every modal |
| Content | `content-primary`, `-secondary`, `-muted`, `-disabled`, `-faint`, `-inverse` | Text and icons; never a series or status colour for body text |
| Borders | `border-subtle`, `-default`, `-strong` | Dividers, inputs, cards |
| Actions | `action-default-*`, `action-danger-*`, `action-selection*` | Buttons by prominence and state; `action-selection` is the one accent (switch on, selection, links) |
| Status | `status-{success,warning,danger,info}-{content,surface,border,strong,faint,emphasis,emphasis-border}` | State only: content is text (≥ 4.5:1), surface/faint the tinted background, border the tinted outline, strong icons and dots, emphasis the fill of a white-lettered pill (`content-on-emphasis`) |
| Charts | `chart-1` … `chart-8`, `chart-neutral` | Data series (below) |
| Highlights | `highlight-match`, `-active`; `evidence-highlight-surface`/`-border`, `pdf-highlight[-border]` | Search matches and the current match; a cited passage in text and PDF previews |
| Fixed roles | `surface-document`/`content-document` (white paper for PDF and DOCX previews), `content-on-media`, `surface-on-media[-hover]`, `border-on-media`, `scrim-media-hover` (controls laid over images) | Colours that intentionally do not follow the theme |

MemoryOS keeps its status **content** colours one step darker than Onyx's text steps so status text reaches 4.5:1 on its tinted surface; the other status steps are Onyx's.

## Charts

- **Categorical series take `chart-1` … `chart-8` in that fixed order, never cycled.** A ninth series folds into "Other", drawn with `chart-neutral`.
- The eight hues are steps of Onyx's ramps (blue-50, orange-55, neon-cyan-80, neon-amber-60, neon-magenta-80, green-60, purple-50, red-50; dark: blue-45, orange-40, neon-cyan-80, neon-amber-80, neon-magenta-80, green-50, purple-45, red-45). They pass the data-visualisation checks on the light (`#ffffff`) and dark (`#19191e`, `#000000`) surfaces: OKLCH lightness band, chroma ≥ 0.10, adjacent colour-blind separation ΔE ≥ 8 and normal-vision separation ΔE ≥ 15. Changing a hue or the order requires re-running those checks.
- **Colour follows the entity, not its position**: a series that means something keeps its hue (AI costs: External `chart-1`, Internal `chart-3`, outside the model catalog and "Other" `chart-neutral`).
- **Status colours are never a series**, and state is never shown by colour alone (icon or label with it).
- Values, labels and legends use content tokens, not the series colour. Two or more series always have a legend.
