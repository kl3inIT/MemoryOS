import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { InvitationTable } from "@/features/invitations/invitation-table";
import type { Invitation } from "@/lib/hey-api/types.gen";

const invitation: Invitation = {
  id: "75c4e810-e1f2-45cb-9480-8e713a934bca",
  email: "member@example.com",
  status: "PENDING",
  createdAt: "2026-08-21T10:00:00Z",
  expiresAt: "2026-08-24T10:00:00Z",
};

describe("InvitationTable", () => {
  it("renders a semantic server-driven table with contextual row actions", () => {
    render(
      <InvitationTable
        invitations={[invitation]}
        sort="CREATED_AT_DESC"
        page={0}
        size={20}
        totalItems={25}
        pendingActions={{}}
        rowErrors={{}}
        onSortChange={vi.fn()}
        onPageChange={vi.fn()}
        onSizeChange={vi.fn()}
        onRotate={vi.fn()}
        onRevoke={vi.fn()}
      />,
    );

    expect(screen.getByRole("table", { name: "Organization invitations" })).toBeVisible();
    expect(screen.getByRole("columnheader", { name: /created/i })).toHaveAttribute(
      "aria-sort",
      "descending",
    );
    expect(
      screen.getByRole("button", { name: "Rotate invitation link for member@example.com" }),
    ).toBeVisible();
    expect(screen.getByText("Showing 1–20 of 25")).toBeVisible();
  });

  it("projects table sort and pagination changes to the canonical URL callbacks", async () => {
    const user = userEvent.setup();
    const onSortChange = vi.fn();
    const onPageChange = vi.fn();
    const onSizeChange = vi.fn();
    render(
      <InvitationTable
        invitations={[invitation]}
        sort="CREATED_AT_DESC"
        page={0}
        size={20}
        totalItems={25}
        pendingActions={{}}
        rowErrors={{}}
        onSortChange={onSortChange}
        onPageChange={onPageChange}
        onSizeChange={onSizeChange}
        onRotate={vi.fn()}
        onRevoke={vi.fn()}
      />,
    );

    await user.click(screen.getByRole("button", { name: "Email" }));
    expect(onSortChange).toHaveBeenCalledWith("EMAIL_ASC");

    await user.click(screen.getByRole("button", { name: "Next" }));
    expect(onPageChange).toHaveBeenCalledWith(1);

    await user.selectOptions(screen.getByRole("combobox", { name: "Rows per page" }), "50");
    expect(onSizeChange).toHaveBeenCalledWith(50);
  });
});
