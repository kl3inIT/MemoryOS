import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { HttpResponse } from "msw";
import { describe, expect, it } from "vitest";
import {
  ApplicationSessionContext,
  type ApplicationSession,
} from "@/features/identity/application-session-context";
import { useGroupMembersDraft } from "./group-members-draft";
import { GroupMembersSection } from "./group-members-section";
import {
  handleAddGroupMembers,
  handleListGroupCandidates,
  handleListGroupMembers,
} from "@/lib/hey-api/msw.gen";
import type { GroupMember, GroupSummary } from "@/lib/hey-api/types.gen";
import { server } from "@/test/msw";

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
  it("keeps added members local until the page-level save and discards them on cancel", async () => {
    const additions: string[][] = [];
    const page = (items: GroupMember[]) => ({
      items,
      page: 0,
      size: 10,
      totalItems: items.length,
      totalPages: 1,
    });
    server.use(
      handleListGroupMembers({ body: page([existingMember]) }),
      handleListGroupCandidates({ body: page([candidate]) }),
      handleAddGroupMembers(async ({ request }) => {
        additions.push((await request.json()).actorIds);
        return new HttpResponse(null, { status: 204 });
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
