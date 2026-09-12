import i18next from "i18next";
import { initReactI18next } from "react-i18next";
import { en } from "./en";
import { vi } from "./vi";

export type UiLanguage = "vi" | "en";
export function uiLanguage(value: unknown): UiLanguage {
  return value === "en" ? "en" : "vi";
}

export const i18n = i18next.createInstance();
void i18n.use(initReactI18next).init({
  resources: { en, vi },
  lng: "vi",
  fallbackLng: "en",
  supportedLngs: ["vi", "en"],
  defaultNS: "common",
  ns: Object.keys(en),
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
