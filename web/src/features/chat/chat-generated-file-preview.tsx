import { useQuery } from "@tanstack/react-query";
import { useEffect, useRef, useState } from "react";
import { z } from "zod";
import { ErrorState } from "@/components/assistant-ui/elements/error-state";
import { HighlightedCode } from "@/components/assistant-ui/elements/code-renderers.aui";
import { Button } from "@/components/ui/button";
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
import { useAppTranslation } from "@/i18n/use-app-translation";
import { getChatFileArtifact, previewChatFileArtifactSpreadsheet } from "@/lib/hey-api/sdk.gen";
import { fileArtifactUrl, type GeneratedFile } from "./chat-code";
import {
  codeLanguage,
  MAX_TEXT_PREVIEW_BYTES,
  parseCsv,
  previewKind,
  sanitizeDocxHtml,
} from "./chat-file-preview";

const spreadsheetSchema = z.object({
  sheets: z.array(z.object({ name: z.string(), csv: z.string(), truncated: z.boolean() })),
});

/** Rows past this count are not rendered; the download holds the whole file. */
const MAX_TABLE_ROWS = 1000;

/**
 * Onyx PreviewModal variants for a file run_python generated, inside the Chat side panel. Content is read
 * through the owner-authorized artifact routes and kept out of the query cache once the panel closes.
 */
export function ChatGeneratedFilePreview({ file }: { file: GeneratedFile }) {
  const ui = useAppTranslation();
  const kind = previewKind(file.filename, file.mediaType);
  return (
    <div className="flex min-h-0 flex-col gap-3">
      {kind === "image" && (
        <img
          src={fileArtifactUrl(file.id)}
          alt={file.filename}
          className="max-h-[70dvh] max-w-full self-start rounded-lg object-contain"
        />
      )}
      {kind === "pdf" && <DocumentPdfView url={fileArtifactUrl(file.id)} pages={[]} boxes={[]} />}
      {kind === "xlsx" && <SpreadsheetPreview file={file} />}
      {(kind === "csv" || kind === "code" || kind === "markdown" || kind === "text") && (
        <TextPreview file={file} kind={kind} />
      )}
      {kind === "docx" && <DocxPreview file={file} />}
      {kind === "doc" && (
        <p className="text-sm text-content-secondary">
          {ui("Không xem trước được tệp .doc cũ. Hãy tải tệp xuống để mở.")}
        </p>
      )}
      {kind === "unsupported" && (
        <p className="text-sm text-content-secondary">
          {ui("Chưa xem trước được loại tệp này. Hãy tải tệp xuống để mở.")}
        </p>
      )}
      <Button asChild size="sm" prominence="secondary" className="self-start">
        <a href={fileArtifactUrl(file.id)} download={file.filename}>
          {ui("Tải xuống")}
        </a>
      </Button>
    </div>
  );
}

function useArtifactQuery<T>(
  file: GeneratedFile,
  part: string,
  read: (signal: AbortSignal) => Promise<T>,
) {
  const { actorId, authorizationVersion } = useApplicationSession();
  return useQuery({
    queryKey: ["chat-generated-file", actorId, authorizationVersion, file.id, part],
    gcTime: 0,
    staleTime: 0,
    retry: false,
    queryFn: ({ signal }) => read(signal),
  });
}

async function artifactBlob(file: GeneratedFile, signal: AbortSignal): Promise<Blob> {
  const { data } = await getChatFileArtifact({
    path: { artifactId: file.id },
    parseAs: "blob",
    signal,
    throwOnError: true,
  });
  if (!(data instanceof Blob)) throw new Error("Invalid file content");
  return data;
}

function Unavailable() {
  const ui = useAppTranslation();
  return (
    <ErrorState
      title={ui("Không xem trước được tệp")}
      detail={ui("Tệp có thể đã bị xóa hoặc không đọc được. Bạn vẫn có thể thử tải xuống.")}
    />
  );
}

function Loading() {
  const ui = useAppTranslation();
  return <p role="status">{ui("Đang đọc tệp…")}</p>;
}

function TextPreview({
  file,
  kind,
}: {
  file: GeneratedFile;
  kind: "csv" | "code" | "markdown" | "text";
}) {
  const ui = useAppTranslation();
  const text = useArtifactQuery(file, "text", async (signal) => {
    const blob = await artifactBlob(file, signal);
    const truncated = blob.size > MAX_TEXT_PREVIEW_BYTES;
    const content = await blob.slice(0, MAX_TEXT_PREVIEW_BYTES).text();
    return { content, truncated };
  });
  if (text.isPending) return <Loading />;
  if (text.isError) return <Unavailable />;
  const { content, truncated } = text.data;
  const note = truncated && (
    <p className="text-xs text-content-muted">{ui("Chỉ hiển thị 1 MB đầu của tệp.")}</p>
  );
  if (kind === "csv")
    return (
      <>
        <CsvTable csv={content} />
        {note}
      </>
    );
  const language = codeLanguage(file.filename, kind);
  let shown = content;
  if (language === "json" && !truncated) {
    try {
      shown = JSON.stringify(JSON.parse(content), null, 2);
    } catch {
      shown = content;
    }
  }
  return (
    <>
      <div className="max-h-[70dvh] overflow-auto rounded-lg bg-surface-sunken text-sm">
        <HighlightedCode code={shown} language={language} />
      </div>
      {note}
    </>
  );
}

