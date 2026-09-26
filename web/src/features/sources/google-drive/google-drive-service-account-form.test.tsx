import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { HttpResponse } from "msw";
import { describe, expect, it, vi } from "vitest";
import {
  handleCreateGoogleDriveServiceAccount,
  handleReplaceGoogleDriveServiceAccount,
} from "@/lib/hey-api/msw.gen";
import type { GoogleDriveCredentialResponse } from "@/lib/hey-api/types.gen";
import { server } from "@/test/msw";
import { GoogleDriveServiceAccountForm } from "./google-drive-service-account-form";

const key = {
  type: "service_account",
  client_email: "indexer@memoryos-prod.iam.gserviceaccount.com",
  client_id: "1045",
  private_key_id: "3f2a9c",
  private_key: "-----BEGIN PRIVATE KEY-----\nsynthetic-private-key\n-----END PRIVATE KEY-----\n",
};

const stored: GoogleDriveCredentialResponse = {
  id: "credential-1",
  name: "Workspace",
  accountEmail: "admin@example.com",
  status: "ACTIVE",
  credentialRevision: 1,
  authMethod: "SERVICE_ACCOUNT",
  serviceAccountEmail: key.client_email,
  oauthClientConfigured: false,
  createdAt: "2026-09-21T00:00:00Z",
  updatedAt: "2026-09-21T00:00:00Z",
  sourceCount: 0,
  actions: ["replace_key", "revoke", "delete"],
};

function jsonFile(content: unknown) {
  return new File([JSON.stringify(content)], "key.json", { type: "application/json" });
}

function setup(replacing: GoogleDriveCredentialResponse | null = null) {
  const requests: Request[] = [];
  server.use(
    handleCreateGoogleDriveServiceAccount(({ request }) => {
      requests.push(request.clone());
      return HttpResponse.json(stored, { status: 201 });
    }),
    handleReplaceGoogleDriveServiceAccount(({ request }) => {
      requests.push(request.clone());
      return HttpResponse.json(stored);
    }),
  );
  const onSaved = vi.fn();
  const { container } = render(
    <GoogleDriveServiceAccountForm
      replacing={replacing}
      disabled={false}
      onPendingChange={() => {}}
      onSaved={onSaved}
      onFailed={() => {}}
    />,
  );
  return { requests, onSaved, container };
}

describe("Google Drive service account form", () => {
  it("refuses a file that is not a service account key without echoing it", async () => {
    const user = userEvent.setup();
    setup();
    await user.type(screen.getByLabelText("Credential name"), "Workspace");
    await user.type(screen.getByLabelText("Primary admin email"), "admin@example.com");
    await user.upload(
      screen.getByLabelText("Upload service account JSON key"),
      jsonFile({ web: { client_id: "web", client_secret: "synthetic-secret" } }),
    );
    expect(await screen.findByRole("alert")).toHaveTextContent("not an OAuth client");
    expect(screen.getByRole("alert")).not.toHaveTextContent("synthetic-secret");
    expect(screen.getByRole("button", { name: "Save service account" })).toBeDisabled();
  });

  it("sends the key once, shows the account it identifies and never renders the private key", async () => {
    const user = userEvent.setup();
    const { requests, onSaved, container } = setup();
    await user.type(screen.getByLabelText("Credential name"), " Workspace ");
    await user.type(screen.getByLabelText("Primary admin email"), "admin@example.com");
    await user.upload(screen.getByLabelText("Upload service account JSON key"), jsonFile(key));
    expect(await screen.findByText(key.client_email)).toBeInTheDocument();
    expect(container).not.toHaveTextContent("synthetic-private-key");

    await user.click(screen.getByRole("button", { name: "Save service account" }));
    await waitFor(() => expect(onSaved).toHaveBeenCalledWith(stored));
    expect(requests).toHaveLength(1);
    expect(new URL(requests[0].url).pathname).toBe("/api/credentials/google-drive/service-account");
    expect(requests[0].method).toBe("POST");
    const body = (await requests[0].json()) as Record<string, string>;
    expect(body.name).toBe("Workspace");
    expect(body.adminEmail).toBe("admin@example.com");
    expect(JSON.parse(body.serviceAccountKeyJson)).toEqual(key);
    expect(screen.queryByText(key.client_email)).not.toBeInTheDocument();
  });

  it("replaces the key of an existing credential with its revision precondition", async () => {
    const user = userEvent.setup();
    const { requests, onSaved } = setup(stored);
    expect(screen.getByLabelText("Primary admin email")).toHaveValue("admin@example.com");
    await user.upload(screen.getByLabelText("Upload service account JSON key"), jsonFile(key));
    await user.click(await screen.findByRole("button", { name: "Replace key" }));
    await waitFor(() => expect(onSaved).toHaveBeenCalled());
    expect(new URL(requests[0].url).pathname).toBe(
      "/api/credentials/google-drive/credential-1/service-account",
    );
    expect(requests[0].method).toBe("PUT");
    expect(requests[0].headers.get("If-Match")).toBe('"1"');
  });
});
