import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { cleanup, render, screen, within } from "@testing-library/react";
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
const perFile = /Readers need access to each file in Google Drive/;

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

describe("SourceSummaryCard", () => {
  it("explains that Auto Sync readers need Google Drive file access", () => {
    renderCard(drive);

    expect(screen.getByText(perFile)).toBeTruthy();
    expect(screen.queryByText(groupsOnly)).toBeNull();
  });

  it("explains that a Private Drive Source follows its groups", () => {
    renderCard({ ...drive, access: "PRIVATE" });

    expect(screen.getByText(groupsOnly)).toBeTruthy();
    expect(screen.queryByText(perFile)).toBeNull();
  });

  it("describes a Public Drive Source and a FILE Source like their access badges", () => {
    renderCard({ ...drive, access: "PUBLIC" });
    renderCard({ ...drive, id: "file", type: "FILE", access: "PRIVATE" });

    expect(
      screen.getByText("Available to workspace members, not the public Internet."),
    ).toBeTruthy();
    expect(
      screen.getByText("Only members of the associated groups can read this Source."),
    ).toBeTruthy();
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
});
