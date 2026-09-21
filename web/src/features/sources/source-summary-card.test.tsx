import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { cleanup, render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import {
  ApplicationSessionContext,
  type ApplicationSession,
} from "@/features/identity/application-session-context";
import type * as Sdk from "@/lib/hey-api/sdk.gen";
import type { SourceGroup, SourceSummary } from "@/lib/hey-api/types.gen";
import { SourceSummaryCard } from "./source-summary-card";

const listSourceGroupsMock = vi.hoisted(() => vi.fn());

vi.mock("@/lib/hey-api/sdk.gen", async (importOriginal) => ({
  ...(await importOriginal<typeof Sdk>()),
  listSourceGroups: listSourceGroupsMock,
}));

const session: ApplicationSession = {
  actorId: "actor",
  authorizationVersion: 1,
  uiLanguage: "en",
  tenant: { displayName: "Team", role: "OWNER" },
  capabilities: ["SOURCES_READ", "SOURCES_MANAGE"],
  scopedCapabilities: [],
};
const drive: SourceSummary = {
  id: "source",
  name: "Drive knowledge",
  type: "GOOGLE_DRIVE",
  access: "SYNC",
  status: "ACTIVE",
  documentCount: 12,
  pendingWork: false,
  lastSucceededAt: null,
  errorCode: null,
  managerActorId: null,
  managerName: null,
  permissions: {
    edit: true,
    delete: true,
    publish: true,
    manageConfiguration: true,
    removeItems: true,
  },
};
const groupsOnly =
  "Document access follows this Source's MemoryOS groups, not Google Drive file permissions.";
const perFile = "Whoever can open the file in Google Drive can read it.";

function renderCard(source: SourceSummary, groups: SourceGroup[] = []) {
  listSourceGroupsMock.mockResolvedValue({ data: { items: groups } });
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(
    <QueryClientProvider client={client}>
      <ApplicationSessionContext.Provider value={session}>
        <SourceSummaryCard source={source} />
      </ApplicationSessionContext.Provider>
    </QueryClientProvider>,
  );
}

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
});

async function openReadersHelp() {
  await userEvent.click(screen.getByRole("button", { name: "Who can read help" }));
}

describe("SourceSummaryCard", () => {
  it("names source-permission readers briefly and explains the Google Drive rule in its help", async () => {
    renderCard(drive, [{ id: "finance", name: "Finance", systemKey: null }]);

    expect(screen.getByText("People with access in Google Drive")).toBeTruthy();
    await openReadersHelp();
    expect(await screen.findByText(perFile)).toBeTruthy();
    expect(screen.queryByText(groupsOnly)).toBeNull();
    // Groups never decide who reads it, so the summary neither lists nor loads them.
    expect(screen.queryByText("Groups")).toBeNull();
    expect(listSourceGroupsMock).not.toHaveBeenCalled();
  });

  it("explains that a Private Drive Source follows its groups", async () => {
    renderCard({ ...drive, access: "PRIVATE" });

    expect(screen.getByText("Members of the chosen groups")).toBeTruthy();
    await openReadersHelp();
    expect(await screen.findByText(groupsOnly)).toBeTruthy();
    expect(screen.queryByText(perFile)).toBeNull();
  });

  it("describes a Public Drive Source and a FILE Source like their access badges", async () => {
    renderCard({ ...drive, access: "PUBLIC" });
    await openReadersHelp();
    expect(await screen.findByText("Everyone can read it.")).toBeTruthy();
    expect(screen.queryByText("Groups")).toBeNull();
    expect(listSourceGroupsMock).not.toHaveBeenCalled();
    cleanup();

    renderCard({ ...drive, id: "file", type: "FILE", access: "PRIVATE" });
    await openReadersHelp();
    expect(await screen.findByText("Only members of the chosen groups can read it.")).toBeTruthy();
    expect(screen.queryByText(groupsOnly)).toBeNull();
    expect(screen.queryByText(perFile)).toBeNull();
  });

  it("lists associated groups without system groups", async () => {
    renderCard({ ...drive, access: "PRIVATE" }, [
      { id: "finance", name: "Finance", systemKey: null },
      { id: "legal", name: "Legal", systemKey: null },
      { id: "admins", name: "Administrators", systemKey: "ADMIN" },
    ]);

    const summary = screen.getByLabelText("Source summary");
    expect(await within(summary).findByText("Finance, Legal")).toBeTruthy();
    expect(within(summary).queryByText(/Administrators/)).toBeNull();
    expect(listSourceGroupsMock).toHaveBeenCalledWith(
      expect.objectContaining({ path: { sourceId: drive.id } }),
    );
  });

  it("caps the summary at three groups and names the remainder on hover", async () => {
    renderCard({ ...drive, access: "PRIVATE" }, [
      { id: "finance", name: "Finance", systemKey: null },
      { id: "legal", name: "Legal", systemKey: null },
      { id: "operations", name: "Operations", systemKey: null },
      { id: "security", name: "Security", systemKey: null },
      { id: "support", name: "Support", systemKey: null },
    ]);

    const summary = screen.getByLabelText("Source summary");
    expect(await within(summary).findByText("Finance, Legal, Operations")).toBeTruthy();
    const additionalGroups = within(summary).getByText("+2 more groups");
    await userEvent.hover(additionalGroups);
    expect(await screen.findByRole("tooltip")).toHaveTextContent(
      "Additional groups: Security, Support",
    );
  });
});
