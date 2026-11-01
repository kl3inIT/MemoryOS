import { preparePdfOriginal } from "./pdf-range-source";
import { pdfWorkerUrl } from "./pdf-worker";
import { previewKind } from "./preview-kind";
import { originalReader, pdfReader } from "./readers";

let workerLinked = false;

/**
 * An original is about to be read: start what its reader waits for before it can draw. For any file that is
 * the readers' code. For a PDF it is also the pdf.js reader, the worker script pdf.js starts only once that
 * reader has run and, given the original's address, its first bytes, so that all of them arrive beside the
 * dialog instead of one after another behind it.
 *
 * A surface passes the address when it opens the document. Without one only code is loaded, which a surface may
 * do when a result is pointed at; the code is fetched once.
 */
export function warmOriginal(filename: string, mediaType?: string | null, url?: string) {
  // A view reports a chunk that cannot load when it renders; a warm-up only leaves it to be tried again.
  originalReader.preload().catch(() => {});
  if (previewKind(filename, mediaType || "application/octet-stream") !== "pdf") return;
  if (url) preparePdfOriginal(url);
  pdfReader.preload().catch(() => {});
  if (workerLinked) return;
  workerLinked = true;
  const link = document.createElement("link");
  link.rel = "modulepreload";
  link.href = pdfWorkerUrl;
  document.head.append(link);
}
