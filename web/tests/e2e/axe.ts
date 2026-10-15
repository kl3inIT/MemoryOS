import AxeBuilder from "@axe-core/playwright";
import { expect, type Page } from "@playwright/test";

/**
 * Asserts that axe finds no serious or critical WCAG 2.2 A/AA violation on the page once its animations settle. Minor and moderate
 * findings are left to review; each reported violation names its rule and the offending elements, so a failure reads
 * without the report.
 */
export async function expectNoSeriousA11yViolations(page: Page) {
  // Colour contrast is measured on the rendered pixels: a panel still fading in reads as low contrast, more so on a
  // slow CI runner. Finite animations settle first; endless ones (spinners) never do and are not awaited.
  await page.evaluate(() =>
    Promise.all(
      document
        .getAnimations()
        .filter((animation) => animation.effect?.getComputedTiming().iterations !== Infinity)
        .map((animation) => animation.finished.catch(() => undefined)),
    ),
  );
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
