import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { act, cleanup, render, screen, waitFor, within } from "@testing-library/react";
import userEvent, { type UserEvent } from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import { ActionNotifications } from "@/components/ui/action-notifications";
import type { ApplicationSession } from "@/features/identity/application-session-context";
import { ApplicationSessionProvider } from "@/features/identity/application-session-provider";
import {
  getGoogleDriveConfigurationQueryKey,
  getSourceOperationQueryKey,
} from "@/lib/hey-api/@tanstack/react-query.gen";
import type {
  GetGoogleDriveConfigurationResponse,
  GoogleDriveSelectionItemResponse,
  SourceOperation,
  SourceSummary,
} from "@/lib/hey-api/types.gen";
import { launchGoogleDriveAuthorization } from "./google-drive-authorization";
import { GoogleDrivePanel } from "./google-drive-panel";

vi.mock("./google-drive-authorization", () => ({ launchGoogleDriveAuthorization: vi.fn() }));
const owner: ApplicationSession = {
  actorId: "7b9f56d0-3026-4d2d-8e5f-1d6af6da93a1",
  tenant: { displayName: "Team", role: "OWNER" },
  capabilities: ["SOURCES_MANAGE"],
};
const source: SourceSummary = {
  id: "46337ebd-a134-41de-b322-196cd9be22c4",
  name: "Team Drive",
  type: "GOOGLE_DRIVE",
  access: "RESTRICTED",
  status: "ACTIVE",
  pendingWork: false,
  documentCount: 0,
  lastSucceededAt: null,
  errorCode: null,
};
const firstLink = "https://drive.google.com/file/d/file-a/view";
const secondLink = "https://drive.google.com/file/d/file-b/view";
const clients: QueryClient[] = [];
const candidate = (id: string, selected = false): GoogleDriveSelectionItemResponse => ({
  id,
  name: id === "budget" ? "Budget" : "Research",
  mimeType: "application/vnd.google-apps.document",
  kind: "LINKED",
  selected,
  coveredByRoots: false,
  status: "AVAILABLE",
  origins: [{ rootId: "file-a", parentId: "file-a", parentName: "Plan", location: "Paragraph 2" }],
});

afterEach(() => {
  cleanup();
  for (const client of clients) client.clear();
  clients.length = 0;
  sessionStorage.clear();
  vi.unstubAllGlobals();
  vi.clearAllMocks();
});

