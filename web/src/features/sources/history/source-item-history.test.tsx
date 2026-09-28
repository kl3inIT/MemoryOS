import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { delay } from "msw";
import { describe, expect, it } from "vitest";
import { handleListSourceIndexAttempts } from "@/lib/hey-api/msw.gen";
import type { SourceIndexAttempt } from "@/lib/hey-api/types.gen";
import { server } from "@/test/msw";
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

function showAttempts(items: SourceIndexAttempt[]) {
  server.use(
    handleListSourceIndexAttempts({ body: { items, nextCursor: null, totalItems: items.length } }),
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

describe("File indexing attempt paging", () => {
  it("keeps paging usable while the open attempts are refetched in the background", async () => {
    let calls = 0;
    server.use(
      handleListSourceIndexAttempts(async () => {
        calls += 1;
        if (calls > 1) await delay("infinite");
        return Response.json({ items: [failed], nextCursor: "next", totalItems: 12 });
      }),
    );
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    render(
      <QueryClientProvider client={client}>
        <SourceItemHistory sourceId="source-a" />
      </QueryClientProvider>,
    );
    const next = await screen.findByRole("button", { name: "Next indexing attempts" });
    expect(next).toBeEnabled();
    void client.invalidateQueries();
    await waitFor(() => expect(calls).toBe(2));
    expect(next).toBeEnabled();
  });
});
