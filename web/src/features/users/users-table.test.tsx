import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import type { ComponentProps } from "react";
import { describe, expect, it, vi } from "vitest";
import type { UserListItem } from "@/lib/hey-api/types.gen";
import { UsersTable } from "./users-table";

const activeMember: UserListItem = {
  actorId: "97c41cb9-55ae-4a52-94ab-7aad59be91e5",
  invitationId: null,
  displayName: "Rowan Brooks",
  email: "rowan@example.com",
  emailVerified: true,
  profileIssuer: "https://identity.example.com",
  role: "MEMBER",
  status: "ACTIVE",
  invitationExpiresAt: null,
  accountType: "STANDARD",
  groups: [
    {
      id: "6d11ec56-34c6-44fe-9ad0-f147f37f571c",
      name: "Admin",
      systemKey: "ADMIN",
    },
    {
      id: "234e1244-b81e-471a-8a67-84f67ebc57b8",
      name: "Research",
      systemKey: null,
    },
    {
      id: "930c8397-36f0-4479-8f46-a2d7c51c7f4f",
      name: "Support",
      systemKey: null,
    },
  ],
};

const owner: UserListItem = {
  ...activeMember,
  actorId: "7b9f56d0-3026-4d2d-8e5f-1d6af6da93a1",
  displayName: "Alex Morgan",
  email: "alex@example.com",
  role: "OWNER",
};

const invitation: UserListItem = {
  actorId: null,
  invitationId: "75c4e810-e1f2-45cb-9480-8e713a934bca",
  displayName: null,
  email: "invitee@example.com",
  emailVerified: null,
  profileIssuer: null,
  role: null,
  status: "INVITED",
  invitationExpiresAt: "2026-09-20T10:00:00Z",
  accountType: null,
  groups: [],
};

function renderTable(overrides: Partial<ComponentProps<typeof UsersTable>> = {}) {
  const props: ComponentProps<typeof UsersTable> = {
    entries: [owner, activeMember, invitation],
    sort: "NAME_ASC",
    page: 0,
    size: 20,
    totalItems: 3,
    totalPages: 1,
    pendingActions: {},
    rowErrors: {},
    invitationPending: false,
    canEditGroups: false,
    onGroupsSaved: vi.fn(async () => undefined),
    onSortChange: vi.fn(),
    onPageChange: vi.fn(),
    onSizeChange: vi.fn(),
    onActivate: vi.fn(async () => undefined),
    onDeactivate: vi.fn(async () => undefined),
    onRotate: vi.fn(async () => undefined),
    onRevoke: vi.fn(async () => undefined),
    ...overrides,
  };
  render(<UsersTable {...props} />);
}

describe("UsersTable", () => {
  it("renders identity, real groups, account type, owner presentation, and invitation boundaries", () => {
    renderTable();

    expect(screen.getByRole("table", { name: "Tenant users" })).toBeVisible();
    expect(screen.getByText("Rowan Brooks")).toBeVisible();
    expect(screen.getByText("rowan@example.com")).toBeVisible();
    expect(screen.getAllByLabelText("Admin, Research, Support")).toHaveLength(2);
    expect(screen.getAllByText("Standard")).toHaveLength(2);
    expect(
      screen.getByLabelText("Account type assigned after invitation acceptance"),
    ).toBeVisible();
    expect(screen.getByText("Owner")).toBeVisible();
    expect(screen.getByLabelText("No actions available for Alex Morgan")).toBeVisible();
  });

  it("confirms member deactivation and restores focus to the row menu", async () => {
    const user = userEvent.setup();
    renderTable({ entries: [activeMember], totalItems: 1, totalPages: 1 });
    const actionButton = screen.getByRole("button", { name: "Actions for Rowan Brooks" });

    await user.click(actionButton);
    await user.click(screen.getByRole("button", { name: "Deactivate member" }));
    const confirmation = screen.getByRole("alertdialog");
    expect(within(confirmation).getByRole("button", { name: "Cancel" })).toHaveFocus();
    await user.click(within(confirmation).getByRole("button", { name: "Deactivate member" }));

    await waitFor(() => expect(actionButton).toHaveFocus());
  });

  it("keeps row-scoped progress and failure feedback adjacent to the affected user", () => {
    renderTable({
      entries: [activeMember],
      totalItems: 1,
      totalPages: 1,
      pendingActions: { [`actor:${activeMember.actorId}`]: "deactivate" },
      rowErrors: { [`actor:${activeMember.actorId}`]: "Access could not be changed." },
    });
    expect(screen.getByText("Deactivating…")).toBeVisible();
    expect(screen.getByRole("alert")).toHaveTextContent("Access could not be changed.");
    expect(screen.getByRole("button", { name: "Deactivating… for Rowan Brooks" })).toBeDisabled();
  });
});