function setup(initial: Partial<GetGoogleDriveConfigurationResponse> = {}) {
  let configuration: GetGoogleDriveConfigurationResponse = {
    sourceId: source.id,
    credentialId: "81c51573-31a9-4e67-91c5-f276960c94af",
    accountEmail: "owner@example.com",
    credentialStatus: "ACTIVE",
    credentialRevision: 1,
    oauthClientConfigured: true,
    revision: 7,
    syncIntervalMinutes: 5,
    scheduleRevision: 3,
    scopeMode: "SPECIFIC",
    discoveryRevision: 2,
    discoveredAt: "2026-09-08T09:00:00Z",
    discoveryErrors: [],
    pendingWork: false,
    lastSyncedAt: null,
    errorCode: null,
    counts: { folders: 1, files: 1, linkedDocuments: 2, approvedLinkedDocuments: 1 },
    pendingSelectionOperation: null,
    ...initial,
  };
  let links = [firstLink];
  let approved = ["research"];
  let lostResponse = false;
  let rejection: number | undefined;
  let scheduleFailure: number | undefined;
  let operation: SourceOperation | null = null;
  let proposed: { links: string[]; linkedDocumentIds: string[]; requestId: string } | null = null;
  let storedRequestId: string | null = null;
  let authorize: (() => Promise<Response>) | undefined;
  const requests: Request[] = [];
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  clients.push(queryClient);
  vi.stubGlobal(
    "fetch",
    vi.fn(async (request: Request) => {
      requests.push(request.clone());
      const url = new URL(request.url);
      if (url.pathname === "/api/credentials/google-drive" && request.method === "GET")
        return Response.json([
          {
            id: configuration.credentialId,
            name: "Shared account",
            accountEmail: configuration.accountEmail,
            status: configuration.credentialStatus,
            credentialRevision: configuration.credentialRevision,
            oauthClientConfigured: configuration.oauthClientConfigured,
            createdAt: "2026-09-01T00:00:00Z",
            updatedAt: "2026-09-01T00:00:00Z",
            sourceCount: 2,
          },
        ]);
      if (url.pathname.endsWith("/selection-policy"))
        return Response.json({
          maxExplicitRootsPerSource: 21,
          maxRequestBytes: 100_000,
          maxLinkedDocuments: 500,
        });
      if (url.pathname.includes("/selection-requests/"))
        return operation && url.pathname.endsWith(storedRequestId ?? "missing")
          ? Response.json({ sourceId: source.id, operation })
          : Response.json({}, { status: 404 });
      if (url.pathname.endsWith("/google-drive/selection-draft"))
        return Response.json({
          revision: configuration.revision,
          discoveryRevision: configuration.discoveryRevision,
          credentialRevision: configuration.credentialRevision,
          links,
          linkedDocumentIds: approved,
        });
      if (
        url.pathname.endsWith("/google-drive/selection") ||
        url.pathname.endsWith("/google-drive/selection-tree")
      ) {
        const tree = url.pathname.endsWith("/selection-tree");
        const parentId = url.searchParams.get("parentId");
        const next = url.searchParams.get("cursor") === "second";
        let items: GoogleDriveSelectionItemResponse[] = next
          ? [candidate("research", true)]
          : [
              {
                id: "folder-a",
                name: "Team folder",
                mimeType: "application/vnd.google-apps.folder",
                kind: "FOLDER",
                status: "AVAILABLE",
                selected: true,
                coveredByRoots: false,
                origins: [],
              },
              {
                id: "file-a",
                name: "Plan",
                mimeType: "text/plain",
                kind: "FILE",
                status: "AVAILABLE",
                selected: true,
                coveredByRoots: false,
                origins: [],
              },
              candidate("budget"),
            ];
        if (url.searchParams.get("kind"))
          items = items.filter((item) => item.kind === url.searchParams.get("kind"));
        if (url.searchParams.get("search"))
          items = items.filter((item) =>
            item.name.toLowerCase().includes(url.searchParams.get("search")!.toLowerCase()),
          );
        if (tree) {
          items =
            parentId === "file-a"
              ? [candidate(next ? "research" : "budget", next)]
              : parentId
                ? []
                : items.filter((item) => item.kind !== "LINKED");
        }
        return Response.json({
          revision: configuration.revision,
          discoveryRevision: configuration.discoveryRevision,
          credentialRevision: configuration.credentialRevision,
          counts: configuration.counts,
          items: tree
            ? items.map((item) => ({
                ...item,
                expandable: item.kind !== "LINKED" || item.selected || item.coveredByRoots,
              }))
            : items,
          nextCursor: tree && parentId !== "file-a" ? null : next ? null : "second",
        });
      }
      if (url.pathname.endsWith("/google-drive") && request.method === "GET")
        return Response.json(configuration);
      if (url.pathname.endsWith("/runs"))
        return Response.json({
          items: [],
          nextCursor: null,
          current: null,
          lastCompleted: null,
          lastSuccessful: null,
        });
      if (url.pathname.endsWith("/roots") && request.method === "PUT") {
        if (rejection)
          return Response.json(
            {
              code:
                rejection === 412
                  ? "SOURCE_GOOGLE_REVISION_CONFLICT"
                  : "SOURCE_GOOGLE_ROOTS_OVERLAP",
              detail: "private provider secret",
            },
            { status: rejection },
          );
        const body = await request.json();
        if (!operation || body.requestId !== storedRequestId) {
          proposed = body;
          storedRequestId = body.requestId;
          operation = {
            id: crypto.randomUUID(),
            type: "VALIDATE_GOOGLE_DRIVE_SELECTION",
            status: "PENDING",
            createdAt: "2026-09-08T10:00:00Z",
            completedAt: null,
            errorCode: null,
          };
          configuration = { ...configuration, pendingSelectionOperation: operation };
        }
        if (lostResponse) {
          lostResponse = false;
          throw new TypeError("Network response lost");
        }
        return Response.json({ sourceId: source.id, operation }, { status: 202 });
      }
      if (url.pathname.startsWith("/api/source-operations/") && operation)
        return Response.json(operation);
      if (url.pathname.endsWith("/schedule")) {
        if (scheduleFailure)
          return Response.json({ detail: "private provider secret" }, { status: scheduleFailure });
        if (request.headers.get("If-Match") !== `"${configuration.scheduleRevision}"`)
          return Response.json({ code: "SOURCE_GOOGLE_REVISION_CONFLICT" }, { status: 412 });
        const body = await request.json();
        configuration = {
          ...configuration,
          syncIntervalMinutes: body.syncIntervalMinutes,
          scheduleRevision: configuration.scheduleRevision + 1,
        };
        return Response.json(configuration);
      }
      if (url.pathname.endsWith("/authorization"))
        return authorize
          ? authorize()
          : Response.json({
              authorizationUrl: "https://accounts.google.com/o/oauth2/v2/auth?state=test",
            });
      if (url.pathname.endsWith("/revoke")) {
        configuration = {
          ...configuration,
          credentialStatus: "REVOKED",
          credentialRevision: configuration.credentialRevision + 1,
        };
        return new Response(null, { status: 204 });
      }
      if (url.pathname.endsWith("/linked-documents/discover")) {
        configuration = {
          ...configuration,
          discoveryRevision: configuration.discoveryRevision + 1,
        };
        return Response.json(configuration);
      }
      if (url.pathname.endsWith("/sync"))
        return Response.json({
          id: "sync",
          type: "SYNC_SOURCE",
          status: "SUCCEEDED",
          createdAt: "2026-09-08T10:00:00Z",
          completedAt: "2026-09-08T10:00:01Z",
          errorCode: null,
        });
      throw new Error(`Unexpected request ${request.method} ${url.pathname}`);
    }),
  );
  const tree = (session: ApplicationSession) => (
    <QueryClientProvider client={queryClient}>
      <ApplicationSessionProvider session={session}>
        <ActionNotifications>
          <GoogleDrivePanel source={source} onBusyChange={() => {}} />
        </ActionNotifications>
      </ApplicationSessionProvider>
    </QueryClientProvider>
  );
  const view = render(tree(owner));
  return {
    requests,
    queryClient,
    loseResponse() {
      lostResponse = true;
    },
    reject(status?: number) {
      rejection = status;
    },
    failSchedule(status?: number) {
      scheduleFailure = status;
    },
    authorization(handler: () => Promise<Response>) {
      authorize = handler;
    },
    changeSession(session: ApplicationSession) {
      view.rerender(tree(session));
    },
    setConfiguration(next: Partial<GetGoogleDriveConfigurationResponse>) {
      configuration = { ...configuration, ...next };
      act(() => {
        queryClient.setQueryData(
          getGoogleDriveConfigurationQueryKey({ path: { sourceId: source.id } }),
          configuration,
        );
      });
    },
    async finish(status = "SUCCEEDED", errorCode: string | null = null) {
      if (!operation) throw new Error("No accepted operation");
      operation = { ...operation, status, errorCode, completedAt: "2026-09-08T10:01:00Z" };
      if (status === "SUCCEEDED" && proposed) {
        links = proposed.links;
        approved = proposed.linkedDocumentIds;
        configuration = {
          ...configuration,
          revision: configuration.revision + 1,
          pendingSelectionOperation: null,
        };
      }
      await act(async () => {
        queryClient.setQueryData(
          getSourceOperationQueryKey({ path: { operationId: operation!.id } }),
          operation,
        );
      });
    },
  };
}

