import { uiLocale } from "@/i18n/format";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { ChevronLeft, ChevronRight } from "lucide-react";
import { useLayoutEffect, useRef } from "react";
import { Button } from "@/components/ui/button";
import type { SelectionPaging } from "./google-drive-selection-paging";

export function SelectionPager({
  paging,
  count,
  hasNext,
  busy,
  previousLabel,
  nextLabel,
  onPrevious,
  onNext,
}: {
  paging: SelectionPaging;
  count: number;
  hasNext: boolean;
  /** The shown page is a placeholder while the requested one loads. */
  busy: boolean;
  previousLabel: string;
  nextLabel: string;
  onPrevious: () => void;
  onNext: () => void;
}) {
  const ui = useAppTranslation();
  const previousButton = useRef<HTMLButtonElement>(null);
  const nextButton = useRef<HTMLButtonElement>(null);
  const pressed = useRef<"previous" | "next" | null>(null);
  const hasPrevious = paging.previous.length > 0;

  // Reaching either end disables the pressed button; keep keyboard focus on the pager.
  useLayoutEffect(() => {
    const lost = (button: HTMLButtonElement | null) =>
      document.activeElement === button || document.activeElement === document.body;
    if (pressed.current === "next" && !hasNext && lost(nextButton.current))
      previousButton.current?.focus();
    if (pressed.current === "previous" && !hasPrevious && lost(previousButton.current))
      nextButton.current?.focus();
  }, [hasNext, hasPrevious]);

  const locale = uiLocale();
  return (
    <div className="flex flex-wrap items-center justify-end gap-x-2 gap-y-1">
      {count ? (
        <p className="text-xs tabular-nums text-content-muted">
          {ui("Items {{v1}}–{{v2}}", {
            v1: paging.start.toLocaleString(locale),
            v2: (paging.start + count - 1).toLocaleString(locale),
          })}
        </p>
      ) : null}
      <div className="flex gap-1">
        <Button
          ref={previousButton}
          size="sm"
          prominence="tertiary"
          aria-label={previousLabel}
          disabled={!hasPrevious}
          onClick={() => {
            if (busy) return;
            pressed.current = "previous";
            onPrevious();
          }}
        >
          <ChevronLeft aria-hidden="true" />
          {ui("Previous")}
        </Button>
        <Button
          ref={nextButton}
          size="sm"
          prominence="tertiary"
          aria-label={nextLabel}
          disabled={!hasNext}
          onClick={() => {
            if (busy) return;
            pressed.current = "next";
            onNext();
          }}
        >
          {ui("Next")}
          <ChevronRight aria-hidden="true" />
        </Button>
      </div>
    </div>
  );
}
