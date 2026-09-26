import { TabsList, TabsTrigger } from "@/components/ui/tabs";
import { useAppTranslation } from "@/i18n/use-app-translation";
import type { SourceSection } from "./source-sections";

/** Underlined section tabs of a Source over one rule across the page. */
export function SourceSectionTabs({ sections }: { sections: readonly SourceSection[] }) {
  const ui = useAppTranslation();

  return (
    <div className="border-b border-border-subtle pb-1">
      <TabsList
        variant="line"
        aria-label={ui("Source sections")}
        className="flex-wrap justify-start"
      >
        {sections.map(({ value, label }) => (
          <TabsTrigger key={value} value={value} className="flex-none">
            {ui(label)}
          </TabsTrigger>
        ))}
      </TabsList>
    </div>
  );
}
