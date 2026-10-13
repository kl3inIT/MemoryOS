import type { ComponentProps } from "react";
import type { PdfView } from "./pdf-view";
import { PreviewCanvas, PreviewSkeleton } from "./preview-surface";
import { pdfReader } from "./readers";

/** The pdf.js reader, with page-shaped placeholders while its code arrives. */
export function LazyPdfView({
  skeletonWidth,
  ...props
}: ComponentProps<typeof PdfView> & { skeletonWidth?: number }) {
  return (
    <pdfReader.Loadable
      {...props}
      fallback={
        <PreviewCanvas>
          <PreviewSkeleton width={skeletonWidth} />
        </PreviewCanvas>
      }
    />
  );
}
