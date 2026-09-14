import { useTranslation } from "react-i18next";
import type { AppCopy } from "./app-text";

/** Static UI source keys only. Do not pass user content or arbitrary server errors. */
export function useAppTranslation() {
  const { t } = useTranslation("app");
  function translate(copy: AppCopy, values?: Record<string, unknown>): string {
    const key = typeof copy === "string" ? copy : copy.app;
    const params = typeof copy === "string" ? values : copy.values;
    const resolved = Object.fromEntries(
      Object.entries(params ?? {}).map(([name, value]) => [
        name,
        typeof value === "object" && value !== null && "app" in value
          ? translate(value as AppCopy)
          : value,
      ]),
    );
    return t(key, { ...resolved, ns: "app", keySeparator: false, nsSeparator: false });
  }
  return translate;
}
