import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { ApiError } from "@/lib/api";
import { InvitationDialog } from "./invitation-dialog";

function renderDialog(onCreate: (email: string) => Promise<void>) {
  render(
    <InvitationDialog
      open
      pending={false}
      issuedInvitation={null}
      returnFocusRef={{ current: null }}
      fallbackFocusRef={{ current: null }}
      onOpenChange={() => {}}
      onCreate={onCreate}
    />,
  );
  return userEvent.setup();
}

describe("InvitationDialog", () => {
  it("asks for a valid email before inviting and sends it trimmed", async () => {
    const onCreate = vi.fn(async () => undefined);
    const user = renderDialog(onCreate);
    const email = screen.getByRole("textbox", { name: "Email address" });

    await user.click(screen.getByRole("button", { name: "Send invitation" }));
    expect(await screen.findByText("Enter an email address.")).toBeVisible();

    await user.type(email, "not-an-email");
    expect(await screen.findByText("Enter a valid email address.")).toBeVisible();
    expect(onCreate).not.toHaveBeenCalled();

    await user.clear(email);
    await user.type(email, "  member@example.com ");
    await user.click(screen.getByRole("button", { name: "Send invitation" }));
    expect(onCreate).toHaveBeenCalledWith("member@example.com");
  });

  it("shows a conflicting invitation on the form and lets the next attempt clear it", async () => {
    const onCreate = vi
      .fn<(email: string) => Promise<void>>()
      .mockRejectedValueOnce(
        new ApiError(409, {
          title: "Conflict",
          status: 409,
          code: "INVITATION_CONFLICT",
        }),
      )
      .mockResolvedValueOnce(undefined);
    const user = renderDialog(onCreate);

    await user.type(screen.getByRole("textbox", { name: "Email address" }), "member@example.com");
    await user.click(screen.getByRole("button", { name: "Send invitation" }));
    expect(
      await screen.findByText("An open invitation already exists for this email."),
    ).toBeVisible();

    await user.click(screen.getByRole("button", { name: "Send invitation" }));
    expect(onCreate).toHaveBeenCalledTimes(2);
    expect(screen.queryByText("An open invitation already exists for this email.")).toBeNull();
  });
});
