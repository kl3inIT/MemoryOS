import { cleanup, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import type { GoogleDriveCredentialResponse } from "@/lib/hey-api/types.gen";
import { GoogleDriveCredentialActions } from "./google-drive-credential-actions";

const credential: GoogleDriveCredentialResponse = {
  id: "credential-1",
  name: "Workspace credential",
  accountEmail: "drive-ops@example.com",
  status: "ACTIVE",
  credentialRevision: 3,
  authMethod: "OAUTH",
  oauthClientConfigured: true,
  createdAt: "2026-09-01T08:00:00Z",
  updatedAt: "2026-09-10T08:00:00Z",
  sourceCount: 2,
  actions: ["reauthorize", "revoke", "delete"],
};

function renderActions(overrides: Partial<GoogleDriveCredentialResponse> = {}) {
  const onReconnect = vi.fn();
  const onRevoke = vi.fn(() => Promise.resolve());
  render(
    <GoogleDriveCredentialActions
      credential={{ ...credential, ...overrides }}
      disabled={false}
      onReconnect={onReconnect}
      onReplaceKey={vi.fn()}
      onRevoke={onRevoke}
      onDelete={vi.fn(() => Promise.resolve())}
      errorMessage={() => "Google Drive is unavailable."}
    />,
  );
  return { onReconnect, onRevoke };
}

afterEach(cleanup);

describe("GoogleDriveCredentialActions", () => {
  it("keeps the actions behind one menu that reports the attached Sources", async () => {
    const user = userEvent.setup();
    const { onReconnect } = renderActions();

    expect(screen.queryByRole("menuitem", { name: "Reconnect" })).not.toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Manage Workspace credential" }));

    expect(screen.getByText("Used by 2 Sources")).toBeInTheDocument();
    await user.click(screen.getByRole("menuitem", { name: "Reconnect" }));

    expect(onReconnect).toHaveBeenCalledTimes(1);
  });

  it("confirms a revocation before revoking", async () => {
    const user = userEvent.setup();
    const { onRevoke } = renderActions();

    await user.click(screen.getByRole("button", { name: "Manage Workspace credential" }));
    await user.click(screen.getByRole("menuitem", { name: "Revoke" }));

    expect(screen.getByRole("alertdialog")).toHaveTextContent("Revoke Workspace credential?");
    expect(onRevoke).not.toHaveBeenCalled();

    await user.click(screen.getByRole("button", { name: "Revoke" }));

    expect(onRevoke).toHaveBeenCalledTimes(1);
  });

  it("blocks deletion while Sources are attached and renders nothing without actions", async () => {
    const user = userEvent.setup();
    renderActions();

    await user.click(screen.getByRole("button", { name: "Manage Workspace credential" }));

    expect(screen.getByRole("menuitem", { name: "Delete" })).toHaveAttribute(
      "aria-disabled",
      "true",
    );
    expect(
      screen.getByText("Delete all attached Sources before deleting this credential"),
    ).toBeInTheDocument();

    cleanup();
    renderActions({ actions: [] });

    expect(screen.queryByRole("button")).not.toBeInTheDocument();
  });
});
