// Self-hosted worker: the bundler emits it under the app origin, so `script-src 'self'` covers it. The URL lives
// apart from the reader so it can be preloaded before the reader's own code has arrived.
export const pdfWorkerUrl = new URL(
  "pdfjs-dist/build/pdf.worker.min.mjs",
  import.meta.url,
).toString();
