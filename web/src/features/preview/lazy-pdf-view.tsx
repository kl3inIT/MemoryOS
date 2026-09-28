import { lazy, Suspense, type ComponentProps } from "react";
import type { PdfView } from "./pdf-view";
import { PreviewCanvas, PreviewSkeleton } from "./preview-surface";

// pdf.js is the heaviest code the app ships, so its reader loads only when a PDF is opened; every surface
// reaches it through this component, never through a static import of `pdf-view`.
const PdfChunk = lazy(() => import("./pdf-view").then((module) => ({ default: module.PdfView })));

/** The pdf.js reader, with page-shaped placeholders while its code arrives. */
export function LazyPdfView({
  skeletonWidth,
  ...props
}: ComponentProps<typeof PdfView> & { skeletonWidth?: number }) {
  return (
    <Suspense
      fallback={
        <PreviewCanvas>
          <PreviewSkeleton width={skeletonWidth} />
        </PreviewCanvas>
      }
    >
      <PdfChunk {...props} />
    </Suspense>
  );
}
