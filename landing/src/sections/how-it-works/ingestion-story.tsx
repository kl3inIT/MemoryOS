import { type ComponentType, type CSSProperties, type ReactNode, useRef } from "react";
import { howItWorks } from "@/content";
import { citationMarker } from "@/lib/illustration";
import { cn } from "@/lib/utils";
import { gsap, useMotion } from "@/motion/motion";

/*
 * The six stages as stations on one beam down the list. Beside each station a large line drawing
 * shows the sample document in that stage's form: sources converge into a page; the page is
 * outlined region by region, scanned and read into text and a table; it splits into passages with
 * their tags; each passage becomes a vector and a point; the points join the index; a question
 * finds the nearest two and the answer cites them. Motion moves one progress value `--p` per station
 * as it crosses the viewport, and every part eases in from it through the `ramp` utilities
 * (src/styles/base.css), so the page runs six tweens rather than one per part. Once a station has
 * finished, and while the list is on screen, light runs along the lit beam and the drawing keeps a
 * quiet CSS loop. Without motion `--p` is unset and every drawing shows its final state. The
 * drawings are hidden from assistive technology; each station's heading and sentence carry the
 * meaning.
 */

type RampVars = Record<`--${string}`, string | number>;

// Custom properties as a style object.
function cssVars(vars: RampVars): CSSProperties & RampVars {
  return vars;
}

// A part's ramp: it eases in while its station's progress runs from `from` to `to`.
function ramp(from: number, to: number, vars: RampVars = {}): CSSProperties & RampVars {
  return cssVars({ "--from": from, "--to": to, ...vars });
}

type Point = readonly [x: number, y: number];

// Every drawing shares one 200 × 140 canvas, aligned to the start of its column.
function Drawing({ children }: { children: ReactNode }) {
  return (
    <svg
      viewBox="0 0 200 140"
      preserveAspectRatio="xMinYMid meet"
      className="size-full overflow-visible"
    >
      {children}
    </svg>
  );
}

const sources = ["Drive", "Files", "APIs", "Systems"];
const sourceRows = [22, 54, 86, 118];
const inbox: Point = [140, 70];
const pageLines = [
  { y: 62, width: 28 },
  { y: 71, width: 32 },
  { y: 80, width: 20 },
];

function PullDrawing() {
  const [inboxX, inboxY] = inbox;
  return (
    <Drawing>
      {sources.map((source, index) => {
        const y = sourceRows[index] ?? 0;
        const leave = 0.05 + index * 0.08;
        return (
          <g key={source}>
            <text
              x={0}
              y={y + 2.7}
              fontSize={7.5}
              className="ramp ramp-fade fill-content-secondary font-medium"
              style={ramp(leave - 0.05, leave + 0.1)}
            >
              {source}
            </text>
            <path
              d={`M58 ${y} L${inboxX} ${inboxY}`}
              pathLength={100}
              strokeWidth={1}
              className="ramp ramp-draw fill-none stroke-border-strong"
              style={ramp(leave, leave + 0.3)}
            />
            <circle
              cx={inboxX}
              cy={inboxY}
              r={2.5}
              className="ramp ramp-pass ramp-travel ambient-packet fill-accent"
              style={ramp(leave + 0.1, leave + 0.45, {
                "--dx": `${58 - inboxX}px`,
                "--dy": `${y - inboxY}px`,
                "--delay": `${index * 0.7}s`,
              })}
            />
          </g>
        );
      })}
      <g className="ramp ramp-pop" style={ramp(0.5, 0.75)}>
        <path
          d="M140 44 h34 l14 14 v38 h-48 z"
          strokeWidth={1.5}
          className="fill-surface-raised stroke-accent"
        />
        <path d="M174 44 v14 h14" strokeWidth={1.5} className="fill-none stroke-accent" />
        {pageLines.map((line) => (
          <rect
            key={line.y}
            x={148}
            y={line.y}
            width={line.width}
            height={3}
            rx={1.5}
            className="fill-border-strong"
          />
        ))}
      </g>
    </Drawing>
  );
}

// Layout analysis finds these regions in reading order: title, paragraph, table, scanned text.
const regions = [
  { order: 1, y: 11, height: 15 },
  { order: 2, y: 30, height: 30 },
  { order: 3, y: 64, height: 36 },
  { order: 4, y: 104, height: 26 },
];
const paragraphLines = [
  { y: 34, width: 84 },
  { y: 42, width: 88 },
  { y: 50, width: 56 },
];
const tableCells = [56, 102].flatMap((x) => [68, 79, 90].map((y) => ({ x, y })));
const scanLines = [
  { y: 108, width: 80 },
  { y: 116, width: 88 },
  { y: 124, width: 56 },
];

