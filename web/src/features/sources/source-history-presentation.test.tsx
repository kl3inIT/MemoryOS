import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { cleanup, fireEvent, render, screen, within } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import type { SourceIndexAttempt, SourceItem, SourceRun } from "@/lib/hey-api/types.gen";
import { listSourceRunsQueryKey } from "@/lib/hey-api/@tanstack/react-query.gen";
import { historyDuration, runHasNoChanges } from "./source-history";
import { HistoryTime, ItemStatus, RunOutcome } from "./source-history-presentation";
import { SourceRunHistory } from "./source-run-history";

const run: SourceRun = {
  id: "run-a",
  sourceId: "source-a",
  trigger: "MANUAL",
  actorId: null,
  status: "SUCCEEDED",
  acquisitionStatus: "SUCCEEDED",
  indexingStatus: "NOT_REQUIRED",
  createdAt: "2026-09-09T09:00:00Z",
  startedAt: "2026-09-09T09:01:00Z",
  acquisitionCompletedAt: "2026-09-09T09:01:30Z",
  completedAt: "2026-09-09T09:01:30Z",
  scopeRevision: 1,
  credentialRevision: 1,
  nextRetryAt: null,
  errorCode: null,
  detailsExpired: false,
  counts: {
    scanned: 3,
    acquired: 0,
    published: 0,
    unchanged: 3,
    alreadyPending: 0,
    acquisitionFailed: 0,
    indexingFailed: 0,
    skipped: 0,
    removed: 0,
    indexingPending: 0,
    indexingSuperseded: 0,
    indexingCancelled: 0,
  },
};
const clients: QueryClient[] = [];
afterEach(() => {
  cleanup();
  for (const client of clients) client.clear();
  clients.length = 0;
  vi.unstubAllGlobals();
});

function showHistory(items: SourceRun[]) {
  const historyPage = {
    items,
    nextCursor: null,
    totalItems: items.length,
    current: null,
    lastCompleted: null,
    lastSuccessful: null,
  };
  vi.stubGlobal(
    "fetch",
    vi.fn(async () => Response.json(historyPage)),
  );
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  clients.push(client);
  client.setQueryData(
    listSourceRunsQueryKey({
      path: { sourceId: run.sourceId },
      query: { size: 5, cursor: undefined },
    }),
    historyPage,
  );
  const result = render(
    <QueryClientProvider client={client}>
      <SourceRunHistory sourceId={run.sourceId} />
    </QueryClientProvider>,
  );
  const details = result.container.querySelector("details")!;
  details.open = true;
  fireEvent(details, new Event("toggle"));
  return result;
}

describe("Source execution and current-file history", () => {
  it("shows unchanged source runs with real per-run counters rather than new-document or corpus totals", () => {
    showHistory([run]);
    const table = screen.getByRole("table");
    const cells = within(within(table).getAllByRole("row")[1]).getAllByRole("cell");
    expect(cells[1]).toHaveTextContent("No changes");
    expect(cells[2]).toHaveTextContent(/^3$/);
    expect(cells[3]).toHaveTextContent(/^0$/);
    expect(cells[4]).toHaveTextContent(/^3$/);
    expect(
      within(table).queryByRole("columnheader", { name: /New Docs|Total Docs/ }),
    ).not.toBeInTheDocument();
    expect(cells[5]).toHaveTextContent("30s");
  });

  it("does not turn missing legacy counters into zero or infer a no-change success", () => {
    const legacy: SourceRun = {
      ...run,
      status: "UNKNOWN",
      indexingStatus: "UNKNOWN",
      startedAt: null,
      completedAt: null,
      counts: { ...run.counts, scanned: null, published: null, unchanged: null },
    };
    showHistory([legacy]);
    const cells = within(screen.getAllByRole("row")[1]).getAllByRole("cell");
    expect(cells[2]).toHaveTextContent(/^Unknown$/);
    expect(cells[3]).toHaveTextContent(/^Unknown$/);
    expect(cells[4]).toHaveTextContent(/^Unknown$/);
    expect(screen.queryByText("No changes")).not.toBeInTheDocument();
    expect(cells[5]).toHaveTextContent("Not recorded");
  });

  it("does not call a successful acquisition complete when indexing is still pending", () => {
    const pending: SourceRun = {
      ...run,
      status: "INDEXING",
      indexingStatus: "PENDING",
      completedAt: null,
      counts: { ...run.counts, acquired: 1, indexingPending: 1 },
    };
    showHistory([pending]);
    const cells = within(screen.getAllByRole("row")[1]).getAllByRole("cell");
    expect(cells[5]).toHaveTextContent("In progress");
    expect(screen.queryByText("No changes")).not.toBeInTheDocument();
    expect(screen.queryByText("Completed", { exact: true })).not.toBeInTheDocument();
  });

  it("preserves failures and refuses no-change claims when prior work or unknown outcomes remain", () => {
    render(
      <RunOutcome
        run={{
          ...run,
          status: "COMPLETED_WITH_ERRORS",
          indexingStatus: "COMPLETED_WITH_ERRORS",
          counts: { ...run.counts, indexingFailed: 1 },
        }}
      />,
    );
    expect(screen.getByText("Completed with errors")).toBeInTheDocument();
    expect(runHasNoChanges({ ...run, counts: { ...run.counts, alreadyPending: 1 } })).toBe(false);
    expect(runHasNoChanges({ ...run, counts: { ...run.counts, skipped: null } })).toBe(false);
    expect(runHasNoChanges({ ...run, counts: { ...run.counts, removed: 1 } })).toBe(false);
  });

  it("exposes full local times accessibly and never measures processing duration from queued fallback", () => {
    const result = render(<HistoryTime value={run.startedAt} />);
    const time = result.container.querySelector("time")!;
    const full = new Date(run.startedAt!).toLocaleString(undefined, {
      dateStyle: "full",
      timeStyle: "long",
    });
    expect(time).toHaveAttribute("aria-label", full);
    expect(time).toHaveAttribute("dateTime", run.startedAt);
    expect(historyDuration(null, run.completedAt)).toBeNull();
    expect(historyDuration(run.completedAt, run.startedAt)).toBeNull();
    expect(historyDuration(run.startedAt, run.completedAt)).toBe("30s");
  });
});

