import { expect, test as base } from "@playwright/test";

/**
 * Specs that import `test` from here fail on any Content-Security-Policy or Permissions-Policy violation the
 * browser reports. The dev server sends neither policy, so this bites in preview mode (MEMORYOS_E2E_PREVIEW=1),
 * where the production build is served with nginx.conf's headers.
 */
type Violation = { directive: string; source: string; style: string };

/** Third-party reports that change nothing the reader relies on; any other report fails the test. */
const ACCEPTED: { reason: string; matches: (violation: Violation) => boolean }[] = [
  {
    // z.config({ jitless: true }) cannot run first: the bundle builds the generated schemas before the entry body.
    reason:
      "Zod probes eval with new Function() in a try/catch and parses without compiled parsers",
    matches: ({ directive, source }) =>
      directive === "script-src" && /\/assets\/schemas-[\w-]+\.js$/.test(source),
  },
  {
    reason:
      "rehype-katex parses KaTeX markup in an inert template; React applies its styles through CSSOM",
    matches: ({ directive, source }) =>
      directive === "style-src-attr" && /\/assets\/markdown-text-[\w-]+\.js$/.test(source),
  },
  {
    reason:
      "react-remove-scroll (Radix modal scroll lock) injects <style>; wheel and touch stay locked by its listeners",
    matches: ({ directive, style }) =>
      directive === "style-src-elem" && /with-scroll-bars-hidden|block-interactivity-/.test(style),
  },
  {
    reason: "Radix Select injects a <style> that only hides its viewport scrollbar",
    matches: ({ directive, style }) =>
      directive === "style-src-elem" && style.includes("[data-radix-select-viewport]"),
  },
];

const REPORT = "memoryos-policy-violation:";

export const test = base.extend<{ deploymentPolicy: void }>({
  deploymentPolicy: [
    async ({ context }, use) => {
      await context.addInitScript((prefix) => {
        document.addEventListener("securitypolicyviolation", (event) => {
          const style = event.target instanceof HTMLStyleElement ? event.target.textContent : "";
          const violation = {
            directive: event.effectiveDirective,
            source: event.sourceFile,
            style,
          };
          console.info(prefix + JSON.stringify(violation));
        });
      }, REPORT);
      const violations: string[] = [];
      context.on("console", (message) => {
        const text = message.text();
        if (text.startsWith(REPORT)) {
          const violation = JSON.parse(text.slice(REPORT.length)) as Violation;
          if (!ACCEPTED.some((accepted) => accepted.matches(violation)))
            violations.push(`${violation.directive} from ${violation.source} ${violation.style}`);
        } else if (/permissions policy/i.test(text)) violations.push(text);
      });
      await use();
      expect(violations, "the deployment policy refused something the page needed").toEqual([]);
    },
    { auto: true },
  ],
});

export { expect };
