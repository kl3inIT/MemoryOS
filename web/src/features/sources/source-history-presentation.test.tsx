import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { cleanup, fireEvent, render, screen, within } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import type { SourceIndexAttempt, SourceRun, SourceRunError } from "@/lib/hey-api/types.gen";
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

function showHistory(items: SourceRun[], runErrors: SourceRunError[] = []) {
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
    vi.fn(async (request: Request) => {
      if (new URL(request.url).pathname.endsWith("/errors")) {
        return Response.json({ items: runErrors, nextCursor: null });
      }
      const selected = items.find((item) =>
        new URL(request.url).pathname.endsWith(`/runs/${item.id}`),
      );
      return Response.json(selected ?? historyPage);
    }),
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
  return result;
}

describe("Source execution and current-file history", () => {
  it("shows unchanged source runs with real per-run counters rather than new-document or corpus totals", () => {
    showHistory([run]);
    const list = within(screen.getByRole("list", { name: /indexing attempts/i }));
    expect(list.getByText("No changes")).toBeInTheDocument();
    expect(list.getByText("30 sec")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: /View details/ }));
    const detail = within(screen.getByRole("complementary", { name: "Run details" }));
    expect(detail.getByText("Checked").nextElementSibling).toHaveTextContent(/^3$/);
    expect(detail.getByText("Indexed").nextElementSibling).toHaveTextContent(/^0$/);
    expect(detail.getByText("Unchanged").nextElementSibling).toHaveTextContent(/^3$/);
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
    const list = within(screen.getByRole("list", { name: /indexing attempts/i }));
    expect(list.queryByText("No changes")).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: /View details/ }));
    const detail = within(screen.getByRole("complementary", { name: "Run details" }));
    expect(detail.getByText("Checked").nextElementSibling).toHaveTextContent(/^Unknown$/);
    expect(detail.getByText("Indexed").nextElementSibling).toHaveTextContent(/^Unknown$/);
    expect(detail.getByText("Unchanged").nextElementSibling).toHaveTextContent(/^Unknown$/);
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
    const list = within(screen.getByRole("list", { name: /indexing attempts/i }));
    expect(list.getByText("In progress")).toBeInTheDocument();
    expect(list.queryByText("No changes")).not.toBeInTheDocument();
    expect(list.queryByText("Completed", { exact: true })).not.toBeInTheDocument();
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
    expect(historyDuration(run.startedAt, run.completedAt)).toBe("30 sec");
  });
  it("shows the retained provider message and expands to the technical detail", async () => {
    const failedRun: SourceRun = {
      ...run,
      status: "COMPLETED_WITH_ERRORS",
      indexingStatus: "COMPLETED_WITH_ERRORS",
      counts: { ...run.counts, indexingFailed: 1 },
    };
    const failure: SourceRunError = {
      id: "error-a",
      runId: failedRun.id,
      operationId: "op-a",
      itemId: null,
      fileId: "file-1",
      fileName: "report.pdf",
      stage: "EXTRACTION",
      code: "SOURCE_EXTRACTION_TIMEOUT",
      occurredAt: "2026-09-09T09:01:20Z",
      errorMessage: "Extraction timed out after 30s",
      errorDetail: "io.memoryos.ingestion.ExtractionException: timeout\n\tat worker",
      currentItemStatus: "FAILED",
      currentItemErrorCode: "SOURCE_EXTRACTION_TIMEOUT",
      currentItemLastIndexedAt: null,
    };
    showHistory([failedRun], [failure]);
    fireEvent.click(screen.getByRole("button", { name: /View details/ }));
    expect(await screen.findByText("Extraction timed out after 30s")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: /Error details for report\.pdf/ }));
    expect(screen.getByText(/ExtractionException: timeout/)).toBeInTheDocument();
  });

  it("falls back to the stable code translation when no message was retained", async () => {
    const failedRun: SourceRun = {
      ...run,
      status: "COMPLETED_WITH_ERRORS",
      indexingStatus: "COMPLETED_WITH_ERRORS",
      counts: { ...run.counts, indexingFailed: 1 },
    };
    const failure = {
      id: "error-b",
      runId: failedRun.id,
      operationId: null,
      itemId: null,
      fileId: null,
      fileName: null,
      stage: "SYSTEM",
      code: "SOURCE_GOOGLE_UNAVAILABLE",
      occurredAt: "2026-09-09T09:01:20Z",
      errorMessage: null,
      errorDetail: null,
      currentItemStatus: null,
      currentItemErrorCode: null,
      currentItemLastIndexedAt: null,
    } as unknown as SourceRunError;
    showHistory([failedRun], [failure]);
    fireEvent.click(screen.getByRole("button", { name: /View details/ }));
    expect(
      await screen.findByText("Google Drive is temporarily unavailable. Try again later."),
    ).toBeInTheDocument();
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
      latestAttempt: {
        ...attempt,
        startedAt: "2026-09-09T09:01:00Z",
        errorCode: "EXTRACTION_FAILED",
      },
    };
    const { rerender } = render(<ItemStatus item={item} />);
    expect(screen.getByText("Queued", { exact: true })).toBeInTheDocument();
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
    render(<ItemStatus item={{ status: "PENDING", latestAttempt: null }} />);
    expect(screen.getByText("Pending", { exact: true })).toBeInTheDocument();
    expect(screen.queryByText(/Queued|Processing|Indexed/)).not.toBeInTheDocument();
  });

  it("does not promote a pending current version from a successful older attempt", () => {
    render(
      <ItemStatus
        item={{
          status: "PENDING",
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
          latestAttempt: { ...attempt, status: "FAILED" },
        }}
      />,
    );
    expect(screen.getByText("Indexed", { exact: true })).toBeInTheDocument();
    expect(screen.getByText("Latest attempt: Failed")).toBeInTheDocument();
  });

  it("keeps deletion authoritative while exposing the latest processing fact separately", () => {
    render(
      <ItemStatus
        item={{
          status: "DELETING",
          latestAttempt: { ...attempt, status: "IN_PROGRESS" },
        }}
      />,
    );
    expect(screen.getByText("Deleting", { exact: true })).toBeInTheDocument();
    expect(screen.getByText("Latest attempt: Processing")).toBeInTheDocument();
    expect(screen.queryByText("Processing", { exact: true })).not.toBeInTheDocument();
  });

  it("preserves unknown states without inventing queued or successful work", () => {
    const item = {
      status: "UNKNOWN",
      latestAttempt: { ...attempt, status: "UNKNOWN" },
    };
    render(<ItemStatus item={item} />);
    expect(screen.getByText("Unknown", { exact: true })).toBeInTheDocument();
    expect(screen.queryByText(/Queued|Processing|Indexed|scheduled/i)).not.toBeInTheDocument();
  });
});
