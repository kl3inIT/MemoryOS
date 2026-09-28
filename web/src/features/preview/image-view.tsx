import { Crop, RotateCcw, RotateCw, ZoomIn, ZoomOut } from "lucide-react";
import { useEffect, useRef, useState } from "react";
import { IconButton } from "@/components/ui/icon-button";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { cn } from "@/lib/utils";
import { useObjectUrl } from "./use-object-url";

/** Zoomed images are dragged rather than scrolled, as an image viewer does; at 100% there is nothing to pan. */
export function ImageView({
  blob,
  alt,
  zoom,
  rotation,
  onZoom,
}: {
  blob: Blob;
  alt: string;
  zoom: number;
  rotation: number;
  onZoom?: (deltaY: number) => void;
}) {
  const frame = useRef<HTMLDivElement>(null);
  const src = useObjectUrl(blob);
  const [pan, setPan] = useState({ x: 0, y: 0 });
  const [dragging, setDragging] = useState(false);
  const from = useRef<{ x: number; y: number } | null>(null);
  const pannable = zoom > 100;
  // At 100% there is nothing to pan, so the offset is derived away rather than reset in an effect.
  const offset = pannable ? pan : { x: 0, y: 0 };
  useEffect(() => {
    const node = frame.current;
    if (!node || !onZoom) return;
    const onWheel = (event: WheelEvent) => {
      event.preventDefault();
      onZoom(event.deltaY);
    };
    node.addEventListener("wheel", onWheel, { passive: false });
    return () => node.removeEventListener("wheel", onWheel);
  }, [onZoom]);
  return (
    <div
      ref={frame}
      className={cn(
        "flex min-h-0 flex-1 items-center justify-center overflow-hidden p-4",
        pannable && (dragging ? "cursor-grabbing" : "cursor-grab"),
      )}
      onPointerDown={(event) => {
        if (!pannable) return;
        from.current = { x: event.clientX - offset.x, y: event.clientY - offset.y };
        setDragging(true);
        event.currentTarget.setPointerCapture(event.pointerId);
      }}
      onPointerMove={(event) => {
        if (!from.current) return;
        setPan({ x: event.clientX - from.current.x, y: event.clientY - from.current.y });
      }}
      onPointerUp={() => {
        from.current = null;
        setDragging(false);
      }}
      onPointerCancel={() => {
        from.current = null;
        setDragging(false);
      }}
    >
      {src && (
        <img
          src={src}
          alt={alt}
          draggable={false}
          className="max-h-full max-w-full object-contain transition-transform duration-300 ease-in-out"
          style={{
            transform: `translate(${offset.x}px, ${offset.y}px) scale(${zoom / 100}) rotate(${rotation}deg)`,
          }}
        />
      )}
    </div>
  );
}

export function ImageControls({
  zoom,
  cropping = false,
  crop,
  minZoom = 25,
  maxZoom = 200,
  zoomStep = 25,
  bare = false,
  onZoom,
  onRotate,
  onCrop,
}: {
  zoom: number;
  cropping?: boolean;
  crop?: { selected: boolean; width?: number; height?: number };
  minZoom?: number;
  maxZoom?: number;
  zoomStep?: number;
  bare?: boolean;
  onZoom: (zoom: number) => void;
  onRotate: (degrees: number) => void;
  onCrop?: () => void;
}) {
  const ui = useAppTranslation();
  return (
    <div
      className={cn(
        "flex items-center gap-1",
        !bare && "rounded-xl border border-border-subtle bg-surface-base p-1 shadow-lg",
      )}
    >
      {cropping ? (
        <span className="px-2 font-secondary-body tabular-nums">
          {!crop?.selected
            ? ui("Chưa chọn vùng")
            : crop.width && crop.height
              ? ui("{{width}} × {{height}} px", { width: crop.width, height: crop.height })
              : ui("Đã chọn vùng")}
        </span>
      ) : (
        <>
          <IconButton
            prominence="internal"
            size="sm"
            aria-label={ui("Thu nhỏ")}
            disabled={zoom <= minZoom}
            onClick={() => onZoom(Math.max(zoom - zoomStep, minZoom))}
          >
            <ZoomOut />
          </IconButton>
          <span className="w-12 text-center font-secondary-body tabular-nums">{zoom}%</span>
          <IconButton
            prominence="internal"
            size="sm"
            aria-label={ui("Phóng to")}
            disabled={zoom >= maxZoom}
            onClick={() => onZoom(Math.min(zoom + zoomStep, maxZoom))}
          >
            <ZoomIn />
          </IconButton>
          {onCrop ? (
            <>
              <IconButton
                prominence="internal"
                size="sm"
                aria-label={ui("Xoay trái")}
                onClick={() => onRotate(-90)}
              >
                <RotateCcw />
              </IconButton>
              <IconButton
                prominence="internal"
                size="sm"
                aria-label={ui("Xoay phải")}
                onClick={() => onRotate(90)}
              >
                <RotateCw />
              </IconButton>
            </>
          ) : (
            <IconButton
              prominence="internal"
              size="sm"
              aria-label={ui("Xoay phải")}
              onClick={() => onRotate(90)}
            >
              <RotateCw />
            </IconButton>
          )}
        </>
      )}
      {onCrop && (
        <IconButton
          prominence="internal"
          size="sm"
          aria-pressed={cropping}
          aria-label={cropping ? ui("Thoát cắt ảnh") : ui("Cắt ảnh")}
          onClick={onCrop}
        >
          <Crop />
        </IconButton>
      )}
    </div>
  );
}
