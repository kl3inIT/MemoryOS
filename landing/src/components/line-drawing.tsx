import type { ReactNode } from "react";
import { ramp, type Point } from "@/lib/ramp";
import { cn } from "@/lib/utils";

/*
 * Parts every line drawing shares. Parts ease in through `ramp` (src/lib/ramp.ts), and `ambient-*`
 * classes give a finished drawing a quiet loop; src/motion/drawings.ts drives both.
 */

type DrawingProps = {
  // The canvas in drawing units; text and strokes scale with it.
  viewBox?: string;
  // Where the canvas sits when its box has another aspect ratio.
  align?: "xMinYMid" | "xMidYMid";
  children: ReactNode;
};

function Drawing({ viewBox = "0 0 200 140", align = "xMinYMid", children }: DrawingProps) {
  return (
    <svg
      viewBox={viewBox}
      preserveAspectRatio={`${align} meet`}
      className="size-full overflow-visible"
    >
      {children}
    </svg>
  );
}

type PacketProps = {
  from: Point;
  to: Point;
  // The part of the drawing's progress during which it travels once.
  build: readonly [start: number, end: number];
  // When it starts in the finished drawing's loop, in seconds.
  delay: number;
};

// A packet that travels from `from` to `to`: once while the drawing builds, then in its loop.
function Packet({ from: [fromX, fromY], to: [toX, toY], build: [start, end], delay }: PacketProps) {
  return (
    <circle
      cx={toX}
      cy={toY}
      r={2.5}
      className="ramp ramp-pass ramp-travel ambient-packet fill-accent"
      style={ramp(start, end, {
        "--dx": `${fromX - toX}px`,
        "--dy": `${fromY - toY}px`,
        "--delay": `${delay}s`,
      })}
    />
  );
}

// A page with a folded corner, 14 × 18, hanging from the top centre at (x, y).
function PageGlyph({ x, y, className }: { x: number; y: number; className?: string }) {
  return (
    <g strokeWidth={1} className={cn("fill-none stroke-border-strong", className)}>
      <path d={`M${x - 7} ${y} h10 l4 4 v14 h-14 z`} className="fill-surface-raised" />
      <path d={`M${x + 3} ${y} v4 h4 M${x - 4} ${y + 9} h8 M${x - 4} ${y + 13} h6`} />
    </g>
  );
}

export { Drawing, Packet, PageGlyph };