async function edit(user: UserEvent) {
  const disclosure = await screen.findByText("File and folder links", { selector: "summary" });
  if (!disclosure.parentElement?.hasAttribute("open")) await user.click(disclosure);
  await user.click(await screen.findByRole("button", { name: "Edit selection" }));
  return screen.findByRole("textbox", { name: "File or folder links" });
}

describe("Google Drive enterprise selection", () => {
  it("loads the full draft separately and preserves hidden approvals through cursor pages and search", async () => {
    const user = userEvent.setup();
    const server = setup();
    await user.selectOptions(
      await screen.findByRole("combobox", { name: "Content type" }),
      "LINKED",
    );
    const input = await edit(user);
    expect(input).toHaveFocus();
    await user.click(await screen.findByRole("checkbox", { name: "Sync Budget" }));
    await user.click(screen.getByRole("button", { name: "Next selection page" }));
    expect(await screen.findByRole("checkbox", { name: "Sync Research" })).toBeChecked();
    await user.type(screen.getByRole("textbox", { name: "Search selected content" }), "Budget");
    await user.click(screen.getByRole("button", { name: "Search" }));
    expect(await screen.findByRole("checkbox", { name: "Sync Budget" })).toBeChecked();
    await user.click(screen.getByRole("button", { name: "Save selection" }));
    await screen.findByText("Pending validation");
    const write = server.requests.find(
      (request) => request.method === "PUT" && request.url.endsWith("roots"),
    )!;
    expect(write.headers.get("If-Match")).toBe('"7"');
    expect(await write.json()).toEqual({
      scopeMode: "SPECIFIC",
      links: [firstLink],
      linkedDocumentIds: ["research", "budget"],
      discoveryRevision: 2,
      credentialRevision: 1,
      requestId: expect.any(String),
    });
    await server.finish();
    expect(await screen.findByRole("button", { name: "Edit selection" })).toBeEnabled();
    expect(screen.queryByRole("textbox", { name: "File or folder links" })).not.toBeInTheDocument();
  });

  it("retains the idempotency key after a lost response and does not replace active roots before activation", async () => {
    const user = userEvent.setup();
    const server = setup();
    const input = await edit(user);
    await user.clear(input);
    await user.paste(secondLink);
    server.loseResponse();
    await user.click(screen.getByRole("button", { name: "Save selection" }));
    const retry = await screen.findByRole("button", { name: "Retry Save selection" });
    expect(input).toBeDisabled();
    await user.click(retry);
    await screen.findByText("Pending validation");
    const bodies = await Promise.all(
      server.requests
        .filter((request) => request.method === "PUT" && request.url.endsWith("roots"))
        .map((request) => request.json()),
    );
    expect(bodies).toHaveLength(2);
    expect(bodies[0]).toEqual(bodies[1]);
    await server.finish("FAILED", "SOURCE_GOOGLE_ROOTS_OVERLAP");
    expect(await screen.findByRole("alert")).not.toHaveTextContent("private provider secret");
    await waitFor(() => expect(input).toBeEnabled());
    expect(input).toHaveValue(secondLink);
  });

  it("uses the server root policy above twenty and cancels drafts without any write", async () => {
    const user = userEvent.setup();
    const server = setup();
    const input = await edit(user);
    const links = Array.from(
      { length: 21 },
      (_, index) => `https://drive.google.com/file/d/file-${index}/view`,
    );
    await user.clear(input);
    await user.paste(links.join("\n"));
    expect(screen.getByRole("button", { name: "Save selection" })).toBeEnabled();
    await user.paste("\nhttps://drive.google.com/file/d/overflow/view");
    expect(screen.getByRole("button", { name: "Save selection" })).toBeDisabled();
    await user.click(
      within(screen.getByRole("region", { name: "Selected content" })).getByRole("button", {
        name: "Cancel",
      }),
    );
    expect(screen.getByRole("button", { name: "Edit selection" })).toHaveFocus();
    expect(server.requests.filter((request) => request.method === "PUT")).toHaveLength(0);
  });

  it("fences a stale complete draft and requires explicit reload rather than dropping approvals", async () => {
    const user = userEvent.setup();
    const server = setup();
    const input = await edit(user);
    await user.clear(input);
    await user.paste(secondLink);
    server.setConfiguration({ discoveryRevision: 3 });
    await waitFor(() =>
      expect(screen.getByRole("button", { name: "Save selection" })).toBeDisabled(),
    );
    expect(input).toHaveValue(secondLink);
    await user.click(screen.getByRole("button", { name: "Reload saved selection" }));
    await waitFor(() => expect(input).toHaveValue(firstLink));
    expect(screen.getByRole("button", { name: "Save selection" })).toBeDisabled();
  });

  it("keeps rejected requests editable with one safe error and no automatic retry", async () => {
    const user = userEvent.setup();
    const server = setup();
    const input = await edit(user);
    await user.clear(input);
    await user.paste(secondLink);
    server.reject(400);
    await user.click(screen.getByRole("button", { name: "Save selection" }));
    expect(await screen.findByRole("alert")).not.toHaveTextContent("private provider secret");
    await waitFor(() => expect(input).toBeEnabled());
    expect(server.requests.filter((request) => request.method === "PUT")).toHaveLength(1);
    expect(input).toHaveValue(secondLink);
  });

  it("rejects fractional and overflowing intervals while allowing the supported integer boundary", async () => {
    const user = userEvent.setup();
    const server = setup();
    await user.click(await screen.findByRole("button", { name: "Edit interval" }));
    const interval = screen.getByRole("spinbutton", { name: "Interval in minutes" });
    await user.clear(interval);
    await user.type(interval, "1.5");
    expect(screen.getByRole("button", { name: "Save interval" })).toBeDisabled();
    await user.clear(interval);
    await user.type(interval, "2147483648");
    expect(screen.getByRole("button", { name: "Save interval" })).toBeDisabled();
    expect(server.requests.filter((request) => request.method === "PUT")).toHaveLength(0);
    await user.clear(interval);
    await user.type(interval, "2147483647");
    await user.click(screen.getByRole("button", { name: "Save interval" }));
    await screen.findByRole("button", { name: "Edit interval" });
    const write = server.requests.find((request) => request.url.endsWith("/schedule"))!;
    expect(await write.json()).toEqual({ syncIntervalMinutes: 2147483647 });
  });

  it("preserves a root draft while independently fencing and saving the automatic interval", async () => {
    const user = userEvent.setup();
    const server = setup();
    const input = await edit(user);
    await user.clear(input);
    await user.paste(secondLink);
    await user.click(screen.getByRole("button", { name: "Edit interval" }));
    const interval = screen.getByRole("spinbutton", { name: "Interval in minutes" });
    await user.clear(interval);
    await user.type(interval, "15");
    server.setConfiguration({ scheduleRevision: 4, syncIntervalMinutes: 30 });
    await waitFor(() =>
      expect(screen.getByRole("button", { name: "Save interval" })).toBeDisabled(),
    );
    expect(interval).toHaveValue(15);
    await user.click(screen.getByRole("button", { name: "Reload saved interval" }));
    await waitFor(() => expect(interval).toHaveValue(30));
    await user.clear(interval);
    await user.type(interval, "1");
    server.failSchedule(503);
    await user.click(screen.getByRole("button", { name: "Save interval" }));
    expect(await screen.findByRole("alert")).not.toHaveTextContent("private provider secret");
    expect(input).toHaveValue(secondLink);
    server.failSchedule();
    await user.click(screen.getByRole("button", { name: "Save interval" }));
    expect(await screen.findByText("1 minute")).toBeVisible();
    expect(input).toHaveValue(secondLink);
  });

  it("keeps General immutable while leaving interval and synchronization controls available", async () => {
    const user = userEvent.setup();
    setup({ scopeMode: "GENERAL" });
    await screen.findByRole("region", { name: "Selected content" });
    expect(screen.queryByRole("button", { name: "Edit selection" })).not.toBeInTheDocument();
    expect(screen.queryByRole("radio")).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Synchronize now" })).toBeEnabled();
    await user.click(screen.getByRole("button", { name: "Edit interval" }));
    expect(screen.getByRole("spinbutton", { name: "Interval in minutes" })).toBeEnabled();
  });

  it("never caches owner-supplied OAuth secrets and ignores late authorization after actor change", async () => {
    const user = userEvent.setup();
    const server = setup();
    await user.click(await screen.findByText("Credentials"));
    await user.click(screen.getByRole("checkbox", { name: "Replace OAuth app on reconnect" }));
    const input = screen.getByRole("textbox", { name: "Upload or paste OAuth app JSON" });
    const secret = "synthetic-secret-never-persist";
    await user.click(input);
    await user.paste(
      JSON.stringify({
        web: { client_id: "test.apps.googleusercontent.com", client_secret: secret },
      }),
    );
    let complete!: (response: Response) => void;
    server.authorization(
      () =>
        new Promise<Response>((resolve) => {
          complete = resolve;
        }),
    );
    await user.click(screen.getByRole("button", { name: "Reconnect Google Drive" }));
    await user.click(
      within(screen.getByRole("alertdialog")).getByRole("button", { name: "Reconnect" }),
    );
    await waitFor(() => expect(complete).toBeTypeOf("function"));
    expect(input).toHaveValue("");
    server.changeSession({ ...owner, actorId: "97c41cb9-55ae-4a52-94ab-7aad59be91e5" });
    await act(async () =>
      complete(
        Response.json({
          authorizationUrl: "https://accounts.google.com/o/oauth2/v2/auth?state=late",
        }),
      ),
    );
    expect(launchGoogleDriveAuthorization).not.toHaveBeenCalled();
    expect(
      JSON.stringify(
        server.queryClient
          .getQueryCache()
          .getAll()
          .map((query) => query.state),
      ),
    ).not.toContain(secret);
    expect(JSON.stringify(server.queryClient.getMutationCache().getAll())).not.toContain(secret);
    expect(JSON.stringify(sessionStorage)).not.toContain(secret);
  });
});
