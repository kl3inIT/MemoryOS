import { describe, expect, it } from "vitest";
import { inlineFontDataUrlPattern } from "./font-data-url.mjs";

describe("inlineFontDataUrlPattern", () => {
  it.each([
    '@font-face { src: url(data:font/woff2;base64,AAAA) format("woff2"); }',
    '@font-face { src: url("data:application/font-woff2;base64,AAAA") format("woff2"); }',
  ])("detects inline font data URLs in @font-face", (css) => {
    expect(inlineFontDataUrlPattern.test(css)).toBe(true);
  });

  it("ignores unrelated data URLs outside @font-face", () => {
    expect(
      inlineFontDataUrlPattern.test('.icon { background: url("data:image/svg+xml,AAAA"); }'),
    ).toBe(false);
  });
});