describe("Current file processing status", () => {
  const attempt: SourceIndexAttempt = {
    id: "attempt-a",
    filename: "report.pdf",
    status: "NOT_STARTED",
    createdAt: "2026-09-09T09:00:00Z",
    startedAt: null,
    completedAt: null,
    errorCode: null,
  };

  it("distinguishes queued retries from active processing without using the retained first start", () => {
    const item = {
      status: "PENDING",
      searchStatus: "READY" as const,
      latestAttempt: {
        ...attempt,
        startedAt: "2026-09-09T09:01:00Z",
        errorCode: "EXTRACTION_FAILED",
      },
    };
    const { rerender } = render(<ItemStatus item={item} />);
    expect(screen.getByText("Queued", { exact: true })).toBeInTheDocument();
    expect(screen.getByText("Search index: Ready")).toBeInTheDocument();
    expect(screen.queryByText("Processing", { exact: true })).not.toBeInTheDocument();

    rerender(
      <ItemStatus
        item={{ ...item, latestAttempt: { ...item.latestAttempt, status: "IN_PROGRESS" } }}
      />,
    );
    expect(screen.getByText("Processing", { exact: true })).toBeInTheDocument();
    expect(screen.queryByText("Queued", { exact: true })).not.toBeInTheDocument();
  });

  it("does not invent queued work when a pending file has no retained attempt", () => {
    render(
      <ItemStatus item={{ status: "PENDING", searchStatus: "WAITING", latestAttempt: null }} />,
    );
    expect(screen.getByText("Pending", { exact: true })).toBeInTheDocument();
    expect(screen.queryByText(/Queued|Processing|Indexed/)).not.toBeInTheDocument();
  });

  it("does not promote a pending current version from a successful older attempt", () => {
    render(
      <ItemStatus
        item={{
          status: "PENDING",
          searchStatus: "READY",
          latestAttempt: { ...attempt, status: "SUCCEEDED" },
        }}
      />,
    );
    expect(screen.getByText("Pending", { exact: true })).toBeInTheDocument();
    expect(screen.getByText("Latest attempt: Indexed")).toBeInTheDocument();
    expect(screen.queryByText("Indexed", { exact: true })).not.toBeInTheDocument();
  });

  it("keeps retained indexed content separate from a failed reprocessing attempt", () => {
    render(
      <ItemStatus
        item={{
          status: "INDEXED",
          searchStatus: "READY",
          latestAttempt: { ...attempt, status: "FAILED" },
        }}
      />,
    );
    expect(screen.getByText("Indexed", { exact: true })).toBeInTheDocument();
    expect(screen.getByText("Latest attempt: Failed")).toBeInTheDocument();
    expect(screen.getByText("Search index: Ready")).toBeInTheDocument();
  });

  it("keeps deletion authoritative while exposing the latest processing fact separately", () => {
    render(
      <ItemStatus
        item={{
          status: "DELETING",
          searchStatus: "WAITING",
          latestAttempt: { ...attempt, status: "IN_PROGRESS" },
        }}
      />,
    );
    expect(screen.getByText("Deleting", { exact: true })).toBeInTheDocument();
    expect(screen.getByText("Latest attempt: Processing")).toBeInTheDocument();
    expect(screen.queryByText("Processing", { exact: true })).not.toBeInTheDocument();
  });

  it("preserves unknown states and does not claim a failed downstream index has a scheduled retry", () => {
    const item = {
      status: "UNKNOWN",
      searchStatus: "FAILED" as const,
      latestAttempt: { ...attempt, status: "UNKNOWN" },
    };
    const { rerender } = render(<ItemStatus item={item} />);
    expect(screen.getByText("Unknown", { exact: true })).toBeInTheDocument();
    expect(screen.getByText("Search index: Failed")).toBeInTheDocument();
    expect(screen.queryByText(/Queued|Processing|Indexed|scheduled/i)).not.toBeInTheDocument();

    rerender(
      <ItemStatus item={{ ...item, searchStatus: "UNKNOWN" as SourceItem["searchStatus"] }} />,
    );
    expect(screen.getByText("Search index: Unknown")).toBeInTheDocument();
    expect(screen.queryByText(/Ready|Waiting|scheduled/i)).not.toBeInTheDocument();
  });
});
