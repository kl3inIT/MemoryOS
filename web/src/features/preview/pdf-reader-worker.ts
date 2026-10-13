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
    if (readers > 0) return;
    const idle = worker;
    worker = undefined;
    // A document released in the same pass still says goodbye to its worker; stop it after that.
    queueMicrotask(() => idle?.destroy());
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
