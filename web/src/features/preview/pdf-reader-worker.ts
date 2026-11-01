import { useSyncExternalStore } from "react";
import { pdfjs } from "react-pdf";
import { pdfWorkerUrl } from "./pdf-worker";

pdfjs.GlobalWorkerOptions.workerSrc = pdfWorkerUrl;

type ReaderWorker = InstanceType<typeof pdfjs.PDFWorker>;

let worker: ReaderWorker | undefined;
let readers = 0;

/** Starts the worker for the first reader and stops it after the last one. */
function join() {
  readers += 1;
  worker ??= new pdfjs.PDFWorker();
  return () => {
    readers -= 1;
    // react-pdf releases a document a microtask after its reader unmounts, and that release still talks to the
    // worker; a reader mounted in the same pass takes the worker over. So it stops a task later, if nobody reads.
    setTimeout(() => {
      if (readers > 0 || !worker) return;
      worker.destroy();
      worker = undefined;
    });
  };
}

/**
 * The pdf.js worker mounted readers share, started as the first of them mounts. pdf.js would otherwise start
 * one only when it is handed a document, which is after the original's first bytes have arrived; started here
 * it boots while they are still on their way. Undefined for the render before it has started.
 */
export function useReaderWorker() {
  return useSyncExternalStore(join, () => worker);
}
