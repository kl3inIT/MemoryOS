import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { act, cleanup, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import { ActionNotifications } from "@/components/ui/action-notifications";
import type { ApplicationSession } from "@/features/identity/application-session-context";
import { ApplicationSessionProvider } from "@/features/identity/application-session-provider";
import { getGoogleDriveConfigurationQueryKey } from "@/lib/hey-api/@tanstack/react-query.gen";
import type { GetGoogleDriveConfigurationResponse, SourceSummary } from "@/lib/hey-api/types.gen";
import { launchGoogleDriveAuthorization } from "./google-drive-authorization";
import { GoogleDrivePanel } from "./google-drive-panel";

vi.mock("./google-drive-authorization", () => ({
  launchGoogleDriveAuthorization: vi.fn(),
}));

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
const folder = {
  id: "folder-a",
  name: "Team folder",
  mimeType: "application/vnd.google-apps.folder",
};
const first = { id: "file-a", name: "Plan", mimeType: "text/plain" };
const second = { id: "file-b", name: "Notes", mimeType: "text/plain" };
const folderLink = "https://drive.google.com/drive/folders/folder-a";
const firstLink = "https://drive.google.com/file/d/file-a/view";
const secondLink = "https://drive.google.com/file/d/file-b/view";
const clientJson = JSON.stringify({
  web: {
    client_id: "test-client.apps.googleusercontent.com",
    client_secret: "synthetic-client-secret",
  },
});
const clients: QueryClient[] = [];

afterEach(() => {
  cleanup();
  for (const client of clients) client.clear();
  clients.length = 0;
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
    revision: 1,
    syncIntervalMinutes: 5,
    scheduleRevision: 1,
    scopeMode: "SPECIFIC",
    roots: [],
    pendingWork: false,
    lastSyncedAt: null,
    errorCode: null,
    ...initial,
  };
  let rejectOverlap = false;
  let rejectRevision = false;
  let saveCount = 0;
  let nextConfigurationRead: (() => Promise<Response>) | undefined;
  let nextScheduleWrite: (() => Promise<void>) | undefined;
  let scheduleFailure: number | undefined;
  let configurationFailure: number | undefined;
  let revokeFailure: number | undefined;
  let authorizationResponse = () =>
    Promise.resolve(
      Response.json({
        authorizationUrl: "https://accounts.google.com/o/oauth2/v2/auth?state=test",
      }),
    );
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
      if (request.method === "GET" && url.pathname === "/api/credentials/google-drive")
        return Response.json([
          {
            id: configuration.credentialId,
            name: "Shared Google account",
            accountEmail: configuration.accountEmail,
            status: configuration.credentialStatus,
            credentialRevision: configuration.credentialRevision,
            oauthClientConfigured: configuration.oauthClientConfigured,
            createdAt: "2026-09-01T00:00:00Z",
            updatedAt: "2026-09-02T00:00:00Z",
            sourceCount: 2,
          },
        ]);
      if (request.method === "GET" && url.pathname.endsWith("/google-drive")) {
        if (configurationFailure)
          return Response.json(
            { detail: "private provider detail" },
            { status: configurationFailure },
          );
        const delayed = nextConfigurationRead;
        nextConfigurationRead = undefined;
        return delayed ? delayed() : Response.json(configuration);
      }
      if (request.method === "PUT" && url.pathname.endsWith("/schedule")) {
        const delayed = nextScheduleWrite;
        nextScheduleWrite = undefined;
        await delayed?.();
        if (scheduleFailure)
          return Response.json({ detail: "private provider detail" }, { status: scheduleFailure });
        if (request.headers.get("If-Match") !== `"${configuration.scheduleRevision}"`)
          return Response.json({ code: "SOURCE_GOOGLE_REVISION_CONFLICT" }, { status: 409 });
        const { syncIntervalMinutes } = (await request.json()) as { syncIntervalMinutes: number };
        configuration = {
          ...configuration,
          syncIntervalMinutes,
          scheduleRevision: configuration.scheduleRevision + 1,
        };
        return Response.json(configuration);
      }
      if (request.method === "PUT" && url.pathname.endsWith("/roots")) {
        if (rejectRevision || request.headers.get("If-Match") !== `"${configuration.revision}"`)
          return Response.json({ code: "SOURCE_GOOGLE_REVISION_CONFLICT" }, { status: 412 });
        if (rejectOverlap)
          return Response.json(
            { code: "SOURCE_GOOGLE_ROOTS_OVERLAP", detail: "private provider detail" },
            { status: 400 },
          );
        const { scopeMode, links } = (await request.json()) as {
          scopeMode: GetGoogleDriveConfigurationResponse["scopeMode"];
          links: string[];
        };
        configuration = {
          ...configuration,
          revision: configuration.revision + 1,
          scopeMode,
          roots: [folder, first, second].filter((_root, index) =>
            links.includes([folderLink, firstLink, secondLink][index]!),
          ),
        };
        saveCount += 1;
        return Response.json(configuration);
      }
      if (
        request.method === "POST" &&
        url.pathname === "/api/credentials/google-drive/authorization"
      )
        return authorizationResponse();
      if (
        request.method === "POST" &&
        url.pathname === `/api/credentials/google-drive/${configuration.credentialId}/revoke`
      ) {
        if (revokeFailure)
          return Response.json({ code: "SOURCE_CONFLICT" }, { status: revokeFailure });
        const body = (await request.json()) as { expectedCredentialRevision: number };
        if (body.expectedCredentialRevision !== configuration.credentialRevision)
          return Response.json({ code: "SOURCE_CONFLICT" }, { status: 409 });
        configuration = {
          ...configuration,
          credentialStatus: "REVOKED",
          credentialRevision: configuration.credentialRevision + 1,
        };
        return new Response(null, { status: 204 });
      }
      if (request.method === "POST" && url.pathname.endsWith("/sync")) {
        configuration = { ...configuration, pendingWork: true };
        return Response.json({
          id: "sync-1",
          type: "SYNC_SOURCE",
          status: "PENDING",
          createdAt: "2026-09-01T00:00:00Z",
          completedAt: null,
          errorCode: null,
        });
      }
      throw new Error(`Unexpected request ${request.method} ${url.pathname}`);
    }),
  );
  const panel = (session: ApplicationSession, showPanel = true) => (
    <QueryClientProvider client={queryClient}>
      <ApplicationSessionProvider session={session}>
        <ActionNotifications>
          {showPanel ? <GoogleDrivePanel source={source} onBusyChange={() => {}} /> : null}
        </ActionNotifications>
      </ApplicationSessionProvider>
    </QueryClientProvider>
  );
  const view = render(panel(owner));
  return {
    requests,
    queryClient,
    changeSession(session: ApplicationSession) {
      view.rerender(panel(session));
    },
    unmount: view.unmount,
    leaveSource() {
      view.rerender(panel(owner, false));
    },
    remount() {
      view.rerender(<></>);
      queryClient.removeQueries({
        queryKey: getGoogleDriveConfigurationQueryKey({ path: { sourceId: source.id } }),
      });
      view.rerender(panel(owner));
    },
    setSavedConfiguration(next: Partial<GetGoogleDriveConfigurationResponse>) {
      configuration = { ...configuration, ...next };
    },
    delayScheduleWrite() {
      let finish!: () => void;
      const pending = new Promise<void>((resolve) => {
        finish = resolve;
      });
      nextScheduleWrite = () => pending;
      return finish;
    },
    failSchedule(status: number | undefined) {
      scheduleFailure = status;
    },
    failConfiguration(status: number | undefined) {
      configurationFailure = status;
    },
    failRevoke(status: number | undefined) {
      revokeFailure = status;
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
    delayNextRead() {
      const snapshot = configuration;
      let finish!: (response: Response) => void;
      let markStarted!: () => void;
      const response = new Promise<Response>((resolve) => {
        finish = resolve;
      });
      const started = new Promise<void>((resolve) => {
        markStarted = resolve;
      });
      nextConfigurationRead = () => {
        markStarted();
        return response;
      };
      return { started, finish: () => finish(Response.json(snapshot)) };
    },
    authorization(handler: () => Promise<Response>) {
      authorizationResponse = handler;
    },
    overlap(value: boolean) {
      rejectOverlap = value;
    },
    revisionConflict(value: boolean) {
      rejectRevision = value;
    },
    saves: () => saveCount,
  };
}

