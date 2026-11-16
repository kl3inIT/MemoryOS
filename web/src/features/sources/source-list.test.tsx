import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { expect, it, vi } from "vitest";
import { noSourceFilters, type SourceFilters } from "./source-filters";
import { SourceList, type SourceListItem, type SourceListTotals } from "./source-list";

const handbooks: SourceListItem = {
  id: "20000000-0000-4000-8000-000000000001",
  name: "Handbooks",
  type: "FILE",
  access: "PUBLIC",
  status: "ACTIVE",
  lastSucceededAt: "2026-09-20T08:00:00Z",
};

const noTotals: SourceListTotals = { total: 0, failed: 0, documents: 0, byStatus: {} };

function renderList(
  sources: SourceListItem[],
  filters: SourceFilters = noSourceFilters,
  onFilters: (next: SourceFilters) => void = () => {},
) {
  return render(
    <SourceList
      sources={sources}
      totals={{
        ...noTotals,
        failed: sources.filter((source) => source.status === "FAILED").length,
      }}
      search=""
      onSearch={() => {}}
      filters={filters}
      onFilters={onFilters}
      documents={() => 0}
      documentsLabel="Total docs"
      renderName={(source) => <span>{source.name}</span>}
    />,
  );
}

it("lists the rows flat in the order given, with the provider icon, and names a status it does not know", () => {
  renderList([
    {
      ...handbooks,
      id: "20000000-0000-4000-8000-000000000002",
      name: "Contracts",
      status: "FAILED",
    },
    handbooks,
    {
      ...handbooks,
      id: "20000000-0000-4000-8000-000000000003",
      name: "Archive",
      // A status a newer server sends before this client knows it.
      status: "ARCHIVED",
    },
  ]);

  const table = screen.getByRole("table", { name: "Connected sources" });
  expect(within(table).queryByRole("button", { name: /group/ })).not.toBeInTheDocument();
  expect(within(table).getAllByRole("rowgroup")).toHaveLength(2);
  const sourceRows = within(table)
    .getAllByRole("row")
    .filter((row) => row.id.startsWith("source-"));
  expect(sourceRows.map((row) => row.id)).toEqual([
    "source-20000000-0000-4000-8000-000000000002",
    "source-20000000-0000-4000-8000-000000000001",
    "source-20000000-0000-4000-8000-000000000003",
  ]);
  expect(sourceRows[1]?.querySelector("svg[aria-hidden='true']")).not.toBeNull();
  // An unknown status says so instead of passing for a scheduled Source.
  const archive = screen.getByRole("row", { name: /Archive/ });
  expect(within(archive).getByText("Unknown")).toBeInTheDocument();
  expect(within(archive).queryByText("Scheduled")).not.toBeInTheDocument();
});

it("says who manages a Source and what its status holds: the failure, running work, or the last success", () => {
  renderList([
    {
      ...handbooks,
      name: "Contracts",
      id: "20000000-0000-4000-8000-000000000002",
      status: "FAILED",
      managerName: "Lan Nguyen",
      errorCode: "GOOGLE_DRIVE_AUTH_FAILED",
    },
    {
      ...handbooks,
      name: "Policies",
      id: "20000000-0000-4000-8000-000000000003",
      status: "INDEXING",
      pendingWork: true,
    },
    {
      ...handbooks,
      name: "Recent",
      id: "20000000-0000-4000-8000-000000000004",
      lastSucceededAt: new Date(Date.now() - 5 * 60_000).toISOString(),
    },
  ]);

  const contracts = screen.getByRole("row", { name: /Contracts/ });
  expect(within(contracts).getByText("Responsible manager: Lan Nguyen")).toBeInTheDocument();
  expect(within(contracts).queryByText(/minutes? ago/)).not.toBeInTheDocument();
  expect(within(contracts).getByText(/Source processing failed|Google/)).toBeInTheDocument();
  expect(
    within(screen.getByRole("row", { name: /Policies/ })).getByText("Still working"),
  ).toBeInTheDocument();
  const recent = within(screen.getByRole("row", { name: /Recent/ })).getByText("5 minutes ago");
  expect(recent).toHaveAttribute("title");
});

