import type { ReactNode, RefObject } from "react";
import { Skeleton } from "@/components/ui/skeleton";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { cn } from "@/lib/utils";

/**
 * The reading canvas: a sunken backdrop the document sheet sits on, as a document reader shows a page.
 * The sheet itself stays `surface-document`, which is white in both themes, because a document is authored
 * on white and a citation highlight multiplies onto it.
 */
export function PreviewCanvas({
  children,
  className,
  ref,
}: {
  children: ReactNode;
  className?: string;
  ref?: RefObject<HTMLDivElement | null>;
}) {
  return (
    <div
      ref={ref}
      data-slot="preview-canvas"
      className={cn(
        "min-h-0 flex-1 overflow-auto overscroll-contain bg-surface-sunken p-4 [scrollbar-gutter:stable] sm:px-5",
        className,
      )}
    >
      {children}
    </div>
  );
}

/**
 * Pages in the shape they will take, so the canvas keeps its size while the document loads instead of
 * collapsing to a line of text and pushing the reader's scroll position around when it arrives.
 */
export function PreviewSkeleton({ pages = 2, width }: { pages?: number; width?: number }) {
  const ui = useAppTranslation();
  return (
    <div
      role="status"
      aria-label={ui("Đang tải tài liệu…")}
      className="flex flex-col items-center gap-5"
    >
      {Array.from({ length: pages }, (_, index) => (
        <Skeleton
          key={index}
          // US Letter portrait, the proportions an unmeasured page borrows everywhere else in the reader.
          className="aspect-[612/792] w-full rounded-sm"
          style={width ? { width, flex: "none" } : undefined}
        />
      ))}
    </div>
  );
}