function CsvTable({ csv, truncated = false }: { csv: string; truncated?: boolean }) {
  const ui = useAppTranslation();
  const [header = [], ...rows] = parseCsv(csv);
  const columns = Math.max(header.length, ...rows.map((row) => row.length));
  if (!columns) return <p className="text-sm text-content-secondary">{ui("Trang tính trống")}</p>;
  const shown = rows.slice(0, MAX_TABLE_ROWS);
  return (
    <div className="flex min-h-0 flex-col gap-2">
      <div className="max-h-[65dvh] overflow-auto rounded-lg border border-border-subtle">
        <Table>
          <TableHeader className="sticky top-0 bg-surface-subtle">
            <TableRow>
              {Array.from({ length: columns }, (_, index) => (
                <TableHead key={index} className="whitespace-nowrap">
                  {header[index] ?? ""}
                </TableHead>
              ))}
            </TableRow>
          </TableHeader>
          <TableBody>
            {shown.map((row, rowIndex) => (
              <TableRow key={rowIndex}>
                {Array.from({ length: columns }, (_, index) => (
                  // One line per row, as a spreadsheet: wrapping in a narrow panel made every cell a column of words.
                  <TableCell
                    key={index}
                    title={row[index] || undefined}
                    className="max-w-64 truncate whitespace-nowrap"
                  >
                    {row[index] ?? ""}
                  </TableCell>
                ))}
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </div>
      <p className="text-xs text-content-muted">
        {truncated || rows.length > MAX_TABLE_ROWS
          ? ui("{{rows}} dòng · {{columns}} cột · bản xem trước bị cắt bớt", {
              rows: rows.length,
              columns,
            })
          : ui("{{rows}} dòng · {{columns}} cột", { rows: rows.length, columns })}
      </p>
    </div>
  );
}

function SpreadsheetPreview({ file }: { file: GeneratedFile }) {
  const preview = useArtifactQuery(file, "sheets", async (signal) =>
    spreadsheetSchema.parse(
      (
        await previewChatFileArtifactSpreadsheet({
          path: { artifactId: file.id },
          signal,
          throwOnError: true,
        })
      ).data,
    ),
  );
  if (preview.isPending) return <Loading />;
  if (preview.isError || !preview.data.sheets.length) return <Unavailable />;
  const sheets = preview.data.sheets;
  return (
    <Tabs defaultValue="0">
      {/* Start-aligned and scrollable: a centered overflowing list clips its first sheet name. */}
      <TabsList className="w-full justify-start overflow-x-auto">
        {sheets.map((sheet, index) => (
          <TabsTrigger
            key={index}
            value={String(index)}
            className="max-w-56 flex-none"
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

function DocxPreview({ file }: { file: GeneratedFile }) {
  const body = useRef<HTMLDivElement>(null);
  const styles = useRef<HTMLDivElement>(null);
  const [rendered, setRendered] = useState(false);
  const [failed, setFailed] = useState(false);
  const bytes = useArtifactQuery(file, "docx", (signal) => artifactBlob(file, signal));
  useEffect(() => {
    const data = bytes.data;
    if (!data || !body.current || !styles.current) return undefined;
    let current = true;
    const bodyElement = body.current;
    const styleElement = styles.current;
    void (async () => {
      try {
        const { renderAsync } = await import("docx-preview");
        // Render detached, then attach only sanitized markup and library <style> elements (Onyx sanitizeDocxHtml).
        const renderedBody = document.createElement("div");
        const renderedStyles = document.createElement("div");
        await renderAsync(data, renderedBody, renderedStyles, {
          className: "docx",
          inWrapper: false,
          // Reflow to the side panel instead of a physical page width that would scroll sideways.
          ignoreWidth: true,
          breakPages: true,
          useBase64URL: true,
          renderHeaders: true,
          renderFooters: true,
          renderFootnotes: true,
          renderEndnotes: true,
        });
        if (!current) return;
        bodyElement.innerHTML = sanitizeDocxHtml(renderedBody.innerHTML);
        styleElement.replaceChildren(
          ...Array.from(renderedStyles.children).filter((child) => child.tagName === "STYLE"),
        );
        setRendered(true);
      } catch {
        if (current) setFailed(true);
      }
    })();
    return () => {
      current = false;
    };
  }, [bytes.data]);
  if (bytes.isError || failed) return <Unavailable />;
  return (
    <>
      {!rendered && <Loading />}
      <div ref={styles} />
      <div
        ref={body}
        data-slot="docx-preview"
        // Page margins are for print; in the narrow panel they left a sliver of text.
        className="max-h-[70dvh] overflow-auto rounded-lg bg-surface-sunken p-3 text-black [&_section.docx]:mx-auto [&_section.docx]:mb-3 [&_section.docx]:!min-h-0 [&_section.docx]:!w-auto [&_section.docx]:bg-white [&_section.docx]:!p-6 [&_section.docx]:shadow-sm"
      />
    </>
  );
}
