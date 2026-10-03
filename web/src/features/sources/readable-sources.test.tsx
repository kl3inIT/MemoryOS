import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen, within } from "@testing-library/react";
import type { ReactNode } from "react";
import { afterEach, expect, it } from "vitest";
import { handleListChatLibrarySources } from "@/lib/hey-api/msw.gen";
import type { ChatLibrarySource } from "@/lib/hey-api/types.gen";
import { server } from "@/test/msw";
import { noSourceFilters } from "./source-filters";
import { ReadableSources } from "./readable-sources";

const handbooks: ChatLibrarySource = {
  id: "10000000-0000-4000-8000-000000000001",
  name: "Handbooks",
  type: "FILE",
  access: "PUBLIC",
  status: "ACTIVE",
  readableDocuments: 3,
  lastSucceededAt: "2026-09-20T08:00:00Z",
  groups: [],
  managerName: null,
};
const clients: QueryClient[] = [];

afterEach(() => {
  for (const client of clients) client.clear();
  clients.length = 0;
});

/** Stands in for the library's link to a Source's documents, naming the Source it opens. */
function DocumentsLink({ sourceId, children }: { sourceId: string; children: ReactNode }) {
  return <a href={`/documents/${sourceId}`}>{children}</a>;
}

function renderSources(sources: ChatLibrarySource[]) {
  server.use(handleListChatLibrarySources({ body: sources }));
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  clients.push(client);
  render(
    <QueryClientProvider client={client}>
      <ReadableSources
        search=""
        onSearch={() => {}}
        filters={noSourceFilters}
        onFilters={() => {}}
        DocumentsLink={DocumentsLink}
      />
    </QueryClientProvider>,
  );
}

it("leads to a Source's documents only while Search serves some the member may read", async () => {
  renderSources([
    handbooks,
    {
      ...handbooks,
      id: "10000000-0000-4000-8000-000000000002",
      name: "Finance files",
      type: "SHAREPOINT",
      access: "PRIVATE",
      status: "PAUSED",
      readableDocuments: 12,
      groups: ["Audit", "Finance"],
      managerName: "Alice Nguyen",
    },
    {
      ...handbooks,
      id: "10000000-0000-4000-8000-000000000003",
      name: "Brand new",
      status: "NOT_STARTED",
      readableDocuments: 0,
      lastSucceededAt: null,
    },
  ]);

  expect(await screen.findByRole("link", { name: "Handbooks" })).toHaveAttribute(
    "href",
    `/documents/${handbooks.id}`,
  );
  // A paused Source keeps its documents out of Search, and a new one holds none yet.
  const finance = screen.getByRole("row", { name: /Finance files/ });
  expect(within(finance).queryByRole("link")).not.toBeInTheDocument();
  expect(
    within(screen.getByRole("row", { name: /Brand new/ })).queryByRole("link"),
  ).not.toBeInTheDocument();
  // Managing a Source stays on the administration pages.
  expect(screen.queryByRole("link", { name: /^Manage/ })).not.toBeInTheDocument();
});
