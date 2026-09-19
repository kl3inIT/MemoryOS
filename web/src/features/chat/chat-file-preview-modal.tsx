import { useQuery } from "@tanstack/react-query";
import { Check, Copy, Download, Loader2, X, ZoomIn, ZoomOut } from "lucide-react";
import { Dialog } from "radix-ui";
import { useEffect, useRef, useState, type ReactNode } from "react";
import { z } from "zod";
import { HighlightedCode } from "@/components/assistant-ui/elements/code-renderers.aui";
import { Button } from "@/components/ui/button";
import { IconButton } from "@/components/ui/icon-button";
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
  target,
  onClose,
  onCloseAutoFocus,
}: {
  target: PreviewTarget;
  onClose: () => void;
  onCloseAutoFocus?: (event: Event) => void;
}) {
  const ui = useAppTranslation();
  const { actorId, authorizationVersion } = useApplicationSession();
  const [zoom, setZoom] = useState(100);
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
        <Dialog.Overlay className="fixed inset-0 z-50 bg-content-primary/40 backdrop-blur-[2px]" />
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
                  <Content loaded={loaded.data} target={target} zoom={zoom} onDocx={setDocxWords} />
                </div>
                <footer className="pointer-events-none absolute inset-x-0 bottom-0 flex items-center justify-between gap-3 bg-linear-to-t from-surface-subtle from-40% to-transparent p-4">
                  <div className="pointer-events-auto text-sm text-content-secondary">
                    {kind === "image" ? (
                      <ZoomControls zoom={zoom} onZoom={setZoom} />
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
  onDocx,
}: {
  loaded: Loaded;
  target: PreviewTarget;
  zoom: number;
  onDocx: (result: { words: number; text: string }) => void;
}) {
  const ui = useAppTranslation();
  switch (loaded.kind) {
    case "image":
      return <ImagePreview blob={loaded.blob} alt={target.filename} zoom={zoom} />;
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

function ImagePreview({ blob, alt, zoom }: { blob: Blob; alt: string; zoom: number }) {
  const src = useObjectUrl(blob);
  return (
    <div className="flex min-h-0 flex-1 items-center justify-center overflow-auto p-4">
      {src && (
        <img
          src={src}
          alt={alt}
          className="max-h-full max-w-full object-contain transition-transform duration-300 ease-in-out"
          style={{ transform: `scale(${zoom / 100})` }}
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

function ZoomControls({ zoom, onZoom }: { zoom: number; onZoom: (zoom: number) => void }) {
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
        className="overflow-auto px-4 py-6 text-black [&_section.docx]:mx-auto [&_section.docx]:mb-6 [&_section.docx]:bg-white [&_section.docx]:shadow-md"
      />
    </>
  );
}

export type { PreviewTarget };