function ExtractDrawing() {
  return (
    <Drawing>
      <rect
        x={46}
        y={4}
        width={108}
        height={132}
        rx={4}
        strokeWidth={1}
        className="fill-surface-raised stroke-border-default"
      />
      {/* The fetched page: shapes without meaning yet. */}
      <g className="ramp ramp-out fill-border-default" style={ramp(0.72, 0.84)}>
        <rect x={56} y={15} width={58} height={6} rx={2} />
        {paragraphLines.map((line) => (
          <rect key={line.y} x={56} y={line.y} width={line.width} height={4} rx={2} />
        ))}
        {tableCells.map((cell) => (
          <rect key={`${cell.x}-${cell.y}`} x={cell.x} y={cell.y} width={42} height={8} rx={1} />
        ))}
        {scanLines.map((line) => (
          <path
            key={line.y}
            d={`M56 ${line.y + 2} h${line.width}`}
            strokeWidth={4}
            strokeDasharray="2 2"
            className="stroke-border-strong"
          />
        ))}
      </g>
      {regions.map((region, index) => {
        const found = 0.04 + index * 0.09;
        return (
          <g key={region.order} className="ramp ramp-fade" style={ramp(found, found + 0.08)}>
            <rect
              x={51}
              y={region.y}
              width={98}
              height={region.height}
              rx={3}
              strokeWidth={1}
              strokeDasharray="3 2"
              className="fill-none stroke-accent"
            />
            <circle cx={149} cy={region.y} r={5.5} className="fill-accent-surface" />
            <text
              x={149}
              y={region.y + 2.6}
              fontSize={7.5}
              textAnchor="middle"
              className="fill-accent-content font-semibold"
            >
              {region.order}
            </text>
          </g>
        );
      })}
      <g
        className="ramp ramp-pass ramp-sweep ambient-scan"
        style={ramp(0.42, 0.72, { "--sweep": "130px" })}
      >
        <rect x={46} y={-6} width={108} height={10} className="fill-accent/15" />
        <path d="M46 4 h108" strokeWidth={1.5} className="stroke-accent" />
      </g>
      {/* The same page read: a title, text, a table with its grid, and the scan as text. */}
      <g className="ramp ramp-fade" style={ramp(0.74, 0.9)}>
        <rect x={56} y={15} width={58} height={6} rx={2} className="fill-content-primary" />
        {paragraphLines.map((line) => (
          <rect
            key={line.y}
            x={56}
            y={line.y}
            width={line.width}
            height={4}
            rx={2}
            className="fill-content-secondary"
          />
        ))}
        <path
          d="M54 66 h92 M54 77 h92 M54 88 h92 M54 99 h92 M100 66 v33"
          strokeWidth={0.75}
          className="fill-none stroke-border-strong"
        />
        {tableCells.map((cell) => (
          <rect
            key={`${cell.x}-${cell.y}`}
            x={cell.x + 2}
            y={cell.y + 2}
            width={cell.x === 56 ? 26 : 30}
            height={3}
            rx={1.5}
            className={cell.y === 68 ? "fill-content-muted" : "fill-content-secondary"}
          />
        ))}
        {scanLines.map((line) => (
          <rect
            key={line.y}
            x={56}
            y={line.y}
            width={line.width}
            height={4}
            rx={2}
            className="fill-content-secondary"
          />
        ))}
      </g>
    </Drawing>
  );
}

// Passages in their final places, and how far each starts from them while the page is whole.
const strips = [
  { y: 6, offset: 13 },
  { y: 53, offset: 0 },
  { y: 100, offset: -13 },
];
// Where the cuts run on the whole page, between the passages.
const cuts = [53, 87];

function StripBody({ index, y }: { index: number; y: number }) {
  if (index === 1) {
    return (
      <path
        d={`M48 ${y + 6} h104 M48 ${y + 14} h104 M100 ${y + 3} v14`}
        strokeWidth={0.75}
        className="fill-none stroke-border-strong"
      />
    );
  }
  return (
    <>
      <rect x={48} y={y + 7} width={80} height={3} rx={1.5} className="fill-content-secondary" />
      <rect x={48} y={y + 14} width={96} height={3} rx={1.5} className="fill-content-secondary" />
    </>
  );
}

