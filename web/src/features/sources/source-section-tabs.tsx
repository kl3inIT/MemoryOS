import { TabsList, TabsTrigger } from "@/components/ui/tabs";
import type { AppCopy } from "@/i18n/app-text";
import { useAppTranslation } from "@/i18n/use-app-translation";

export type SourceSection = { value: string; label: AppCopy };

/**
 * Underlined section tabs of a Source. Radix marks the active tab with `data-state`, so the
 * active styles are set here rather than through the primitive's `data-active` classes.
 */
export function SourceSectionTabs({ sections }: { sections: readonly SourceSection[] }) {
  const ui = useAppTranslation();

  return (
    <TabsList
      variant="line"
      aria-label={ui("Source sections")}
      className="flex w-full flex-wrap justify-start border-b border-border-subtle p-0"
    >
      {sections.map(({ value, label }) => (
        <TabsTrigger
          key={value}
          value={value}
          className="min-h-11 flex-none rounded-none border-0 border-b-2 border-transparent px-4 py-3 text-content-muted hover:text-content-primary focus-visible:ring-2 focus-visible:ring-focus-ring focus-visible:outline-0 data-[state=active]:border-content-primary data-[state=active]:text-content-primary dark:text-content-muted dark:hover:text-content-primary dark:data-[state=active]:text-content-primary"
        >
          {ui(label)}
        </TabsTrigger>
      ))}
    </TabsList>
  );
}
