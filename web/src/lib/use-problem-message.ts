import { useTranslation } from "react-i18next";
import type { ErrorMessage } from "./problem-presentation";

/** Translate descriptors at render time, validating interpolation requirements. */
export function useProblemMessage() {
  const { t } = useTranslation("errors");
  return (message: ErrorMessage) => {
    const params = message.params ?? {};
    switch (message.key) {
      case "size":
        return params.min !== undefined && params.max !== undefined
          ? t("size", { min: params.min, max: params.max })
          : t("invalid");
      case "min":
        return params.min !== undefined ? t("min", { min: params.min }) : t("invalid");
      case "max":
        return params.max !== undefined ? t("max", { max: params.max }) : t("invalid");
      default:
        return t(message.key);
    }
  };
}
