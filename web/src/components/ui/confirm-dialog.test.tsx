import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";

import { Button } from "@/components/ui/button";
import { ConfirmDialog } from "@/components/ui/confirm-dialog";

describe("ConfirmDialog", () => {
  it("focuses Cancel and closes without confirming", async () => {
    const user = userEvent.setup();
    const onConfirm = vi.fn(async () => undefined);
    render(
      <ConfirmDialog
        trigger={<Button>Delete source</Button>}
        title="Delete Product documentation?"
        description="Indexed documents will become unavailable."
        confirmLabel="Delete source"
        pendingLabel="Deleting source"
        onConfirm={onConfirm}
      />,
    );

    const trigger = screen.getByRole("button", { name: "Delete source" });
    await user.click(trigger);

    const cancel = screen.getByRole("button", { name: "Cancel" });
    await waitFor(() => expect(cancel).toHaveFocus());
    expect(screen.getByRole("alertdialog")).toHaveAccessibleDescription(
      "Indexed documents will become unavailable.",
    );

    await user.keyboard("{Escape}");
    expect(screen.queryByRole("alertdialog")).not.toBeInTheDocument();
    expect(onConfirm).not.toHaveBeenCalled();
    expect(trigger).toHaveFocus();

    await user.click(trigger);
    await user.click(screen.getByRole("button", { name: "Cancel" }));
    expect(onConfirm).not.toHaveBeenCalled();
  });

  it("blocks duplicate activation and closes only after success", async () => {
    const user = userEvent.setup();
    const operation = Promise.withResolvers<void>();
    const onConfirm = vi.fn(() => operation.promise);
    render(
      <ConfirmDialog
        trigger={<Button>Remove</Button>}
        title="Remove knowledge.txt?"
        description="Its indexed document will become unavailable."
        confirmLabel="Remove file"
        pendingLabel="Removing file"
        onConfirm={onConfirm}
      />,
    );

    const trigger = screen.getByRole("button", { name: "Remove" });
    await user.click(trigger);
    await user.click(screen.getByRole("button", { name: "Remove file" }));

    const pending = screen.getByRole("button", { name: "Removing file" });
    expect(pending).toBeDisabled();
    expect(pending).toHaveAttribute("aria-busy", "true");
    expect(screen.getByRole("button", { name: "Cancel" })).toBeDisabled();
    fireEvent.click(pending);
    fireEvent.keyDown(pending, { key: "Enter" });
    expect(onConfirm).toHaveBeenCalledTimes(1);
    expect(screen.getByRole("alertdialog")).toBeInTheDocument();

    operation.resolve();
    await waitFor(() => expect(screen.queryByRole("alertdialog")).not.toBeInTheDocument());
    expect(trigger).toHaveFocus();
  });

  it("retains a failed confirmation with safe feedback and permits retry", async () => {
    const user = userEvent.setup();
    const onConfirm = vi
      .fn<() => Promise<void>>()
      .mockRejectedValueOnce(new Error("unsafe backend detail"))
      .mockResolvedValueOnce(undefined);
    render(
      <ConfirmDialog
        trigger={<Button>Delete source</Button>}
        title="Delete Product documentation?"
        description="Indexed documents will become unavailable."
        confirmLabel="Delete source"
        pendingLabel="Deleting source"
        onConfirm={onConfirm}
        errorMessage={() => "Source cleanup failed. Try deleting the source again."}
      />,
    );

    await user.click(screen.getByRole("button", { name: "Delete source" }));
    await user.click(
      within(screen.getByRole("alertdialog")).getByRole("button", { name: "Delete source" }),
    );

    expect(await screen.findByRole("alert")).toHaveTextContent(
      "Source cleanup failed. Try deleting the source again.",
    );
    expect(screen.getByRole("alertdialog")).toBeInTheDocument();
    expect(screen.queryByText("unsafe backend detail")).not.toBeInTheDocument();

    await user.click(
      within(screen.getByRole("alertdialog")).getByRole("button", { name: "Delete source" }),
    );
    await waitFor(() => expect(screen.queryByRole("alertdialog")).not.toBeInTheDocument());
    expect(onConfirm).toHaveBeenCalledTimes(2);
  });
});
