import { useQuery } from "@tanstack/react-query";
import { useState } from "react";
import { CheckCircle2, ChevronLeft, ChevronRight, RefreshCw } from "lucide-react";
import { Button } from "@/components/ui/button";
import { HelpPopover } from "@/components/ui/help-popover";
import { Select } from "@/components/ui/select";
import { StatusBadge } from "@/components/ui/status-badge";
import { listSourceIndexAttemptsOptions } from "@/lib/hey-api/@tanstack/react-query.gen";
import { terminalOperationStatuses } from "./source-operations";
import { sourceStatusMessage } from "./source-errors";
import { historyDuration } from "./source-history";
import { HistoryTime } from "./source-history-presentation";

export function SourceItemHistory({ sourceId }: { sourceId: string }) {
  const [open, setOpen] = useState(false);
  const [size, setSize] = useState(5);
  const [cursor, setCursor] = useState<string>();
  const [previous, setPrevious] = useState<Array<string | undefined>>([]);
  const history = useQuery({
    ...listSourceIndexAttemptsOptions({ path: { sourceId }, query: { size, cursor } }),
    enabled: open,
    retry: false,
    refetchInterval: (query) =>
      query.state.data?.items.some(
        (operation) => !Object.hasOwn(terminalOperationStatuses, operation.status),
      )
        ? 1_500
        : false,
  });
  return (
    <details
      className="relative mt-6 min-w-0 border-t border-border-subtle pt-4"
      onToggle={(event) => setOpen(event.currentTarget.open)}
    >
      <summary className="min-h-11 cursor-pointer py-3 pr-24 font-heading-h3 text-content-primary focus-visible:outline-2 focus-visible:outline-focus-ring">
        <span className="inline-flex items-center gap-2 align-middle">
          <span>File indexing attempts</span>
          <HelpPopover label="File indexing attempts">
            <p>
              Each row processes one file version, including manual reindexing. Files above is the
              current corpus; this history records individual file outcomes. Queued time is shown
              only when the actual processing start was not recorded.
            </p>
          </HelpPopover>
        </span>
      </summary>
      {open ? (
        <section aria-label="File indexing attempts" className="min-w-0 space-y-2">
          <div className="absolute right-0 top-6 flex items-center gap-1">
            <Button
              size="sm"
              prominence="tertiary"
              pending={history.isFetching}
              onClick={() => void history.refetch()}
            >
              <RefreshCw aria-hidden="true" /> Refresh
            </Button>
          </div>
          {history.isError ? (
            <p role="alert" className="text-sm text-status-danger-content">
              File attempts could not be refreshed. Displayed attempts may be out of date.
            </p>
          ) : history.isPending ? (
            <p role="status" className="text-sm text-content-muted">
              Loading file attempts…
            </p>
          ) : null}
          {history.data ? (
            <>
              <div
                role="region"
                aria-label="File indexing attempt records"
                tabIndex={0}
                className="overflow-x-auto outline-none focus-visible:ring-2 focus-visible:ring-inset focus-visible:ring-focus-ring"
              >
                <table className="w-full min-w-[40rem] border-collapse text-left text-sm">
                  <caption className="sr-only">File indexing attempts, newest first</caption>
                  <thead className="border-b border-border-subtle text-xs text-content-muted">
                    <tr>
                      <th scope="col" className="px-3 py-2 font-normal">
                        File
                      </th>
                      <th scope="col" className="px-3 py-2 font-normal">
                        Started
                      </th>
                      <th scope="col" className="px-3 py-2 font-normal">
                        Status
                      </th>
                      <th scope="col" className="px-3 py-2 font-normal">
                        Completed / duration
                      </th>
                      <th scope="col" className="px-3 py-2 font-normal">
                        Error message
                      </th>
                    </tr>
                  </thead>
                  <tbody className="divide-y divide-border-subtle">
                    {history.data.items.map((operation) => (
                      <tr key={operation.id} className="align-middle hover:bg-surface-base">
                        <td className="whitespace-nowrap px-3 py-3 font-medium text-content-primary">
                          <span className="break-words">
                            {operation.filename ?? "File name unavailable"}
                          </span>
                        </td>
                        <td className="whitespace-nowrap px-3 py-3 text-content-secondary">
                          {operation.startedAt ? (
                            <HistoryTime value={operation.startedAt} />
                          ) : (
                            <>
                              <span className="block text-xs text-content-muted">
                                Queued · start not recorded
                              </span>
                              <HistoryTime value={operation.createdAt} />
                            </>
                          )}
                        </td>
                        <td className="px-3 py-3">
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
                              ? "Indexed"
                              : operation.status.charAt(0) +
                                operation.status.slice(1).toLowerCase()}
                          </StatusBadge>
                        </td>
                        <td className="whitespace-nowrap px-3 py-3 text-content-secondary">
                          {operation.completedAt ? (
                            <>
                              <HistoryTime value={operation.completedAt} />
                              <span className="block text-xs text-content-muted">
                                {historyDuration(operation.startedAt, operation.completedAt) ??
                                  "Duration unknown"}
                              </span>
                            </>
                          ) : (
                            "—"
                          )}
                        </td>
                        <td className="max-w-sm px-3 py-3 text-xs text-content-muted">
                          {operation.errorCode ? (
                            <span className="break-words text-status-danger-content">
                              {sourceStatusMessage(operation.errorCode)}
                            </span>
                          ) : (
                            "—"
                          )}
                        </td>
                      </tr>
                    ))}
                    {!history.data.items.length ? (
                      <tr>
                        <td colSpan={5} className="px-3 py-6 text-center text-content-muted">
                          No file indexing attempts on this page.
                        </td>
                      </tr>
                    ) : null}
                  </tbody>
                </table>
              </div>
              <nav
                aria-label="Indexing attempt pages"
                className="flex flex-wrap items-center justify-between gap-3 border-t border-border-subtle px-3 py-3 text-xs text-content-muted"
              >
                <label className="flex items-center gap-2">
                  Rows
                  <Select
                    aria-label="Rows per page"
                    size="sm"
                    className="w-auto px-2"
                    value={size}
                    onChange={(event) => {
                      setSize(Number(event.target.value));
                      setCursor(undefined);
                      setPrevious([]);
                    }}
                  >
                    {[5, 10, 25, 50].map((value) => (
                      <option key={value} value={value}>
                        {value}
                      </option>
                    ))}
                  </Select>
                </label>
                <div className="flex items-center gap-2">
                  <span aria-live="polite">
                    {previous.length + 1}/{Math.max(1, Math.ceil(history.data.totalItems / size))}
                  </span>
                  <Button
                    size="sm"
                    prominence="secondary"
                    aria-label="Previous indexing attempts"
                    disabled={!previous.length || history.isFetching}
                    onClick={() => {
                      setCursor(previous.at(-1));
                      setPrevious((pages) => pages.slice(0, -1));
                    }}
                  >
                    <ChevronLeft aria-hidden="true" />
                  </Button>
                  <Button
                    size="sm"
                    prominence="secondary"
                    aria-label="Next indexing attempts"
                    disabled={!history.data.nextCursor || history.isFetching}
                    onClick={() => {
                      setPrevious((pages) => [...pages, cursor]);
                      setCursor(history.data.nextCursor ?? undefined);
                    }}
                  >
                    <ChevronRight aria-hidden="true" />
                  </Button>
                </div>
              </nav>
            </>
          ) : null}
        </section>
      ) : null}
    </details>
  );
}
