import { useQuery } from "@tanstack/react-query";
import { Check, ChevronLeft, ChevronRight, Copy, Download, Loader2, Undo2, X } from "lucide-react";
import { Dialog } from "radix-ui";
import { useCallback, useEffect, useState } from "react";
import { Button } from "@/components/ui/button";
import { IconButton } from "@/components/ui/icon-button";
import { useApplicationSession } from "@/features/identity/application-session-context";
import { CsvView } from "@/features/preview/csv-view";
import { DocxView } from "@/features/preview/docx-view";
import { DownloadView } from "@/features/preview/download-view";
import { ImageControls, ImageView } from "@/features/preview/image-view";
import { useObjectUrl } from "@/features/preview/use-object-url";
import {
  codeLanguage,
  lineCount,
  MAX_TEXT_PREVIEW_BYTES,
  parseCsv,
  previewKind,
  previewSize,
  sheetsSchema,
  type PreviewKind,
  type Sheets,
} from "@/features/preview/preview-kind";
import { SheetView } from "@/features/preview/sheet-view";
import { MarkdownView } from "@/features/preview/markdown-view";
import { TextView } from "@/features/preview/text-view";
import { PdfView } from "@/features/preview/pdf-view";
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
import { ChatFileAskComposer, type AskExtras } from "./file-ask-composer";
import { fileSize } from "@/features/chat/chat-code";
import { downloadUrl, type PreviewTarget } from "./file-preview";
import { ImageCropper } from "./image-cropper";
import { croppedFileName, cropPixels, cropType, renderCrop, type CropRect } from "./image-crop";

/** What the viewer may scale an image to, and the step its buttons take. */
const ZOOM = { min: 25, max: 400, step: 25 } as const;
const clampZoom = (value: number) => Math.min(ZOOM.max, Math.max(ZOOM.min, value));

async function readBlob(target: PreviewTarget, signal: AbortSignal): Promise<Blob> {
  const { data } =
    target.source === "generated"
      ? await getChatFileArtifact({
          path: { artifactId: target.id },
          parseAs: "blob",
          signal,
        })
      : target.source === "image"
        ? await getChatImageArtifact({
            path: { artifactId: target.id },
            parseAs: "blob",
            signal,
          })
        : await downloadChatFile({
            path: { fileId: target.id },
            parseAs: "blob",
            signal,
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
        })
      : await previewChatFileSpreadsheet({
          path: { fileId: target.id },
          signal,
        });
  return sheetsSchema.parse(data).sheets;
}