function ChunkDrawing() {
  return (
    <Drawing>
      {strips.map((strip, index) => (
        <g
          key={strip.y}
          className="ramp ramp-travel"
          style={ramp(0.32, 0.68, { "--dy": `${strip.offset}px` })}
        >
          <rect
            x={40}
            y={strip.y}
            width={120}
            height={34}
            rx={3}
            strokeWidth={1}
            className="fill-surface-raised stroke-border-default"
          />
          <StripBody index={index} y={strip.y} />
          {/* Each passage keeps its source and access tags. */}
          <g className="ramp ramp-fade" style={ramp(0.7 + index * 0.06, 0.82 + index * 0.06)}>
            <rect
              x={48}
              y={strip.y + 24}
              width={22}
              height={6}
              rx={3}
              className="fill-border-default"
            />
            <rect x={74} y={strip.y + 24} width={32} height={6} rx={3} className="fill-accent/40" />
          </g>
        </g>
      ))}
      {cuts.map((y, index) => (
        <path
          key={y}
          d={`M34 ${y} h132`}
          strokeWidth={1.25}
          strokeDasharray="4 3"
          className="ramp ramp-pass ramp-grow-x ambient-blink fill-none stroke-accent"
          style={ramp(0.06 + index * 0.1, 0.42 + index * 0.1, { "--delay": `${index * 1.2}s` })}
        />
      ))}
    </Drawing>
  );
}

// One vector per passage, drawn as relative component values.
const vectors = [
  [0.5, 0.9, 0.3, 0.7, 0.4, 1, 0.6, 0.2],
  [0.8, 0.4, 0.6, 1, 0.3, 0.5, 0.9, 0.4],
  [0.3, 0.6, 1, 0.5, 0.8, 0.2, 0.7, 0.5],
];
const vectorRows = [24, 70, 116];
const barHeight = 26;
const barPitch = 11;

function EmbedDrawing() {
  return (
    <Drawing>
      {vectors.map((bars, row) => {
        const y = vectorRows[row] ?? 0;
        const begin = 0.08 + row * 0.2;
        return (
          <g key={y}>
            <rect
              x={4}
              y={y - 10}
              width={30}
              height={20}
              rx={2}
              strokeWidth={1}
              className="fill-surface-raised stroke-border-default"
            />
            <rect
              x={9}
              y={y - 4}
              width={20}
              height={2.5}
              rx={1}
              className="fill-content-secondary"
            />
            <rect
              x={9}
              y={y + 1}
              width={14}
              height={2.5}
              rx={1}
              className="fill-content-secondary"
            />
            {bars.map((value, bar) => (
              <rect
                key={bar}
                x={48 + bar * barPitch}
                y={y + 13 - value * barHeight}
                width={6}
                height={value * barHeight}
                rx={2}
                className="ramp ramp-grow-y ambient-level fill-accent/70"
                style={ramp(begin + bar * 0.02, begin + bar * 0.02 + 0.18, {
                  "--delay": `${row * 0.45 + bar * 0.14}s`,
                })}
              />
            ))}
            <path
              d={`M${48 + bars.length * barPitch} ${y} H151`}
              pathLength={100}
              strokeWidth={1}
              className="ramp ramp-draw fill-none stroke-accent/60"
              style={ramp(begin + 0.14, begin + 0.22)}
            />
            <circle
              cx={158}
              cy={y}
              r={5}
              className="ramp ramp-pop fill-accent"
              style={ramp(begin + 0.2, begin + 0.32)}
            />
          </g>
        );
      })}
    </Drawing>
  );
}

// Other documents already in the index, and the three new passages landing in the contracts
// cluster, linked to their nearest neighbours.
const indexedPoints: readonly Point[] = [
  [32, 42],
  [48, 56],
  [24, 64],
  [60, 39],
  [44, 76],
  [40, 98],
  [64, 109],
  [52, 90],
  [80, 101],
  [88, 115],
  [168, 42],
  [176, 64],
  [160, 84],
  [184, 48],
  [108, 64],
  [116, 106],
  [132, 120],
  [96, 31],
];
const passagePoints: readonly Point[] = [
  [140, 50],
  [156, 59],
  [132, 84],
];
const neighbourLinks: readonly (readonly [Point, Point])[] = [
  [
    [140, 50],
    [168, 42],
  ],
  [
    [140, 50],
    [156, 59],
  ],
  [
    [156, 59],
    [176, 64],
  ],
  [
    [132, 84],
    [160, 84],
  ],
];

