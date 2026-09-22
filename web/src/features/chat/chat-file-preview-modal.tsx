import { useQuery } from "@tanstack/react-query";
import {
  Check,
  ChevronLeft,
  ChevronRight,
  Copy,
  Crop,
  Download,
  Loader2,
  MessageSquare,
  RotateCw,
  X,
  ZoomIn,
  ZoomOut,
} from "lucide-react";
import { Dialog } from "radix-ui";
import { useEffect, useRef, useState, type ReactNode } from "react";
import { z } from "zod";
import { HighlightedCode } from "@/components/assistant-ui/elements/code-renderers.aui";
import { Button } from "@/components/ui/button";
import { IconButton } from "@/components/ui/icon-button";
import { Input } from "@/components/ui/input";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { useApplicationSession } from "@/features/identity/application-session-context";
import { DocumentPdfView } from "@/features/search/document-pdf-view";
import { uiLocale } from "@/i18n/format";
import { useAppTranslation } from "@/i18n/use-app-translation";
import {
  downloadChatFile,
  getChatFileArtifact,
  getChatImageArtifact,
  getChatFileArtifactPdfPreview,
  previewChatFileArtifactSpreadsheet,
  previewChatFileSpreadsheet,
} from "@/lib/hey-api/sdk.gen";
import { cn } from "@/lib/utils";
import { fileSize } from "./chat-code";
import {
  codeLanguage,
  downloadUrl,
  lineCount,
  MAX_TEXT_PREVIEW_BYTES,
  parseCsv,
  previewKind,
  previewSize,
  sanitizeDocxHtml,
  type PreviewKind,
  type PreviewTarget,
} from "./chat-file-preview";
import { ImageCropper } from "./chat-image-cropper";
import {
  croppedFileName,
  cropPixels,
  cropType,
  renderCrop,
  type CropRect,
} from "./chat-image-crop";

const spreadsheetSchema = z.object({
  sheets: z.array(z.object({ name: z.string(), csv: z.string(), truncated: z.boolean() })),
});
type Sheets = z.infer<typeof spreadsheetSchema>["sheets"];

/** Rows past this count are not rendered; the download holds the whole file. */
const MAX_TABLE_ROWS = 1000;

async function readBlob(target: PreviewTarget, signal: AbortSignal): Promise<Blob> {
  const { data } =
    target.source === "generated"
      ? await getChatFileArtifact({
          path: { artifactId: target.id },
          parseAs: "blob",
          signal,
          throwOnError: true,
        })
      : target.source === "image"
        ? await getChatImageArtifact({
            path: { artifactId: target.id },
            parseAs: "blob",
            signal,
            throwOnError: true,
          })
        : await downloadChatFile({
            path: { fileId: target.id },
            parseAs: "blob",
            signal,
            throwOnError: true,
          });
  if (!(data instanceof Blob)) throw new Error("Invalid file content");
  return data;
}

async function readSheets(target: PreviewTarget, signal: AbortSignal): Promise<Sheets> {
  const { data } =
    target.source === "generated"
      ? await previewChatFileArtifactSpreadsheet({
          path: { artifactId: target.id },
          signal,
          throwOnError: true,
        })
      : await previewChatFileSpreadsheet({
          path: { fileId: target.id },
          signal,
          throwOnError: true,
        });
  return spreadsheetSchema.parse(data).sheets;
}

type Loaded =
  | { kind: "xlsx"; sheets: Sheets }
  | { kind: "text" | "code" | "markdown" | "csv"; text: string; truncated: boolean; bytes: number }
  | { kind: "image" | "pdf" | "docx"; blob: Blob; converted?: boolean }
  | { kind: "doc" | "unsupported" };

/**
 * Onyx PreviewModal fetch: resolve the variant by name, then by the stored type the response reports, so a
 * workbook is read through the parsed spreadsheet route and never as bytes.
 */
