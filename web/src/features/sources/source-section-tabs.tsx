import { TabsList, TabsTrigger } from "@/components/ui/tabs";
import type { AppCopy } from "@/i18n/app-text";
import { useAppTranslation } from "@/i18n/use-app-translation";

export type SourceSection = { value: string; label: AppCopy };

/**
 * Underlined section tabs of a Source. The active underline is the primitive's `::after` line,
 * moved from 5px below the tab onto the list's bottom border so the two read as one rule.
 */
export function SourceSectionTabs({ sections }: { sections: readonly SourceSection[] }) {
  const ui = useAppTranslation();

  return (
    <TabsList
      variant="line"
      aria-label={ui("Source sections")}
      className="flex h-auto w-full flex-wrap justify-start border-b border-border-subtle p-0 group-data-horizontal/tabs:h-auto"
    >
      {sections.map(({ value, label }) => (
        <TabsTrigger
          key={value}
          value={value}
          className="h-auto min-h-11 flex-none rounded-none border-0 px-4 py-3 text-content-muted group-data-horizontal/tabs:after:-bottom-px hover:text-content-primary focus-visible:ring-2 focus-visible:ring-focus-ring focus-visible:outline-0 data-[state=active]:text-content-primary dark:text-content-muted dark:hover:text-content-primary dark:data-[state=active]:text-content-primary"
        >
          {ui(label)}
        </TabsTrigger>
      ))}
    </TabsList>
  );
}
