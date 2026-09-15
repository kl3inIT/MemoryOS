import {
  Table,
  TableBody,
  TableCaption,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";
import { Collapsible, CollapsibleContent, CollapsibleTrigger } from "@/components/ui/collapsible";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { useQuery } from "@tanstack/react-query";
import { useState } from "react";
import { CheckCircle2, ChevronRight, History, RefreshCw } from "lucide-react";
import { Button } from "@/components/ui/button";
import { HelpPopover } from "@/components/ui/help-popover";
import { PageSizeSelect } from "@/components/ui/page-size-select";
import { StatusBadge } from "@/components/ui/status-badge";
import { TablePagination } from "@/components/ui/table-pagination";
import { listSourceIndexAttemptsOptions } from "@/lib/hey-api/@tanstack/react-query.gen";
import { terminalOperationStatuses } from "./source-operations";
import { sourceStatusMessage } from "./source-errors";
import { historyDuration } from "./source-history";
import { HistoryTime } from "./source-history-presentation";
import { SourceSectionIcon } from "./source-section-icon";

export function SourceItemHistory({ sourceId }: { sourceId: string }) {
  const ui = useAppTranslation();

  const [open, setOpen] = useState(false);
  const [size, setSize] = useState(5);
  const [cursor, setCursor] = useState<string>();
  const [previous, setPrevious] = useState<Array<string | undefined>>([]);
  const history = useQuery({
    ...listSourceIndexAttemptsOptions({ path: { sourceId }, query: { size, cursor } }),
    enabled: open,
    retry: false,
    staleTime: 0,
    refetchInterval: (query) =>
      query.state.data?.items.some(
        (operation) => !Object.hasOwn(terminalOperationStatuses, operation.status),
      )
        ? 1_500
        : false,
  });
  const totalPages = history.data ? Math.ceil(history.data.totalItems / size) : undefined;
  if (totalPages !== undefined && previous.length >= Math.max(totalPages, 1)) {
    setCursor(undefined);
    setPrevious([]);
  }

  return (
    <Collapsible
      className="group/history relative mt-8 min-w-0 border-t border-border-subtle pt-6"
      onOpenChange={setOpen}
    >
      <CollapsibleTrigger className="min-h-10 cursor-pointer list-none pr-24 text-content-primary focus-visible:outline-2 focus-visible:outline-focus-ring [&::-webkit-details-marker]:hidden">
        <h2 className="inline-flex items-center gap-3 align-middle font-heading-h3">
          <SourceSectionIcon icon={History} />
          <span>{ui("File indexing attempts")}</span>
          <ChevronRight
            className="size-4 shrink-0 group-open/history:rotate-90"
            aria-hidden="true"
          />
          <HelpPopover label={ui("File indexing attempts")}>
            <p>
              {ui(
                "Each row processes one file version, including manual reindexing. Files above is the current corpus; this history records individual file outcomes. Queued time is shown only when the actual processing start was not recorded.",
              )}
            </p>
          </HelpPopover>
        </h2>
      </CollapsibleTrigger>
      <CollapsibleContent>
        {open ? (
          <section aria-label={ui("File indexing attempts")} className="mt-4 min-w-0 space-y-2">
            <div className="absolute right-0 top-7 flex items-center gap-1">
              <Button
                size="sm"
                prominence="tertiary"
                pending={history.isFetching}
                onClick={() => void history.refetch()}
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
                  <Table className="w-full min-w-[40rem] border-collapse text-left text-sm">
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
                      </TableRow>
                    </TableHeader>
                    <TableBody className="divide-y divide-border-subtle">
                      {history.data.items.map((operation) => (
                        <TableRow key={operation.id} className="align-middle hover:bg-surface-base">
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
                            <StatusBadge
                              className="gap-1"
                              tone={
                                operation.status === "SUCCEEDED"
                                  ? "success"
                                  : operation.status === "FAILED"
                                    ? "danger"
                                    : Object.hasOwn(terminalOperationStatuses, operation.status)
                                      ? "neutral"
                                      : "info"
                              }
                            >
                              {operation.status === "SUCCEEDED" ? (
                                <CheckCircle2 className="size-3" aria-hidden="true" />
                              ) : null}
                              {operation.status === "SUCCEEDED"
                                ? ui("Indexed")
                                : ui(statusLabel(operation.status))}
                            </StatusBadge>
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
                              <span className="break-words text-status-danger-content">
                                {ui(sourceStatusMessage(operation.errorCode))}
                              </span>
                            ) : (
                              "—"
                            )}
                          </TableCell>
                        </TableRow>
                      ))}
                      {!history.data.items.length ? (
                        <TableRow>
                          <TableCell
                            colSpan={5}
                            className="px-3 py-6 text-center text-content-muted"
                          >
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
                  previousDisabled={!previous.length || history.isFetching}
                  nextDisabled={!history.data.nextCursor || history.isFetching || history.isError}
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
                    disabled={history.isFetching}
                    onSizeChange={(next) => {
                      setSize(next);
                      setCursor(undefined);
                      setPrevious([]);
                    }}
                  />
                </TablePagination>
              </>
            ) : null}
          </section>
        ) : null}
      </CollapsibleContent>
    </Collapsible>
  );
}
import { statusLabel } from "@/i18n/status-copy";
