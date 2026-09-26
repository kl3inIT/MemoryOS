import { act, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { QueryClientProvider } from "@tanstack/react-query";
import { HttpResponse } from "msw";
import { beforeEach, describe, expect, it } from "vitest";
import { ActionNotifications } from "@/components/ui/action-notifications";
import { createMemoryOsQueryClient } from "@/lib/query-client";
import { getCurrentIdentityQueryKey } from "@/lib/hey-api/@tanstack/react-query.gen";
import {
  handleGetChatPreferences,
  handleGetCurrentIdentity,
  handleSetCurrentIdentityLanguage,
} from "@/lib/hey-api/msw.gen";
import type { CurrentIdentity } from "@/lib/hey-api/types.gen";
import { server } from "@/test/msw";
import { ApplicationSessionBoundary } from "./application-session-boundary";
import { ThemeProvider } from "@/features/theme/theme-provider";
import { GeneralSettingsPage } from "./general-settings-page";

const identity: CurrentIdentity = {
  actorId: "actor-a",
  tenant: { role: "MEMBER", displayName: "Fixture" },
  authorizationVersion: 1,
  capabilities: [],
  scopedCapabilities: [],
  uiLanguage: "en",
};

beforeEach(() => {
  server.use(
    handleGetChatPreferences({
      body: {
        workRole: "",
        personalPreferences: "",
        defaultModelId: null,
        temperatureDefault: null,
        reasoningEffortDefault: "OFF",
        autoScroll: true,
        displayName: "Fixture Member",
        email: "member@example.com",
      },
    }),
  );
});

function mount() {
  const client = createMemoryOsQueryClient();
  render(
    <QueryClientProvider client={client}>
      <ActionNotifications>
        <ApplicationSessionBoundary>
          <ThemeProvider>
            <GeneralSettingsPage />
          </ThemeProvider>
        </ApplicationSessionBoundary>
      </ActionNotifications>
    </QueryClientProvider>,
  );
  return client;
}

/** A language save the test answers when it chooses, with the request it received. */
function heldSave() {
  const save: { request?: Request; finish?: (response: Response) => void } = {};
  server.use(
    handleSetCurrentIdentityLanguage(
      ({ request }) =>
        new Promise<Response>((resolve) => {
          save.request = request;
          save.finish = resolve;
        }),
    ),
  );
  return save;
}

describe("account language settings", () => {
  it("saves from an ordinary member with the guard, waits for confirmation and preserves the select node", async () => {
    server.use(handleGetCurrentIdentity({ body: identity }));
    const save = heldSave();
    const client = mount();
    const select = await screen.findByLabelText("Display language");
    fireEvent.change(select, { target: { value: "vi" } });
    await waitFor(() => expect(select).toBeDisabled());
    expect(document.documentElement.lang).toBe("en");
    await waitFor(() => expect(save.finish).toBeTypeOf("function"));
    expect(save.request?.headers.get("X-MemoryOS-CSRF")).toBe("1");
    expect(await save.request?.json()).toEqual({ uiLanguage: "vi" });
    await act(async () => save.finish?.(HttpResponse.json({ uiLanguage: "vi" })));
    expect(await screen.findByLabelText("Ngôn ngữ giao diện")).toBe(select);
    expect(select).toHaveValue("vi");
    expect(
      client.getQueryData<CurrentIdentity>(getCurrentIdentityQueryKey())?.authorizationVersion,
    ).toBe(1);
    expect(await screen.findByText("Đã lưu ngôn ngữ.")).toBeInTheDocument();
  });
  it("reconciles a lost save response with the saved account preference", async () => {
    let saved = false;
    server.use(
      handleGetCurrentIdentity(() =>
        HttpResponse.json({ ...identity, uiLanguage: saved ? "vi" : "en" }),
      ),
      handleSetCurrentIdentityLanguage(() => {
        saved = true;
        return HttpResponse.error();
      }),
    );
    mount();
    fireEvent.change(await screen.findByLabelText("Display language"), { target: { value: "vi" } });
    const select = await screen.findByLabelText("Ngôn ngữ giao diện");
    await waitFor(() => expect(select).not.toBeDisabled());
    expect(select).toHaveValue("vi");
  });
  it("ignores a late successful response after the active account changes", async () => {
    server.use(handleGetCurrentIdentity({ body: identity }));
    const save = heldSave();
    const client = mount();
    fireEvent.change(await screen.findByLabelText("Display language"), { target: { value: "vi" } });
    await waitFor(() => expect(save.finish).toBeTypeOf("function"));
    await act(async () =>
      client.setQueryData(getCurrentIdentityQueryKey(), { ...identity, actorId: "actor-b" }),
    );
    await act(async () => save.finish?.(HttpResponse.json({ uiLanguage: "vi" })));
    await waitFor(() => expect(screen.getByLabelText("Display language")).toHaveValue("en"));
    expect(client.getQueryData<CurrentIdentity>(getCurrentIdentityQueryKey())?.actorId).toBe(
      "actor-b",
    );
  });
});
