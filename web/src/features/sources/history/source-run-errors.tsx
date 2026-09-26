import { useAppTranslation } from "@/i18n/use-app-translation";
import { keepPreviousData, useQuery } from "@tanstack/react-query";
import { CircleCheck, RefreshCw } from "lucide-react";
import { Button } from "@/components/ui/button";
import { StatusBadge } from "@/components/ui/status-badge";
import { TablePagination } from "@/components/ui/table-pagination";
import { listSourceRunErrorsOptions } from "@/lib/hey-api/@tanstack/react-query.gen";
import type { SourceRun, SourceRunError } from "@/lib/hey-api/types.gen";
import { cn } from "@/lib/utils";
import { useManualRefresh } from "@/lib/use-manual-refresh";
import { sourceStatusMessage } from "@/features/sources/shared/source-errors";
import { useCursorPaging } from "@/components/data-table/use-cursor-paging";
import { runErrorMessage, runIsActive } from "./source-history";
import { HistoryTime } from "./source-history-presentation";
import { ExpandableRow } from "./expandable-row";

const runErrorStages = {
  PROVIDER: "Provider",
  STORAGE_READ: "Reading storage",
  STORAGE_WRITE: "Writing storage",
  EXTRACTION: "Extraction",
  PUBLICATION: "Publication",
  SYSTEM: "System",
} as const;

export function RunErrors({ run }: { run: SourceRun }) {
  const ui = useAppTranslation();
  const paging = useCursorPaging();
  const errors = useQuery({
    ...listSourceRunErrorsOptions({
      path: { sourceId: run.sourceId, runId: run.id },
      query: { size: 5, cursor: paging.cursor },
    }),
    retry: false,
    staleTime: 0,
    placeholderData: keepPreviousData,
    refetchInterval: (query) =>
      runIsActive(run) ||
      query.state.data?.items.some(
        (error) => error.currentItemStatus === "PENDING" || error.currentItemStatus === "DELETING",
      )
        ? 5_000
        : false,
  });
  // A live run is polled, so the refresh control follows the press rather than the poll.
  const errorsRefresh = useManualRefresh(errors.refetch);
  return (
    <div
      className="flex flex-col gap-3 text-sm text-content-secondary"
      aria-busy={errors.isPlaceholderData}
    >
      <div className="flex justify-end">
        <Button
          size="sm"
          prominence="tertiary"
          pending={errorsRefresh.pending}
          onClick={errorsRefresh.refresh}
        >
          <RefreshCw data-icon="inline-start" aria-hidden="true" />
          {ui("Refresh file states")}
        </Button>
      </div>
      {errors.isError ? (
        <p role="alert" className="text-status-danger-content">
          {ui(
            "Error details and current file states could not be refreshed. Displayed states may be out of date.",
          )}{" "}
          <Button size="sm" prominence="tertiary" onClick={() => void errors.refetch()}>
            {ui("Retry")}
          </Button>
        </p>
      ) : errors.isPending ? (
        <p role="status">{ui("Loading errors…")}</p>
      ) : null}
      {errors.data ? (
        <>
          {errors.data.items.length ? (
            <ul
              aria-label={ui("Run errors")}
              className="divide-y divide-border-subtle rounded-lg border border-border-subtle"
            >
              {errors.data.items.map((error) => (
                <li key={error.id} className="min-w-0">
                  <RunErrorRow error={error} />
                </li>
              ))}
            </ul>
          ) : null}
          {paging.hasPrevious || errors.data.nextCursor ? (
            <TablePagination
              label={ui("Run error pages")}
              page={paging.page}
              totalPages={undefined}
              previousLabel={ui("Previous errors")}
              nextLabel={ui("Next errors")}
              previousDisabled={!paging.hasPrevious || errors.isPlaceholderData}
              nextDisabled={!errors.data.nextCursor || errors.isPlaceholderData || errors.isError}
              onPrevious={paging.goPrevious}
              onNext={() => paging.goNext(errors.data?.nextCursor)}
            />
          ) : null}
        </>
      ) : null}
    </div>
  );
}

