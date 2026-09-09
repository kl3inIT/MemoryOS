import { useQuery } from "@tanstack/react-query";
import { useState } from "react";
import { ChevronLeft, ChevronRight, RefreshCw } from "lucide-react";
import { Button } from "@/components/ui/button";
import { HelpPopover } from "@/components/ui/help-popover";
import { Select } from "@/components/ui/select";
import {
  listSourceRunErrorsOptions,
  listSourceRunsOptions,
} from "@/lib/hey-api/@tanstack/react-query.gen";
import type { SourceRun, SourceRunCounts } from "@/lib/hey-api/types.gen";
import { sourceStatusMessage } from "./source-errors";
import { historyDuration, runIsActive } from "./source-history";
import { HistoryTime, RunOutcome } from "./source-history-presentation";

const additionalCounts: Array<[keyof SourceRunCounts, string]> = [
  ["acquired", "Acquired"],
  ["alreadyPending", "Already pending"],
  ["removed", "Removed"],
  ["skipped", "Skipped"],
  ["acquisitionFailed", "Acquisition failed"],
  ["indexingFailed", "Indexing failed"],
  ["indexingPending", "Indexing pending"],
  ["indexingSuperseded", "Superseded"],
  ["indexingCancelled", "Cancelled"],
];

export function SourceRunHistory({ sourceId }: { sourceId: string }) {
  const [open, setOpen] = useState(false);
  const [size, setSize] = useState(5);
  const [cursor, setCursor] = useState<string>();
  const [previous, setPrevious] = useState<Array<string | undefined>>([]);
  const history = useQuery({
    ...listSourceRunsOptions({ path: { sourceId }, query: { size, cursor } }),
    enabled: open,
    retry: false,
    refetchInterval: open ? 5_000 : false,
  });
  return (
    <details
      className="relative mt-6 min-w-0 border-t border-border-subtle pt-4"
      onToggle={(event) => setOpen(event.currentTarget.open)}
    >
      <summary className="min-h-11 cursor-pointer py-3 pr-24 font-heading-h3 text-content-primary focus-visible:outline-2 focus-visible:outline-focus-ring">
        <span className="inline-flex items-center gap-2 align-middle">
          <span>Indexing attempts</span>
          <HelpPopover label="Source indexing attempts">
            <p>
              One row per Source execution, not per file. Files above is the current corpus; these
              counts describe each execution.
            </p>
            <p>
              Checked counts distinct files observed, Indexed counts successful publications (new or
              replaced), and Unchanged counts files needing no new indexing. Counts can overlap and
              are not a corpus total. Unknown means not recorded.
            </p>
            <p>
              Completed includes acquisition and this run’s indexing. Already pending belongs to
              earlier work; completion does not guarantee that every file is searchable.
            </p>
          </HelpPopover>
        </span>
      </summary>
      {open ? (
        <section aria-label="Source indexing attempts" className="min-w-0 space-y-2">
          <div className="absolute right-0 top-6">
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
              Source attempts could not be refreshed. Displayed outcomes may be out of date. Retry
              with Refresh.
            </p>
          ) : history.isPending ? (
            <p role="status" className="text-sm text-content-muted">
              Loading source attempts…
            </p>
          ) : null}
          {history.data ? (
            <>
              <div
                role="region"
                aria-label="Source execution records"
                tabIndex={0}
                className="overflow-x-auto outline-none focus-visible:ring-2 focus-visible:ring-inset focus-visible:ring-focus-ring"
              >
                <table className="w-full min-w-[58rem] border-collapse text-left text-sm">
                  <caption className="sr-only">Source indexing attempts, newest first</caption>
                  <thead className="border-b border-border-subtle text-xs text-content-muted">
                    <tr>
                      {[
                        "Started",
                        "Outcome",
                        "Checked",
                        "Indexed",
                        "Unchanged",
                        "Completed / duration",
                        "Errors",
                      ].map((heading) => (
                        <th key={heading} scope="col" className="px-3 py-2 font-normal">
                          {heading}
                        </th>
                      ))}
                    </tr>
                  </thead>
                  <tbody className="divide-y divide-border-subtle">
                    {history.data.items.map((run) => (
                      <SourceRunRow key={run.id} run={run} />
                    ))}
                    {!history.data.items.length ? (
                      <tr>
                        <td colSpan={7} className="px-3 py-6 text-center text-content-muted">
                          No Source executions on this page.
                        </td>
                      </tr>
                    ) : null}
                  </tbody>
                </table>
              </div>
              <nav
                aria-label="Source attempt pages"
                className="flex flex-wrap items-center justify-between gap-3 border-t border-border-subtle px-3 py-3 text-xs text-content-muted"
              >
                <label className="flex items-center gap-2">
                  Rows{" "}
                  <Select
                    aria-label="Source attempts per page"
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
                  <span aria-live="polite">Page {previous.length + 1}</span>
                  <Button
                    size="sm"
                    prominence="secondary"
                    aria-label="Previous source attempts"
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
                    aria-label="Next source attempts"
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

function SourceRunRow({ run }: { run: SourceRun }) {
  const duration = historyDuration(run.startedAt, run.completedAt);
  const relevantCounts = additionalCounts.filter(([field]) => run.counts[field] !== 0);
  const hasErrors =
    Boolean(run.errorCode) ||
    run.status === "FAILED" ||
    run.status === "COMPLETED_WITH_ERRORS" ||
    (run.counts.acquisitionFailed ?? 0) > 0 ||
    (run.counts.indexingFailed ?? 0) > 0;
  return (
    <tr className="align-top hover:bg-surface-base">
      <td className="whitespace-nowrap px-3 py-3 text-content-primary">
        <HistoryTime value={run.startedAt} />
      </td>
      <td className="max-w-xs px-3 py-3">
        <RunOutcome run={run} />
        {relevantCounts.length > 0 || run.nextRetryAt ? (
          <details className="mt-1 text-xs text-content-muted">
            <summary className="cursor-pointer py-2 focus-visible:outline-2 focus-visible:outline-focus-ring">
              Details
            </summary>
            <dl>
              {relevantCounts.map(([field, label]) => (
                <div key={field}>
                  <dt className="inline">{label}: </dt>
                  <dd className="inline">{run.counts[field]?.toLocaleString() ?? "Unknown"}</dd>
                </div>
              ))}
            </dl>
            {run.nextRetryAt ? (
              <p className="mt-1">
                Next retry: <HistoryTime value={run.nextRetryAt} />
              </p>
            ) : null}
          </details>
        ) : null}
      </td>
      {["scanned", "published", "unchanged"].map((field) => (
        <td key={field} className="px-3 py-3 tabular-nums text-content-secondary">
          {run.counts[field as keyof SourceRunCounts]?.toLocaleString() ?? "Unknown"}
        </td>
      ))}
      <td className="whitespace-nowrap px-3 py-3 text-content-secondary">
        {run.completedAt && !runIsActive(run) ? (
          <>
            <HistoryTime value={run.completedAt} />
            <span className="ml-2 text-xs text-content-muted">
              {duration ?? "Duration unknown"}
            </span>
          </>
        ) : (
          <span className="text-content-muted">
            {runIsActive(run) ? "In progress" : "Not recorded"}
          </span>
        )}
      </td>
      <td className="max-w-sm px-3 py-3 text-xs text-content-muted">
        {run.errorCode ? (
          <p className="break-words text-status-danger-content">
            {sourceStatusMessage(run.errorCode)}
          </p>
        ) : null}
        {run.detailsExpired ? (
          <p>Detailed errors expired; retained totals are shown.</p>
        ) : hasErrors ? (
          <RunErrors run={run} />
        ) : run.status === "UNKNOWN" ? (
          "Unknown"
        ) : (
          "—"
        )}
      </td>
    </tr>
  );
}

function RunErrors({ run }: { run: SourceRun }) {
  const [open, setOpen] = useState(false);
  const [cursor, setCursor] = useState<string>();
  const [previous, setPrevious] = useState<Array<string | undefined>>([]);
  const errors = useQuery({
    ...listSourceRunErrorsOptions({
      path: { sourceId: run.sourceId, runId: run.id },
      query: { size: 5, cursor },
    }),
    enabled: open,
    retry: false,
    refetchInterval: open && runIsActive(run) ? 5_000 : false,
  });
  return (
    <details onToggle={(event) => setOpen(event.currentTarget.open)}>
      <summary className="min-h-11 cursor-pointer py-3 focus-visible:outline-2 focus-visible:outline-focus-ring">
        Error details
      </summary>
      {open ? (
        <>
          {errors.isError ? (
            <p role="alert">
              Errors could not be loaded.{" "}
              <Button size="sm" prominence="tertiary" onClick={() => void errors.refetch()}>
                Retry
              </Button>
            </p>
          ) : errors.isPending ? (
            <p role="status">Loading errors…</p>
          ) : null}
          {errors.data ? (
            <>
              <ul className="space-y-2">
                {errors.data.items.map((error) => (
                  <li key={error.id} className="break-words">
                    <p className="font-medium text-content-primary">
                      {error.fileName ?? "Source execution"}
                    </p>
                    <p>
                      {error.stage.replaceAll("_", " ").toLowerCase()}:{" "}
                      {sourceStatusMessage(error.code)}
                    </p>
                    <HistoryTime value={error.occurredAt} />
                  </li>
                ))}
              </ul>
              {!errors.data.items.length ? (
                <p>
                  No retained details on this page. Review the Source connection and retry
                  synchronization if the error persists.
                </p>
              ) : null}
              {previous.length || errors.data.nextCursor ? (
                <div className="mt-2 flex gap-2">
                  <Button
                    size="sm"
                    prominence="tertiary"
                    disabled={!previous.length || errors.isFetching}
                    onClick={() => {
                      setCursor(previous.at(-1));
                      setPrevious((pages) => pages.slice(0, -1));
                    }}
                  >
                    Previous errors
                  </Button>
                  <Button
                    size="sm"
                    prominence="tertiary"
                    disabled={!errors.data.nextCursor || errors.isFetching}
                    onClick={() => {
                      setPrevious((pages) => [...pages, cursor]);
                      setCursor(errors.data.nextCursor ?? undefined);
                    }}
                  >
                    Next errors
                  </Button>
                </div>
              ) : null}
            </>
          ) : null}
        </>
      ) : null}
    </details>
  );
}
