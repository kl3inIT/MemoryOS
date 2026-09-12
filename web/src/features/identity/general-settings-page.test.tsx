import { act, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { QueryClientProvider } from "@tanstack/react-query";
import { afterEach, describe, expect, it, vi } from "vitest";
import { createMemoryOsQueryClient } from "@/lib/query-client";
import { getCurrentIdentityQueryKey } from "@/lib/hey-api/@tanstack/react-query.gen";
import type { CurrentIdentity } from "@/lib/hey-api/types.gen";
import { ApplicationSessionBoundary } from "./application-session-boundary";
import { GeneralSettingsPage } from "./general-settings-page";

const identity: CurrentIdentity = {
  actorId: "actor-a",
  tenant: { role: "MEMBER", displayName: "Fixture" },
  authorizationVersion: 1,
  capabilities: [],
  scopedCapabilities: [],
  uiLanguage: "en",
};
afterEach(() => vi.unstubAllGlobals());
function mount() {
  const client = createMemoryOsQueryClient();
  render(
    <QueryClientProvider client={client}>
      <ApplicationSessionBoundary>
        <GeneralSettingsPage />
      </ApplicationSessionBoundary>
    </QueryClientProvider>,
  );
  return client;
}
describe("account language settings", () => {
  it("saves from an ordinary member with the guard, waits for confirmation and preserves the select node", async () => {
    let finish!: (response: Response) => void;
    const fetch = vi.fn((request: Request) =>
      request.method === "PUT"
        ? new Promise<Response>((resolve) => {
            finish = resolve;
          })
        : Promise.resolve(Response.json(identity)),
    );
    vi.stubGlobal("fetch", fetch);
    const client = mount();
    const select = await screen.findByLabelText("Display language");
    fireEvent.change(select, { target: { value: "vi" } });
    await waitFor(() => expect(select).toBeDisabled());
    expect(document.documentElement.lang).toBe("en");
    await waitFor(() => expect(finish).toBeTypeOf("function"));
    const request = fetch.mock.calls
      .map(([value]) => value)
      .find((value) => value.method === "PUT")!;
    expect(request.headers.get("X-MemoryOS-CSRF")).toBe("1");
    expect(await request.json()).toEqual({ uiLanguage: "vi" });
    await act(async () => finish(Response.json({ uiLanguage: "vi" })));
    expect(await screen.findByLabelText("Ngôn ngữ giao diện")).toBe(select);
    expect(select).toHaveValue("vi");
    expect(
      client.getQueryData<CurrentIdentity>(getCurrentIdentityQueryKey())?.authorizationVersion,
    ).toBe(1);
    expect(await screen.findByText("Đã lưu ngôn ngữ.")).toBeInTheDocument();
  });
  it("reconciles a lost save response with the saved account preference", async () => {
    let saved = false;
    vi.stubGlobal(
      "fetch",
      vi.fn(async (request: Request) => {
        if (request.method === "PUT") {
          saved = true;
          throw new TypeError("private network diagnostic");
        }
        return Response.json({ ...identity, uiLanguage: saved ? "vi" : "en" });
      }),
    );
    mount();
    fireEvent.change(await screen.findByLabelText("Display language"), { target: { value: "vi" } });
    const select = await screen.findByLabelText("Ngôn ngữ giao diện");
    await waitFor(() => expect(select).not.toBeDisabled());
    expect(select).toHaveValue("vi");
    expect(document.body.textContent).not.toContain("private network diagnostic");
  });
  it("ignores a late successful response after the active account changes", async () => {
    let finish!: (response: Response) => void;
    vi.stubGlobal(
      "fetch",
      vi.fn((request: Request) =>
        request.method === "PUT"
          ? new Promise<Response>((resolve) => {
              finish = resolve;
            })
          : Promise.resolve(Response.json(identity)),
      ),
    );
    const client = mount();
    fireEvent.change(await screen.findByLabelText("Display language"), { target: { value: "vi" } });
    await waitFor(() => expect(finish).toBeTypeOf("function"));
    await act(async () =>
      client.setQueryData(getCurrentIdentityQueryKey(), { ...identity, actorId: "actor-b" }),
    );
    await act(async () => finish(Response.json({ uiLanguage: "vi" })));
    await waitFor(() => expect(screen.getByLabelText("Display language")).toHaveValue("en"));
    expect(client.getQueryData<CurrentIdentity>(getCurrentIdentityQueryKey())?.actorId).toBe(
      "actor-b",
    );
  });
});
