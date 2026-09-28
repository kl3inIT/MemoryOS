import i18next, { type BackendModule } from "i18next";
import { initReactI18next } from "react-i18next";
import type { en } from "./en";
import { vi } from "./vi";

export type UiLanguage = "vi" | "en";
export function uiLanguage(value: unknown): UiLanguage {
  return value === "en" ? "en" : "vi";
}

/** Vietnamese ships in the entry bundle; English loads when a person chooses it. */
const lazyEnglish: BackendModule = {
  type: "backend",
  init() {},
  read(language, namespace, callback) {
    if (language !== "en") {
      callback(null, {});
      return;
    }
    import("./en").then(
      ({ en: catalog }) => callback(null, catalog[namespace as keyof typeof catalog]),
      (error: Error) => callback(error, false),
    );
  },
};

export const i18n = i18next.createInstance();
void i18n
  .use(lazyEnglish)
  .use(initReactI18next)
  .init({
    resources: { vi },
    partialBundledLanguages: true,
    lng: "vi",
    // A key missing from the active language is its own text: app keys are natural-language source copy.
    fallbackLng: false,
    supportedLngs: ["vi", "en"],
    defaultNS: "common",
    ns: Object.keys(vi),
    initAsync: false,
    interpolation: { escapeValue: false },
    react: { useSuspense: false },
  });
function setHtmlLanguage() {
  document.documentElement.lang = uiLanguage(i18n.resolvedLanguage);
}
i18n.on("languageChanged", setHtmlLanguage);
setHtmlLanguage();

declare module "i18next" {
  interface CustomTypeOptions {
    defaultNS: "common";
    resources: typeof en;
    strictKeyChecks: true;
  }
}
