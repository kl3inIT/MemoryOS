import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import {
  columnVisibilityFeature,
  createColumnHelper,
  tableFeatures,
  useTable,
} from "@tanstack/react-table";
import { describe, expect, it, vi } from "vitest";
import { DataTable, type DataTableColumnMeta } from "@/components/data-table/data-table";

type Run = { id: string; name: string; kind: string };

const features = tableFeatures({ columnVisibilityFeature, columnMeta: {} as DataTableColumnMeta });
const column = createColumnHelper<typeof features, Run>();
const columns = column.columns([
  column.accessor("name", { header: "Name" }),
  column.accessor("kind", { header: "Kind" }),
  column.display({
    id: "open",
    header: "Open",
    cell: ({ row }) => <button type="button">Open {row.original.name}</button>,
  }),
]);

function Runs({
  runs,
  showKind = true,
  selected,
  onOpen = () => {},
}: {
  runs: Run[];
  showKind?: boolean;
  selected?: string;
  onOpen?: (run: Run) => void;
}) {
  const table = useTable({
    features,
    columns,
    data: runs,
    getRowId: (run) => run.id,
    state: { columnVisibility: { kind: showKind } },
  });
  return (
    <DataTable
      table={table}
      label="Runs"
      empty={<p>No runs</p>}
      rowProps={(row) => ({
        selected: row.id === selected,
        "data-run": row.id,
        onClick: (event) => {
          if (!(event.target as Element).closest("button")) onOpen(row.original);
        },
      })}
    />
  );
}

const runs = [
  { id: "a", name: "First", kind: "Refresh" },
  { id: "b", name: "Second", kind: "Prune" },
];

describe("DataTable", () => {
  it("leaves hidden columns out of the header and the rows", () => {
    render(<Runs runs={runs} showKind={false} />);

    const table = screen.getByRole("table", { name: "Runs" });
    expect(
      within(table)
        .getAllByRole("columnheader")
        .map((cell) => cell.textContent),
    ).toEqual(["Name", "Open"]);
    expect(within(table).queryByText("Refresh")).toBeNull();
    expect(table.querySelectorAll("col")).toHaveLength(2);
  });

  it("marks the selected row and applies the page's row props", async () => {
    const onOpen = vi.fn();
    const user = userEvent.setup();
    render(<Runs runs={runs} selected="b" onOpen={onOpen} />);

    const [, first, second] = screen.getAllByRole("row");
    expect(second).toHaveAttribute("aria-selected", "true");
    expect(second).toHaveAttribute("data-state", "selected");
    expect(first).not.toHaveAttribute("aria-selected");
    expect(first).toHaveAttribute("data-run", "a");

    await user.click(within(first!).getByText("First"));
    expect(onOpen).toHaveBeenCalledWith(runs[0]);
    await user.click(within(first!).getByRole("button", { name: "Open First" }));
    expect(onOpen).toHaveBeenCalledTimes(1);
  });

  it("shows the empty slot across every visible column when there are no rows", () => {
    render(<Runs runs={[]} />);

    const empty = screen.getByText("No runs").closest("td");
    expect(empty).toHaveAttribute("colspan", "3");
  });
});
