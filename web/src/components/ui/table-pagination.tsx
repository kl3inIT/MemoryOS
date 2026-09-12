import { useAppTranslation } from "@/i18n/use-app-translation";
import type { ReactNode } from "react";
import { Button } from "@/components/ui/button";
import { cn } from "@/lib/utils";

type TablePaginationProps = {
  label: string;
  page: number;
  totalPages: number | undefined;
  previousDisabled: boolean;
  nextDisabled: boolean;
  onPrevious: () => void;
  onNext: () => void;
  previousLabel?: string;
  nextLabel?: string;
  summary?: ReactNode;
  children?: ReactNode;
  className?: string;
};

export function TablePagination({
  label,
  page,
  totalPages,
  previousDisabled,
  nextDisabled,
  onPrevious,
  onNext,
  previousLabel,
  nextLabel,
  summary,
  children,
  className,
}: TablePaginationProps) {
  const ui = useAppTranslation();

  return (
    <nav
      aria-label={label}
      className={cn(
        "flex flex-col gap-3 border-t border-border-subtle px-4 py-3 sm:flex-row sm:items-center sm:justify-between",
        className,
      )}
    >
      {summary ? (
        <p className="font-secondary-body tabular-nums text-content-muted">{summary}</p>
      ) : null}
      <div className="flex flex-wrap items-center gap-2 sm:ml-auto">
        {children}
        <div className="flex items-center gap-2">
          <span
            role="status"
            className="min-w-16 text-center font-secondary-body whitespace-nowrap tabular-nums text-content-secondary"
          >
            {page + 1} / {totalPages === undefined ? "—" : Math.max(totalPages, 1)}
          </span>
          <Button
            size="sm"
            prominence="secondary"
            aria-label={previousLabel}
            disabled={previousDisabled}
            onClick={onPrevious}
          >
            {ui("Previous")}
          </Button>
          <Button
            size="sm"
            prominence="secondary"
            aria-label={nextLabel}
            disabled={nextDisabled}
            onClick={onNext}
          >
            {ui("Next")}
          </Button>
        </div>
      </div>
    </nav>
  );
}
