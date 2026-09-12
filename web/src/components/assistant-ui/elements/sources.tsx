"use client";

import { Files } from "lucide-react";
import type { ComponentProps } from "react";
import { cn } from "@/lib/utils";
import { fieldInteractive, mono } from "./surfaces";
import { useTranslation } from "react-i18next";

// Keep the registry Sources pill; the application opens its document panel.
export function Sources({
  count,
  className,
  ...props
}: ComponentProps<"button"> & { count: number }) {
  const { t, i18n } = useTranslation("chatStatus");
  return (
    <button
      type="button"
      data-slot="sources"
      aria-label={t("sourcesCount", { count })}
      className={cn(
        fieldInteractive,
        "inline-flex items-center gap-1.5 rounded-lg px-2.5 py-1.5 text-xs text-content-secondary outline-none hover:text-content-primary focus-visible:ring-2 focus-visible:ring-ring aria-expanded:bg-accent",
        className,
      )}
      {...props}
    >
      <Files className="size-3.5" aria-hidden="true" />
      <span>{t("sources")}</span>
      <span className={cn(mono, "text-content-muted tabular-nums")}>
        {new Intl.NumberFormat(i18n.resolvedLanguage).format(count)}
      </span>
    </button>
  );
}
