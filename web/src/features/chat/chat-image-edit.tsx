import { useEffect, useId, useRef, useState, type PointerEvent } from "react";
import { Dialog } from "radix-ui";
import { EraserIcon } from "lucide-react";
import { useTranslation } from "react-i18next";
import { Button } from "@/components/ui/button";
import { inputVariants } from "@/components/ui/input";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { cn } from "@/lib/utils";
import {
  drawStrokes,
  maskFileName,
  renderMask,
  toNatural,
  type MaskStroke,
} from "./chat-image-mask";

// Strokes are painted opaque and the canvas is shown translucent, so overlaps stay even.
const PAINT = "#3b82f6";

/**
 * Paint the area to change (optional) and describe the edit. The instruction and a mask named after
 * the image go to the composer; the server keeps every unpainted pixel.
 */
export function ChatImageEditDialog({
  imageId,
  src,
  onOpenChange,
  onSubmit,
}: {
  imageId: string;
  src: string;
  onOpenChange: (open: boolean) => void;
  onSubmit: (edit: { instruction: string; mask: File }) => void | Promise<void>;
}) {
  const ui = useAppTranslation();
  const { t } = useTranslation("common");
  const instructionId = useId();
  const brushId = useId();
  const image = useRef<HTMLImageElement>(null);
  const canvas = useRef<HTMLCanvasElement>(null);
  const drawing = useRef<MaskStroke | null>(null);
  const [natural, setNatural] = useState<{ width: number; height: number }>();
  const [strokes, setStrokes] = useState<MaskStroke[]>([]);
  const [brush, setBrush] = useState(6);
  const [instruction, setInstruction] = useState("");
  const [pending, setPending] = useState(false);
  const [failed, setFailed] = useState(false);

  // A cached image can finish loading before the load handler attaches.
  useEffect(() => {
    const element = image.current;
    if (element?.complete && element.naturalWidth > 0)
      setNatural({ width: element.naturalWidth, height: element.naturalHeight });
  }, [src]);

  // Redraw the committed strokes; the stroke in progress is drawn by the pointer handlers.
  useEffect(() => {
    const context = canvas.current?.getContext("2d");
    if (!context || !natural) return;
    context.clearRect(0, 0, natural.width, natural.height);
    drawStrokes(context, strokes, PAINT);
  }, [strokes, natural]);

  function point(event: PointerEvent<HTMLCanvasElement>, size: { width: number; height: number }) {
    return toNatural(event, event.currentTarget.getBoundingClientRect(), size);
  }

  function finishStroke() {
    const stroke = drawing.current;
    drawing.current = null;
    if (stroke) setStrokes((current) => [...current, stroke]);
  }

  async function submit() {
    const text = instruction.trim();
    if (!natural || !text || pending) return;
    setPending(true);
    setFailed(false);
    try {
      const blob = await renderMask(strokes, natural.width, natural.height);
      await onSubmit({
        instruction: text,
        mask: new File([blob], maskFileName(imageId), { type: "image/png" }),
      });
      onOpenChange(false);
    } catch {
      setFailed(true);
    } finally {
      setPending(false);
    }
  }

  return (
    <Dialog.Root
      open
      onOpenChange={(open) => {
        if (!pending) onOpenChange(open);
      }}
    >
      <Dialog.Portal>
        <Dialog.Overlay className="fixed inset-0 z-40 bg-surface-scrim backdrop-blur-[2px] data-[state=closed]:animate-out data-[state=open]:animate-in data-[state=closed]:fade-out data-[state=open]:fade-in motion-reduce:animate-none" />
        <Dialog.Content className="fixed top-1/2 left-1/2 z-50 max-h-[calc(100dvh-2rem)] w-[min(40rem,calc(100vw-2rem))] -translate-x-1/2 -translate-y-1/2 overflow-y-auto rounded-2xl border border-border-subtle bg-surface-overlay p-6 shadow-md outline-none">
          <Dialog.Title className="font-heading-h3 text-content-primary">
            {ui("Sửa ảnh")}
          </Dialog.Title>
          <Dialog.Description className="mt-2 font-main-ui-body text-content-secondary">
            {ui("Tô lên vùng muốn thay đổi. Không tô thì sửa toàn bộ ảnh.")}
          </Dialog.Description>

          <div className="relative mx-auto mt-4 w-fit max-w-full">
            <img
              ref={image}
              src={src}
              alt={ui("Ảnh đã tạo")}
              onLoad={(event) =>
                setNatural({
                  width: event.currentTarget.naturalWidth,
                  height: event.currentTarget.naturalHeight,
                })
              }
              className="block max-h-[55dvh] max-w-full rounded-lg object-contain"
            />
            {natural && (
              <canvas
                ref={canvas}
                width={natural.width}
                height={natural.height}
                aria-label={ui("Tô vùng cần sửa")}
                className="absolute inset-0 size-full cursor-crosshair touch-none rounded-lg opacity-50"
                onPointerDown={(event) => {
                  if (pending) return;
                  event.currentTarget.setPointerCapture(event.pointerId);
                  const stroke = {
                    size: (natural.width * brush) / 100,
                    points: [point(event, natural)],
                  };
                  drawing.current = stroke;
                  const context = canvas.current?.getContext("2d");
                  if (context) drawStrokes(context, [stroke], PAINT);
                }}
                onPointerMove={(event) => {
                  const stroke = drawing.current;
                  const last = stroke?.points.at(-1);
                  if (!stroke || !last) return;
                  const next = point(event, natural);
                  stroke.points.push(next);
                  const context = canvas.current?.getContext("2d");
                  if (context)
                    drawStrokes(context, [{ size: stroke.size, points: [last, next] }], PAINT);
                }}
                onPointerUp={finishStroke}
                onPointerCancel={finishStroke}
              />
            )}
          </div>

          <div className="mt-3 flex flex-wrap items-center justify-between gap-3">
            <div className="flex items-center gap-3">
              <label htmlFor={brushId} className="font-secondary-body text-content-secondary">
                {ui("Cỡ cọ")}
              </label>
              <input
                id={brushId}
                type="range"
                min={2}
                max={20}
                value={brush}
                disabled={pending}
                onChange={(event) => setBrush(Number(event.target.value))}
                className="w-32 accent-blue-500"
              />
            </div>
            <Button
              type="button"
              size="sm"
              prominence="secondary"
              disabled={strokes.length === 0 || pending}
              onClick={() => setStrokes([])}
            >
              <EraserIcon aria-hidden="true" />
              {ui("Xoá vùng tô")}
            </Button>
          </div>

          <label
            htmlFor={instructionId}
            className="mt-4 block font-main-ui-action text-content-primary"
          >
            {ui("Mô tả thay đổi")}
          </label>
          <textarea
            id={instructionId}
            value={instruction}
            rows={3}
            maxLength={4000}
            disabled={pending}
            placeholder={ui("Ví dụ: đổi áo sang màu đỏ, giữ nguyên mọi thứ khác")}
            onChange={(event) => setInstruction(event.target.value)}
            className={cn(inputVariants(), "mt-1.5 h-auto resize-none py-2")}
          />

          {failed && (
            <p
              role="alert"
              className="mt-4 rounded-lg bg-status-danger-surface px-4 py-3 font-secondary-body text-status-danger-content"
            >
              {ui("Không tạo được vùng tô. Hãy thử lại.")}
            </p>
          )}

          <div className="mt-6 flex flex-col-reverse gap-2 sm:flex-row sm:justify-end">
            <Dialog.Close asChild>
              <Button type="button" prominence="secondary" disabled={pending}>
                {t("cancel")}
              </Button>
            </Dialog.Close>
            <Button
              type="button"
              pending={pending}
              disabled={!natural || !instruction.trim()}
              onClick={() => void submit()}
            >
              {ui("Đưa vào khung chat")}
            </Button>
          </div>
        </Dialog.Content>
      </Dialog.Portal>
    </Dialog.Root>
  );
}
