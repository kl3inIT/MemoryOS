import type { CSSProperties, RefObject } from "react";
import type { PlacedOutline } from "./pdf-pages";

/**
 * One cited region on a page or a picture, inside the viewer's multiplying highlight layer. A region with an
 * outline is drawn in the block's own shape, so a tilted line or a curved heading is marked where its text is
 * rather than by the upright box around it; one without stays the rounded box. Both keep the slot, the label
 * and the scroll target, so the rail and the tests find the same element either way. Once the viewer has read
 * the ink inside the outline, `lines` replaces it with one band per text line.
 */
export function CitationRegion({
  slot,
  label,
  style,
  outline,
  lines,
  regionRef,
}: {
  slot: string;
  label: string;
  /** The placed rectangle: the box, which bounds the outline. */
  style: CSSProperties;
  /** The outline as fractions of the placed rectangle. */
  outline?: PlacedOutline;
  /** One band per text line, as fractions of the placed rectangle; drawn instead of the outline. */
  lines?: readonly PlacedOutline[];
  regionRef?: RefObject<Element | null>;
}) {
  const ref = regionRef
    ? (node: Element | null) => {
        regionRef.current = node;
      }
    : undefined;
  const shapes = lines?.length ? lines : outline?.length ? [outline] : [];
  if (!shapes.length) {
    return (
      <span
        ref={ref}
        role="img"
        aria-label={label}
        data-slot={slot}
        className="absolute scroll-m-12 rounded-sm bg-pdf-highlight ring-1 ring-pdf-highlight-border/80"
        style={style}
      />
    );
  }
  return (
    <svg
      ref={ref}
      role="img"
      aria-label={label}
      data-slot={slot}
      data-shape={lines?.length ? "lines" : "outline"}
      className="absolute scroll-m-12 overflow-visible"
      style={style}
      viewBox="0 0 1 1"
      preserveAspectRatio="none"
    >
      {shapes.map((shape, index) => (
        <polygon
          key={index}
          points={shape.map(([x, y]) => `${x},${y}`).join(" ")}
          className="fill-pdf-highlight stroke-pdf-highlight-border/80"
          strokeWidth={1}
          strokeLinejoin="round"
          vectorEffect="non-scaling-stroke"
        />
      ))}
    </svg>
  );
}
