import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { cleanup, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import type { SourceIndexAttempt } from "@/lib/hey-api/types.gen";
import { SourceItemHistory } from "./source-item-history";

const failed: SourceIndexAttempt = {
  id: "attempt-a",
  filename: "report.pdf",
  status: "FAILED",
  createdAt: "2026-09-09T09:00:00Z",
  startedAt: "2026-09-09T09:01:00Z",
  completedAt: "2026-09-09T09:02:30Z",
  errorCode: "SOURCE_GOOGLE_UNAVAILABLE",
};

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

function showAttempts(items: SourceIndexAttempt[]) {
  vi.stubGlobal(
    "fetch",
    vi.fn(async () => Response.json({ items, nextCursor: null, totalItems: items.length })),
  );
  render(
    <QueryClientProvider
      client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}
    >
      <SourceItemHistory sourceId="source-a" />
    </QueryClientProvider>,
  );
}

describe("File indexing attempt details", () => {
  it("opens an attempt in a Sheet with its translated error and identifiers", async () => {
    const user = userEvent.setup();
    showAttempts([failed]);
    const trigger = await screen.findByRole("button", { name: /^View details for report\.pdf/ });
    await user.click(trigger);
    const sheet = await screen.findByRole("dialog", { name: "Indexing attempt details" });
    expect(
      within(sheet).getByText("Google Drive is temporarily unavailable. Try again later."),
    ).toBeInTheDocument();
    expect(
      within(sheet).getByText("To index this file again, use Reindex in the Files tab."),
    ).toBeInTheDocument();
    expect(within(sheet).getByText("SOURCE_GOOGLE_UNAVAILABLE")).toBeInTheDocument();
    expect(within(sheet).getByText("attempt-a")).toBeInTheDocument();
    await user.keyboard("{Escape}");
    await waitFor(() => expect(trigger).toHaveFocus());
  });

  it("shows unrecorded steps of a queued attempt as not yet reached", async () => {
    const user = userEvent.setup();
    showAttempts([
      {
        ...failed,
        id: "attempt-b",
        status: "NOT_STARTED",
        startedAt: null,
        completedAt: null,
        errorCode: null,
      },
    ]);
    await user.click(await screen.findByRole("button", { name: /^View details for report\.pdf/ }));
    const sheet = await screen.findByRole("dialog", { name: "Indexing attempt details" });
    expect(within(sheet).getAllByText("Not yet")).toHaveLength(2);
    expect(within(sheet).getByText("In progress")).toBeInTheDocument();
    expect(
      within(sheet).queryByText("To index this file again, use Reindex in the Files tab."),
    ).not.toBeInTheDocument();
  });
});