describe("Google Drive source management", () => {
  it("keeps a General mode draft through status and interval saves, then saves and reloads its scope", async () => {
    const user = userEvent.setup();
    const server = setup({ scopeMode: "SPECIFIC", roots: [first], revision: 7 });
    await user.click(await screen.findByRole("radio", { name: "General" }));
    expect(screen.queryByRole("textbox", { name: "File or folder links" })).not.toBeInTheDocument();
    expect(screen.getByRole("list", { name: "Saved roots" })).toHaveTextContent("Plan");
    expect(screen.getByRole("button", { name: "Synchronize now" })).toBeDisabled();
    await user.click(screen.getByRole("button", { name: "Refresh status" }));
    expect(screen.getByRole("radio", { name: "General" })).toBeChecked();
    await user.click(screen.getByRole("button", { name: "Edit interval" }));
    const interval = screen.getByRole("spinbutton", { name: "Interval in minutes" });
    await user.clear(interval);
    await user.type(interval, "1");
    await user.click(screen.getByRole("button", { name: "Save interval" }));
    expect(await screen.findByText("1 minute")).toBeVisible();
    await waitFor(() =>
      expect(screen.getByRole("button", { name: "Save selection" })).toBeEnabled(),
    );
    expect(screen.getByRole("radio", { name: "General" })).toBeChecked();
    expect(server.saves()).toBe(0);
    await user.click(screen.getByRole("button", { name: "Save selection" }));
    await waitFor(() =>
      expect(screen.getByRole("button", { name: "Synchronize now" })).toBeEnabled(),
    );
    const request = server.requests.find(
      (entry) => entry.method === "PUT" && new URL(entry.url).pathname.endsWith("/roots"),
    )!;
    expect(request.headers.get("If-Match")).toBe('"7"');
    expect(await request.json()).toEqual({ scopeMode: "GENERAL", links: [] });
    server.remount();
    expect(await screen.findByRole("radio", { name: "General" })).toBeChecked();
    expect(screen.queryByRole("list", { name: "Saved roots" })).not.toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "Saved scope" })).toBeVisible();
    expect(screen.getByRole("button", { name: "Synchronize now" })).toBeEnabled();
    expect(screen.getByText("1 minute")).toBeVisible();
  });

  it("retains Specific text when switching back to saved General without marking hidden links dirty", async () => {
    const user = userEvent.setup();
    const server = setup({ scopeMode: "GENERAL", roots: [] });
    await user.click(await screen.findByRole("radio", { name: "Specific" }));
    expect(screen.getByRole("button", { name: "Save selection" })).toBeDisabled();
    await user.click(screen.getByRole("textbox", { name: "File or folder links" }));
    await user.paste(secondLink);
    await user.click(screen.getByRole("radio", { name: "General" }));
    expect(screen.getByRole("button", { name: "Save selection" })).toBeDisabled();
    expect(screen.getByRole("button", { name: "Synchronize now" })).toBeEnabled();
    await user.click(screen.getByRole("button", { name: "Refresh status" }));
    await user.click(screen.getByRole("radio", { name: "Specific" }));
    expect(screen.getByRole("textbox", { name: "File or folder links" })).toHaveValue(secondLink);
    expect(screen.getByRole("button", { name: "Synchronize now" })).toBeDisabled();
    await user.click(screen.getByRole("button", { name: "Save selection" }));
    await waitFor(() =>
      expect(screen.getByRole("button", { name: "Synchronize now" })).toBeEnabled(),
    );
    expect(screen.getByRole("list", { name: "Saved roots" })).toHaveTextContent("Notes");
    const request = server.requests.find(
      (entry) => entry.method === "PUT" && new URL(entry.url).pathname.endsWith("/roots"),
    )!;
    expect(await request.json()).toEqual({ scopeMode: "SPECIFIC", links: [secondLink] });
  });

  it("fences a preserved General draft on credential and actor transitions until explicit reload", async () => {
    const user = userEvent.setup();
    const server = setup({ scopeMode: "SPECIFIC", roots: [first] });
    await user.click(await screen.findByRole("radio", { name: "General" }));
    server.setConfiguration({ credentialRevision: 2 });
    await waitFor(() =>
      expect(screen.getByRole("button", { name: "Save selection" })).toBeDisabled(),
    );
    expect(screen.getByRole("radio", { name: "General" })).toBeChecked();
    await user.click(screen.getByRole("button", { name: "Reload saved selection" }));
    await waitFor(() => expect(screen.getByRole("radio", { name: "Specific" })).toBeChecked());
    await user.click(screen.getByRole("radio", { name: "General" }));
    server.changeSession({ ...owner, actorId: "97c41cb9-55ae-4a52-94ab-7aad59be91e5" });
    expect(screen.getByRole("radio", { name: "General" })).toBeChecked();
    expect(screen.getByRole("button", { name: "Save selection" })).toBeDisabled();
    expect(screen.getByRole("button", { name: "Synchronize now" })).toBeDisabled();
    expect(server.saves()).toBe(0);
  });

  it("does not retry a conflicted General save or replace its draft with the refreshed Specific scope", async () => {
    const user = userEvent.setup();
    const server = setup({ scopeMode: "SPECIFIC", roots: [first], revision: 7 });
    await user.click(await screen.findByRole("radio", { name: "General" }));
    server.setSavedConfiguration({ revision: 8, roots: [second] });
    await user.click(screen.getByRole("button", { name: "Save selection" }));
    await waitFor(() =>
      expect(screen.getByRole("button", { name: "Reload saved selection" })).toBeEnabled(),
    );
    expect(screen.getByRole("radio", { name: "General" })).toBeChecked();
    expect(screen.getByRole("button", { name: "Save selection" })).toBeDisabled();
    expect(server.saves()).toBe(0);
    expect(screen.getByRole("listitem", { name: "Selection save failed" })).toBeVisible();
    expect(screen.queryByRole("listitem", { name: "Selection saved" })).not.toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Reload saved selection" }));
    expect(await screen.findByRole("textbox", { name: "File or folder links" })).toHaveValue(
      secondLink,
    );
    expect(screen.getByRole("radio", { name: "Specific" })).toBeChecked();
    expect(screen.getByRole("button", { name: "Synchronize now" })).toBeEnabled();
  });

  it("persists the interval across refresh and reload without saving or discarding edited roots", async () => {
    const user = userEvent.setup();
    const server = setup({
      roots: [first],
      revision: 7,
      syncIntervalMinutes: 12,
      scheduleRevision: 3,
    });
    expect(await screen.findByText("12 minutes")).toBeVisible();
    const roots = screen.getByRole("textbox", { name: "File or folder links" });
    await user.clear(roots);
    await user.paste(secondLink);
    await user.click(screen.getByRole("button", { name: "Edit interval" }));
    const interval = screen.getByRole("spinbutton", { name: "Interval in minutes" });
    expect(interval).toHaveFocus();
    await user.clear(interval);
    await user.type(interval, "20");
    const delayed = server.delayNextRead();
    await user.click(screen.getByRole("button", { name: "Refresh status" }));
    await delayed.started;
    await user.click(interval);
    await user.keyboard("{Enter}");
    await waitFor(() =>
      expect(screen.getByRole("button", { name: "Edit interval" })).toBeEnabled(),
    );
    await act(async () => delayed.finish());
    expect(screen.getByText("20 minutes")).toBeVisible();
    expect(roots).toHaveValue(secondLink);
    expect(screen.getByRole("button", { name: "Save selection" })).toBeEnabled();
    expect(server.saves()).toBe(0);
    const request = server.requests.find((entry) =>
      new URL(entry.url).pathname.endsWith("/schedule"),
    )!;
    expect(request.headers.get("If-Match")).toBe('"3"');
    expect(request.headers.get("x-memoryos-csrf")).toBe("1");
    expect(await request.json()).toEqual({ syncIntervalMinutes: 20 });
    await user.click(screen.getByRole("button", { name: "Refresh status" }));
    expect(screen.getByText("20 minutes")).toBeVisible();
    server.remount();
    expect(await screen.findByText("20 minutes")).toBeVisible();
    expect(screen.getByRole("textbox", { name: "File or folder links" })).toHaveValue(firstLink);
  });

  it("retains an interval draft during status updates and requires explicit reload after a schedule change", async () => {
    const user = userEvent.setup();
    const server = setup({ roots: [first] });
    await user.click(await screen.findByRole("button", { name: "Edit interval" }));
    const interval = screen.getByRole("spinbutton", { name: "Interval in minutes" });
    await user.clear(interval);
    await user.type(interval, "15");
    expect(screen.getByRole("button", { name: "Synchronize now" })).toBeEnabled();
    server.setConfiguration({ pendingWork: true });
    await screen.findByText("Acquisition or indexing in progress");
    expect(interval).toHaveValue(15);
    expect(screen.getByRole("button", { name: "Save interval" })).toBeEnabled();
    server.setConfiguration({ syncIntervalMinutes: 30, scheduleRevision: 2, pendingWork: false });
    await screen.findByText("30 minutes");
    expect(interval).toHaveValue(15);
    expect(screen.getByRole("button", { name: "Save interval" })).toBeDisabled();
    expect(screen.getByRole("button", { name: "Synchronize now" })).toBeEnabled();
    await user.click(screen.getByRole("button", { name: "Refresh status" }));
    expect(interval).toHaveValue(15);
    expect(screen.getByRole("button", { name: "Save interval" })).toBeDisabled();
    await user.click(screen.getByRole("button", { name: "Reload saved interval" }));
    await waitFor(() => expect(interval).toHaveValue(30));
    await waitFor(() =>
      expect(screen.getByRole("button", { name: "Save interval" })).toBeEnabled(),
    );
    await user.keyboard("{Escape}");
    expect(screen.getByRole("button", { name: "Edit interval" })).toHaveFocus();
    expect(screen.queryByRole("spinbutton")).not.toBeInTheDocument();
  });

  it("does not retry a stale interval save and recovers without replacing the root draft or its revision", async () => {
    const user = userEvent.setup();
    const server = setup({ roots: [first], revision: 7, scheduleRevision: 3 });
    const roots = await screen.findByRole("textbox", { name: "File or folder links" });
    await user.clear(roots);
    await user.paste(secondLink);
    await user.click(screen.getByRole("button", { name: "Edit interval" }));
    const interval = screen.getByRole("spinbutton", { name: "Interval in minutes" });
    await user.clear(interval);
    await user.type(interval, "20");
    server.setSavedConfiguration({ syncIntervalMinutes: 30, scheduleRevision: 4 });
    await user.click(screen.getByRole("button", { name: "Save interval" }));
    expect(
      await screen.findByText(/The automatic interval changed while you were editing/),
    ).toBeVisible();
    await waitFor(() =>
      expect(screen.getByRole("button", { name: "Reload saved interval" })).toBeEnabled(),
    );
    expect(interval).toHaveValue(20);
    expect(roots).toHaveValue(secondLink);
    expect(screen.getByRole("button", { name: "Save interval" })).toBeDisabled();
    expect(
      server.requests.filter((entry) => new URL(entry.url).pathname.endsWith("/schedule")),
    ).toHaveLength(1);
    await user.click(screen.getByRole("button", { name: "Reload saved interval" }));
    await waitFor(() => expect(interval).toHaveValue(30));
    await waitFor(() => expect(interval).toBeEnabled());
    expect(roots).toHaveValue(secondLink);
    await user.clear(interval);
    await user.type(interval, "40");
    await user.click(screen.getByRole("button", { name: "Save interval" }));
    expect(await screen.findByText("40 minutes")).toBeVisible();
    await waitFor(() =>
      expect(screen.getByRole("button", { name: "Save selection" })).toBeEnabled(),
    );
    expect(roots).toHaveValue(secondLink);
    await user.click(screen.getByRole("button", { name: "Save selection" }));
    await waitFor(() => expect(server.saves()).toBe(1));
    const savedRoots = server.requests.find(
      (entry) => entry.method === "PUT" && new URL(entry.url).pathname.endsWith("/roots"),
    )!;
    expect(savedRoots.headers.get("If-Match")).toBe('"7"');
    expect(await savedRoots.json()).toEqual({ scopeMode: "SPECIFIC", links: [secondLink] });
    const schedules = server.requests.filter((entry) =>
      new URL(entry.url).pathname.endsWith("/schedule"),
    );
    expect(schedules.map((entry) => entry.headers.get("If-Match"))).toEqual(['"3"', '"4"']);
  });

  it("validates integer bounds inline and keeps interval saves single-flight", async () => {
    const user = userEvent.setup();
    const server = setup();
    await user.click(await screen.findByRole("button", { name: "Edit interval" }));
    const interval = screen.getByRole("spinbutton", { name: "Interval in minutes" });
    const save = screen.getByRole("button", { name: "Save interval" });
    for (const invalid of ["", "0", "1.5", "2147483648"]) {
      await user.clear(interval);
      if (invalid) await user.type(interval, invalid);
      expect(interval).toHaveAttribute("aria-invalid", "true");
      expect(save).toBeDisabled();
      expect(screen.getByRole("alert")).toHaveTextContent("whole number of minutes");
    }
    await user.clear(interval);
    await user.type(interval, "2147483647");
    expect(save).toBeEnabled();
    const finish = server.delayScheduleWrite();
    await user.dblClick(save);
    expect(save).toBeDisabled();
    expect(interval).toBeDisabled();
    expect(screen.getByRole("button", { name: "Cancel" })).toBeDisabled();
    expect(
      server.requests.filter((entry) => new URL(entry.url).pathname.endsWith("/schedule")),
    ).toHaveLength(1);
    await act(async () => finish());
    expect(await screen.findByText("2147483647 minutes")).toBeVisible();
  });

  it("keeps both drafts after an interval failure and lets the owner retry without exposing error detail", async () => {
    const user = userEvent.setup();
    const server = setup({ roots: [first] });
    const roots = await screen.findByRole("textbox", { name: "File or folder links" });
    await user.clear(roots);
    await user.paste(secondLink);
    await user.click(screen.getByRole("button", { name: "Edit interval" }));
    const interval = screen.getByRole("spinbutton", { name: "Interval in minutes" });
    await user.clear(interval);
    await user.type(interval, "1");
    server.failSchedule(503);
    await user.click(screen.getByRole("button", { name: "Save interval" }));
    const error = await screen.findByRole("alert");
    expect(error).toHaveTextContent("The automatic interval could not be updated");
    expect(error).not.toHaveTextContent("private provider detail");
    expect(screen.getByRole("listitem", { name: "Automatic interval save failed" })).toBeVisible();
    expect(
      screen.queryByRole("listitem", { name: "Automatic interval saved" }),
    ).not.toBeInTheDocument();
    await waitFor(() => expect(interval).toBeEnabled());
    expect(interval).toHaveValue(1);
    expect(roots).toHaveValue(secondLink);
    expect(screen.getByText("5 minutes")).toBeVisible();
    server.failSchedule(undefined);
    await user.click(screen.getByRole("button", { name: "Save interval" }));
    expect(await screen.findByText("1 minute")).toBeVisible();
    expect(roots).toHaveValue(secondLink);
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
    expect(screen.getByRole("listitem", { name: "Automatic interval saved" })).toBeVisible();
    expect(
      screen.queryByRole("listitem", { name: "Drive status refreshed" }),
    ).not.toBeInTheDocument();
  });

  it("never browses candidates and synchronizes only a saved, bounded link selection", async () => {
    const user = userEvent.setup();
    const server = setup();
    const input = await screen.findByRole("textbox", { name: "File or folder links" });
    expect(screen.queryByRole("heading", { name: "Saved scope" })).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Synchronize now" })).toBeDisabled();
    await user.click(input);
    await user.paste(
      Array.from(
        { length: 21 },
        (_, index) => `https://drive.google.com/file/d/file-${index}/view`,
      ).join("\n"),
    );
    expect(screen.getByRole("button", { name: "Save selection" })).toBeDisabled();
    await user.clear(input);
    await user.paste(`${firstLink},\n${secondLink}`);
    expect(screen.queryByRole("heading", { name: "Saved scope" })).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Synchronize now" })).toBeDisabled();
    await user.click(screen.getByRole("button", { name: "Save selection" }));
    await waitFor(() =>
      expect(screen.getByRole("button", { name: "Synchronize now" })).toBeEnabled(),
    );
    expect(
      within(screen.getByRole("list", { name: "Saved roots" })).getByText("Plan"),
    ).toBeVisible();
    expect(screen.getByRole("heading", { name: "Saved scope" })).toBeVisible();
    expect(
      within(screen.getByRole("list", { name: "Saved roots" })).getByText("Notes"),
    ).toBeVisible();
    await user.click(screen.getByRole("button", { name: "Synchronize now" }));
    await waitFor(() =>
      expect(screen.getByRole("button", { name: "Synchronize now" })).toBeDisabled(),
    );
    expect(
      server.requests.filter(
        (request) => request.method === "GET" && new URL(request.url).pathname.endsWith("/roots"),
      ),
    ).toEqual([]);
    expect(server.saves()).toBe(1);
  });

  it("restores saved-state actions when a link edit is undone", async () => {
    const user = userEvent.setup();
    const server = setup({ roots: [first] });
    const input = await screen.findByRole("textbox", { name: "File or folder links" });
    await user.clear(input);
    await user.paste(secondLink);
    expect(screen.getByRole("button", { name: "Synchronize now" })).toBeDisabled();
    await user.clear(input);
    await user.paste(firstLink);
    expect(screen.getByRole("button", { name: "Synchronize now" })).toBeEnabled();
    expect(screen.getByRole("button", { name: "Save selection" })).toBeDisabled();
    expect(server.saves()).toBe(0);
  });

  it("does not replace a saved selection with an older in-flight status response", async () => {
    const user = userEvent.setup();
    const server = setup();
    await user.click(await screen.findByRole("textbox", { name: "File or folder links" }));
    await user.paste(firstLink);
    const delayed = server.delayNextRead();
    await user.click(screen.getByRole("button", { name: "Refresh status" }));
    await delayed.started;
    await user.click(screen.getByRole("button", { name: "Save selection" }));
    await waitFor(() =>
      expect(screen.getByRole("button", { name: "Synchronize now" })).toBeEnabled(),
    );
    await act(async () => {
      delayed.finish();
    });
    expect(
      within(screen.getByRole("list", { name: "Saved roots" })).getByText("Plan"),
    ).toBeVisible();
    expect(screen.getByRole("button", { name: "Save selection" })).toBeDisabled();
    expect(server.saves()).toBe(1);
  });

  it("preserves edited links during refreshes and requires reload after a concurrent revision change", async () => {
    const user = userEvent.setup();
    const server = setup();
    const input = await screen.findByRole("textbox", { name: "File or folder links" });
    await user.click(input);
    await user.paste(firstLink);
    server.setConfiguration({ pendingWork: true });
    await screen.findByText("Acquisition or indexing in progress");
    expect(input).toHaveValue(firstLink);
    expect(screen.getByRole("button", { name: "Save selection" })).toBeEnabled();
    server.setConfiguration({ revision: 2, pendingWork: false, roots: [second] });
    await waitFor(() =>
      expect(screen.getByRole("button", { name: "Save selection" })).toBeDisabled(),
    );
    expect(input).toHaveValue(firstLink);
    expect(server.saves()).toBe(0);
    await user.click(screen.getByRole("button", { name: "Reload saved selection" }));
    await waitFor(() => expect(input).toHaveValue(secondLink));
    expect(screen.getByRole("listitem", { name: "Saved selection loaded" })).toBeVisible();
    expect(screen.getByRole("button", { name: "Synchronize now" })).toBeEnabled();
  });

  it("keeps rejected overlap links editable without exposing provider detail", async () => {
    const user = userEvent.setup();
    const server = setup();
    server.overlap(true);
    const input = await screen.findByRole("textbox", { name: "File or folder links" });
    await user.click(input);
    await user.paste(`${folderLink}\n${firstLink}`);
    await user.click(screen.getByRole("button", { name: "Save selection" }));
    expect(await screen.findByRole("alert")).not.toHaveTextContent("private provider detail");
    await waitFor(() => expect(input).toBeEnabled());
    expect(input).toHaveValue(`${folderLink}\n${firstLink}`);
    expect(server.saves()).toBe(0);
    server.overlap(false);
    await user.clear(input);
    await user.paste(folderLink);
    await user.click(screen.getByRole("button", { name: "Save selection" }));
    await waitFor(() =>
      expect(screen.getByRole("button", { name: "Synchronize now" })).toBeEnabled(),
    );
    expect(
      within(screen.getByRole("list", { name: "Saved roots" })).getByText("Team folder"),
    ).toBeVisible();
  });

  it("does not automatically retry a stale link save with a newer revision", async () => {
    const user = userEvent.setup();
    const server = setup();
    server.revisionConflict(true);
    const input = await screen.findByRole("textbox", { name: "File or folder links" });
    await user.click(input);
    await user.paste(firstLink);
    await user.click(screen.getByRole("button", { name: "Save selection" }));
    await waitFor(() =>
      expect(screen.getByRole("button", { name: "Reload saved selection" })).toBeEnabled(),
    );
    expect(screen.getByRole("button", { name: "Save selection" })).toBeDisabled();
    expect(input).toHaveValue(firstLink);
    expect(server.saves()).toBe(0);
  });

  it("treats credential changes as conflicts even when the root revision is unchanged", async () => {
    const user = userEvent.setup();
    const server = setup();
    await user.click(await screen.findByText("Credentials"));
    const input = await screen.findByRole("textbox", { name: "File or folder links" });
    await user.click(input);
    await user.paste(firstLink);
    server.setConfiguration({ credentialRevision: 2 });
    await waitFor(() =>
      expect(screen.getByRole("button", { name: "Save selection" })).toBeDisabled(),
    );
    expect(screen.getByRole("button", { name: "Reconnect Google Drive" })).toBeDisabled();
    expect(input).toHaveValue(firstLink);
    await user.click(screen.getByRole("button", { name: "Reload saved selection" }));
    await waitFor(() =>
      expect(screen.getByRole("button", { name: "Reconnect Google Drive" })).toBeEnabled(),
    );
  });

  it("retains named roots after confirmed local disconnect", async () => {
    const user = userEvent.setup();
    setup({ roots: [first] });
    await user.click(await screen.findByText("Credentials"));
    await user.click(await screen.findByRole("button", { name: "Disconnect" }));
    const dialog = screen.getByRole("alertdialog");
    expect(dialog).toHaveTextContent("all 2 Sources");
    expect(within(dialog).getByRole("button", { name: "Cancel" })).toHaveFocus();
    await user.click(within(dialog).getByRole("button", { name: "Disconnect" }));
    await waitFor(() => expect(screen.queryByRole("alertdialog")).not.toBeInTheDocument());
    expect(
      screen.getByRole("listitem", { name: "Google credential disconnected" }),
    ).toHaveTextContent("Shared Google account");
    expect(screen.queryByRole("button", { name: "Synchronize now" })).not.toBeInTheDocument();
    expect(screen.getByRole("textbox", { name: "File or folder links" })).toBeDisabled();
    expect(screen.getByRole("button", { name: "Reconnect Google Drive" })).toBeEnabled();
    expect(
      within(screen.getByRole("list", { name: "Saved roots" })).getByText("Plan"),
    ).toBeVisible();
  });

  it("reuses the saved OAuth app without requesting replacement credentials", async () => {
    const user = userEvent.setup();
    const server = setup();
    await user.click(await screen.findByText("Credentials"));
    await user.click(await screen.findByRole("button", { name: "Reconnect Google Drive" }));
    await user.click(
      within(screen.getByRole("alertdialog")).getByRole("button", {
        name: "Reconnect",
      }),
    );
    await waitFor(() => expect(launchGoogleDriveAuthorization).toHaveBeenCalledOnce());
    const request = server.requests.find((entry) =>
      new URL(entry.url).pathname.endsWith("/authorization"),
    )!;
    expect(new URL(request.url).pathname).toBe("/api/credentials/google-drive/authorization");
    expect(await request.json()).toEqual({
      name: "Shared Google account",
      credentialId: "81c51573-31a9-4e67-91c5-f276960c94af",
      expectedCredentialRevision: 1,
    });
    expect(server.queryClient.getMutationCache().getAll()).toEqual([]);
  });

  it("requires an explicit OAuth app for a legacy source and never caches or echoes the submitted secret", async () => {
    const user = userEvent.setup();
    const server = setup({
      oauthClientConfigured: false,
      credentialStatus: "REAUTHORIZATION_REQUIRED",
      roots: [first],
    });
    server.authorization(() =>
      Promise.resolve(
        Response.json(
          { code: "GOOGLE_DRIVE_OAUTH_CLIENT_INVALID", detail: clientJson },
          { status: 400 },
        ),
      ),
    );
    const submit = await screen.findByRole("button", { name: "Reconnect Google Drive" });
    expect(submit).toBeDisabled();
    expect(screen.queryByRole("button", { name: "Synchronize now" })).not.toBeInTheDocument();
    const input = screen.getByRole("textbox", { name: "Upload or paste OAuth app JSON" });
    await user.click(input);
    await user.paste(clientJson);
    await user.click(submit);
    await user.click(
      within(screen.getByRole("alertdialog")).getByRole("button", {
        name: "Reconnect",
      }),
    );
    expect(input).toHaveValue("");
    expect(await screen.findByRole("alert")).not.toHaveTextContent("synthetic-client-secret");
    await user.click(
      within(screen.getByRole("alertdialog")).getByRole("button", { name: "Cancel" }),
    );
    expect(submit).toBeDisabled();
    expect(
      JSON.stringify(
        server.queryClient
          .getQueryCache()
          .getAll()
          .map((query) => query.state),
      ),
    ).not.toContain("synthetic-client-secret");
    expect(server.queryClient.getMutationCache().getAll()).toEqual([]);
    expect(
      within(screen.getByRole("list", { name: "Saved roots" })).getByText("Plan"),
    ).toBeVisible();
  });

  it("clears replacement credentials and refuses a late consent response after an authority transition", async () => {
    const user = userEvent.setup();
    const server = setup();
    await user.click(await screen.findByText("Credentials"));
    let finish!: (response: Response) => void;
    server.authorization(
      () =>
        new Promise<Response>((resolve) => {
          finish = resolve;
        }),
    );
    await user.click(
      await screen.findByRole("checkbox", { name: "Replace OAuth app on reconnect" }),
    );
    const input = screen.getByRole("textbox", { name: "Upload or paste OAuth app JSON" });
    await user.click(input);
    await user.paste(clientJson);
    await user.click(screen.getByRole("button", { name: "Reconnect Google Drive" }));
    await user.click(
      within(screen.getByRole("alertdialog")).getByRole("button", {
        name: "Reconnect",
      }),
    );
    await waitFor(() => expect(finish).toBeTypeOf("function"));
    expect(input).toHaveValue("");
    server.changeSession({ ...owner, actorId: "97c41cb9-55ae-4a52-94ab-7aad59be91e5" });
    await act(async () => {
      finish(
        Response.json({
          authorizationUrl: "https://accounts.google.com/o/oauth2/v2/auth?state=stale",
        }),
      );
    });
    expect(launchGoogleDriveAuthorization).not.toHaveBeenCalled();
    expect(server.queryClient.getMutationCache().getAll()).toEqual([]);
  });

  it("reports explicit refresh failure and recovery without toasting internal save refreshes", async () => {
    const user = userEvent.setup();
    const server = setup({ roots: [first] });
    const refresh = await screen.findByRole("button", { name: "Refresh status" });
    expect(
      screen.queryByRole("listitem", { name: "Drive status refreshed" }),
    ).not.toBeInTheDocument();
    server.failConfiguration(503);
    await user.click(refresh);
    expect(
      await screen.findByRole("listitem", { name: "Drive status refresh failed" }),
    ).toBeVisible();
    expect(
      screen.queryByRole("listitem", { name: "Drive status refreshed" }),
    ).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Save selection" })).toBeDisabled();
    server.failConfiguration(undefined);
    await user.click(refresh);
    expect(await screen.findByRole("listitem", { name: "Drive status refreshed" })).toBeVisible();
    const roots = screen.getByRole("textbox", { name: "File or folder links" });
    await waitFor(() => expect(roots).toBeEnabled());
    await user.clear(roots);
    await user.paste(secondLink);
    await user.click(screen.getByRole("button", { name: "Save selection" }));
    expect(await screen.findByRole("listitem", { name: "Selection saved" })).toBeVisible();
    await waitFor(() =>
      expect(screen.getByRole("button", { name: "Synchronize now" })).toBeEnabled(),
    );
    expect(screen.getAllByRole("listitem", { name: "Drive status refreshed" })).toHaveLength(1);
  });

  it("keeps the draft after a failed explicit selection reload until a successful retry", async () => {
    const user = userEvent.setup();
    const server = setup({ roots: [first] });
    const roots = await screen.findByRole("textbox", { name: "File or folder links" });
    await user.clear(roots);
    await user.paste(secondLink);
    server.setSavedConfiguration({ roots: [folder], revision: 2 });
    server.failConfiguration(503);
    await user.click(screen.getByRole("button", { name: "Reload saved selection" }));
    expect(await screen.findByRole("listitem", { name: "Selection reload failed" })).toBeVisible();
    expect(
      screen.queryByRole("listitem", { name: "Saved selection loaded" }),
    ).not.toBeInTheDocument();
    expect(roots).toHaveValue(secondLink);
    server.failConfiguration(undefined);
    await waitFor(() =>
      expect(screen.getByRole("button", { name: "Reload saved selection" })).toBeEnabled(),
    );
    await user.click(screen.getByRole("button", { name: "Reload saved selection" }));
    expect(await screen.findByRole("listitem", { name: "Saved selection loaded" })).toBeVisible();
    expect(roots).toHaveValue(folderLink);
    expect(screen.getByRole("button", { name: "Save selection" })).toBeDisabled();
  });

  it("does not announce a rejected shared disconnect and preserves confirmation for retry", async () => {
    const user = userEvent.setup();
    const server = setup({ roots: [first] });
    server.failRevoke(409);
    await user.click(await screen.findByText("Credentials"));
    await user.click(screen.getByRole("button", { name: "Disconnect" }));
    const dialog = screen.getByRole("alertdialog");
    await user.click(within(dialog).getByRole("button", { name: "Disconnect" }));
    expect(await within(dialog).findByRole("alert")).toBeVisible();
    expect(
      screen.queryByRole("listitem", { name: "Google credential disconnected" }),
    ).not.toBeInTheDocument();
    server.failRevoke(undefined);
    await user.click(within(dialog).getByRole("button", { name: "Disconnect" }));
    await waitFor(() => expect(screen.queryByRole("alertdialog")).not.toBeInTheDocument());
    expect(screen.getByRole("listitem", { name: "Google credential disconnected" })).toBeVisible();
    expect(screen.queryByRole("button", { name: "Synchronize now" })).not.toBeInTheDocument();
    expect(screen.getByRole("list", { name: "Saved roots" })).toHaveTextContent("Plan");
  });

  it("suppresses a late interval outcome after leaving the Source with the notification host still mounted", async () => {
    const user = userEvent.setup();
    const server = setup();
    await user.click(await screen.findByRole("button", { name: "Edit interval" }));
    const interval = screen.getByRole("spinbutton", { name: "Interval in minutes" });
    await user.clear(interval);
    await user.type(interval, "20");
    const finish = server.delayScheduleWrite();
    await user.click(screen.getByRole("button", { name: "Save interval" }));
    expect(screen.getByRole("button", { name: "Save interval" })).toBeDisabled();
    server.leaveSource();
    await act(async () => finish());
    expect(screen.queryByRole("listitem")).not.toBeInTheDocument();
  });
});
