"use client";

// Inspired by assistant-ui elements-image-generation (MIT): a dot-grid holds the
// frame over a blurred gradient while generating, then the resolved image "develops"
// top-to-bottom (clip + unblur) the way ChatGPT/Copilot reveal a fresh render.
// Clicking the image opens a fullscreen viewer. Prop-driven (labels passed in) so the
// element stays i18n-free and unit-testable.
import { type ComponentProps, useEffect, useRef, useState } from "react";
import { DownloadIcon, XIcon } from "lucide-react";
import { Dialog } from "radix-ui";
import { cn } from "@/lib/utils";
import { ShimmerLabel } from "./surfaces";

export function ImageGeneration({
  prompt,
  generating,
  src,
  alt,
  label,
  downloadLabel,
  viewLabel,
  closeLabel,
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
  viewLabel?: string;
  closeLabel?: string;
  failed?: boolean;
}) {
  const showImage = !!src && !generating && !failed;
  const description = alt ?? prompt ?? label;
  const [revealed, setRevealed] = useState(false);
  const [dimensions, setDimensions] = useState<string | null>(null);
  const imageRef = useRef<HTMLImageElement>(null);

  // Cached images (e.g. reopening a saved conversation) can finish loading before the
  // load handler attaches, so settle the reveal immediately when the element is complete.
  useEffect(() => {
    const image = imageRef.current;
    if (image?.complete && image.naturalWidth) {
      setRevealed(true);
      setDimensions(`${image.naturalWidth} × ${image.naturalHeight}`);
    }
  }, [src]);

  return (
    <div
      data-slot="image-generation"
      className={cn("flex w-full max-w-sm flex-col gap-2", className)}
      {...props}
    >
      <div
        data-slot="image-generation-frame"
        // `isolate` keeps the inner z-10 image inside its own stacking context so it
        // never paints over the sticky composer while the conversation scrolls.
        className="relative isolate aspect-square w-full overflow-hidden rounded-xl border border-border/60"
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
          <Dialog.Root>
            <Dialog.Trigger asChild>
              <button
                type="button"
                aria-label={viewLabel ?? description}
                className="group relative z-10 block size-full cursor-zoom-in outline-none"
              >
                <img
                  ref={imageRef}
                  src={src}
                  alt={description}
                  onLoad={(event) => {
                    setRevealed(true);
                    setDimensions(
                      `${event.currentTarget.naturalWidth} × ${event.currentTarget.naturalHeight}`,
                    );
                  }}
                  data-revealed={revealed}
                  className={cn(
                    "size-full object-contain transition-[clip-path,filter,transform,opacity] duration-[1100ms] ease-out motion-reduce:!transition-none",
                    revealed
                      ? "scale-100 opacity-100 blur-0 [clip-path:inset(0%_0_0_0)]"
                      : "scale-[1.03] opacity-0 blur-md [clip-path:inset(0_0_100%_0)]",
                  )}
                />
                {/* Hover affordance for the "click to view" interaction. */}
                <span
                  aria-hidden="true"
                  className="absolute inset-0 bg-black/0 transition-colors duration-200 group-hover:bg-black/10 group-focus-visible:bg-black/10"
                />
              </button>
            </Dialog.Trigger>
            <Dialog.Portal>
              <Dialog.Overlay className="fixed inset-0 z-50 bg-surface-scrim/90 backdrop-blur-sm data-[state=closed]:animate-out data-[state=open]:animate-in data-[state=closed]:fade-out data-[state=open]:fade-in motion-reduce:animate-none" />
              <Dialog.Content
                aria-label={viewLabel ?? description}
                className="fixed inset-0 z-50 flex items-center justify-center p-4 outline-none data-[state=closed]:animate-out data-[state=open]:animate-in data-[state=closed]:zoom-out-95 data-[state=open]:zoom-in-95 motion-reduce:animate-none sm:p-8"
              >
                <Dialog.Title className="sr-only">{viewLabel ?? description}</Dialog.Title>
                <img
                  src={src}
                  alt={description}
                  className="max-h-[90dvh] max-w-full rounded-lg object-contain shadow-2xl"
                />
                <div className="fixed top-3 right-3 flex items-center gap-2 sm:top-4 sm:right-4">
                  {src && (
                    <a
                      href={src}
                      download
                      aria-label={downloadLabel}
                      className="flex size-9 items-center justify-center rounded-full bg-white/10 text-white outline-none backdrop-blur transition-colors hover:bg-white/20 focus-visible:ring-2 focus-visible:ring-white/50"
                    >
                      <DownloadIcon className="size-4" aria-hidden="true" />
                    </a>
                  )}
                  <Dialog.Close
                    aria-label={closeLabel}
                    className="flex size-9 items-center justify-center rounded-full bg-white/10 text-white outline-none backdrop-blur transition-colors hover:bg-white/20 focus-visible:ring-2 focus-visible:ring-white/50"
                  >
                    <XIcon className="size-4" aria-hidden="true" />
                  </Dialog.Close>
                </div>
              </Dialog.Content>
            </Dialog.Portal>
          </Dialog.Root>
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
        {/* Size badge in the corner, echoing the assistant-ui reference; real dimensions from the loaded image. */}
        {showImage && dimensions && (
          <span
            aria-hidden="true"
            className="pointer-events-none absolute top-2 right-2 z-20 rounded-md bg-black/45 px-1.5 py-0.5 font-mono text-[10px] leading-none text-white/85 backdrop-blur-sm"
          >
            {dimensions}
          </span>
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
          ) : failed ? (
            label
          ) : (
            prompt || label
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
