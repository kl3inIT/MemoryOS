import { describe, expect, it } from "vitest";
import {
  capabilities,
  deployment,
  faq,
  footer,
  hero,
  howItWorks,
  product,
  roadmap,
} from "@/content";

const maxWords = 12;

// Technical detail belongs in the FAQ; everything else says one short thing.
const shortCopy = [
  hero.statement,
  product.description,
  product.search.description,
  product.governance.description,
  howItWorks.description,
  ...howItWorks.stages.map((stage) => stage.description),
  howItWorks.gate.summary,
  capabilities.description,
  ...capabilities.items.map((item) => item.description),
  deployment.description,
  roadmap.description,
  ...roadmap.milestones.map((milestone) => milestone.description),
  faq.description,
  footer.description,
];

describe("page copy", () => {
  it.each(shortCopy)("keeps %j to one sentence of at most 12 words", (text) => {
    expect(text.trim().split(/\s+/).length).toBeLessThanOrEqual(maxWords);
    expect(text.slice(0, -1)).not.toMatch(/[.!?]\s/);
  });
});
