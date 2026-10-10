import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen, within } from "@testing-library/react";
import {
  RouterProvider,
  createMemoryHistory,
  createRootRoute,
  createRoute,
  createRouter,
} from "@tanstack/react-router";
import { beforeEach, describe, expect, it } from "vitest";
import {
  ApplicationSessionContext,
  type ApplicationSession,
} from "@/features/identity/application-session-context";
import { i18n } from "@/i18n/index";
import type { MeetingDetail } from "@/lib/hey-api/types.gen";
import { MinutesSummary } from "./meeting-minutes";

function failed(failure: string | null): MeetingDetail {
  return {
    id: "0f6b3c1e-9a7d-4d5e-8c2b-6e1f4a9b3d77",
    title: "Giao ban tuần",
    kind: "IN_PERSON",
    language: "vi",
    participants: [],
    terms: [],
    notes: "",
    status: "ENDED",
    provider: null,
    diarized: true,
    createdAt: "2026-09-24T09:00:00Z",
    endedAt: "2026-09-24T10:00:00Z",
    revision: 4,
    speakers: [],
    utterances: [],
    minutes: {
      status: "FAILED",
      failure,
      summary: "",
      kind: "",
      generatedAt: null,
      decisions: [],
      actions: [],
      edited: false,
      topics: [],
    },
    audio: { status: "NONE", failure: null, filename: null, sizeBytes: 0, provider: null },
    owned: true,
    readers: [],
    starred: [],
    bookmarks: [],
    correcting: false,
  };
}

/** The failed minutes as the owner sees them, with or without the right to manage models. */
async function show(failure: string | null, capabilities: ApplicationSession["capabilities"] = []) {
  const session: ApplicationSession = {
    actorId: "owner",
    displayName: null,
    authorizationVersion: 1,
    uiLanguage: "vi",
    tenant: { displayName: "Team", role: "MEMBER" },
    capabilities,
    scopedCapabilities: [],
  };
  const rootRoute = createRootRoute();
  const route = createRoute({
    getParentRoute: () => rootRoute,
    path: "/",
    component: () => (
      <ApplicationSessionContext value={session}>
        <MinutesSummary meeting={failed(failure)} />
      </ApplicationSessionContext>
    ),
  });
  const router = createRouter({
    routeTree: rootRoute.addChildren([route]),
    history: createMemoryHistory({ initialEntries: ["/"] }),
  });
  render(
    <QueryClientProvider client={new QueryClient()}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  );
  return screen.findByRole("alert");
}

beforeEach(async () => {
  await i18n.changeLanguage("vi");
});

describe("minutes that could not be written", () => {
  it("say the provider refused the key, and show a model manager where to change it", async () => {
    const alert = await show("CHAT_PROVIDER_CREDENTIAL_REJECTED", ["MODELS_MANAGE"]);

    expect(alert).toHaveTextContent("Provider từ chối API key");
    expect(within(alert).getByRole("link", { name: "Cập nhật API key" })).toHaveAttribute(
      "href",
      "/admin/ai-providers",
    );
    expect(within(alert).getByRole("button", { name: "Viết lại" })).toBeEnabled();
  });

  it("give a member who cannot manage models the reason without the link", async () => {
    const alert = await show("CHAT_PROVIDER_CREDENTIAL_REJECTED");

    expect(alert).toHaveTextContent("Provider từ chối API key");
    expect(within(alert).queryByRole("link")).not.toBeInTheDocument();
  });

  it("say no model is set up, and show a model manager where to add one", async () => {
    const alert = await show("CHAT_MODEL_NOT_CONFIGURED", ["MODELS_MANAGE"]);

    expect(alert).toHaveTextContent("Chưa có model nào");
    expect(within(alert).getByRole("link", { name: "Thêm model" })).toHaveAttribute(
      "href",
      "/admin/ai-providers",
    );
  });

  it("say the model's answer could not be read", async () => {
    expect(await show("CHAT_MODEL_ANSWER_UNREADABLE")).toHaveTextContent(
      "Model trả về biên bản không đọc được.",
    );
  });

  it("ask for another try when the cause is unknown", async () => {
    expect(await show("MEETING_MINUTES_FAILED")).toHaveTextContent("Hãy thử lại.");
  });
});
