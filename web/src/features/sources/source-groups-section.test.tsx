import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { cleanup, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { useState, type ReactNode } from "react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { GroupSourcesSection } from "@/features/groups/group-sources-section";
import {
  ApplicationSessionContext,
  type ApplicationSession,
} from "@/features/identity/application-session-context";
import type { GroupSummary, SourceGroup, SourceSummary } from "@/lib/hey-api/types.gen";
import { SourceGroupPicker } from "./source-group-picker";
import { SourceGroupsSection } from "./source-groups-section";

const ordinary: SourceGroup = { id: "team", name: "Knowledge team", systemKey: null };
const other: SourceGroup = { id: "other", name: "Research", systemKey: null };
const systemGroups: SourceGroup[] = [
  { id: "admin", name: "Admin", systemKey: "ADMIN" },
  { id: "basic", name: "Basic", systemKey: "BASIC" },
];
const group: GroupSummary = {
  ...ordinary,
  memberCount: 1,
  managerCount: 1,
  capabilities: [],
  actions: ["manage_sources"],
};
const source: SourceSummary = {
  id: "source",
  name: "Team knowledge",
  type: "FILE",
  access: "RESTRICTED",
  status: "ACTIVE",
  documentCount: 0,
  pendingWork: false,
  lastSucceededAt: null,
  errorCode: null,
  actions: ["manage_groups"],
};
const globalSession: ApplicationSession = {
  actorId: "actor",
  authorizationVersion: 1,
  uiLanguage: "en",
  tenant: { displayName: "Team", role: "OWNER" },
  capabilities: ["SOURCES_READ", "SOURCES_MANAGE"],
  scopedCapabilities: [],
};
const scopedSession: ApplicationSession = {
  ...globalSession,
  capabilities: [],
  scopedCapabilities: ["SOURCES_READ", "SOURCES_MANAGE"],
};
const clients: QueryClient[] = [];

afterEach(() => {
  cleanup();
  for (const client of clients) client.clear();
  clients.length = 0;
  vi.unstubAllGlobals();
});

function setup(children: ReactNode, session = globalSession, paginated = false) {
  const saved: string[][] = [];
  vi.stubGlobal(
    "fetch",
    vi.fn(async (request: Request) => {
      const url = new URL(request.url);
      if (url.pathname === "/api/sources/group-options") {
        return Response.json({
          items:
            paginated && url.searchParams.get("page") === "1"
              ? [other]
              : [...systemGroups, ordinary],
          page: Number(url.searchParams.get("page") ?? 0),
          size: 25,
          totalItems: paginated ? 2 : 1,
          totalPages: paginated ? 2 : 1,
        });
      }
      if (url.pathname === "/api/sources/source/groups") {
        if (request.method === "POST") {
          const body = (await request.json()) as { groupIds: string[] };
          saved.push(body.groupIds);
          return Response.json({ items: body.groupIds.map((id) => ({ ...ordinary, id })) });
        }
        return Response.json({ items: [...systemGroups, ordinary] });
      }
      if (url.pathname === "/api/sources") return Response.json([source]);
      if (url.pathname === "/api/groups/team/sources") return Response.json({ items: [source] });
      throw new Error(`Unexpected request: ${request.method} ${url.pathname}`);
    }),
  );
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  clients.push(client);
  const wrap = (currentSession: ApplicationSession) => (
    <QueryClientProvider client={client}>
      <ApplicationSessionContext.Provider value={currentSession}>
        {children}
      </ApplicationSessionContext.Provider>
    </QueryClientProvider>
  );
  const view = render(wrap(session));
  return { saved, setSession: (next: ApplicationSession) => view.rerender(wrap(next)) };
}

function PickerSelection() {
  const [selected, setSelected] = useState(() => new Set(["admin", "basic", "unloaded"]));
  return (
    <>
      <SourceGroupPicker selected={selected} knownGroups={systemGroups} onChange={setSelected} />
      <output aria-label="Submitted group IDs">{[...selected].sort().join(",")}</output>
    </>
  );
}

describe("ordinary Source associations", () => {
  it("removes known system IDs while preserving selected ordinary IDs across pages", async () => {
    const user = userEvent.setup();
    setup(<PickerSelection />, globalSession, true);
    await user.click(await screen.findByRole("checkbox", { name: ordinary.name }));
    expect(screen.queryByRole("checkbox", { name: /Admin|Basic/ })).not.toBeInTheDocument();
    expect(screen.getByLabelText("Submitted group IDs")).toHaveTextContent("team,unloaded");
    expect(screen.getByText("2 selected")).toBeVisible();
    await user.click(screen.getByRole("button", { name: "Next" }));
    await user.click(await screen.findByRole("checkbox", { name: other.name }));
    expect(screen.getByLabelText("Submitted group IDs")).toHaveTextContent("other,team,unloaded");
    const chips = screen.getByLabelText("Selected Source groups");
    expect(within(chips).getByText(ordinary.name)).toBeVisible();
    expect(within(chips).getByText(other.name)).toBeVisible();
    expect(within(chips).queryByText(/Admin|Basic/)).not.toBeInTheDocument();
  });

  it("hides legacy system associations from readonly chips", async () => {
    setup(
      <SourceGroupsSection
        sourceId="source"
        editable={false}
        onAuthorityChanged={async () => {}}
      />,
    );
    const chips = await screen.findByLabelText("Source groups");
    expect(within(chips).getByText(ordinary.name)).toBeVisible();
    expect(within(chips).queryByText(/Admin|Basic/)).not.toBeInTheDocument();
  });

  it("lets a global manager clear the final ordinary association without submitting system IDs", async () => {
    const user = userEvent.setup();
    const { saved } = setup(
      <SourceGroupsSection sourceId="source" editable onAuthorityChanged={async () => {}} />,
    );
    const choice = await screen.findByRole("checkbox", { name: ordinary.name });
    await waitFor(() => expect(choice).toBeChecked());
    await user.click(choice);
    await user.click(screen.getByRole("button", { name: "Save associations" }));
    await waitFor(() => expect(saved).toEqual([[]]));
  });

  it("blocks a cleared selection immediately when global management becomes scoped", async () => {
    const user = userEvent.setup();
    const { saved, setSession } = setup(
      <SourceGroupsSection sourceId="source" editable onAuthorityChanged={async () => {}} />,
    );
    const choice = await screen.findByRole("checkbox", { name: ordinary.name });
    await waitFor(() => expect(choice).toBeChecked());
    await user.click(choice);
    expect(screen.getByRole("button", { name: "Save associations" })).toBeEnabled();
    setSession(scopedSession);
    expect(screen.getByRole("button", { name: "Save associations" })).toBeDisabled();
    expect(screen.getByRole("alert")).toBeVisible();
    await user.click(choice);
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
    expect(saved).toEqual([]);
  });

  it("allows a global manager to remove the last association from Group detail", async () => {
    const user = userEvent.setup();
    const { saved } = setup(
      <GroupSourcesSection group={group} onAuthorityChanged={async () => {}} />,
    );
    const choice = await screen.findByRole("checkbox", { name: /Team knowledge/ });
    await waitFor(() => expect(choice).toBeChecked());
    await user.click(choice);
    await user.click(screen.getByRole("button", { name: "Save associations" }));
    await waitFor(() => expect(saved).toEqual([[]]));
  });

  it("blocks a scoped manager removing the last ordinary association from Group detail", async () => {
    const user = userEvent.setup();
    const { saved } = setup(
      <GroupSourcesSection group={group} onAuthorityChanged={async () => {}} />,
      scopedSession,
    );
    const choice = await screen.findByRole("checkbox", { name: /Team knowledge/ });
    await waitFor(() => expect(choice).toBeChecked());
    await user.click(choice);
    await user.click(screen.getByRole("button", { name: "Save associations" }));
    expect(await screen.findByRole("alert")).toBeVisible();
    expect(saved).toEqual([]);
  });
});
