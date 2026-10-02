import { render, screen, within } from "@testing-library/react";
import { expect, it } from "vitest";
import { noSourceFilters } from "./source-filters";
import { SourceList, type SourceListItem } from "./source-list";

const handbooks: SourceListItem = {
  id: "20000000-0000-4000-8000-000000000001",
  name: "Handbooks",
  type: "FILE",
  access: "PUBLIC",
  status: "ACTIVE",
  lastSucceededAt: "2026-09-20T08:00:00Z",
  errorCode: null,
};

function renderList(sources: SourceListItem[]) {
  render(
    <SourceList
      sources={sources}
      search=""
      onSearch={() => {}}
      filters={noSourceFilters}
      onFilters={() => {}}
      documents={() => 0}
      documentsLabel="Total docs"
      renderName={(source) => <span>{source.name}</span>}
    />,
  );
}

it("leads its group with a failed Source and says why it failed", () => {
  renderList([
    handbooks,
    {
      ...handbooks,
      id: "20000000-0000-4000-8000-000000000002",
      name: "Contracts",
      status: "FAILED",
      errorCode: "SEARCH_INDEX_NO_TEXT",
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
  const reason = "The file contains no searchable text.";
  expect(
    within(screen.getByRole("row", { name: /Contracts/ })).getAllByText(reason),
  ).not.toHaveLength(0);
  expect(
    within(screen.getByRole("row", { name: /Handbooks/ })).queryByText(reason),
  ).not.toBeInTheDocument();
  // An unknown status says so instead of passing for a scheduled Source.
  const archive = screen.getByRole("row", { name: /Archive/ });
  expect(within(archive).getByText("Unknown")).toBeInTheDocument();
  expect(within(archive).queryByText("Scheduled")).not.toBeInTheDocument();
});
