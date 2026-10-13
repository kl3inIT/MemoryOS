import type { ComponentProps } from "react";
import type { OriginalView } from "./original-view";
import { PreviewCanvas, PreviewSkeleton } from "./preview-surface";
import { originalReader } from "./readers";

/** The stored original of a document, with page-shaped placeholders while the readers' code arrives. */
export function LazyOriginalView(props: ComponentProps<typeof OriginalView>) {
  return (
    <originalReader.Loadable
      {...props}
      fallback={
        <PreviewCanvas>
          <PreviewSkeleton />
        </PreviewCanvas>
      }
    />
  );
}
