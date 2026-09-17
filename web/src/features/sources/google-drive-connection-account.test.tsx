import { cleanup, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it } from "vitest";
import type { GoogleDriveCredentialResponse } from "@/lib/hey-api/types.gen";
import { GoogleDriveConnectionAccount } from "./google-drive-connection-account";

const credential: GoogleDriveCredentialResponse = {
  id: "credential-1",
  name: "Workspace credential",
  accountEmail: "drive-ops@example.com",
  status: "ACTIVE",
  credentialRevision: 3,
  oauthClientConfigured: true,
  createdAt: "2026-09-01T08:00:00Z",
  updatedAt: "2026-09-10T08:00:00Z",
  sourceCount: 2,
  actions: [],
};

afterEach(cleanup);

describe("GoogleDriveConnectionAccount", () => {
  it("names the credential with its account and reports the connection", () => {
    render(<GoogleDriveConnectionAccount credential={credential} connected={true} />);

    expect(screen.getByText("Google Drive")).toBeInTheDocument();
    expect(screen.getByText("Workspace credential (drive-ops@example.com)")).toBeInTheDocument();
    expect(screen.getByText("Connected")).toBeInTheDocument();
  });

  it("says no credential is selected while none is chosen", () => {
    render(<GoogleDriveConnectionAccount credential={undefined} connected={false} />);

    expect(screen.getByText("No credential selected")).toBeInTheDocument();
    expect(screen.getByText("Not connected")).toBeInTheDocument();
  });

  it("warns when the chosen credential is not connected", () => {
    render(<GoogleDriveConnectionAccount credential={credential} connected={false} />);

    expect(screen.getByText("Workspace credential (drive-ops@example.com)")).toBeInTheDocument();
    expect(screen.getByText("Not connected")).toBeInTheDocument();
    expect(screen.queryByText("Connected")).not.toBeInTheDocument();
  });
});
