import { useId, type CSSProperties, type RefObject } from "react";
import { cn } from "@/lib/utils";
import type { PlacedOutline } from "./pdf-pages";

/**
 * One cited region on a page or a picture, inside the viewer's multiplying highlight layer. A region with an
 * outline is drawn in the block's own shape, so a tilted line or a curved heading is marked where its text is
 * rather than by the upright box around it; one without stays the rounded box. Both keep the slot, the label
 * and the scroll target, so the rail and the tests find the same element either way. Once the viewer has read
 * the ink inside the outline, `lines` replaces it with one highlighter stroke per text line: filled without an
 * edge, so it reads as a marker rather than a traced shape, and cut to the outline, which stays the boundary.
 */
export function CitationRegion({
  slot,
  label,
  style,
  outline,
  lines,
  regionRef,
  variant = "fill",
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
  /**
   * How the region is drawn. `fill` marks the whole region, as recorded. A searched region whose own text holds
   * the question is a `bar` in its left margin, the marks inside it doing the pointing; one whose text holds
   * none of it (a match by meaning) is a dashed `outline`; `pending` takes the region's place unseen while its
   * text is read, so the reader is still scrolled to it.
   */
  variant?: "fill" | "bar" | "outline" | "pending";
}) {
  // An id the clip path can be named by: useId's own may hold characters a url() reference does not.
  const clip = `citation-clip-${useId().replace(/[^\w-]/g, "")}`;
  const ref = regionRef
    ? (node: Element | null) => {
        regionRef.current = node;
      }
    : undefined;
  if (variant !== "fill") {
    return (
      <span
        ref={ref}
        role={variant === "pending" ? undefined : "img"}
        aria-label={variant === "pending" ? undefined : label}
        data-slot={slot}
        data-variant={variant}
        className={cn(
          "absolute scroll-m-12",
          variant === "outline" &&
            "rounded-sm border-2 border-dashed border-pdf-highlight-border/90",
        )}
        style={style}
      >
        {variant === "bar" ? (
          <span className="absolute inset-y-0.5 -left-2 w-0.75 rounded-full bg-pdf-highlight-border" />
        ) : null}
      </span>
    );
  }
  const stroked = Boolean(lines?.length);
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
      {stroked && outline ? (
        <defs>
          <clipPath id={clip}>
            <polygon points={points(outline)} />
          </clipPath>
        </defs>
      ) : null}
      <g clipPath={stroked && outline ? `url(#${clip})` : undefined}>
        {shapes.map((shape, index) =>
          stroked ? (
            <polygon
              key={index}
              data-band=""
              points={points(shape)}
              className="fill-pdf-highlight"
            />
          ) : (
            <polygon
              key={index}
              points={points(shape)}
              className="fill-pdf-highlight stroke-pdf-highlight-border/80"
              strokeWidth={1}
              strokeLinejoin="round"
              vectorEffect="non-scaling-stroke"
            />
          ),
        )}
      </g>
    </svg>
  );
}

const points = (shape: PlacedOutline) => shape.map(([x, y]) => `${x},${y}`).join(" ");
