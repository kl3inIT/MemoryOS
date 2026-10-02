import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { expect, it, vi } from "vitest";
import { noSourceFilters, type SourceFilters } from "./source-filters";
import { SourceList, type SourceListItem } from "./source-list";

const handbooks: SourceListItem = {
  id: "20000000-0000-4000-8000-000000000001",
  name: "Handbooks",
  type: "FILE",
  access: "PUBLIC",
  status: "ACTIVE",
  lastSucceededAt: "2026-09-20T08:00:00Z",
};

function renderList(
  sources: SourceListItem[],
  filters: SourceFilters = noSourceFilters,
  onFilters: (next: SourceFilters) => void = () => {},
) {
  return render(
    <SourceList
      sources={sources}
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

it("leads its group with a failed Source and names a status it does not know", () => {
  renderList([
    handbooks,
    {
      ...handbooks,
      id: "20000000-0000-4000-8000-000000000002",
      name: "Contracts",
      status: "FAILED",
    },
    {
      ...handbooks,
      id: "20000000-0000-4000-8000-000000000003",
      name: "Archive",
      // A status a newer server sends before this client knows it.
      status: "ARCHIVED",
    },
  ]);

  const table = screen.getByRole("table", { name: "Connected sources" });
  const sourceRows = within(table)
    .getAllByRole("row")
    .filter((row) => row.id.startsWith("source-"));
  expect(sourceRows.map((row) => row.id)).toEqual([
    "source-20000000-0000-4000-8000-000000000002",
    "source-20000000-0000-4000-8000-000000000001",
    "source-20000000-0000-4000-8000-000000000003",
  ]);
  // An unknown status says so instead of passing for a scheduled Source.
  const archive = screen.getByRole("row", { name: /Archive/ });
  expect(within(archive).getByText("Unknown")).toBeInTheDocument();
  expect(within(archive).queryByText("Scheduled")).not.toBeInTheDocument();
});

it("filters the list to its failed Sources from the failed figure, and back", async () => {
  const user = userEvent.setup();
  const onFilters = vi.fn();
  const failed = { ...handbooks, id: "20000000-0000-4000-8000-000000000004", status: "FAILED" };
  const { rerender } = renderList([handbooks, failed], noSourceFilters, onFilters);

  const tile = screen.getByRole("button", { name: "Show failed sources, 1" });
  expect(tile).toHaveAttribute("aria-pressed", "false");
  await user.click(tile);
  expect(onFilters).toHaveBeenLastCalledWith({ ...noSourceFilters, status: "FAILED" });

  rerender(
    <SourceList
      sources={[handbooks, failed]}
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
