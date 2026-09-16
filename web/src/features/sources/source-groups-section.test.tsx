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
import { listSourceGroupOptionsOptions } from "@/lib/hey-api/@tanstack/react-query.gen";
import type { GroupSummary, SourceGroup, SourceSummary } from "@/lib/hey-api/types.gen";
import { GroupAccessPicker } from "@/features/groups/group-access-picker";
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
  permissions: {
    manage: false,
    manageMembers: false,
    delete: false,
    editPermissions: false,
    manageSources: true,
  },
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
  managerActorId: null,
  permissions: {
    edit: true,
    delete: false,
    publish: false,
    manageConfiguration: false,
    removeItems: false,
  },
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

function setup(
  children: ReactNode,
  session = globalSession,
  paginated = false,
  removableSourceIds = ["source"],
  sourceGroups: SourceGroup[] = [...systemGroups, ordinary],
) {
  const saved: string[][] = [];
  const removed: string[] = [];
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
        return Response.json({ items: sourceGroups });
      }
      if (url.pathname === "/api/sources") return Response.json([source]);
      if (url.pathname === "/api/groups/team/sources")
        return Response.json({ items: [source], removableSourceIds });
      if (url.pathname === "/api/groups/team/sources/source/remove" && request.method === "POST") {
        removed.push("source");
        return new Response(null, { status: 204 });
      }
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
  return {
    saved,
    removed,
    setSession: (next: ApplicationSession) => view.rerender(wrap(next)),
  };
}

function PickerSelection() {
  const [selected, setSelected] = useState(() => new Set(["admin", "basic", "unloaded"]));
  return (
    <>
      <GroupAccessPicker
        selected={selected}
        knownGroups={systemGroups}
        load={(query) => listSourceGroupOptionsOptions({ query })}
        description="Group members can search and read imported documents."
        onChange={setSelected}
      />
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
    const chips = screen.getByLabelText("Selected groups");
    expect(within(chips).getByText(ordinary.name)).toBeVisible();
    expect(within(chips).getByText(other.name)).toBeVisible();
    expect(within(chips).queryByText(/Admin|Basic/)).not.toBeInTheDocument();
  });

  it("hides legacy system associations from readonly chips", async () => {
    setup(
      <SourceGroupsSection
        sourceId="source"
        editable={false}
        restricted
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
      <SourceGroupsSection
        sourceId="source"
        editable
        restricted
        onAuthorityChanged={async () => {}}
      />,
    );
    const choice = await screen.findByRole("checkbox", { name: ordinary.name });
    await waitFor(() => expect(choice).toBeChecked());
    await user.click(choice);
    await user.click(screen.getByRole("button", { name: "Save associations" }));
    await waitFor(() => expect(saved).toEqual([[]]));
  });

  it("lets the responsible manager clear the last association and warns that nobody can read it", async () => {
    const user = userEvent.setup();
    const { saved } = setup(
      <SourceGroupsSection
        sourceId="source"
        editable
        restricted
        onAuthorityChanged={async () => {}}
      />,
      scopedSession,
    );
    const choice = await screen.findByRole("checkbox", { name: ordinary.name });
    await waitFor(() => expect(choice).toBeChecked());
    await user.click(choice);
    await user.click(screen.getByRole("button", { name: "Save associations" }));
    await waitFor(() => expect(saved).toEqual([[]]));
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });

  it("warns when a restricted Source belongs to no group", async () => {
    setup(
      <SourceGroupsSection
        sourceId="source"
        editable={false}
        restricted
        onAuthorityChanged={async () => {}}
      />,
      globalSession,
      false,
      ["source"],
      [],
    );
    expect(
      await screen.findByText(
        "This Source belongs to no group yet, so nobody can search or read its documents. Associate it with a group to make it usable.",
      ),
    ).toBeInTheDocument();
  });

  it("allows a global manager to remove the last association from Group detail", async () => {
    const user = userEvent.setup();
    const { saved, removed } = setup(
      <GroupSourcesSection group={group} onAuthorityChanged={async () => {}} />,
    );
    const remove = await screen.findByRole("button", { name: /Remove Team knowledge/ });
    await user.click(remove);
    await user.click(screen.getByRole("button", { name: "Save associations" }));
    await waitFor(() => expect(removed).toEqual(["source"]));
    expect(saved).toEqual([]);
  });

  it("lets a scoped manager remove a Source shared with another group from Group detail", async () => {
    const user = userEvent.setup();
    const { saved, removed } = setup(
      <GroupSourcesSection group={group} onAuthorityChanged={async () => {}} />,
      scopedSession,
    );
    await user.click(await screen.findByRole("button", { name: /Remove Team knowledge/ }));
    await user.click(screen.getByRole("button", { name: "Save associations" }));
    await waitFor(() => expect(removed).toEqual(["source"]));
    expect(saved).toEqual([]);
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });

  it("locks a Source being deleted from Group detail", async () => {
    setup(
      <GroupSourcesSection group={group} onAuthorityChanged={async () => {}} />,
      scopedSession,
      false,
      [],
    );
    expect(
      await screen.findByText(
        "Sources with a lock are being deleted and can't be removed from this group.",
      ),
    ).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /Remove Team knowledge/ })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Save associations" })).not.toBeInTheDocument();
  });
});
