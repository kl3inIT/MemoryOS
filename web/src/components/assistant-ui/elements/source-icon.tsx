"use client";

// SourceIcon from assistant-ui Sources (MIT), registry retrieved 2026-09-13.
// Adapted to MemoryOS tokens and no-referrer/anonymous favicon requests.
import { Globe } from "lucide-react";
import { useState } from "react";
import { cn } from "@/lib/utils";

export function SourceIcon({
  domain,
  className,
  favicon = true,
  fallback = "letter",
}: {
  domain: string;
  className?: string;
  /** False renders the fallback without sending the domain to the favicon service. */
  favicon?: boolean;
  /**
   * Shown when no favicon is available. Next to a citation number a letter reads as part of the
   * number ("R2"), so chips pass `none` and icon stacks pass `globe`.
   */
  fallback?: "letter" | "globe" | "none";
}) {
  const src = `https://icons.duckduckgo.com/ip3/${encodeURIComponent(domain)}.ico`;
  const [errorSrc, setErrorSrc] = useState<string>();
  if (!favicon || errorSrc === src) {
    if (fallback === "none") return null;
    if (fallback === "globe")
      return (
        <Globe
          data-slot="source-icon-fallback"
          aria-hidden="true"
          className={cn("size-3.5 shrink-0 text-content-muted", className)}
        />
      );
    return (
      <span
        data-slot="source-icon-fallback"
        className={cn(
          "flex size-3.5 shrink-0 items-center justify-center rounded-sm bg-surface-sunken text-[10px] font-medium",
          className,
        )}
      >
        {domain.charAt(0).toUpperCase() || "?"}
      </span>
    );
  }
  return (
    <img
      data-slot="source-icon"
      src={src}
      alt=""
      referrerPolicy="no-referrer"
      crossOrigin="anonymous"
      className={cn("size-3.5 shrink-0 rounded-sm", className)}
      onError={() => setErrorSrc(src)}
      ref={(element) => {
        if (element?.complete && element.naturalWidth === 0) setErrorSrc(src);
      }}
    />
  );
}
