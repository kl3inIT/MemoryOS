/**
 * Controls revealed while their `group` is hovered or focused. Touch screens cannot hover, so the controls stay
 * visible there (Onyx Opal `hoverable-item`).
 */
export const hoverReveal =
  "opacity-0 transition-opacity duration-150 group-hover:opacity-100 group-focus-within:opacity-100 no-hover:opacity-100";
