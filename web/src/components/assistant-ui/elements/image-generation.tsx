"use client";

// Inspired by assistant-ui elements-image-generation (MIT): a dot-grid holds the
// frame over a blurred gradient while generating, settling into the resolved image.
// Prop-driven (labels passed in) so the element stays i18n-free and unit-testable.
import type { ComponentProps } from "react";
import { DownloadIcon } from "lucide-react";
import { cn } from "@/lib/utils";
import { ShimmerLabel } from "./surfaces";

export function ImageGeneration({
  prompt,
  generating,
  src,
  alt,
  label,
  downloadLabel,
  failed = false,
  className,
  ...props
}: ComponentProps<"div"> & {
  prompt?: string;
  generating: boolean;
  src?: string;
  alt?: string;
  label: string;
  downloadLabel?: string;
  failed?: boolean;
}) {
  const showImage = !!src && !generating && !failed;
  return (
    <div
      data-slot="image-generation"
      className={cn("flex w-full max-w-sm flex-col gap-2", className)}
      {...props}
    >
      <div
        data-slot="image-generation-frame"
        className="relative aspect-square w-full overflow-hidden rounded-xl border border-border/60"
      >
        {/* Fixed decorative gradient, present in both states; only its blur/opacity changes. */}
        <div
          aria-hidden="true"
          className={cn(
            "absolute inset-0 bg-gradient-to-br from-blue-400/30 via-fuchsia-400/25 to-amber-300/30 transition-[filter,opacity] duration-500",
            showImage ? "opacity-30 blur-2xl" : "opacity-90 blur-xl",
          )}
        />
        {showImage ? (
          <img
            src={src}
            alt={alt ?? prompt ?? label}
            className="relative z-10 h-full w-full object-contain"
          />
        ) : (
          <div
            data-slot="image-generation-grid"
            aria-hidden="true"
            className="absolute inset-0 grid place-items-center"
          >
            <div className="grid grid-cols-8 gap-1.5">
              {Array.from({ length: 64 }).map((_, index) => (
                <span
                  key={index}
                  className={cn(
                    "size-1 rounded-full bg-foreground/40 transition-opacity",
                    generating ? "animate-pulse motion-reduce:animate-none" : "opacity-0",
                  )}
                  style={
                    generating
                      ? { animationDelay: `${((index % 8) + Math.floor(index / 8)) * 70}ms` }
                      : undefined
                  }
                />
              ))}
            </div>
          </div>
        )}
      </div>
      <div className="flex items-center gap-2">
        <p
          role="status"
          className="min-w-0 flex-1 truncate text-xs text-content-muted"
          title={prompt || label}
        >
          {generating ? (
            <ShimmerLabel className="relative inline-block leading-none">{label}</ShimmerLabel>
          ) : (
            (failed ? label : prompt || label)
          )}
        </p>
        {showImage && (
          <a
            href={src}
            download
            aria-label={downloadLabel}
            className="flex size-6 shrink-0 items-center justify-center rounded-full text-foreground/45 outline-none transition-colors hover:bg-foreground/[0.06] hover:text-foreground/90 focus-visible:ring-1 focus-visible:ring-foreground/20 dark:hover:bg-foreground/[0.09]"
          >
            <DownloadIcon className="size-3" aria-hidden="true" />
          </a>
        )}
      </div>
    </div>
  );
}
