import { loadable } from "@/lib/loadable";

/**
 * The readers whose code loads on demand. They are declared apart from the components that show them so a
 * surface can start the loading before either is rendered (`warmOriginal`).
 */

// The viewers pull in pdf.js and docx-preview, so they load only when a reader opens a document.
export const originalReader = loadable(() =>
  import("./original-view").then((module) => module.OriginalView),
);

// pdf.js is the heaviest code the app ships, so its reader loads only when a PDF is opened; every surface
// reaches it through `LazyPdfView`, never through a static import of `pdf-view`.
export const pdfReader = loadable(() => import("./pdf-view").then((module) => module.PdfView));
