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
  authMethod: "OAUTH",
  oauthClientConfigured: true,
  createdAt: "2026-09-01T08:00:00Z",
  updatedAt: "2026-09-10T08:00:00Z",
  sourceCount: 2,
  actions: [],
};

afterEach(cleanup);

describe("GoogleDriveConnectionAccount", () => {
  it.each([
    [
      "names an OAuth credential with its account and reports the connection",
      credential,
      true,
      "Workspace credential (drive-ops@example.com)",
      "Connected",
    ],
    [
      "names a service account with the account it acts as",
      { ...credential, authMethod: "SERVICE_ACCOUNT" },
      false,
      "Workspace credential (service account acting as drive-ops@example.com)",
      "Not connected",
    ],
    [
      "says no credential is selected while none is chosen",
      undefined,
      false,
      "No credential selected",
      "Not connected",
    ],
  ] as const)("%s", (_, chosen, connected, account, status) => {
    render(<GoogleDriveConnectionAccount credential={chosen} connected={connected} />);

    expect(screen.getByText("Google Drive")).toBeInTheDocument();
    expect(screen.getByText(account)).toBeInTheDocument();
    expect(screen.getByText(status)).toBeInTheDocument();
  });
});