function IndexDrawing() {
  return (
    <Drawing>
      <circle
        cx={152}
        cy={60}
        r={34}
        strokeWidth={1}
        strokeDasharray="3 3"
        className="ramp ramp-fade fill-accent/5 stroke-accent/40"
        style={ramp(0.5, 0.7)}
      />
      {indexedPoints.map(([x, y], index) => (
        <circle
          key={`${x}-${y}`}
          cx={x}
          cy={y}
          r={2}
          className="ramp ramp-fade fill-border-strong"
          style={ramp(index * 0.015, index * 0.015 + 0.15)}
        />
      ))}
      {neighbourLinks.map(([[x1, y1], [x2, y2]], index) => (
        <path
          key={`${x1}-${y1}-${x2}-${y2}`}
          d={`M${x1} ${y1} L${x2} ${y2}`}
          pathLength={100}
          strokeWidth={1}
          className="ramp ramp-draw fill-none stroke-accent/50"
          style={ramp(0.66 + index * 0.03, 0.82 + index * 0.03)}
        />
      ))}
      {passagePoints.map(([x, y], index) => (
        <circle
          key={`${x}-${y}`}
          cx={x}
          cy={y}
          r={4}
          className="ramp ramp-fade ramp-travel fill-accent"
          style={ramp(0.3 + index * 0.08, 0.6 + index * 0.08, { "--dx": "-80px" })}
        />
      ))}
      {/* A source changes and only its passage is indexed again. */}
      <circle
        cx={140}
        cy={50}
        r={11}
        strokeWidth={1.25}
        className="ramp ramp-pass ramp-pop ambient-pulse fill-none stroke-accent"
        style={ramp(0.84, 1)}
      />
    </Drawing>
  );
}

const question = "Who approves contracts above our limit?";
const answer = "Legal reviews contracts above the limit.";
const queryPoint: Point = [150, 35];
const nearbyPoints: readonly Point[] = [
  [30, 30],
  [20, 55],
  [104, 58],
  [112, 14],
  [96, 40],
];
// The two passages nearest the question, cited as 1 and 2.
const citedPoints: readonly Point[] = [
  [70, 20],
  [58, 52],
];

function AnswerDrawing() {
  const [queryX, queryY] = queryPoint;
  return (
    <div className="flex size-full flex-col gap-3">
      <p
        className="ramp ramp-rise font-main-content-body text-content-secondary"
        style={ramp(0, 0.2)}
      >
        “{question}”
      </p>
      <svg
        viewBox="0 0 200 70"
        preserveAspectRatio="xMinYMid meet"
        className="min-h-0 flex-1 overflow-visible"
      >
        {nearbyPoints.map(([x, y]) => (
          <circle key={`${x}-${y}`} cx={x} cy={y} r={2} className="fill-border-strong" />
        ))}
        {citedPoints.map(([x, y], index) => (
          <path
            key={`${x}-${y}`}
            d={`M${queryX} ${queryY} L${x} ${y}`}
            pathLength={100}
            strokeWidth={1}
            className="ramp ramp-draw fill-none stroke-accent"
            style={ramp(0.34 + index * 0.06, 0.5 + index * 0.06)}
          />
        ))}
        {/* Once answered, the question keeps reaching the passages it cites. */}
        {citedPoints.map(([x, y], index) => (
          <path
            key={`${x}-${y}`}
            d={`M${queryX} ${queryY} L${x} ${y}`}
            pathLength={100}
            strokeWidth={2}
            className="ambient-glint fill-none stroke-accent"
            style={cssVars({ "--delay": `${index * 0.6}s` })}
          />
        ))}
        {citedPoints.map(([x, y], index) => (
          <g key={`${x}-${y}`}>
            <circle cx={x} cy={y} r={4} className="fill-accent" />
            <g className="ramp ramp-pop" style={ramp(0.5 + index * 0.06, 0.62 + index * 0.06)}>
              <circle cx={x - 10} cy={y - 9} r={6.5} className="fill-citation-surface" />
              <text
                x={x - 10}
                y={y - 6.3}
                fontSize={8}
                textAnchor="middle"
                className="fill-citation-content font-semibold"
              >
                {index + 1}
              </text>
            </g>
          </g>
        ))}
        <g className="ramp ramp-pop" style={ramp(0.18, 0.34)}>
          <circle
            cx={queryX}
            cy={queryY}
            r={9}
            strokeWidth={1.25}
            className="fill-accent/10 stroke-accent"
          />
          <circle cx={queryX} cy={queryY} r={2.5} className="fill-accent" />
        </g>
      </svg>
      <p
        className="ramp ramp-rise font-main-content-body text-content-primary"
        style={ramp(0.62, 0.9)}
      >
        {answer}{" "}
        <span className="whitespace-nowrap">
          <span className={citationMarker}>1</span> <span className={citationMarker}>2</span>
        </span>
      </p>
    </div>
  );
}

