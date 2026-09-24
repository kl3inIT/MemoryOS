import { useRef, useState, type PointerEvent as ReactPointerEvent } from "react";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { cn } from "@/lib/utils";
import { MIN_CROP, rectBetween, type CropRect } from "./image-crop";

/**
 * Selecting part of an image by dragging over it, the way an image editor shows a crop: everything outside
 * the selection is dimmed and the image itself is never altered until the crop is saved.
 */
export function ImageCropper({
  src,
  alt,
  rect,
  onRect,
  onNatural,
}: {
  src: string;
  alt: string;
  rect?: CropRect;
  onRect: (rect: CropRect | undefined) => void;
  /** The image's own pixel size, so the crop can be reported in pixels. */
  onNatural: (size: { width: number; height: number }) => void;
}) {
  const ui = useAppTranslation();
  const frame = useRef<HTMLDivElement>(null);
  const from = useRef<{ clientX: number; clientY: number } | null>(null);
  const [size, setSize] = useState<{ width: number; height: number }>();

  function drawTo(event: ReactPointerEvent<HTMLDivElement>) {
    if (!from.current || !frame.current) return;
    onRect(rectBetween(from.current, event, frame.current.getBoundingClientRect()));
  }

  return (
    <div className="flex min-h-0 flex-1 items-center justify-center overflow-hidden p-4">
      {/*
       * The frame is exactly the picture, because the selection is read as a fraction of it. Its height is
       * the viewer's and its shape the image's own, so `max-h-full` on the picture has a height to resolve
       * against: without that the browser draws it at natural size and the viewer cuts off its bottom.
       */}
      <div
        ref={frame}
        style={size && { aspectRatio: `${size.width} / ${size.height}` }}
        className={cn(
          "relative touch-none overflow-hidden select-none",
          size ? "h-full max-h-full max-w-full" : "max-h-full max-w-full",
        )}
        onPointerDown={(event) => {
          from.current = { clientX: event.clientX, clientY: event.clientY };
          // jsdom has no pointer capture; the drag still works without it.
          event.currentTarget.setPointerCapture?.(event.pointerId);
          onRect(undefined);
        }}
        onPointerMove={drawTo}
        onPointerUp={(event) => {
          drawTo(event);
          from.current = null;
          // A click rather than a drag selects nothing, so the whole image stays the subject.
          if (rect && rect.width < MIN_CROP && rect.height < MIN_CROP) onRect(undefined);
        }}
        onPointerCancel={() => {
          from.current = null;
          onRect(undefined);
        }}
      >
        <img
          src={src}
          alt={alt}
          draggable={false}
          className={cn(
            "block cursor-crosshair object-contain",
            size ? "size-full" : "max-h-full max-w-full",
          )}
          onLoad={(event) => {
            const natural = {
              width: event.currentTarget.naturalWidth,
              height: event.currentTarget.naturalHeight,
            };
            setSize(natural);
            onNatural(natural);
          }}
        />
        {rect ? (
          // One ring dims everything around the selection, so the crop reads at a glance.
          <div
            aria-hidden
            className="pointer-events-none absolute border border-border-on-media shadow-[0_0_0_9999px_rgb(0_0_0/0.55)]"
            style={{
              left: `${rect.x * 100}%`,
              top: `${rect.y * 100}%`,
              width: `${rect.width * 100}%`,
              height: `${rect.height * 100}%`,
            }}
          />
        ) : (
          <p className="pointer-events-none absolute inset-x-0 bottom-3 text-center text-xs text-content-on-media">
            <span className="rounded-lg bg-surface-scrim px-2 py-1">
              {ui("Kéo trên ảnh để chọn vùng cắt")}
            </span>
          </p>
        )}
      </div>
    </div>
  );
}
