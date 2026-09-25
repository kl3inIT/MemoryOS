import { keepPreviousData, useQuery } from "@tanstack/react-query";
import { CheckCircle2, History, RefreshCw } from "lucide-react";
import { useRef, useState } from "react";
import { Button } from "@/components/ui/button";
import { HelpPopover } from "@/components/ui/help-popover";
import { PageSizeSelect } from "@/components/ui/page-size-select";
import {
  Sheet,
  SheetContent,
  SheetDescription,
  SheetHeader,
  SheetTitle,
} from "@/components/ui/sheet";
import { StatusBadge } from "@/components/ui/status-badge";
import {
  Table,
  TableBody,
  TableCaption,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";
import { TablePagination } from "@/components/ui/table-pagination";
import { uiLocale } from "@/i18n/format";
import { statusLabel } from "@/i18n/status-copy";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { listSourceIndexAttemptsOptions } from "@/lib/hey-api/@tanstack/react-query.gen";
import type { SourceIndexAttempt } from "@/lib/hey-api/types.gen";
import { useManualRefresh } from "@/lib/use-manual-refresh";
import { sourceStatusMessage } from "@/features/sources/shared/source-errors";
import { historyDuration } from "./source-history";
import { HistoryTime } from "./source-history-presentation";
import { terminalOperationStatuses } from "@/features/sources/shared/source-operations";
import { SourceSectionIcon } from "@/features/sources/shared/source-section-icon";

/** File indexing attempts of a Source; it loads when its section tab opens. */
export function SourceItemHistory({ sourceId }: { sourceId: string }) {
  const ui = useAppTranslation();

  const [size, setSize] = useState(5);
  const [cursor, setCursor] = useState<string>();
  const [previous, setPrevious] = useState<Array<string | undefined>>([]);
  const [detail, setDetail] = useState<SourceIndexAttempt | null>(null);
  const [detailOpen, setDetailOpen] = useState(false);
  const detailOpener = useRef<HTMLElement | null>(null);
  const history = useQuery({
    ...listSourceIndexAttemptsOptions({ path: { sourceId }, query: { size, cursor } }),
    retry: false,
    staleTime: 0,
    placeholderData: keepPreviousData,
    refetchInterval: (query) =>
      query.state.data?.items.some((operation) => !attemptFinished(operation)) ? 1_500 : false,
  });
  // An open attempt is polled, so the refresh control follows the press rather than the poll.
  const manualRefresh = useManualRefresh(history.refetch);
  const totalPages = history.data ? Math.ceil(history.data.totalItems / size) : undefined;
  // Polling keeps the open attempt current while it stays on the displayed page.
  const detailAttempt =
    detail && (history.data?.items.find((attempt) => attempt.id === detail.id) ?? detail);
  if (totalPages !== undefined && previous.length >= Math.max(totalPages, 1)) {
    setCursor(undefined);
    setPrevious([]);
  }

  return (
    <section aria-label={ui("File indexing attempts")} className="min-w-0 space-y-4">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div className="flex items-center gap-3">
          <SourceSectionIcon icon={History} />
          <h2 className="font-heading-h3 text-content-primary">{ui("File indexing attempts")}</h2>
          <HelpPopover label={ui("File indexing attempts")}>
            <p>
              {ui(
                "Each row processes one file version, including manual reindexing. The Files tab shows the current corpus; this history records individual file outcomes. Queued time is shown only when the actual processing start was not recorded.",
              )}
            </p>
          </HelpPopover>
        </div>
        <Button
          size="sm"
          prominence="tertiary"
          pending={manualRefresh.pending}
          onClick={manualRefresh.refresh}
        >
          <RefreshCw aria-hidden="true" /> {ui("Refresh")}
        </Button>
      </div>
      {history.isError ? (
        <p role="alert" className="text-sm text-status-danger-content">
          {ui("File attempts could not be refreshed. Displayed attempts may be out of date.")}
        </p>
      ) : history.isPending ? (
        <p role="status" className="text-sm text-content-muted">
          {ui("Loading file attempts…")}
        </p>
      ) : null}
      {history.data ? (
        <>
          <div
            role="region"
            aria-label={ui("File indexing attempt records")}
            tabIndex={0}
            className="overflow-x-auto outline-none focus-visible:ring-2 focus-visible:ring-inset focus-visible:ring-focus-ring"
          >
            <Table className="w-full min-w-[46rem] border-collapse text-left text-sm">
              <TableCaption className="sr-only">
                {ui("File indexing attempts, newest first")}
              </TableCaption>
              <TableHeader className="border-b border-border-subtle text-xs text-content-muted">
                <TableRow>
                  <TableHead scope="col" className="px-3 py-2 font-normal">
                    {ui("File")}
                  </TableHead>
                  <TableHead scope="col" className="px-3 py-2 font-normal">
                    {ui("Started")}
                  </TableHead>
                  <TableHead scope="col" className="px-3 py-2 font-normal">
                    {ui("Status")}
                  </TableHead>
                  <TableHead scope="col" className="px-3 py-2 font-normal">
                    {ui("Completed / duration")}
                  </TableHead>
                  <TableHead scope="col" className="px-3 py-2 font-normal">
                    {ui("Error message")}
                  </TableHead>
                  <TableHead scope="col" className="px-3 py-2 text-right font-normal">
                    <span className="sr-only">{ui("Indexing attempt details")}</span>
                  </TableHead>
                </TableRow>
              </TableHeader>
              <TableBody className="divide-y divide-border-subtle">
                {history.data.items.map((operation) => (
                  <TableRow
                    key={operation.id}
                    data-state={detailOpen && detail?.id === operation.id ? "selected" : undefined}
                    className="align-middle hover:bg-surface-base"
                  >
                    <TableCell className="whitespace-nowrap px-3 py-3 font-medium text-content-primary">
                      <span className="break-words">
                        {operation.filename ?? ui("File name unavailable")}
                      </span>
                    </TableCell>
                    <TableCell className="whitespace-nowrap px-3 py-3 text-content-secondary">
                      {operation.startedAt ? (
                        <HistoryTime value={operation.startedAt} />
                      ) : (
                        <>
                          <span className="block text-xs text-content-muted">
                            {ui("Queued · start not recorded")}
                          </span>
                          <HistoryTime value={operation.createdAt} />
                        </>
                      )}
                    </TableCell>
                    <TableCell className="px-3 py-3">
                      <AttemptStatus attempt={operation} />
                    </TableCell>
                    <TableCell className="whitespace-nowrap px-3 py-3 text-content-secondary">
                      {operation.completedAt ? (
                        <>
                          <HistoryTime value={operation.completedAt} />
                          <span className="block text-xs text-content-muted">
                            {historyDuration(operation.startedAt, operation.completedAt) ??
                              ui("Duration unknown")}
                          </span>
                        </>
                      ) : (
                        "—"
                      )}
                    </TableCell>
                    <TableCell className="max-w-sm px-3 py-3 text-xs text-content-muted">
                      {operation.errorCode ? (
                        <span className="line-clamp-2 break-words text-status-danger-content">
                          {ui(sourceStatusMessage(operation.errorCode))}
                        </span>
                      ) : (
                        "—"
                      )}
                    </TableCell>
                    <TableCell className="px-3 py-3 text-right">
                      <Button
                        size="sm"
                        prominence="tertiary"
                        aria-label={ui("View details for {{v1}} queued {{v2}}", {
                          v1: operation.filename ?? ui("File name unavailable"),
                          v2: new Date(operation.createdAt).toLocaleString(uiLocale()),
                        })}
                        onClick={(event) => {
                          detailOpener.current = event.currentTarget;
                          setDetail(operation);
                          setDetailOpen(true);
                        }}
                      >
                        {ui("View details")}
                      </Button>
                    </TableCell>
                  </TableRow>
                ))}
                {!history.data.items.length ? (
                  <TableRow>
                    <TableCell colSpan={6} className="px-3 py-6 text-center text-content-muted">
                      {ui("No file indexing attempts on this page.")}
                    </TableCell>
                  </TableRow>
                ) : null}
              </TableBody>
            </Table>
          </div>
          <TablePagination
            label={ui("Indexing attempt pages")}
            page={previous.length}
            totalPages={totalPages}
            previousLabel={ui("Previous indexing attempts")}
            nextLabel={ui("Next indexing attempts")}
            previousDisabled={!previous.length || history.isPlaceholderData}
            nextDisabled={!history.data.nextCursor || history.isPlaceholderData || history.isError}
            onPrevious={() => {
              setCursor(previous.at(-1));
              setPrevious((pages) => pages.slice(0, -1));
            }}
            onNext={() => {
              setPrevious((pages) => [...pages, cursor]);
              setCursor(history.data?.nextCursor ?? undefined);
            }}
          >
            <PageSizeSelect
              label={ui("Rows per page")}
              rowsLabel={ui("Rows")}
              value={size}
              sizes={[5, 10, 25, 50]}
              disabled={history.isPlaceholderData}
              onSizeChange={(next) => {
                setSize(next);
                setCursor(undefined);
                setPrevious([]);
              }}
            />
          </TablePagination>
        </>
      ) : null}
      <Sheet open={detailOpen} onOpenChange={setDetailOpen}>
        <SheetContent
          className="w-full gap-0 overflow-y-auto sm:max-w-xl"
          onCloseAutoFocus={(event) => {
            // Opened without a SheetTrigger, so Radix has no trigger to refocus.
            event.preventDefault();
            detailOpener.current?.focus();
          }}
        >
          <SheetHeader className="border-b border-border-subtle pr-12">
            <SheetTitle className="font-heading-h3 text-content-primary">
              {ui("Indexing attempt details")}
            </SheetTitle>
            <SheetDescription className="break-words">
              {detailAttempt?.filename ?? ui("File name unavailable")}
            </SheetDescription>
          </SheetHeader>
          {detailAttempt ? <AttemptDetails attempt={detailAttempt} /> : null}
        </SheetContent>
      </Sheet>
    </section>
  );
}

function attemptFinished(attempt: SourceIndexAttempt) {
  return Object.hasOwn(terminalOperationStatuses, attempt.status);
}

function AttemptStatus({ attempt }: { attempt: SourceIndexAttempt }) {
  const ui = useAppTranslation();
  const succeeded = attempt.status === "SUCCEEDED";
  return (
    <StatusBadge
      className="gap-1"
      tone={
        succeeded
          ? "success"
          : attempt.status === "FAILED"
            ? "danger"
            : attemptFinished(attempt)
              ? "neutral"
              : "info"
      }
    >
      {succeeded ? <CheckCircle2 className="size-3" aria-hidden="true" /> : null}
      {succeeded ? ui("Indexed") : ui(statusLabel(attempt.status))}
    </StatusBadge>
  );
}

const attemptTimeline = [
  ["Queued", "createdAt"],
  ["Started", "startedAt"],
  ["Completed", "completedAt"],
] as const;

/** One file attempt. Attempts carry no item identity, so reindexing stays in the Files tab. */
function AttemptDetails({ attempt }: { attempt: SourceIndexAttempt }) {
  const ui = useAppTranslation();
  const finished = attemptFinished(attempt);
  return (
    <div className="min-w-0 space-y-4 p-4">
      <div className="flex flex-wrap items-center justify-between gap-3 rounded-lg border border-border-subtle bg-surface-sunken px-4 py-3">
        <AttemptStatus attempt={attempt} />
        <span className="text-sm font-medium tabular-nums text-content-primary">
          {historyDuration(attempt.startedAt, attempt.completedAt) ??
            (finished ? ui("Duration unknown") : ui("In progress"))}
        </span>
      </div>
      <dl className="divide-y divide-border-subtle rounded-lg border border-border-subtle text-sm">
        {attemptTimeline.map(([label, field]) => {
          const value = attempt[field];
          return (
            <div
              key={field}
              className="flex flex-wrap items-center justify-between gap-2 px-4 py-3"
            >
              <dt className="text-content-muted">{ui(label)}</dt>
              <dd className="text-content-primary">
                {value ? (
                  <HistoryTime value={value} />
                ) : (
                  <span className="text-content-muted">
                    {finished ? ui("Not recorded") : ui("Not yet")}
                  </span>
                )}
              </dd>
            </div>
          );
        })}
      </dl>
      {attempt.errorCode ? (
        <section className="rounded-xl bg-status-danger-surface p-4">
          <h3 className="text-sm font-medium text-status-danger-content">{ui("Error message")}</h3>
          <p className="mt-2 text-sm break-words text-content-primary">
            {ui(sourceStatusMessage(attempt.errorCode))}
          </p>
        </section>
      ) : null}
      {attempt.status === "FAILED" ? (
        <p className="text-sm text-content-secondary">
          {ui("To index this file again, use Reindex in the Files tab.")}
        </p>
      ) : null}
      <dl className="space-y-2 text-xs text-content-secondary">
        {attempt.errorCode ? (
          <div>
            <dt>{ui("Error code")}</dt>
            <dd className="mt-1 select-text [overflow-wrap:anywhere]">
              <code>{attempt.errorCode}</code>
            </dd>
          </div>
        ) : null}
        <div>
          <dt>{ui("Attempt ID")}</dt>
          <dd className="mt-1 select-text [overflow-wrap:anywhere]">
            <code>{attempt.id}</code>
          </dd>
        </div>
      </dl>
    </div>
  );
}
