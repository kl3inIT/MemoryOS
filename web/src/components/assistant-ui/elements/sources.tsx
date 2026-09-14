"use client";

import type { ComponentProps } from "react";
import { cn } from "@/lib/utils";
import { Button } from "@/components/ui/button";
import { DocumentSourceIcon } from "@/features/search/document-source-icon";
import {
  documentKind,
  type DocumentSourceType,
} from "@/features/search/document-source-presentation";
import { SourceIcon } from "./source-icon";
import { useTranslation } from "react-i18next";

type SourceSummary = {
  url?: string;
  mediaType?: string | null;
  sourceTypes?: readonly DocumentSourceType[];
};

// Onyx's grouped toolbar trigger, composed with assistant-ui source icons.
// The application keeps ownership of its selected message and document panel.
export function Sources({
  count,
  sources,
  className,
  ...props
}: ComponentProps<"button"> & { count: number; sources: readonly SourceSummary[] }) {
  const { t } = useTranslation("chatStatus");
  // Distinct by Web hostname, or by document kind.
  const icons = [
    ...new Map(
      sources.map((source): [string, { domain?: string; source: SourceSummary }] => {
        const domain = webDomain(source.url);
        if (domain) return [domain, { domain, source }];
        // Chip-size document icons carry no provider badge, so one icon per kind avoids identical twins.
        return [`document:${documentKind(source.mediaType)}`, { source }];
      }),
    ),
  ].slice(0, 3);
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
        {icons.map(([key, icon], index) => (
          <span
            key={key}
            className="relative flex size-5 items-center justify-center rounded border border-border-subtle bg-surface-base"
            style={{ zIndex: icons.length - index }}
          >
            {icon.domain ? (
              <SourceIcon domain={icon.domain} fallback="globe" />
            ) : (
              <span data-slot="source-document-icon" className="flex">
                <DocumentSourceIcon
                  size="xs"
                  mediaType={icon.source.mediaType}
                  sourceTypes={icon.source.sourceTypes}
                />
              </span>
            )}
          </span>
        ))}
      </span>
      <span>{t("sources")}</span>
    </Button>
  );
}

function webDomain(value: string | undefined) {
  if (!value) return undefined;
  try {
    const url = new URL(value);
    return ["https:", "http:"].includes(url.protocol) && !url.username && !url.password
      ? url.hostname
      : undefined;
  } catch {
    return undefined;
  }
}
