import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { cleanup, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import { ActionNotifications } from "@/components/ui/action-notifications";
import type { ApplicationSession } from "@/features/identity/application-session-context";
import { ApplicationSessionProvider } from "@/features/identity/application-session-provider";
import type {
  SharePointConfigurationResponse,
  SourceOperation,
  SourceSummary,
} from "@/lib/hey-api/types.gen";
import { SharePointPanel } from "./sharepoint-panel";

const owner: ApplicationSession = {
  actorId: "7b9f56d0-3026-4d2d-8e5f-1d6af6da93a1",
  authorizationVersion: 1,
  uiLanguage: "en",
  tenant: { displayName: "Team", role: "OWNER" },
  capabilities: ["SOURCES_READ", "SOURCES_MANAGE", "SOURCES_DELETE"],
  scopedCapabilities: [],
};
const source: SourceSummary = {
  id: "46337ebd-a134-41de-b322-196cd9be22c4",
  name: "Finance SharePoint",
  type: "SHAREPOINT",
  access: "PRIVATE",
  status: "ACTIVE",
  pendingWork: false,
  documentCount: 0,
  lastSucceededAt: null,
  errorCode: null,
  managerActorId: null,
  managerName: null,
  permissions: {
    edit: true,
    delete: true,
    publish: false,
    manageConfiguration: true,
    removeItems: true,
  },
};
const clients: QueryClient[] = [];

afterEach(() => {
  cleanup();
  for (const client of clients) client.clear();
  clients.length = 0;
  sessionStorage.clear();
  vi.unstubAllGlobals();
});

function setup(initial: Partial<SharePointConfigurationResponse> = {}) {
  let configuration: SharePointConfigurationResponse = {
    sourceId: source.id,
    credentialId: "81c51573-31a9-4e67-91c5-f276960c94af",
    credentialName: "Contoso app",
    credentialStatus: "ACTIVE",
    credentialRevision: 1,
    scopeRevision: 2,
    scopeMode: "SPECIFIC",
    rootCount: 1,
    excludedSites: [],
    excludedPaths: [],
    includeDocuments: true,
    includePages: false,
    syncIntervalMinutes: 30,
    pruneIntervalHours: 168,
    scheduleRevision: 3,
    syncPaused: false,
    tenantHost: "contoso.sharepoint.com",
    pendingWork: false,
    ...initial,
  };
  let syncOperation: SourceOperation | null = null;
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
      if (url.pathname === "/api/credentials/sharepoint" && request.method === "GET")
        return Response.json([
          {
            id: configuration.credentialId,
            name: configuration.credentialName,
            directoryId: "d1f4a9b0-0000-4000-8000-000000000001",
            clientId: "d1f4a9b0-0000-4000-8000-000000000002",
            cloud: "GLOBAL",
            authMethod: "CLIENT_SECRET",
            status: configuration.credentialStatus,
            credentialRevision: configuration.credentialRevision,
            createdAt: "2026-09-01T00:00:00Z",
            updatedAt: "2026-09-01T00:00:00Z",
            sourceCount: 1,
            actions: ["test", "rename", "replace_authentication", "delete"],
          },
        ]);
      if (url.pathname === "/api/sources/sharepoint/selection-policy")
        return Response.json({
          maxRootsPerSource: 100,
          maxExclusionsPerKind: 100,
          maxRequestBytes: 2_097_152,
        });
      if (url.pathname.endsWith("/sharepoint/roots"))
        return Response.json({
          scopeRevision: configuration.scopeRevision,
          roots: [
            {
              url: "https://contoso.sharepoint.com/sites/Finance",
              kind: "SITE",
              displayName: "Finance",
              verified: true,
            },
          ],
          total: 1,
        });
      if (url.pathname.endsWith("/sharepoint") && request.method === "GET")
        return Response.json(configuration);
      if (url.pathname.endsWith("/sharepoint/pause") && request.method === "PUT") {
        const body = await request.json();
        if (body.expectedRevision !== configuration.scheduleRevision)
          return Response.json({ code: "SOURCE_SHAREPOINT_REVISION_CONFLICT" }, { status: 412 });
        configuration = {
          ...configuration,
          syncPaused: body.paused,
          scheduleRevision: configuration.scheduleRevision + 1,
        };
        return Response.json(configuration);
      }
      if (url.pathname.endsWith("/sharepoint/sync") && request.method === "POST") {
        syncOperation = {
          id: "sync-1",
          type: "SYNC_SOURCE",
          status: "SUCCEEDED",
          createdAt: "2026-09-08T10:00:00Z",
          completedAt: "2026-09-08T10:00:01Z",
          errorCode: null,
        };
        return Response.json(syncOperation, { status: 202 });
      }
      if (url.pathname.startsWith("/api/source-operations/") && syncOperation)
        return Response.json(syncOperation);
      throw new Error(`Unexpected request ${request.method} ${url.pathname}`);
    }),
  );
  const view = render(
    <QueryClientProvider client={queryClient}>
      <ApplicationSessionProvider session={owner}>
        <ActionNotifications>
          <SharePointPanel source={source} sourceStale={false} onBusyChange={() => {}} />
        </ActionNotifications>
      </ApplicationSessionProvider>
    </QueryClientProvider>,
  );
  return { requests, view };
}

describe("SharePoint panel", () => {
  it("shows the saved schedule, credential and scope", async () => {
    setup();
    await screen.findByText("30 minutes");
    expect(screen.getByText("Prune every 168 hours")).toBeInTheDocument();
    expect(screen.getByText("Contoso app")).toBeInTheDocument();
    expect(screen.getByText("contoso.sharepoint.com")).toBeInTheDocument();
    expect(
      await screen.findByText("https://contoso.sharepoint.com/sites/Finance"),
    ).toBeInTheDocument();
  });

  it("sends the schedule revision when pausing", async () => {
    const { requests } = setup();
    const user = userEvent.setup();
    await screen.findByText("30 minutes");
    await user.click(screen.getByRole("button", { name: "Pause automatic sync" }));
    const pause = requests.find((request) => request.url.endsWith("/sharepoint/pause"));
    expect(pause?.headers.get("X-MemoryOS-CSRF")).toBeTruthy();
    expect(await pause?.json()).toMatchObject({ expectedRevision: 3, paused: true });
  });

  it("requests a synchronization and reports completion", async () => {
    setup();
    const user = userEvent.setup();
    await screen.findByText("30 minutes");
    await user.click(screen.getByRole("button", { name: "Synchronize now" }));
    expect(await screen.findByText("Synchronization complete")).toBeInTheDocument();
  });
});
