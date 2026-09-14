"use client";

import { FileText } from "lucide-react";
import type { ComponentProps } from "react";
import { cn } from "@/lib/utils";
import { Button } from "@/components/ui/button";
import { SourceIcon } from "./source-icon";
import { useTranslation } from "react-i18next";

// Onyx's grouped toolbar trigger, composed with assistant-ui source icons.
// The application keeps ownership of its selected message and document panel.
export function Sources({
  count,
  sources,
  className,
  ...props
}: ComponentProps<"button"> & { count: number; sources: readonly { url?: string }[] }) {
  const { t } = useTranslation("chatStatus");
  const icons = Array.from(
    new Set(
      sources.map((source) => {
        if (!source.url) return "document";
        try {
          const url = new URL(source.url);
          return ["https:", "http:"].includes(url.protocol) && !url.username && !url.password
            ? url.hostname
            : "document";
        } catch {
          return "document";
        }
      }),
    ),
  ).slice(0, 3);
  return (
    <Button
      type="button"
      size="sm"
      prominence="internal"
      data-slot="sources"
      aria-label={t("sourcesCount", { count })}
      className={cn("aria-expanded:bg-surface-sunken", className)}
      {...props}
    >
      <span
        data-slot="source-icon-stack"
        className="flex items-center -space-x-1.5"
        aria-hidden="true"
      >
        {icons.map((icon, index) => (
          <span
            key={icon}
            className="relative flex size-5 items-center justify-center rounded border border-border-subtle bg-surface-base"
            style={{ zIndex: icons.length - index }}
          >
            {icon === "document" ? (
              <FileText data-slot="source-document-icon" className="size-3.5" />
            ) : (
              <SourceIcon domain={icon} />
            )}
          </span>
        ))}
      </span>
      <span>{t("sources")}</span>
    </Button>
  );
}
