import { Section } from "@/components/section";
import { howItWorks } from "@/content";
import { AccessGate } from "@/sections/how-it-works/access-gate";
import { IngestionStory } from "@/sections/how-it-works/ingestion-story";

function HowItWorks() {
  // The ingestion track overflows to the side of the viewport before and after it is pinned.
  return (
    <Section
      id="how-it-works"
      title={howItWorks.title}
      description={howItWorks.description}
      className="overflow-x-clip"
    >
      <IngestionStory />
      <AccessGate />
    </Section>
  );
}

export { HowItWorks };