// In the order of `howItWorks.stages`.
const drawings: readonly ComponentType[] = [
  PullDrawing,
  ExtractDrawing,
  ChunkDrawing,
  EmbedDrawing,
  IndexDrawing,
  AnswerDrawing,
];

// One segment of the beam per station, down the left edge of the list: a hairline track, the lit
// part that grows with the station's progress, a comet at its head while it moves, the light that
// keeps running once the station has finished, and the station's node beside its title. A segment
// reaches the next node, so its overhang is the list's row gap plus the node's offset (3.5).
function BeamSegment({ index, last }: { index: number; last: boolean }) {
  const node = "absolute top-0 left-0 -mt-1 -ml-[3.5px] size-2 rounded-full";
  return (
    <div
      aria-hidden="true"
      className={cn(
        "absolute top-3.5 left-2.5 w-px",
        last ? "bottom-0" : "-bottom-19.5 lg:-bottom-27.5",
      )}
    >
      <span className="absolute inset-0 bg-border-default" />
      <span className="ramp ramp-grow-down absolute inset-0 bg-accent" style={ramp(0, 1)} />
      <span className={cn(node, "bg-border-strong")} />
      <span className={cn(node, "ramp ramp-fade bg-accent")} style={ramp(0, 0.04)} />
      <span
        className={cn(
          node,
          "ramp ramp-pass top-[calc(var(--p,1)*100%)] bg-accent shadow-[0_0_10px_2px_var(--accent)]",
        )}
        style={ramp(0, 1)}
      />
      <span className="beam-flow absolute inset-0">
        <span className="beam-flow-run absolute inset-0" style={cssVars({ "--i": index })}>
          <span className={cn(node, "bg-accent shadow-[0_0_10px_2px_var(--accent)]")} />
        </span>
      </span>
    </div>
  );
}

function IngestionStory() {
  const scope = useRef<HTMLOListElement>(null);

  useMotion(scope, () => {
    const list = scope.current;
    if (!list) {
      return;
    }
    const stations = gsap.utils.toArray<HTMLElement>("[data-station]", list);

    // A finished station keeps its loops (src/styles/base.css), which run while the list is on
    // screen.
    const setLive = (station: HTMLElement, live: boolean) => {
      if (station.hasAttribute("data-live") !== live) {
        station.toggleAttribute("data-live", live);
      }
    };
    const visibility = new IntersectionObserver(([entry]) => {
      list.toggleAttribute("data-visible", Boolean(entry?.isIntersecting));
    });
    visibility.observe(list);
    const stopLoops = () => {
      visibility.disconnect();
      list.removeAttribute("data-visible");
      stations.forEach((station) => station.removeAttribute("data-live"));
    };

    // Each station builds while it crosses the viewport.
    for (const station of stations) {
      const build = gsap.timeline({
        scrollTrigger: { trigger: station, start: "top 85%", end: "bottom 55%", scrub: 0.5 },
        onUpdate: () => setLive(station, build.progress() === 1),
      });
      build.fromTo(station, { "--p": 0 }, { "--p": 1, ease: "none" });
    }
    return stopLoops;
  });

  return (
    <ol ref={scope} className="grid gap-y-16 lg:gap-y-24">
      {howItWorks.stages.map((stage, index) => {
        const StageDrawing = drawings[index];
        return (
          <li
            key={stage.title}
            data-station=""
            className="relative grid gap-y-6 pl-9 sm:pl-12 lg:grid-cols-[minmax(0,2fr)_minmax(0,3fr)] lg:gap-x-16"
          >
            <BeamSegment index={index} last={index === howItWorks.stages.length - 1} />
            <div>
              <h3 className="font-heading-h3 text-content-primary">
                <span className="mr-2 text-accent tabular-nums">{index + 1}</span>
                {stage.title}
              </h3>
              <p className="mt-2 max-w-sm font-main-content-body text-content-secondary">
                {stage.description}
              </p>
            </div>
            {StageDrawing ? (
              <div aria-hidden="true" className="aspect-[10/7] w-full max-w-xl">
                <StageDrawing />
              </div>
            ) : null}
          </li>
        );
      })}
    </ol>
  );
}

export { IngestionStory };
