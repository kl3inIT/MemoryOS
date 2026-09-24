import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { cleanup, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { useState } from "react";
import { afterEach, describe, expect, it, vi } from "vitest";
// Configures the generated client's base URL, as the application shell does at startup.
import "@/lib/api";
import type { SourceGroup } from "@/lib/hey-api/types.gen";
import { SourceGroupPicker } from "./source-group-picker";

const team: SourceGroup = { id: "team", name: "Knowledge team", systemKey: null };
const admin: SourceGroup = { id: "admin", name: "Admin", systemKey: "ADMIN" };

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

function Picker({ onChange }: { onChange: (groupIds: Set<string>) => void }) {
  const [selected, setSelected] = useState<Set<string>>(() => new Set());
  return (
    <SourceGroupPicker
      label="Access groups"
      placeholder="Select groups"
      selected={selected}
      onChange={(groupIds) => {
        setSelected(groupIds);
        onChange(groupIds);
      }}
    />
  );
}

function renderPicker() {
  const searches: string[] = [];
  vi.stubGlobal(
    "fetch",
    vi.fn(async (request: Request) => {
      const url = new URL(request.url);
      if (url.pathname !== "/api/sources/group-options") {
        throw new Error(`Unexpected request: ${request.method} ${url.pathname}`);
      }
      const search = url.searchParams.get("search") ?? "";
      searches.push(search);
      const items = [admin, team].filter((group) =>
        group.name.toLowerCase().includes(search.toLowerCase()),
      );
      return Response.json({ items, page: 0, size: 25, totalItems: items.length, totalPages: 1 });
    }),
  );
  const onChange = vi.fn();
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(
    <QueryClientProvider client={client}>
      <Picker onChange={onChange} />
    </QueryClientProvider>,
  );
  return { onChange, searches };
}

describe("SourceGroupPicker", () => {
  it("adds a group as a chip, hides system groups and removes the chip again", async () => {
    const user = userEvent.setup();
    const { onChange } = renderPicker();

    await user.click(screen.getByRole("combobox", { name: "Access groups" }));
    const option = await screen.findByRole("option", { name: "Knowledge team" });
    expect(screen.queryByRole("option", { name: "Admin" })).toBeNull();
    await user.click(option);
    expect(onChange).toHaveBeenLastCalledWith(new Set([team.id]));
    // A chosen group leaves the list and stays only as its chip.
    expect(screen.queryByRole("option", { name: "Knowledge team" })).toBeNull();

    await user.click(screen.getByRole("button", { name: "Remove Knowledge team" }));
    expect(onChange).toHaveBeenLastCalledWith(new Set());
    expect(screen.queryByRole("button", { name: "Remove Knowledge team" })).toBeNull();
  });

  it("searches on the server and says when nothing matches", async () => {
    const user = userEvent.setup();
    const { searches } = renderPicker();

    await user.type(screen.getByRole("combobox", { name: "Access groups" }), "zzz");
    expect(await screen.findByText("No groups match your search.")).toBeVisible();
    expect(searches).toContain("zzz");
  });
});