it("pages the rows it is given and reports the range", async () => {
  const user = userEvent.setup();
  const onPage = vi.fn();
  render(
    <SourceList
      sources={[handbooks]}
      totals={{ ...noTotals, total: 34 }}
      search=""
      onSearch={() => {}}
      filters={noSourceFilters}
      onFilters={() => {}}
      documents={() => 0}
      documentsLabel="Total docs"
      renderName={(source) => <span>{source.name}</span>}
      pager={{ page: 1, size: 20, totalItems: 34, totalPages: 2, onPage }}
    />,
  );

  expect(screen.getByText("Showing 21–34 of 34")).toBeInTheDocument();
  expect(screen.getByRole("button", { name: "Next" })).toBeDisabled();
  await user.click(screen.getByRole("button", { name: "Previous" }));
  expect(onPage).toHaveBeenLastCalledWith(0);
});

it("filters by status from the chips that hold Sources", async () => {
  const user = userEvent.setup();
  const onFilters = vi.fn();
  render(
    <SourceList
      sources={[handbooks]}
      totals={{ total: 3, failed: 0, documents: 0, byStatus: { ACTIVE: 2, INDEXING: 1 } }}
      search=""
      onSearch={() => {}}
      filters={noSourceFilters}
      onFilters={onFilters}
      documents={() => 0}
      documentsLabel="Total docs"
      renderName={(source) => <span>{source.name}</span>}
    />,
  );

  const chips = screen.getByRole("group", { name: "Filter sources by status" });
  expect(within(chips).queryByRole("button", { name: /Failed/ })).not.toBeInTheDocument();
  await user.click(within(chips).getByRole("button", { name: /Indexing/ }));
  expect(onFilters).toHaveBeenLastCalledWith({ ...noSourceFilters, status: "INDEXING" });
});

it("filters the list to its failed Sources from the failed figure, and back", async () => {
  const user = userEvent.setup();
  const onFilters = vi.fn();
  const failed = { ...handbooks, id: "20000000-0000-4000-8000-000000000004", status: "FAILED" };
  const { rerender } = renderList([handbooks, failed], noSourceFilters, onFilters);

  const tile = screen.getByRole("button", { name: "Failed 1" });
  expect(tile).toHaveAttribute("aria-pressed", "false");
  await user.click(tile);
  expect(onFilters).toHaveBeenLastCalledWith({ ...noSourceFilters, status: "FAILED" });

  rerender(
    <SourceList
      sources={[handbooks, failed]}
      totals={{ ...noTotals, failed: 1 }}
      search=""
      onSearch={() => {}}
      filters={{ ...noSourceFilters, status: "FAILED" }}
      onFilters={onFilters}
      documents={() => 0}
      documentsLabel="Total docs"
      renderName={(source) => <span>{source.name}</span>}
    />,
  );
  expect(tile).toHaveAttribute("aria-pressed", "true");
  await user.click(tile);
  expect(onFilters).toHaveBeenLastCalledWith(noSourceFilters);
});

it("searches through the shared field", async () => {
  const user = userEvent.setup();
  const onSearch = vi.fn();
  render(
    <SourceList
      sources={[handbooks]}
      totals={noTotals}
      search="hand"
      onSearch={onSearch}
      filters={noSourceFilters}
      onFilters={() => {}}
      documents={() => 0}
      documentsLabel="Total docs"
      renderName={(source) => <span>{source.name}</span>}
    />,
  );

  expect(screen.queryByRole("button", { name: /Collapse all|Expand all/ })).not.toBeInTheDocument();
  expect(screen.getByRole("textbox", { name: "Search sources" })).toHaveValue("hand");
  await user.click(screen.getByRole("button", { name: "Clear the search" }));
  expect(onSearch).toHaveBeenLastCalledWith("");
});
