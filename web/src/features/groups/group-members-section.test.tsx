import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { cleanup, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import {
  ApplicationSessionContext,
  type ApplicationSession,
} from "@/features/identity/application-session-context";
import { useGroupMembersDraft } from "./group-members-draft";
import { GroupMembersSection } from "./group-members-section";
import type { GroupMember, GroupSummary } from "@/lib/hey-api/types.gen";

const group: GroupSummary = {
  id: "team",
  name: "Team",
  systemKey: null,
  memberCount: 1,
  managerCount: 1,
  capabilities: [],
  permissions: {
    manage: true,
    manageMembers: true,
    delete: false,
    editPermissions: false,
    manageSources: false,
  },
};
const existingMember: GroupMember = {
  actorId: "existing",
  displayName: null,
  email: "existing@example.com",
  accountType: "STANDARD",
  status: "ACTIVE",
  isManager: true,
  protectedOwner: false,
};
const candidate: GroupMember = {
  actorId: "candidate",
  displayName: null,
  email: "candidate@example.com",
  accountType: "STANDARD",
  status: "ACTIVE",
  isManager: false,
  protectedOwner: false,
};
const session: ApplicationSession = {
  actorId: "owner",
  authorizationVersion: 1,
  uiLanguage: "en",
  tenant: { displayName: "Team", role: "OWNER" },
  capabilities: ["GROUPS_MANAGE"],
  scopedCapabilities: [],
};

function DeferredMembers() {
  const draft = useGroupMembersDraft(group);

  return (
    <>
      <GroupMembersSection group={group} draft={draft} />
      <button type="button" disabled={!draft.dirty} onClick={() => void draft.save()}>
        Save Changes
      </button>
      <button type="button" onClick={draft.reset}>
        Cancel
      </button>
    </>
  );
}

describe("GroupMembersSection", () => {
  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
  });

  it("keeps added members local until the page-level save and discards them on cancel", async () => {
    const additions: string[][] = [];
    vi.stubGlobal(
      "fetch",
      vi.fn(async (request: Request) => {
        const url = new URL(request.url);
        if (url.pathname === "/api/groups/team/members") {
          if (request.method === "POST") {
            additions.push(((await request.json()) as { actorIds: string[] }).actorIds);
            return Response.json({});
          }
          return Response.json({
            items: [existingMember],
            page: 0,
            size: 10,
            totalItems: 1,
            totalPages: 1,
          });
        }
        if (url.pathname === "/api/groups/team/candidates") {
          return Response.json({
            items: [candidate],
            page: 0,
            size: 10,
            totalItems: 1,
            totalPages: 1,
          });
        }
        throw new Error(`Unexpected request: ${request.method} ${url.pathname}`);
      }),
    );
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const user = userEvent.setup();
    render(
      <QueryClientProvider client={client}>
        <ApplicationSessionContext value={session}>
          <DeferredMembers />
        </ApplicationSessionContext>
      </QueryClientProvider>,
    );

    await user.click(await screen.findByRole("button", { name: "Add" }));
    await user.click(await screen.findByRole("checkbox"));
    await user.click(screen.getByRole("button", { name: "Add 1" }));
    expect(additions).toEqual([]);
    expect(screen.getByRole("button", { name: "Save Changes" })).toBeEnabled();

    await user.click(screen.getByRole("button", { name: "Cancel" }));
    expect(screen.getByRole("button", { name: "Save Changes" })).toBeDisabled();
    expect(additions).toEqual([]);

    await user.click(screen.getByRole("button", { name: "Add" }));
    await user.click(await screen.findByRole("checkbox"));
    await user.click(screen.getByRole("button", { name: "Add 1" }));
    await user.click(screen.getByRole("button", { name: "Save Changes" }));
    await waitFor(() => expect(additions).toEqual([["candidate"]]));
    client.clear();
  });
});
