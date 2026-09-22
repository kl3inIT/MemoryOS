import {
  MEMORYOS_WORDMARK_PATHS,
  MEMORYOS_WORDMARK_VIEW_BOX,
} from "@/components/memoryos-wordmark";
import { useAppTranslation } from "@/i18n/use-app-translation";

const { x, y, width, height } = MEMORYOS_WORDMARK_VIEW_BOX;

type BrandProps = {
  compact?: boolean;
};

/**
 * The MemoryOS brand, drawn from the same traced artwork the brand loader uses. The name is carried by the
 * label rather than by text beside the mark, because the wordmark already spells it.
 */
export function Brand({ compact = false }: BrandProps) {
  const ui = useAppTranslation();

  return (
    <span className="inline-flex items-center" aria-label={ui("MemoryOS")}>
      {compact ? <BrandMark /> : <BrandWordmark />}
    </span>
  );
}

function BrandWordmark() {
  return (
    <svg
      data-slot="brand-wordmark"
      viewBox={`${x} ${y} ${width} ${height}`}
      className="h-4 w-auto text-[#083372] dark:text-content-primary"
      fill="currentColor"
      aria-hidden="true"
    >
      {MEMORYOS_WORDMARK_PATHS.map((path) => (
        <path key={path} d={path} />
      ))}
    </svg>
  );
}

/** The app icon the favicon carries, for a rail too narrow to read a wordmark in. */
function BrandMark() {
  return (
    <svg data-slot="brand-mark" viewBox="0 0 32 32" className="size-7" aria-hidden="true">
      <rect width="32" height="32" rx="8" className="fill-content-primary" />
      <path
        d="M8 9h3.2l4.8 7 4.8-7H24v14h-3.3v-8.7L16 21l-4.7-6.7V23H8V9Z"
        className="fill-surface-raised"
      />
    </svg>
  );
}
