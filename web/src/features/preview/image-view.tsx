import { RotateCw, ZoomIn, ZoomOut } from "lucide-react";
import { useRef, useState } from "react";
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
}: {
  blob: Blob;
  alt: string;
  zoom: number;
  rotation: number;
}) {
  const src = useObjectUrl(blob);
  const [pan, setPan] = useState({ x: 0, y: 0 });
  const [dragging, setDragging] = useState(false);
  const from = useRef<{ x: number; y: number } | null>(null);
  const pannable = zoom > 100;
  // At 100% there is nothing to pan, so the offset is derived away rather than reset in an effect.
  const offset = pannable ? pan : { x: 0, y: 0 };
  return (
    <div
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
  onZoom,
  onRotate,
}: {
  zoom: number;
  onZoom: (zoom: number) => void;
  onRotate: () => void;
}) {
  const ui = useAppTranslation();
  return (
    <div className="flex items-center gap-1 rounded-xl border border-border-subtle bg-surface-base p-1 shadow-lg">
      <IconButton
        prominence="internal"
        size="sm"
        aria-label={ui("Thu nhỏ")}
        disabled={zoom <= 25}
        onClick={() => onZoom(Math.max(zoom - 25, 25))}
      >
        <ZoomOut />
      </IconButton>
      <span className="w-12 text-center font-mono text-xs tabular-nums">{zoom}%</span>
      <IconButton
        prominence="internal"
        size="sm"
        aria-label={ui("Phóng to")}
        disabled={zoom >= 200}
        onClick={() => onZoom(Math.min(zoom + 25, 200))}
      >
        <ZoomIn />
      </IconButton>
      <IconButton prominence="internal" size="sm" aria-label={ui("Xoay ảnh")} onClick={onRotate}>
        <RotateCw />
      </IconButton>
    </div>
  );
}
