import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { cleanup, render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { HttpResponse } from "msw";
import { describe, expect, it } from "vitest";
import {
  ApplicationSessionContext,
  type ApplicationSession,
} from "@/features/identity/application-session-context";
import { handleListSourceGroups } from "@/lib/hey-api/msw.gen";
import type { SourceGroup, SourceSummary } from "@/lib/hey-api/types.gen";
import { server } from "@/test/msw";
import { SourceSummaryCard } from "./source-summary-card";

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
  const requested: string[] = [];
  server.use(
    handleListSourceGroups(({ params }) => {
      requested.push(params.sourceId);
      return HttpResponse.json({ items: groups });
    }),
  );
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(
    <QueryClientProvider client={client}>
      <ApplicationSessionContext value={session}>
        <SourceSummaryCard source={source} />
      </ApplicationSessionContext>
    </QueryClientProvider>,
  );
  return { requested };
}

async function openReadersHelp() {
  await userEvent.click(screen.getByRole("button", { name: "Who can read help" }));
}

describe("SourceSummaryCard", () => {
  it("names source-permission readers briefly and explains the Google Drive rule in its help", async () => {
    const { requested } = renderCard(drive, [{ id: "finance", name: "Finance", systemKey: null }]);

    expect(screen.getByText("People with access in Google Drive")).toBeTruthy();
    await openReadersHelp();
    expect(await screen.findByText(perFile)).toBeTruthy();
    expect(screen.queryByText(groupsOnly)).toBeNull();
    // Groups never decide who reads it, so the summary neither lists nor loads them.
    expect(screen.queryByText("Groups")).toBeNull();
    expect(requested).toEqual([]);
  });

  it("explains that a Private Drive Source follows its groups", async () => {
    renderCard({ ...drive, access: "PRIVATE" });

    expect(screen.getByText("Members of the chosen groups")).toBeTruthy();
    await openReadersHelp();
    expect(await screen.findByText(groupsOnly)).toBeTruthy();
    expect(screen.queryByText(perFile)).toBeNull();
  });

  it("describes a Public Drive Source and a FILE Source like their access badges", async () => {
    const { requested } = renderCard({ ...drive, access: "PUBLIC" });
    await openReadersHelp();
    expect(await screen.findByText("Everyone can read it.")).toBeTruthy();
    expect(screen.queryByText("Groups")).toBeNull();
    expect(requested).toEqual([]);
    cleanup();

    renderCard({ ...drive, id: "file", type: "FILE", access: "PRIVATE" });
    await openReadersHelp();
    expect(await screen.findByText("Only members of the chosen groups can read it.")).toBeTruthy();
    expect(screen.queryByText(groupsOnly)).toBeNull();
    expect(screen.queryByText(perFile)).toBeNull();
  });

  it("lists associated groups without system groups", async () => {
    const { requested } = renderCard({ ...drive, access: "PRIVATE" }, [
      { id: "finance", name: "Finance", systemKey: null },
      { id: "legal", name: "Legal", systemKey: null },
      { id: "admins", name: "Administrators", systemKey: "ADMIN" },
    ]);

    const summary = screen.getByLabelText("Source summary");
    expect(await within(summary).findByText("Finance, Legal")).toBeTruthy();
    expect(within(summary).queryByText(/Administrators/)).toBeNull();
    expect(requested).toContain(drive.id);
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
