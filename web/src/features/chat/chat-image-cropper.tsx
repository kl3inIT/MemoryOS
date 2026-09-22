import { useRef, type PointerEvent as ReactPointerEvent } from "react";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { MIN_CROP, rectBetween, type CropRect } from "./chat-image-crop";

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

  function drawTo(event: ReactPointerEvent<HTMLDivElement>) {
    if (!from.current || !frame.current) return;
    onRect(rectBetween(from.current, event, frame.current.getBoundingClientRect()));
  }

  return (
    <div className="flex min-h-0 flex-1 items-center justify-center overflow-hidden p-4">
      <div
        ref={frame}
        className="relative max-h-full touch-none overflow-hidden select-none"
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
          className="block max-h-full max-w-full cursor-crosshair object-contain"
          onLoad={(event) =>
            onNatural({
              width: event.currentTarget.naturalWidth,
              height: event.currentTarget.naturalHeight,
            })
          }
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