async function load(target: PreviewTarget, signal: AbortSignal): Promise<Loaded> {
  const known = previewKind(target.filename, target.mediaType ?? "application/octet-stream");
  if (known === "xlsx") return { kind: "xlsx", sheets: await readSheets(target, signal) };
  if (known === "pptx") {
    // Onyx Craft converts decks with LibreOffice in its sandbox; the API converts in the interpreter executor
    // and caches the PDF, which the pdf.js reader shows. Attachments are not converted.
    if (target.source !== "generated") return { kind: "unsupported" };
    const { data } = await getChatFileArtifactPdfPreview({
      path: { artifactId: target.id },
      parseAs: "blob",
      signal,
      throwOnError: true,
    });
    if (!(data instanceof Blob)) throw new Error("Invalid preview");
    return { kind: "pdf", blob: data.slice(0, data.size, "application/pdf"), converted: true };
  }
  if (known === "doc" || (known === "unsupported" && target.mediaType)) return { kind: known };
  const blob = await readBlob(target, signal);
  const stored =
    target.mediaType ?? (blob.type && blob.type !== "application/octet-stream" ? blob.type : "");
  const kind = previewKind(target.filename, stored || "application/octet-stream");
  if (kind === "xlsx") return { kind, sheets: await readSheets(target, signal) };
  if (kind === "doc" || kind === "unsupported" || kind === "pptx")
    return { kind: kind === "pptx" ? "unsupported" : kind };
  if (kind === "image" || kind === "pdf" || kind === "docx") {
    // The attachment route serves octet-stream under nosniff; give the viewer the stored type.
    return { kind, blob: stored && blob.type !== stored ? blob.slice(0, blob.size, stored) : blob };
  }
  const truncated = blob.size > MAX_TEXT_PREVIEW_BYTES;
  return {
    kind,
    text: await blob.slice(0, MAX_TEXT_PREVIEW_BYTES).text(),
    truncated,
    bytes: blob.size,
  };
}

function useObjectUrl(blob: Blob | undefined): string | undefined {
  const [entry, setEntry] = useState<{ blob: Blob; url: string }>();
  useEffect(() => {
    if (!blob) return undefined;
    const url = URL.createObjectURL(blob);
    // The object URL is a browser resource whose lifetime is the effect's.
    // eslint-disable-next-line react-hooks/set-state-in-effect
    setEntry({ blob, url });
    return () => URL.revokeObjectURL(url);
  }, [blob]);
  return entry && entry.blob === blob ? entry.url : undefined;
}

const SIZES = {
  full: "sm:h-[calc(100dvh-3rem)] sm:w-[calc(100vw-3rem)] sm:max-w-[96rem]",
  large: "sm:h-[min(48rem,calc(100dvh-3rem))] sm:w-[min(64rem,calc(100vw-3rem))]",
  tall: "sm:h-[calc(100dvh-3rem)] sm:w-[min(64rem,calc(100vw-3rem))]",
} as const;

