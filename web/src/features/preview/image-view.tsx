import { Crop, RotateCcw, RotateCw, ZoomIn, ZoomOut } from "lucide-react";
import { useEffect, useRef, useState } from "react";
import { IconButton } from "@/components/ui/icon-button";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { cn } from "@/lib/utils";
import { frameBoxRect, type PdfHighlight } from "./pdf-pages";
import { useObjectUrl } from "./use-object-url";

type Layout = { left: number; top: number; width: number; height: number };

const NO_BOXES: readonly PdfHighlight[] = [];

/**
 * Zoomed images are dragged rather than scrolled, as an image viewer does; at 100% there is nothing to pan.
 *
 * A cited region is drawn over the picture from the box the extraction recorded, in a layer that takes the
 * picture's own place and transform, so it follows zoom, pan and the reader's turn. The box is in pixels of
 * the picture the OCR read; it is drawn only on a picture of exactly that size, because a picture decoded
 * to another size or orientation is not known to be the one the box was measured in.
 */
export function ImageView({
  blob,
  alt,
  zoom,
  rotation,
  onZoom,
  boxes = NO_BOXES,
  onMarked,
}: {
  blob: Blob;
  alt: string;
  zoom: number;
  rotation: number;
  onZoom?: (deltaY: number) => void;
  /** The recorded regions of the citation being read; only those on the picture's one page are drawn. */
  boxes?: readonly PdfHighlight[];
  /** Whether the regions were drawn, once the picture has loaded; not called when there are none. */
  onMarked?: (marked: boolean) => void;
}) {
  const ui = useAppTranslation();
  const frame = useRef<HTMLDivElement>(null);
  const picture = useRef<HTMLImageElement>(null);
  const src = useObjectUrl(blob);
  const [pan, setPan] = useState({ x: 0, y: 0 });
  const [dragging, setDragging] = useState(false);
  const from = useRef<{ x: number; y: number } | null>(null);
  // The picture's decoded size and the place its untransformed box takes in the frame.
  const [loaded, setLoaded] = useState<{ src: string; width: number; height: number }>();
  const [layout, setLayout] = useState<Layout>();
  const pannable = zoom > 100;
  // At 100% there is nothing to pan, so the offset is derived away rather than reset in an effect.
  const offset = pannable ? pan : { x: 0, y: 0 };
  const transform = `translate(${offset.x}px, ${offset.y}px) scale(${zoom / 100}) rotate(${rotation}deg)`;
  const natural = loaded && loaded.src === src ? loaded : undefined;
  const cited = boxes.filter((box) => box.page === 1 && box.frame);
  const regions = natural
    ? cited
        .filter((box) => box.frame?.width === natural.width && box.frame.height === natural.height)
        .map(frameBoxRect)
        .filter((rect) => rect !== undefined)
    : [];
  const marked = natural && cited.length ? regions.length > 0 : undefined;
  const report = useRef(onMarked);
  useEffect(() => {
    report.current = onMarked;
  }, [onMarked]);
  useEffect(() => {
    if (marked !== undefined) report.current?.(marked);
  }, [marked]);
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
  useEffect(() => {
    const image = picture.current;
    const node = frame.current;
    if (!image || !node || !natural) return undefined;
    const measure = () =>
      setLayout((known) => {
        const next = {
          left: image.offsetLeft,
          top: image.offsetTop,
          width: image.offsetWidth,
          height: image.offsetHeight,
        };
        return known &&
          known.left === next.left &&
          known.top === next.top &&
          known.width === next.width &&
          known.height === next.height
          ? known
          : next;
      });
    const observer = new ResizeObserver(measure);
    observer.observe(image);
    observer.observe(node);
    measure();
    return () => observer.disconnect();
  }, [natural]);
  return (
    <div
      ref={frame}
      className={cn(
        "relative flex min-h-0 flex-1 items-center justify-center overflow-hidden p-4",
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
          ref={picture}
          src={src}
          alt={alt}
          draggable={false}
          className="max-h-full max-w-full object-contain transition-transform duration-300 ease-in-out"
          style={{ transform }}
          onLoad={(event) =>
            setLoaded({
              src,
              width: event.currentTarget.naturalWidth,
              height: event.currentTarget.naturalHeight,
            })
          }
        />
      )}
      {/* One multiplying layer for every region, as on a PDF page: overlapping regions stay one yellow. */}
      {regions.length && layout ? (
        <div
          data-slot="image-citation-layer"
          className="pointer-events-none absolute opacity-55 mix-blend-multiply transition-transform duration-300 ease-in-out"
          style={{ ...layout, transform }}
        >
          {regions.map((rect, index) => (
            <span
              key={`${rect.left}:${rect.top}:${index}`}
              role="img"
              aria-label={ui("Vùng được trích dẫn trên ảnh")}
              data-slot="image-citation-box"
              className="absolute rounded-sm bg-pdf-highlight ring-1 ring-pdf-highlight-border/80"
              style={{
                left: `${rect.left * 100}%`,
                top: `${rect.top * 100}%`,
                width: `${rect.width * 100}%`,
                height: `${rect.height * 100}%`,
              }}
            />
          ))}
        </div>
      ) : null}
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