function RunErrorRow({ error }: { error: SourceRunError }) {
  const ui = useAppTranslation();
  const name = error.fileName ?? error.fileId ?? ui("Source execution");
  // A later run acquired the file again, so the error no longer needs attention (Onyx resolves it the same way).
  const resolved = error.resolvedAt !== null;
  const text = resolved ? "text-content-muted" : "text-content-primary";
  return (
    <ExpandableRow
      label={ui("Error details for {{v1}}", { v1: name })}
      summary={
        <span className="min-w-0">
          <span className="flex min-w-0 flex-wrap items-center gap-x-3 gap-y-1">
            <span
              className={cn(
                "min-w-0 break-words font-medium whitespace-pre-wrap wrap-anywhere",
                text,
              )}
            >
              {name}
            </span>
            {resolved ? (
              <StatusBadge tone="success" variant="pill">
                <CircleCheck aria-hidden="true" />
                {ui("Resolved")}
              </StatusBadge>
            ) : (
              <CurrentFileStateBadge error={error} />
            )}
          </span>
          <span className="mt-0.5 block text-xs text-content-muted">
            {ui(runErrorStages[error.stage])}
            {" · "}
            <HistoryTime value={error.occurredAt} />
          </span>
          <span className={cn("mt-1 block text-sm leading-relaxed wrap-anywhere", text)}>
            {error.errorMessage ?? runErrorMessage(ui, error.code)}
          </span>
        </span>
      }
    >
      <dl className="grid gap-3 text-xs sm:grid-cols-2">
        <div>
          <dt className="text-content-muted">{ui("Stage")}</dt>
          <dd className="mt-1">{ui(runErrorStages[error.stage])}</dd>
        </div>
        <div>
          <dt className="text-content-muted">{ui("Occurred at")}</dt>
          <dd className="mt-1">
            <HistoryTime value={error.occurredAt} />
          </dd>
        </div>
        {error.resolvedAt ? (
          <div>
            <dt className="text-content-muted">{ui("Resolved at")}</dt>
            <dd className="mt-1">
              <HistoryTime value={error.resolvedAt} />
            </dd>
          </div>
        ) : null}
        <div>
          <dt className="text-content-muted">{ui("Error code")}</dt>
          <dd className="mt-1 select-text wrap-anywhere">
            <code>{error.code}</code>
          </dd>
        </div>
        <div>
          <dt className="text-content-muted">{ui("Operation ID")}</dt>
          <dd className="mt-1 select-text wrap-anywhere">
            <code>{error.operationId ?? ui("Not recorded")}</code>
          </dd>
        </div>
        <div>
          <dt className="text-content-muted">{ui("Run ID")}</dt>
          <dd className="mt-1 select-text wrap-anywhere">
            <code>{error.runId}</code>
          </dd>
        </div>
        {error.fileId ? (
          <div>
            <dt className="text-content-muted">{ui("File ID")}</dt>
            <dd className="mt-1 select-text wrap-anywhere">
              <code>{error.fileId}</code>
            </dd>
          </div>
        ) : null}
      </dl>
      {error.errorDetail ? (
        <div className="mt-3">
          <p className="text-xs text-content-muted">{ui("Technical details")}</p>
          <pre className="mt-1 max-h-64 overflow-auto rounded-md bg-surface-sunken p-3 text-xs whitespace-pre-wrap wrap-anywhere text-content-secondary select-text">
            {error.errorDetail}
          </pre>
        </div>
      ) : null}
      <div className="mt-3">
        <p className="text-xs text-content-muted">{ui("Current state")}</p>
        <div className="mt-1">
          {error.itemId || error.fileId || error.fileName ? (
            <CurrentFileState error={error} />
          ) : (
            <span className="text-content-muted">{ui("Not recorded")}</span>
          )}
        </div>
      </div>
    </ExpandableRow>
  );
}

function CurrentFileStateBadge({ error }: { error: SourceRunError }) {
  const ui = useAppTranslation();
  const status = error.currentItemStatus;
  const tone =
    status === "INDEXED"
      ? "success"
      : status === "FAILED"
        ? "danger"
        : status === "PENDING"
          ? "info"
          : "neutral";
  return (
    <StatusBadge tone={tone} variant="pill">
      {ui(
        status === "INDEXED"
          ? "Indexed"
          : status === "FAILED"
            ? "Failed"
            : status === "PENDING"
              ? "Pending"
              : status === "DELETING"
                ? "Deleting"
                : "Unknown",
      )}
    </StatusBadge>
  );
}

function CurrentFileState({ error }: { error: SourceRunError }) {
  const ui = useAppTranslation();
  const status = error.currentItemStatus;
  const indexedSinceError =
    status === "INDEXED" &&
    error.currentItemLastIndexedAt !== null &&
    new Date(error.currentItemLastIndexedAt).getTime() > new Date(error.occurredAt).getTime();
  return (
    <div className="flex flex-col items-start gap-1">
      <CurrentFileStateBadge error={error} />
      <p className="text-xs text-content-muted">
        {status === "INDEXED"
          ? indexedSinceError
            ? ui("Content indexed since this error.")
            : ui("Current content is indexed.")
          : status === "PENDING"
            ? ui("Indexing is pending or in progress.")
            : status === "DELETING"
              ? ui("Removal is in progress.")
              : status === "FAILED"
                ? error.currentItemErrorCode && error.currentItemErrorCode !== error.code
                  ? ui(sourceStatusMessage(error.currentItemErrorCode))
                  : ui("This file still needs attention.")
                : error.itemId
                  ? ui("Current file state is unavailable; the file may have been removed.")
                  : ui("No current file is linked to this error.")}
        {status === "INDEXED" && error.currentItemLastIndexedAt ? (
          <>
            {" "}
            {ui("Last indexed")} <HistoryTime value={error.currentItemLastIndexedAt} />
          </>
        ) : null}
      </p>
    </div>
  );
}
