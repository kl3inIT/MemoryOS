import { Section } from "@/components/section";
import { howItWorks } from "@/content";
import { AccessGate } from "@/sections/how-it-works/access-gate";
import { IngestionStory } from "@/sections/how-it-works/ingestion-story";

function HowItWorks() {
  return (
    <Section id="how-it-works" title={howItWorks.title} description={howItWorks.description}>
      <IngestionStory />
      <AccessGate />
    </Section>
  );
}

export { HowItWorks };