type Loaded =
  | { kind: "xlsx"; sheets: Sheets }
  | { kind: "text" | "code"; text: string; truncated: boolean; bytes: number }
  | { kind: "markdown" | "csv"; text: string; truncated: boolean; bytes: number }
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
  /** Keeps an edited image where the modal was opened from; without it an edit can only be downloaded. */
  onSaveImage?: (file: File) => void | Promise<void>;
  /**
   * Opens a conversation about this file. `edited` is the image the viewer is currently showing when a crop
   * has been applied, so the question is asked about what is on screen. Surfaces inside Chat leave it out:
   * they are already a conversation.
   */
  onAsk?: (
    target: PreviewTarget,
    question: string,
    extras: AskExtras & { edited?: File },
  ) => Promise<void>;
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
  /** The crop the owner applied: from here the viewer, the download, a save and a question all use it. */
  const [edited, setEdited] = useState<{
    blob: Blob;
    filename: string;
    type: string;
    pixels: { width: number; height: number };
  }>();
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
    setEdited(undefined);
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
  const original = loaded.data?.kind === "image" ? loaded.data.blob : undefined;
  // Once a crop is applied the viewer shows it, so everything downstream reads one image.
  const image = edited?.blob ?? original;
  const override = edited && original ? ({ kind: "image", blob: edited.blob } as const) : undefined;
  const filename = edited?.filename ?? target.filename;
  const shownFile = override ?? loaded.data;
  const view = shownFile ? describe(shownFile, filename, docxWords, ui) : undefined;
  // The cropper draws the same bytes the preview holds, so it shares the preview's object URL.
  const imageSource = useObjectUrl(image);
  const editedSource = useObjectUrl(edited?.blob);
  // A wheel over the picture scales it, as an image viewer does; the listener must stay identical to detach.
  const zoomBy = useCallback(
    (deltaY: number) => setZoom((current) => clampZoom(current - Math.sign(deltaY) * 10)),
    [],
  );

  /**
   * The crop is encoded from the bytes the preview already holds and replaces what the viewer shows, so it
   * is usable in this session at once: cropped again, downloaded, saved as a new file or asked about.
   */
  const applyCrop = async () => {
    if (!crop || !image) return;
    setSavingCrop(true);
    setCropFailed(false);
    try {
      const type = cropType(edited?.type ?? target.mediaType ?? image.type);
      const bitmap = await createImageBitmap(image);
      const cropped = await renderCrop(bitmap, crop, type);
      const pixels = cropPixels(crop, { width: bitmap.width, height: bitmap.height });
      bitmap.close();
      setEdited({
        blob: cropped,
        // A second crop refines the same file rather than naming it twice.
        filename: edited?.filename ?? croppedFileName(target.filename, type),
        type,
        pixels,
      });
      setCropping(false);
      setNatural(undefined);
      showUpright();
    } catch {
      setCropFailed(true);
    } finally {
      setSavingCrop(false);
    }
  };

  const saveEdited = async () => {
    if (!edited || !onSaveImage) return;
    setSavingCrop(true);
    try {
      await onSaveImage(new File([edited.blob], edited.filename, { type: edited.type }));
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
              <Dialog.Title className="min-w-0 truncate text-content-primary" title={filename}>
                {filename}
              </Dialog.Title>
              {view?.description && (
                <>
                  <span className="shrink-0 text-content-muted" aria-hidden="true">
                    ·
                  </span>
                  <span className="shrink-0 truncate text-content-muted">{view.description}</span>
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
                  {/* A download takes what the viewer shows, so an applied crop is what lands on disk. */}
                  <a href={editedSource ?? downloadUrl(target)} download={filename}>
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
                <div
                  className={cn(
                    "flex min-h-0 flex-1 flex-col overflow-auto",
                    kind === "image" || cropping ? "pt-14" : "pt-3",
                    // The question floats over the file, so the file keeps room beneath it.
                    onAsk && !cropping ? "pb-28" : "pb-4",
                  )}
                >
                  {cropping && imageSource ? (
                    <ImageCropper
                      src={imageSource}
                      alt={filename}
                      rect={crop}
                      onRect={setCrop}
                      onNatural={setNatural}
                    />
                  ) : (
                    <Content
                      loaded={override ?? loaded.data}
                      target={target}
                      zoom={zoom}
                      rotation={rotation}
                      onZoom={zoomBy}
                      onDocx={setDocxWords}
                    />
                  )}
                </div>
                {/*
                 * The picture's tools float over it rather than under it; what the file is is said once, in
                 * the header, so a narrow window never reads it twice.
                 */}
                {(kind === "image" || cropping) && (
                  <div className="pointer-events-none absolute inset-x-0 top-3 flex justify-center px-3">
                    <div className="pointer-events-auto flex max-w-full flex-wrap items-center gap-1 rounded-full border border-border-subtle bg-surface-overlay px-1.5 py-1 shadow-lg">
                      {kind === "image" && (
                        <ImageControls
                          zoom={zoom}
                          bare
                          maxZoom={ZOOM.max}
                          cropping={cropping}
                          crop={
                            crop && { selected: true, ...(natural && cropPixels(crop, natural)) }
                          }
                          onZoom={setZoom}
                          onRotate={(degrees) => setRotation((current) => current + degrees)}
                          onCrop={() => {
                            // A crop is drawn on the image as stored: upright, unzoomed and unrotated.
                            setCropping(!cropping);
                            showUpright();
                          }}
                        />
                      )}
                      {cropFailed && (
                        <span role="alert" className="px-2 font-secondary-body text-content-danger">
                          {ui("Không cắt được ảnh.")}
                        </span>
                      )}
                      {cropping ? (
                        <Button
                          size="sm"
                          disabled={!crop || savingCrop}
                          onClick={() => void applyCrop()}
                        >
                          {savingCrop ? ui("Đang cắt…") : ui("Cắt")}
                        </Button>
                      ) : (
                        edited && (
                          // The applied crop is what the viewer, a download, a save and a question now use.
                          <>
                            <span className="px-2 font-secondary-body tabular-nums text-content-muted">
                              {ui("Đã cắt · {{width}} × {{height}} px", edited.pixels)}
                            </span>
                            {onSaveImage && (
                              <Button
                                size="sm"
                                disabled={savingCrop}
                                onClick={() => void saveEdited()}
                              >
                                {savingCrop ? ui("Đang lưu…") : ui("Lưu thành tệp mới")}
                              </Button>
                            )}
                            <IconButton
                              prominence="internal"
                              size="sm"
                              aria-label={ui("Về ảnh gốc")}
                              onClick={() => {
                                setEdited(undefined);
                                setNatural(undefined);
                                showUpright();
                              }}
                            >
                              <Undo2 />
                            </IconButton>
                          </>
                        )
                      )}
                    </div>
                  </div>
                )}
              </>
            )}
            {onAsk && !cropping && (
              <ChatFileAskComposer
                name={filename}
                onAsk={(question, extras) =>
                  onAsk(target, question, {
                    ...extras,
                    edited:
                      edited && new File([edited.blob], edited.filename, { type: edited.type }),
                  })
                }
              />
            )}
          </div>
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
): { description?: string; copy?: string } {
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
        copy: loaded.text,
      };
    }
    case "markdown":
      return { copy: loaded.text };
    case "csv": {
      const [header = [], ...rows] = parseCsv(loaded.text);
      const columns = Math.max(header.length, ...rows.map((row) => row.length));
      return {
        description: ui("{{size}} · {{columns}} cột · {{rows}} dòng", {
          size: fileSize(loaded.bytes, locale),
          columns,
          rows: rows.length,
        }),
        copy: loaded.text,
      };
    }
    case "xlsx":
      return { description: ui("{{count}} trang tính", { count: loaded.sheets.length }) };
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
  onZoom,
  onDocx,
}: {
  loaded: Loaded;
  target: PreviewTarget;
  zoom: number;
  rotation: number;
  /** Wheel over the picture; the same callback on every render, so its listener is attached once. */
  onZoom: (deltaY: number) => void;
  onDocx: (result: { words: number; text: string }) => void;
}) {
  const ui = useAppTranslation();
  switch (loaded.kind) {
    case "image":
      return (
        <ImageView
          blob={loaded.blob}
          alt={target.filename}
          zoom={zoom}
          rotation={rotation}
          onZoom={onZoom}
        />
      );
    case "pdf":
      return <PdfPreview blob={loaded.blob} />;
    case "xlsx":
      return <SheetView sheets={loaded.sheets} />;
    case "csv":
      return (
        <div className="p-4">
          <CsvView csv={loaded.text} truncated={loaded.truncated} />
        </div>
      );
    case "docx":
      return <DocxView blob={loaded.blob} onLoad={onDocx} />;
    case "markdown":
      return <MarkdownView text={loaded.text} truncated={loaded.truncated} />;
    case "code":
    case "text":
      return (
        <TextView
          text={loaded.text}
          filename={target.filename}
          kind={loaded.kind}
          truncated={loaded.truncated}
        />
      );
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
  return <DownloadView href={downloadUrl(target)} filename={target.filename} message={message} />;
}

function PdfPreview({ blob }: { blob: Blob }) {
  // pdf.js reads the Blob directly; a blob: URL would be fetched, which connect-src 'self' refuses.
  return (
    <div className="mx-auto flex min-h-0 w-full max-w-5xl flex-1 flex-col">
      <PdfView url={blob} pages={[]} boxes={[]} />
    </div>
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

export type { PreviewTarget };
