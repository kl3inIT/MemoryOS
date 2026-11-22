import { useId } from "react";
import {
  MEMORYOS_WORDMARK_PATHS,
  MEMORYOS_WORDMARK_VIEW_BOX,
} from "@/components/memoryos-wordmark";
import { useAppTranslation } from "@/i18n/use-app-translation";

// The M from public/favicon.svg, without the favicon's background.
const MEMORYOS_MARK_PATHS = ["M8 9h3.2l4.8 7 4.8-7H24v14h-3.3v-8.7L16 21l-4.7-6.7V23H8V9Z"];
const MEMORYOS_MARK_VIEW_BOX = { x: 8, y: 9, width: 16, height: 14 };

type BrandProps = {
  compact?: boolean;
};

/** Masks the MemoryOS artwork with the theme's primary text colour. */
export function Brand({ compact = false }: BrandProps) {
  const ui = useAppTranslation();
  const maskId = useId();
  const { x, y, width, height } = compact ? MEMORYOS_MARK_VIEW_BOX : MEMORYOS_WORDMARK_VIEW_BOX;
  const paths = compact ? MEMORYOS_MARK_PATHS : MEMORYOS_WORDMARK_PATHS;

  return (
    <svg
      role="img"
      aria-label={ui("MemoryOS")}
      viewBox={`${x} ${y} ${width} ${height}`}
      className={
        compact
          ? "size-7 rounded-md border border-border-default bg-surface-raised p-[5px] text-content-primary"
          : "h-4 w-28 text-content-primary"
      }
    >
      <mask id={maskId}>
        {paths.map((d) => (
          <path key={d} d={d} fill="white" fillRule="evenodd" />
        ))}
      </mask>
      <rect
        x={x}
        y={y}
        width={width}
        height={height}
        fill="currentColor"
        mask={`url(#${maskId})`}
      />
    </svg>
  );
}
