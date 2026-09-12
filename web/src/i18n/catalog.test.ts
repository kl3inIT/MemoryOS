import { describe, expect, it } from "vitest";
import { en } from "./en";
import { vi } from "./vi";
import { i18n } from "./index";

function flatten(value: object, prefix = ""): Record<string, string> {
  return Object.fromEntries(
    Object.entries(value).flatMap(([key, child]) => {
      const path = prefix ? `${prefix}.${key}` : key;
      return typeof child === "string" ? [[path, child]] : Object.entries(flatten(child, path));
    }),
  );
}

describe("bundled language catalogs", () => {
  it("keeps keys and interpolation parameters in parity with nonempty translations", () => {
    const english = flatten(en);
    const vietnamese = flatten(vi);
    expect(Object.keys(vietnamese).sort()).toEqual(Object.keys(english).sort());
    for (const [key, source] of Object.entries(english)) {
      expect(vietnamese[key].trim(), key).not.toBe("");
      const parameters = (text: string) =>
        [...text.matchAll(/{{\s*([\w]+)\s*}}/g)].map((match) => match[1]).sort();
      expect(parameters(vietnamese[key]), key).toEqual(parameters(source));
    }
  });
  it("changes HTML language and renders both locales without translation keys", async () => {
    await i18n.changeLanguage("vi");
    expect(document.documentElement.lang).toBe("vi");
    expect(i18n.t("settings:language")).toBe("Ngôn ngữ giao diện");
    await i18n.changeLanguage("en");
    expect(document.documentElement.lang).toBe("en");
    expect(i18n.t("settings:language")).toBe("Display language");
  });
});
