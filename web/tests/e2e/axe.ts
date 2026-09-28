import AxeBuilder from "@axe-core/playwright";
import { expect, type Page } from "@playwright/test";

/**
 * Asserts that axe finds no serious or critical WCAG 2.2 A/AA violation on the page as it is now. Minor and moderate
 * findings are left to review; each reported violation names its rule and the offending elements, so a failure reads
 * without the report.
 */
export async function expectNoSeriousA11yViolations(page: Page) {
  const { violations } = await new AxeBuilder({ page })
    .withTags(["wcag2a", "wcag2aa", "wcag21a", "wcag21aa", "wcag22aa"])
    .analyze();
  const serious = violations
    .filter((violation) => violation.impact === "serious" || violation.impact === "critical")
    .map((violation) => ({
      rule: violation.id,
      impact: violation.impact,
      nodes: violation.nodes.map((node) => `${node.target.join(" ")}: ${node.html.slice(0, 160)}`),
    }));
  expect(serious).toEqual([]);
}
