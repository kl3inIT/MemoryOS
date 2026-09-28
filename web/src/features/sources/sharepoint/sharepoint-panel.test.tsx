import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { HttpResponse } from "msw";
import { afterEach, describe, expect, it } from "vitest";
import { ActionNotifications } from "@/components/ui/action-notifications";
import type { ApplicationSession } from "@/features/identity/application-session-context";
import { ApplicationSessionProvider } from "@/features/identity/application-session-provider";
import type {
  SharePointConfigurationResponse,
  SourceOperation,
  SourceSummary,
} from "@/lib/hey-api/types.gen";
import {
  handleGetSharePointConfiguration,
  handleGetSharePointRoots,
  handleGetSharePointSelectionPolicy,
  handleGetSourceOperation,
  handleListSharePointCredentials,
  handleListSourceGroups,
  handleSynchronizeSharePointSource,
  handleUpdateSharePointPause,
} from "@/lib/hey-api/msw.gen";
import { server } from "@/test/msw";
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
  for (const client of clients) client.clear();
  clients.length = 0;
  sessionStorage.clear();
});

function setup(
  initial: Partial<SharePointConfigurationResponse> = {},
  syncOutcome: Pick<SourceOperation, "status" | "errorCode"> = {
    status: "SUCCEEDED",
    errorCode: null,
  },
) {
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
  const record = (request: Request) => requests.push(request.clone());
  server.use(
    handleListSharePointCredentials(({ request }) => {
      record(request);
      return HttpResponse.json([
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
    }),
    handleGetSharePointSelectionPolicy({
      body: { maxRootsPerSource: 100, maxExclusionsPerKind: 100, maxRequestBytes: 2_097_152 },
    }),
    handleGetSharePointRoots({
      body: {
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
      },
    }),
    handleGetSharePointConfiguration(() => HttpResponse.json(configuration)),
    handleUpdateSharePointPause(async ({ request }) => {
      record(request);
      const body = (await request.json()) as { expectedRevision: number; paused: boolean };
      if (body.expectedRevision !== configuration.scheduleRevision)
        return HttpResponse.json({ code: "SOURCE_SHAREPOINT_REVISION_CONFLICT" }, { status: 412 });
      configuration = {
        ...configuration,
        syncPaused: body.paused,
        scheduleRevision: configuration.scheduleRevision + 1,
      };
      return HttpResponse.json(configuration);
    }),
    handleSynchronizeSharePointSource(({ request }) => {
      record(request);
      syncOperation = {
        id: "sync-1",
        type: "SYNC_SOURCE",
        createdAt: "2026-09-08T10:00:00Z",
        completedAt: "2026-09-08T10:00:01Z",
        ...syncOutcome,
      };
      return HttpResponse.json(syncOperation, { status: 202 });
    }),
    handleListSourceGroups({ body: { items: [] } }),
    handleGetSourceOperation(() =>
      syncOperation ? HttpResponse.json(syncOperation) : new HttpResponse(null, { status: 404 }),
    ),
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

  it("reports a synchronization stopped by a pause as cancelled, not failed or superseded", async () => {
    setup({}, { status: "CANCELLED", errorCode: "SOURCE_PAUSED" });
    const user = userEvent.setup();
    await screen.findByText("30 minutes");
    await user.click(screen.getByRole("button", { name: "Synchronize now" }));
    expect(await screen.findByText("Synchronization cancelled")).toBeInTheDocument();
    expect(screen.queryByText("Synchronization failed")).not.toBeInTheDocument();
    expect(screen.queryByText("Synchronization superseded")).not.toBeInTheDocument();
  });
});