/** Onyx PreviewModal: a centered modal sized by variant, with a header description and a floating footer. */
export function ChatFilePreviewModal({
  target: opened,
  siblings,
  onClose,
  onCloseAutoFocus,
  onSaveImage,
  onAsk,
}: {
  target: PreviewTarget;
  /** The files shown beside this one, in the order the page lists them; enables previous/next. */
  siblings?: readonly PreviewTarget[];
  onClose: () => void;
  onCloseAutoFocus?: (event: Event) => void;
  /** Keeps a cropped image where the modal was opened from; without it a crop can only be downloaded. */
  onSaveImage?: (file: File) => void | Promise<void>;
  /** Opens a conversation about this file. Surfaces inside Chat leave it out: they are already one. */
  onAsk?: (target: PreviewTarget, question: string) => Promise<void>;
}) {
  const ui = useAppTranslation();
  const { actorId, authorizationVersion } = useApplicationSession();
  const [zoom, setZoom] = useState(100);
  const [rotation, setRotation] = useState(0);
  const [shown, setShown] = useState(opened);
  const [cropping, setCropping] = useState(false);
  const [crop, setCrop] = useState<CropRect>();
  const [natural, setNatural] = useState<{ width: number; height: number }>();
  const [cropFailed, setCropFailed] = useState(false);
  const [savingCrop, setSavingCrop] = useState(false);
  const target = shown;
  const gallery = siblings ?? [];
  const at = gallery.findIndex((file) => file.source === target.source && file.id === target.id);
  const showUpright = () => {
    setZoom(100);
    setRotation(0);
    setCrop(undefined);
    setCropFailed(false);
  };
  const step = (delta: number) => {
    const next = gallery[at + delta];
    if (at < 0 || !next) return;
    setShown(next);
    setCropping(false);
    setNatural(undefined);
    showUpright();
  };
  // Arrow keys step through the gallery, as an image viewer does; the dialog keeps Escape for closing.
  useEffect(() => {
    if (at < 0) return;
    const onKey = (event: KeyboardEvent) => {
      if (event.altKey || event.ctrlKey || event.metaKey || cropping) return;
      if (event.key === "ArrowLeft") step(-1);
      else if (event.key === "ArrowRight") step(1);
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  });
  const loaded = useQuery({
    queryKey: ["chat-file-preview", actorId, authorizationVersion, target.source, target.id],
    gcTime: 0,
    staleTime: 0,
    retry: false,
    queryFn: ({ signal }) => load(target, signal),
  });
  const guessed = previewKind(target.filename, target.mediaType ?? "application/octet-stream");
  const kind: PreviewKind = loaded.data?.kind ?? guessed;
  const [docxWords, setDocxWords] = useState<{ words: number; text: string }>();
  const view = loaded.data ? describe(loaded.data, target.filename, docxWords, ui) : undefined;
  const image = loaded.data?.kind === "image" ? loaded.data.blob : undefined;
  // The cropper draws the same bytes the preview holds, so it shares the preview's object URL.
  const imageSource = useObjectUrl(image);

  /**
   * The crop is encoded from the bytes the preview already holds, so nothing is uploaded until the owner
   * keeps it: a download writes the file locally, saving hands it to whoever opened the modal.
   */
  const keepCrop = async (destination: "download" | "library") => {
    if (!crop || !image) return;
    setSavingCrop(true);
    setCropFailed(false);
    try {
      const type = cropType(target.mediaType ?? image.type);
      const bitmap = await createImageBitmap(image);
      const cropped = await renderCrop(bitmap, crop, type);
      bitmap.close();
      const filename = croppedFileName(target.filename, type);
      if (destination === "library") await onSaveImage?.(new File([cropped], filename, { type }));
      else {
        const url = URL.createObjectURL(cropped);
        const link = document.createElement("a");
        link.href = url;
        link.download = filename;
        link.click();
        // Revoking in the same task can cancel the download the click just started.
        setTimeout(() => URL.revokeObjectURL(url), 0);
      }
      setCropping(false);
      setCrop(undefined);
    } catch {
      setCropFailed(true);
    } finally {
      setSavingCrop(false);
    }
  };

  return (
    <Dialog.Root open onOpenChange={(open) => !open && onClose()}>
      <Dialog.Portal>
        <Dialog.Overlay className="fixed inset-0 z-50 bg-surface-scrim backdrop-blur-[2px]" />
        <Dialog.Content
          data-slot="file-preview-modal"
          aria-describedby={undefined}
          onOpenAutoFocus={(event) => event.preventDefault()}
          onCloseAutoFocus={onCloseAutoFocus}
          className={cn(
            "fixed inset-0 z-50 flex flex-col overflow-hidden bg-surface-base outline-none",
            "sm:inset-auto sm:top-1/2 sm:left-1/2 sm:-translate-x-1/2 sm:-translate-y-1/2 sm:rounded-2xl sm:border sm:border-border-default sm:shadow-lg",
            SIZES[previewSize(kind)],
          )}
        >
          {/*
           * The viewer's own chrome: the way out first, then where the file is and what it is called, then the
           * things done to it. Nothing sits on the file itself, so the picture is read on an empty surface.
           */}
          <header className="flex shrink-0 items-center gap-2 px-3 py-2">
            <Dialog.Close asChild>
              <IconButton prominence="internal" size="sm" aria-label={ui("Đóng xem trước")}>
                <X />
              </IconButton>
            </Dialog.Close>
            <nav
              aria-label={ui("Đường dẫn tệp")}
              className="flex min-w-0 flex-1 items-center gap-1.5 font-secondary-body"
            >
              <span className="shrink-0 text-content-muted">{ui("Thư viện")}</span>
              <span className="shrink-0 text-content-muted" aria-hidden="true">
                /
              </span>
              <Dialog.Title
                className="min-w-0 truncate text-content-primary"
                title={target.filename}
              >
                {target.filename}
              </Dialog.Title>
              {view?.description && (
                <>
                  <span className="hidden shrink-0 text-content-muted md:inline" aria-hidden="true">
                    ·
                  </span>
                  <span className="hidden shrink-0 text-content-muted md:inline">
                    {view.description}
                  </span>
                </>
              )}
            </nav>
            {at >= 0 && gallery.length > 1 && (
              <div className="flex shrink-0 items-center gap-1">
                <IconButton
                  prominence="internal"
                  size="sm"
                  aria-label={ui("Tệp trước")}
                  disabled={at === 0}
                  onClick={() => step(-1)}
                >
                  <ChevronLeft />
                </IconButton>
                <span className="font-secondary-body tabular-nums text-content-muted">
                  {ui("{{position}}/{{total}}", { position: at + 1, total: gallery.length })}
                </span>
                <IconButton
                  prominence="internal"
                  size="sm"
                  aria-label={ui("Tệp sau")}
                  disabled={at === gallery.length - 1}
                  onClick={() => step(1)}
                >
                  <ChevronRight />
                </IconButton>
              </div>
            )}
            {!cropping && (
              <div className="flex shrink-0 items-center gap-1">
                {view?.copy !== undefined && <CopyButton text={view.copy} />}
                <IconButton prominence="internal" size="sm" asChild aria-label={ui("Tải xuống")}>
                  <a href={downloadUrl(target)} download={target.filename}>
                    <Download />
                  </a>
                </IconButton>
              </div>
            )}
          </header>
          <div className="relative flex min-h-0 flex-1 flex-col bg-surface-subtle">
            {loaded.isPending ? (
              <div className="flex flex-1 items-center justify-center" role="status">
                <div className="flex flex-col items-center gap-3">
                  <Loader2 className="size-8 animate-spin text-content-muted" aria-hidden />
                  {guessed === "pptx" && target.source === "generated" ? (
                    <span className="text-sm text-content-secondary">
                      {ui("Đang tạo bản xem trước trình chiếu…")}
                    </span>
                  ) : (
                    <span className="sr-only">{ui("Đang đọc tệp…")}</span>
                  )}
                </div>
              </div>
            ) : loaded.isError ? (
              <Unavailable
                target={target}
                message={ui("Không xem trước được tệp. Bạn vẫn có thể tải tệp xuống.")}
              />
            ) : (
              <>
                <div className="flex min-h-0 flex-1 flex-col overflow-auto pt-14 pb-4">
                  {cropping && imageSource ? (
                    <ImageCropper
                      src={imageSource}
                      alt={target.filename}
                      rect={crop}
                      onRect={setCrop}
                      onNatural={setNatural}
                    />
                  ) : (
                    <Content
                      loaded={loaded.data}
                      target={target}
                      zoom={zoom}
                      rotation={rotation}
                      onDocx={setDocxWords}
                    />
                  )}
                </div>
                {/*
                 * The tools float over the file rather than under it: one pill in the middle for what is done
                 * to the picture, and what a crop or a document has to say is a quiet line beside it.
                 */}
                {(kind === "image" || view?.footer || cropping) && (
                  <div className="pointer-events-none absolute inset-x-0 top-3 flex justify-center px-3">
                    <div className="pointer-events-auto flex max-w-full flex-wrap items-center gap-1 rounded-full border border-border-subtle bg-surface-overlay px-1.5 py-1 shadow-lg">
                      {kind === "image" && (
                        <ImageControls
                          zoom={zoom}
                          cropping={cropping}
                          crop={
                            crop && { selected: true, ...(natural && cropPixels(crop, natural)) }
                          }
                          onZoom={setZoom}
                          onRotate={() => setRotation((current) => (current + 90) % 360)}
                          onCrop={() => {
                            // A crop is drawn on the image as stored: upright, unzoomed and unrotated.
                            setCropping(!cropping);
                            showUpright();
                          }}
                        />
                      )}
                      {cropping && (
                        <>
                          {cropFailed && (
                            <span
                              role="alert"
                              className="px-2 font-secondary-body text-content-danger"
                            >
                              {ui("Không cắt được ảnh.")}
                            </span>
                          )}
                          <Button
                            size="sm"
                            prominence="internal"
                            disabled={!crop || savingCrop}
                            onClick={() => void keepCrop("download")}
                          >
                            {ui("Tải ảnh đã cắt")}
                          </Button>
                          {onSaveImage && (
                            <Button
                              size="sm"
                              disabled={!crop || savingCrop}
                              onClick={() => void keepCrop("library")}
                            >
                              {savingCrop ? ui("Đang lưu…") : ui("Lưu thành tệp mới")}
                            </Button>
                          )}
                        </>
                      )}
                      {kind !== "image" && !cropping && view?.footer && (
                        <span className="px-2 font-secondary-body text-content-secondary">
                          {view.footer}
                        </span>
                      )}
                    </div>
                  </div>
                )}
              </>
            )}
          </div>
          {onAsk && !cropping && (
            <AskAboutFile name={target.filename} onAsk={(text) => onAsk(target, text)} />
          )}
        </Dialog.Content>
      </Dialog.Portal>
    </Dialog.Root>
  );
}

type Translate = ReturnType<typeof useAppTranslation>;

/** Onyx headerDescription, footer text and copy text per variant. */
function describe(
  loaded: Loaded,
  filename: string,
  docx: { words: number; text: string } | undefined,
  ui: Translate,
): { description?: string; footer?: ReactNode; copy?: string } {
  const locale = uiLocale();
  switch (loaded.kind) {
    case "code":
    case "text": {
      const lines = lineCount(loaded.text);
      const size = fileSize(loaded.bytes, locale);
      return {
        description:
          loaded.kind === "code"
            ? ui("{{size}} · {{language}} · {{lines}} dòng", {
                size,
                language: codeLanguage(filename, "code"),
                lines,
              })
            : ui("{{size}} · {{lines}} dòng", { size, lines }),
        footer: ui("{{count}} dòng", { count: lines }),
        copy: loaded.text,
      };
    }
    case "markdown":
      return { copy: loaded.text };
    case "csv": {
      const [header = [], ...rows] = parseCsv(loaded.text);
      const columns = Math.max(header.length, ...rows.map((row) => row.length));
      return {
        description: ui("{{size}} · {{rows}} dòng", {
          size: fileSize(loaded.bytes, locale),
          rows: rows.length,
        }),
        footer: ui("{{columns}} cột · {{rows}} dòng", { columns, rows: rows.length }),
        copy: loaded.text,
      };
    }
    case "xlsx":
      return {
        description: ui("{{count}} trang tính", { count: loaded.sheets.length }),
        footer: ui("{{count}} trang tính", { count: loaded.sheets.length }),
      };
    case "pdf":
      return loaded.converted ? { description: ui("Bản xem trước PDF của trình chiếu") } : {};
    case "docx":
      return docx
        ? { description: ui("{{count}} từ", { count: docx.words }), copy: docx.text }
        : { description: ui("Tài liệu Word") };
    default:
      return {};
  }
}

function Content({
  loaded,
  target,
  zoom,
  rotation,
  onDocx,
}: {
  loaded: Loaded;
  target: PreviewTarget;
  zoom: number;
  rotation: number;
  onDocx: (result: { words: number; text: string }) => void;
}) {
  const ui = useAppTranslation();
  switch (loaded.kind) {
    case "image":
      return (
        <ImagePreview blob={loaded.blob} alt={target.filename} zoom={zoom} rotation={rotation} />
      );
    case "pdf":
      return <PdfPreview blob={loaded.blob} />;
    case "xlsx":
      return <SheetsPreview sheets={loaded.sheets} />;
    case "csv":
      return (
        <div className="p-4">
          <CsvTable csv={loaded.text} truncated={loaded.truncated} />
        </div>
      );
    case "docx":
      return <DocxPreview blob={loaded.blob} onLoad={onDocx} />;
    case "code":
    case "text":
    case "markdown": {
      const language = codeLanguage(target.filename, loaded.kind);
      let shown = loaded.text;
      if (language === "json" && !loaded.truncated) {
        try {
          shown = JSON.stringify(JSON.parse(loaded.text), null, 2);
        } catch {
          shown = loaded.text;
        }
      }
      return (
        <div className="min-h-full bg-surface-sunken p-4 text-sm">
          <HighlightedCode code={shown} language={language} />
          {loaded.truncated && (
            <p className="mt-3 text-xs text-content-muted">
              {ui("Chỉ hiển thị 1 MB đầu của tệp.")}
            </p>
          )}
        </div>
      );
    }
    case "doc":
      return (
        <Unavailable
          target={target}
          message={ui("Không xem trước được tệp .doc cũ. Hãy tải tệp xuống để mở.")}
        />
      );
    default:
      return (
        <Unavailable
          target={target}
          message={ui("Chưa xem trước được loại tệp này. Hãy tải tệp xuống để mở.")}
        />
      );
  }
}

function Unavailable({ target, message }: { target: PreviewTarget; message: string }) {
  const ui = useAppTranslation();
  return (
    <div className="flex flex-1 flex-col items-center justify-center gap-4 p-6 text-center">
      <p className="text-sm text-content-secondary">{message}</p>
      <Button asChild size="sm" prominence="secondary">
        <a href={downloadUrl(target)} download={target.filename}>
          {ui("Tải xuống")}
        </a>
      </Button>
    </div>
  );
}

/** Zoomed images are dragged rather than scrolled, as an image viewer does; at 100% there is nothing to pan. */
function ImagePreview({
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

function PdfPreview({ blob }: { blob: Blob }) {
  // pdf.js reads the Blob directly; a blob: URL would be fetched, which connect-src 'self' refuses.
  return (
    <div className="mx-auto flex min-h-0 w-full max-w-5xl flex-1 flex-col">
      <DocumentPdfView url={blob} pages={[]} boxes={[]} />
    </div>
  );
}

function ImageControls({
  zoom,
  cropping,
  crop,
  onZoom,
  onRotate,
  onCrop,
}: {
  zoom: number;
  cropping: boolean;
  /** Whether a region has been drawn, and its size in the image's own pixels once that is known. */
  crop?: { selected: boolean; width?: number; height?: number };
  onZoom: (zoom: number) => void;
  onRotate: () => void;
  onCrop: () => void;
}) {
  const ui = useAppTranslation();
  return (
    // The pill around these is the viewer's, so the controls are bare buttons in a row.
    <div className="flex items-center gap-1">
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
            disabled={zoom <= 25}
            onClick={() => onZoom(Math.max(zoom - 25, 25))}
          >
            <ZoomOut />
          </IconButton>
          <span className="w-12 text-center font-secondary-body tabular-nums">{zoom}%</span>
          <IconButton
            prominence="internal"
            size="sm"
            aria-label={ui("Phóng to")}
            disabled={zoom >= 200}
            onClick={() => onZoom(Math.min(zoom + 25, 200))}
          >
            <ZoomIn />
          </IconButton>
          <IconButton
            prominence="internal"
            size="sm"
            aria-label={ui("Xoay ảnh")}
            onClick={onRotate}
          >
            <RotateCw />
          </IconButton>
        </>
      )}
      <IconButton
        prominence="internal"
        size="sm"
        aria-pressed={cropping}
        aria-label={cropping ? ui("Thoát cắt ảnh") : ui("Cắt ảnh")}
        onClick={onCrop}
      >
        <Crop />
      </IconButton>
    </div>
  );
}

/**
 * A question about the file that is opened, asked where the file is read: it starts a conversation with the
 * file attached, so the answer is given by Chat itself rather than by a second chat surface here.
 */
function AskAboutFile({
  name,
  onAsk,
}: {
  name: string;
  onAsk: (question: string) => Promise<void>;
}) {
  const ui = useAppTranslation();
  const [question, setQuestion] = useState("");
  const [pending, setPending] = useState(false);
  const [failed, setFailed] = useState(false);
  return (
    <form
      className="flex shrink-0 items-center gap-2 border-t border-border-subtle px-4 py-3"
      onSubmit={(event) => {
        event.preventDefault();
        if (pending) return;
        setPending(true);
        setFailed(false);
        void onAsk(question.trim())
          .catch(() => setFailed(true))
          .finally(() => setPending(false));
      }}
    >
      <Input
        value={question}
        onChange={(event) => setQuestion(event.target.value)}
        disabled={pending}
        maxLength={2000}
        aria-label={ui("Hỏi về {{name}}", { name })}
        placeholder={ui("Hỏi về tệp này…")}
      />
      <Button type="submit" size="sm" disabled={pending} className="shrink-0">
        <MessageSquare />
        {pending ? ui("Đang mở hội thoại…") : ui("Hỏi trong Chat")}
      </Button>
      {failed && (
        <span role="alert" className="shrink-0 text-sm text-content-danger">
          {ui("Không mở được hội thoại.")}
        </span>
      )}
    </form>
  );
}

function CopyButton({ text }: { text: string }) {
  const ui = useAppTranslation();
  const [copied, setCopied] = useState(false);
  return (
    <IconButton
      prominence="internal"
      size="sm"
      aria-label={copied ? ui("Đã sao chép") : ui("Sao chép")}
      onClick={() => {
        void navigator.clipboard?.writeText(text).then(() => {
          setCopied(true);
          window.setTimeout(() => setCopied(false), 1500);
        });
      }}
    >
      {copied ? <Check /> : <Copy />}
    </IconButton>
  );
}

function CsvTable({ csv, truncated = false }: { csv: string; truncated?: boolean }) {
  const ui = useAppTranslation();
  const [header = [], ...rows] = parseCsv(csv);
  const columns = Math.max(header.length, ...rows.map((row) => row.length));
  if (!columns) return <p className="text-sm text-content-secondary">{ui("Trang tính trống")}</p>;
  const shown = rows.slice(0, MAX_TABLE_ROWS);
  return (
    <div className="flex flex-col gap-2">
      <div className="overflow-auto rounded-lg border border-border-subtle bg-surface-base">
        <Table>
          <TableHeader className="sticky top-0 bg-surface-subtle">
            <TableRow>
              {Array.from({ length: columns }, (_, index) => (
                <TableHead
                  key={index}
                  className={cn(
                    "whitespace-nowrap",
                    index === 0 && "sticky left-0 bg-surface-subtle",
                  )}
                >
                  {header[index] ?? ""}
                </TableHead>
              ))}
            </TableRow>
          </TableHeader>
          <TableBody>
            {shown.map((row, rowIndex) => (
              <TableRow key={rowIndex}>
                {Array.from({ length: columns }, (_, index) => (
                  <TableCell
                    key={index}
                    title={row[index] || undefined}
                    className={cn(
                      "max-w-80 truncate whitespace-nowrap",
                      index === 0 && "sticky left-0 bg-surface-base font-medium",
                    )}
                  >
                    {row[index] ?? ""}
                  </TableCell>
                ))}
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </div>
      {(truncated || rows.length > MAX_TABLE_ROWS) && (
        <p className="text-xs text-content-muted">{ui("Bản xem trước bị cắt bớt")}</p>
      )}
    </div>
  );
}

function SheetsPreview({ sheets }: { sheets: Sheets }) {
  const ui = useAppTranslation();
  if (!sheets.length)
    return <p className="p-4 text-sm text-content-secondary">{ui("Không đọc được bảng tính.")}</p>;
  return (
    <Tabs defaultValue="0" className="p-4">
      <TabsList className="w-full justify-start overflow-x-auto">
        {sheets.map((sheet, index) => (
          <TabsTrigger
            key={index}
            value={String(index)}
            className="max-w-64 flex-none"
            title={sheet.name}
          >
            <span className="truncate">{sheet.name}</span>
          </TabsTrigger>
        ))}
      </TabsList>
      {sheets.map((sheet, index) => (
        <TabsContent key={index} value={String(index)}>
          <CsvTable csv={sheet.csv} truncated={sheet.truncated} />
        </TabsContent>
      ))}
    </Tabs>
  );
}

function DocxPreview({
  blob,
  onLoad,
}: {
  blob: Blob;
  onLoad: (result: { words: number; text: string }) => void;
}) {
  const body = useRef<HTMLDivElement>(null);
  const styles = useRef<HTMLDivElement>(null);
  const onLoadRef = useRef(onLoad);
  const [state, setState] = useState<"rendering" | "done" | "failed">("rendering");
  useEffect(() => {
    onLoadRef.current = onLoad;
  }, [onLoad]);
  useEffect(() => {
    if (!body.current || !styles.current) return undefined;
    let current = true;
    const bodyElement = body.current;
    const styleElement = styles.current;
    let adopted: CSSStyleSheet[] = [];
    void (async () => {
      try {
        const { renderAsync } = await import("docx-preview");
        // Render detached, then attach only sanitized markup and library <style> elements (Onyx sanitizeDocxHtml).
        const renderedBody = document.createElement("div");
        const renderedStyles = document.createElement("div");
        await renderAsync(blob, renderedBody, renderedStyles, {
          className: "docx",
          inWrapper: false,
          ignoreWidth: false,
          ignoreHeight: false,
          ignoreFonts: false,
          breakPages: true,
          useBase64URL: true,
          renderHeaders: true,
          renderFooters: true,
          renderFootnotes: true,
          renderEndnotes: true,
        });
        if (!current) return;
        bodyElement.innerHTML = sanitizeDocxHtml(renderedBody.innerHTML);
        // The deployment CSP (style-src 'self') ignores style attributes parsed from markup and inline <style>
        // elements; the same rules are applied through CSSOM, which the policy allows.
        for (const element of bodyElement.querySelectorAll<HTMLElement>("[style]"))
          element.style.cssText = element.getAttribute("style") ?? "";
        adopted = Array.from(renderedStyles.querySelectorAll("style")).flatMap((style) => {
          try {
            const sheet = new CSSStyleSheet();
            sheet.replaceSync(style.textContent ?? "");
            return [sheet];
          } catch {
            return [];
          }
        });
        document.adoptedStyleSheets = [...document.adoptedStyleSheets, ...adopted];
        styleElement.replaceChildren();
        const text = bodyElement.innerText ?? "";
        onLoadRef.current({ words: text.split(/\s+/).filter(Boolean).length, text });
        setState("done");
      } catch {
        if (current) setState("failed");
      }
    })();
    return () => {
      current = false;
      document.adoptedStyleSheets = document.adoptedStyleSheets.filter(
        (sheet) => !adopted.includes(sheet),
      );
    };
  }, [blob]);
  const ui = useAppTranslation();
  return (
    <>
      {state === "rendering" && (
        <div className="flex justify-center p-6" role="status">
          <Loader2 className="size-8 animate-spin text-content-muted" aria-hidden />
        </div>
      )}
      {state === "failed" && (
        <p className="p-6 text-center text-sm text-content-secondary">
          {ui("Không đọc được tài liệu Word này.")}
        </p>
      )}
      <div ref={styles} />
      <div
        ref={body}
        data-slot="docx-preview"
        // Pages keep their layout as in Onyx; narrow screens scroll sideways instead of reflowing.
        className="overflow-auto px-4 py-6 text-content-document [&_section.docx]:mx-auto [&_section.docx]:mb-6 [&_section.docx]:bg-surface-document [&_section.docx]:shadow-md"
      />
    </>
  );
}

export type { PreviewTarget };
