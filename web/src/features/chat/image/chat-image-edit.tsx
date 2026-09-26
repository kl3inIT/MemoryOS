import { useEffect, useId, useRef, useState, type PointerEvent } from "react";
import { cssToken } from "@/lib/css-token";
import { EraserIcon } from "lucide-react";
import { useTranslation } from "react-i18next";
import { Button } from "@/components/ui/button";
import { Alert, AlertTitle } from "@/components/ui/alert";
import {
  Dialog,
  DialogClose,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { Field, FieldLabel } from "@/components/ui/field";
import { Textarea } from "@/components/ui/textarea";
import { useAppTranslation } from "@/i18n/use-app-translation";
import {
  drawStrokes,
  maskFileName,
  renderMask,
  toNatural,
  type MaskStroke,
} from "./chat-image-mask";

// Strokes are painted opaque and the canvas is shown translucent, so overlaps stay even. The mask colour is the
// selection accent, read from the theme because a canvas needs a literal colour.
const paint = () => cssToken("--action-selection", "#286df8");

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
    drawStrokes(context, strokes, paint());
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
    <Dialog
      open
      onOpenChange={(open) => {
        if (!pending) onOpenChange(open);
      }}
    >
      <DialogContent showCloseButton={false} className="sm:max-w-2xl">
        <DialogHeader>
          <DialogTitle>{ui("Sửa ảnh")}</DialogTitle>
          <DialogDescription>
            {ui("Tô lên vùng muốn thay đổi. Không tô thì sửa toàn bộ ảnh.")}
          </DialogDescription>
        </DialogHeader>

        <div className="flex flex-col gap-3">
          <div className="relative mx-auto w-fit max-w-full">
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
              className="block max-h-96 max-w-full rounded-lg object-contain"
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
                  if (context) drawStrokes(context, [stroke], paint());
                }}
                onPointerMove={(event) => {
                  const stroke = drawing.current;
                  const last = stroke?.points.at(-1);
                  if (!stroke || !last) return;
                  const next = point(event, natural);
                  stroke.points.push(next);
                  const context = canvas.current?.getContext("2d");
                  if (context)
                    drawStrokes(context, [{ size: stroke.size, points: [last, next] }], paint());
                }}
                onPointerUp={finishStroke}
                onPointerCancel={finishStroke}
              />
            )}
          </div>

          <div className="flex flex-wrap items-center justify-between gap-3">
            <Field orientation="horizontal" className="w-auto">
              <FieldLabel htmlFor={brushId}>{ui("Cỡ cọ")}</FieldLabel>
              <input
                id={brushId}
                type="range"
                min={2}
                max={20}
                value={brush}
                disabled={pending}
                onChange={(event) => setBrush(Number(event.target.value))}
                className="w-32 accent-action-selection"
              />
            </Field>
            <Button
              type="button"
              size="sm"
              prominence="secondary"
              disabled={strokes.length === 0 || pending}
              onClick={() => setStrokes([])}
            >
              <EraserIcon data-icon="inline-start" aria-hidden="true" />
              {ui("Xoá vùng tô")}
            </Button>
          </div>
        </div>

        <Field>
          <FieldLabel htmlFor={instructionId}>{ui("Mô tả thay đổi")}</FieldLabel>
          <Textarea
            id={instructionId}
            value={instruction}
            rows={3}
            maxLength={4000}
            disabled={pending}
            placeholder={ui("Ví dụ: đổi áo sang màu đỏ, giữ nguyên mọi thứ khác")}
            onChange={(event) => setInstruction(event.target.value)}
            className="resize-none"
          />
        </Field>

        {failed && (
          <Alert variant="destructive">
            <AlertTitle>{ui("Không tạo được vùng tô. Hãy thử lại.")}</AlertTitle>
          </Alert>
        )}

        <DialogFooter>
          <DialogClose asChild>
            <Button type="button" prominence="secondary" disabled={pending}>
              {t("cancel")}
            </Button>
          </DialogClose>
          <Button
            type="button"
            pending={pending}
            disabled={!natural || !instruction.trim()}
            onClick={() => void submit()}
          >
            {ui("Đưa vào khung chat")}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
