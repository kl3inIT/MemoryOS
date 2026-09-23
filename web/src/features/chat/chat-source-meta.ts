import type { useAppTranslation } from "@/i18n/use-app-translation";
import { documentSourceLabels } from "@/features/search/document-source-presentation";
import { formatPages } from "@/features/preview/pdf-pages";
import { readSourceLocation } from "@/features/search/source-provenance";
import type { ChatSource } from "./chat-evidence";

type Translate = ReturnType<typeof useAppTranslation>;

/** Page and sheet recorded in a document or file citation's provenance, translated. */
export function sourceLocationLabels(source: ChatSource, ui: Translate): string[] {
  if (source.web) return [];
  const location = readSourceLocation(source.provenance.map((item) => item.provenanceJson));
  const pages = formatPages(location.pages);
  return [
    ...(pages ? [ui("Trang {{pages}}", { pages })] : []),
    ...(location.sheet ? [ui("Trang tính {{name}}", { name: location.sheet })] : []),
  ];
}

/** Type, provider and recorded location as plain text, for places that cannot hold a link. */
export function sourceMeta(source: ChatSource, ui: Translate): string[] {
  if (source.web) return [];
  const labels = documentSourceLabels(source.mediaType, source.sourceTypes);
  return [
    ...(labels.type ? [ui(labels.type)] : []),
    ...labels.providers.map((provider) => ui(provider)),
    ...sourceLocationLabels(source, ui),
  ];
}

/** Cited pages and regions when the citation is an indexed PDF whose provenance records pages. */
export function citedPdfLocation(source: ChatSource) {
  if (!source.documentId || !source.generation || source.mediaType !== "application/pdf")
    return undefined;
  const location = readSourceLocation(source.provenance.map((item) => item.provenanceJson));
  return location.pages.length ? location : undefined;
}

/** Host and path without protocol or `www.`, the way search results print a URL. */
export function webDisplayUrl(value: string) {
  const url = new URL(value);
  return `${url.hostname.replace(/^www\./, "")}${url.pathname === "/" ? "" : url.pathname}`;
}
