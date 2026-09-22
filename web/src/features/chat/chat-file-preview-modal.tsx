import { useQuery } from "@tanstack/react-query";
import { Check, ChevronLeft, ChevronRight, Copy, Download, Loader2, X } from "lucide-react";
import { Dialog } from "radix-ui";
import { useEffect, useState, type ReactNode } from "react";
import { IconButton } from "@/components/ui/icon-button";
import { useApplicationSession } from "@/features/identity/application-session-context";
import { CsvView } from "@/features/preview/csv-view";
import { DocxView } from "@/features/preview/docx-view";
import { DownloadView } from "@/features/preview/download-view";
import { ImageControls, ImageView } from "@/features/preview/image-view";
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
import { fileSize } from "./chat-code";
import { downloadUrl, type PreviewTarget } from "./chat-file-preview";

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
  return sheetsSchema.parse(data).sheets;
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
}: {
  target: PreviewTarget;
  /** The files shown beside this one, in the order the page lists them; enables previous/next. */
  siblings?: readonly PreviewTarget[];
  onClose: () => void;
  onCloseAutoFocus?: (event: Event) => void;
}) {
  const ui = useAppTranslation();
  const { actorId, authorizationVersion } = useApplicationSession();
  const [zoom, setZoom] = useState(100);
  const [rotation, setRotation] = useState(0);
  const [shown, setShown] = useState(opened);
  const target = shown;
  const gallery = siblings ?? [];
  const at = gallery.findIndex((file) => file.source === target.source && file.id === target.id);
  const step = (delta: number) => {
    const next = gallery[at + delta];
    if (at < 0 || !next) return;
    setShown(next);
    setZoom(100);
    setRotation(0);
  };
  // Arrow keys step through the gallery, as an image viewer does; the dialog keeps Escape for closing.
  useEffect(() => {
    if (at < 0) return;
    const onKey = (event: KeyboardEvent) => {
      if (event.altKey || event.ctrlKey || event.metaKey) return;
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
          <header className="flex shrink-0 items-start gap-3 border-b border-border-subtle px-5 py-3">
            <div className="min-w-0 flex-1">
              <Dialog.Title className="truncate font-main-ui-action" title={target.filename}>
                {target.filename}
              </Dialog.Title>
              {view?.description && (
                <p className="mt-0.5 truncate text-xs text-content-muted">{view.description}</p>
              )}
            </div>
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
                <span className="text-xs text-content-muted tabular-nums">
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
            <Dialog.Close asChild>
              <IconButton prominence="internal" size="sm" aria-label={ui("Đóng xem trước")}>
                <X />
              </IconButton>
            </Dialog.Close>
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
                <div className="flex min-h-0 flex-1 flex-col overflow-auto pb-20">
                  <Content
                    loaded={loaded.data}
                    target={target}
                    zoom={zoom}
                    rotation={rotation}
                    onDocx={setDocxWords}
                  />
                </div>
                <footer className="pointer-events-none absolute inset-x-0 bottom-0 flex items-center justify-between gap-3 bg-linear-to-t from-surface-subtle from-40% to-transparent p-4">
                  <div className="pointer-events-auto text-sm text-content-secondary">
                    {kind === "image" ? (
                      <ImageControls
                        zoom={zoom}
                        onZoom={setZoom}
                        onRotate={() => setRotation((current) => (current + 90) % 360)}
                      />
                    ) : view?.footer ? (
                      <span className="rounded-lg bg-surface-base/90 px-2 py-1 shadow-sm">
                        {view.footer}
                      </span>
                    ) : null}
                  </div>
                  <div className="pointer-events-auto flex items-center gap-0.5 rounded-xl border border-border-subtle bg-surface-base p-1 shadow-lg">
                    {view?.copy !== undefined && <CopyButton text={view.copy} />}
                    <IconButton
                      prominence="internal"
                      size="sm"
                      asChild
                      aria-label={ui("Tải xuống")}
                    >
                      <a href={downloadUrl(target)} download={target.filename}>
                        <Download />
                      </a>
                    </IconButton>
                  </div>
                </footer>
              </>
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
      return <ImageView blob={loaded.blob} alt={target.filename} zoom={zoom} rotation={rotation} />;
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
    case "code":
    case "text":
    case "markdown":
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
