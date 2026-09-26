import { useQuery } from "@tanstack/react-query";
import { useApplicationSession } from "@/features/identity/application-session-context";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { DownloadView } from "./download-view";
import { FilePreview, type PreviewCitations } from "./file-preview";
import { readPreviewContent, type PreviewContent } from "./preview-content";
import { previewKind, type Sheets } from "./preview-kind";
import { PreviewCanvas, PreviewSkeleton } from "./preview-surface";

/** The three ways a reader reaches one stored original; a surface supplies the routes its authority allows. */
export type OriginalReader = {
  /** Same-origin URL of the original; pdf.js reads it by HTTP range and the download link points at it. */
  url: string;
  bytes: (signal: AbortSignal) => Promise<Blob>;
  /** A workbook is read as CSV per sheet, because the app ships no client-side workbook parser. */
  sheets: (signal: AbortSignal) => Promise<Sheets>;
};

/**
 * Reads only what the chosen view needs: pdf.js reads the URL itself by range, a workbook arrives already
 * parsed per sheet, and a file this app cannot show is never downloaded to find that out.
 */
async function load(
  reader: OriginalReader,
  filename: string,
  mediaType: string,
  signal: AbortSignal,
): Promise<PreviewContent> {
  const kind = previewKind(filename, mediaType);
  if (kind === "pdf") return { kind, file: reader.url };
  if (kind === "xlsx") return { kind, sheets: await reader.sheets(signal) };
  if (kind === "doc" || kind === "pptx" || kind === "unsupported") return { kind };
  return readPreviewContent(await reader.bytes(signal), kind);
}

/**
 * The stored original of a cited Document, read through the surface's authority and shown by `FilePreview`
 * with the cited passages painted where they were found in it.
 */
export function OriginalView({
  reader,
  filename,
  mediaType,
  thumbnails = false,
  ...citations
}: {
  reader: OriginalReader;
  filename: string;
  mediaType?: string | null;
  /** A page rail beside a paged original, for a reader wide enough to hold one. */
  thumbnails?: boolean;
} & PreviewCitations) {
  const ui = useAppTranslation();
  const { actorId, authorizationVersion } = useApplicationSession();
  const declared = mediaType || "application/octet-stream";
  const loaded = useQuery({
    queryKey: ["document-original", actorId, authorizationVersion, reader.url],
    queryFn: ({ signal }) => load(reader, filename, declared, signal),
    retry: false,
    staleTime: 0,
    gcTime: 0,
  });

  if (loaded.isPending)
    return (
      <PreviewCanvas>
        <PreviewSkeleton />
      </PreviewCanvas>
    );
  if (loaded.isError)
    return (
      <DownloadView
        href={reader.url}
        filename={filename}
        message={ui("Không mở được tệp gốc. Hãy tải xuống để xem toàn bộ tệp.")}
      />
    );
  return (
    <FilePreview
      content={loaded.data}
      filename={filename}
      download={reader.url}
      citations={citations}
      thumbnails={thumbnails}
    />
  );
}
