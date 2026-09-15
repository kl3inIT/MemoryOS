import { useId } from "react";
import {
  MEMORYOS_WORDMARK_PATHS,
  MEMORYOS_WORDMARK_VIEW_BOX,
} from "@/components/memoryos-wordmark";
import "./brand-loader.css";

const STRIP_COUNT = 14;
const STRIP_STAGGER_MS = 30;
const { x, y, width, height } = MEMORYOS_WORDMARK_VIEW_BOX;
const stripWidth = width / STRIP_COUNT;
const sheenPoints = [
  [x - 110, y - 10],
  [x - 20, y - 10],
  [x - 80, y + height + 10],
  [x - 170, y + height + 10],
]
  .map((point) => point.join(","))
  .join(" ");

type BrandLoaderProps = {
  label: string;
  size?: "sm" | "lg";
};

/**
 * Loading indicator in the short form of the boot splash: ribbons draw the MemoryOS wordmark once,
 * then a light sheen crosses it for as long as the loader is mounted. It is visual only; callers keep
 * ownership of status semantics (role, live region, busy state).
 */
export function BrandLoader({ label, size = "sm" }: BrandLoaderProps) {
  const id = `brand-loader-${useId().replace(/[^\w-]/g, "")}`;
  const wordmarkId = `${id}-wordmark`;

  return (
    <span className="brand-loader inline-flex flex-col items-center gap-3">
      <svg
        className={`${size === "lg" ? "w-44" : "w-28"} aspect-[1120/166] h-auto overflow-visible text-[#083372] dark:text-content-primary`}
        viewBox={`${x} ${y} ${width} ${height}`}
        aria-hidden="true"
        focusable="false"
      >
        <defs>
          <g id={wordmarkId}>
            {MEMORYOS_WORDMARK_PATHS.map((d) => (
              <path key={d} fillRule="evenodd" d={d} />
            ))}
          </g>
          {Array.from({ length: STRIP_COUNT }, (_, index) => (
            <clipPath key={index} id={`${id}-strip-${index}`}>
              <rect
                x={x + index * stripWidth}
                y={y - 10}
                width={stripWidth + 1}
                height={height + 20}
              />
            </clipPath>
          ))}
          <mask id={`${id}-mask`}>
            <use href={`#${wordmarkId}`} fill="#ffffff" />
          </mask>
          <linearGradient id={`${id}-sheen`}>
            <stop className="brand-loader__sheen-stop" offset="0" stopOpacity="0" />
            <stop className="brand-loader__sheen-stop" offset="0.5" stopOpacity="0.6" />
            <stop className="brand-loader__sheen-stop" offset="1" stopOpacity="0" />
          </linearGradient>
        </defs>
        <g className="brand-loader__strips">
          {Array.from({ length: STRIP_COUNT }, (_, index) => (
            <g key={index} clipPath={`url(#${id}-strip-${index})`}>
              <use
                className={`brand-loader__strip ${index % 2 === 0 ? "brand-loader__strip--down" : "brand-loader__strip--up"}`}
                href={`#${wordmarkId}`}
                style={{ animationDelay: `${index * STRIP_STAGGER_MS}ms` }}
              />
            </g>
          ))}
        </g>
        <use className="brand-loader__still" href={`#${wordmarkId}`} />
        <g mask={`url(#${id}-mask)`}>
          <polygon
            className="brand-loader__sheen"
            points={sheenPoints}
            fill={`url(#${id}-sheen)`}
          />
        </g>
      </svg>
      <span className="font-secondary-body text-content-muted">{label}</span>
    </span>
  );
}
