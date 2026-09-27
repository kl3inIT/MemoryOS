import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { cleanup, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { HttpResponse } from "msw";
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import type { ApplicationSession } from "@/features/identity/application-session-context";
import { ApplicationSessionProvider } from "@/features/identity/application-session-provider";
import {
  handleGetChatGuardrails,
  handleGetChatSettings,
  handleSaveChatGrounded,
  handleSaveChatGuardrails,
} from "@/lib/hey-api/msw.gen";
import type {
  ChatGroundedRequest,
  ChatGuardrailsRequest,
  ChatGuardrailsResponse,
  ChatSettingsResponse,
} from "@/lib/hey-api/types.gen";
import { server } from "@/test/msw";
import { AdminChatSettings } from "./admin-chat-settings";

const SESSION: ApplicationSession = {
  actorId: "0f2f5e6e-4e6c-4d55-9c07-6b0b1d4b39a4",
  authorizationVersion: 1,
  uiLanguage: "en",
  tenant: { displayName: "Tasco", role: "OWNER" },
  capabilities: ["SYSTEM_BASIC", "MODELS_MANAGE"],
  scopedCapabilities: [],
};

let settings: ChatSettingsResponse;
let guardrails: ChatGuardrailsResponse;
const savedGrounded = vi.fn<(body: ChatGroundedRequest) => void>();
const savedGuardrails = vi.fn<(body: ChatGuardrailsRequest) => void>();

function show() {
  server.use(
    handleGetChatSettings(() => HttpResponse.json(settings)),
    handleGetChatGuardrails(() => HttpResponse.json(guardrails)),
    handleSaveChatGrounded(async ({ request }) => {
      const body = await request.json();
      savedGrounded(body);
      settings = { ...settings, ...body, revision: settings.revision + 1 };
      return HttpResponse.json(settings);
    }),
    handleSaveChatGuardrails(async ({ request }) => {
      const body = await request.json();
      savedGuardrails(body);
      guardrails = {
        topics: body.topics,
        blockedPhrases: body.blockedPhrases,
        blockedPhraseMessage: body.blockedPhraseMessage ?? "",
        revision: guardrails.revision + 1,
      };
      return HttpResponse.json(guardrails);
    }),
  );
  render(
    <ApplicationSessionProvider session={SESSION}>
      <QueryClientProvider
        client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}
      >
        <AdminChatSettings />
      </QueryClientProvider>
    </ApplicationSessionProvider>,
  );
  return userEvent.setup();
}

beforeEach(() => {
  settings = {
    deepResearchEnabled: true,
    chatHistoryVisibility: "NORMAL",
    groundedAnswers: false,
    groundedAllowWeb: false,
    revision: 3,
  };
  guardrails = {
    topics: [
      { topic: "POLITICS", enabled: false, message: "" },
      { topic: "LEADERS", enabled: false, message: "" },
      { topic: "RELIGION", enabled: false, message: "" },
    ],
    blockedPhrases: [],
    blockedPhraseMessage: "",
    revision: 7,
  };
});
afterEach(() => {
  cleanup();
  vi.clearAllMocks();
});

it("turns on answers from documents, after which Web search may be allowed", async () => {
  const user = show();
  const allowWeb = await screen.findByRole("switch", {
    name: "Let people turn on Web search",
  });
  expect(allowWeb).toBeDisabled();

  await user.click(
    screen.getByRole("switch", { name: "Answer only from the organization's documents" }),
  );

  expect(savedGrounded).toHaveBeenCalledWith({
    groundedAnswers: true,
    groundedAllowWeb: false,
    revision: 3,
  });
  await waitFor(() => expect(allowWeb).toBeEnabled());
  await user.click(allowWeb);
  expect(savedGrounded).toHaveBeenLastCalledWith({
    groundedAnswers: true,
    groundedAllowWeb: true,
    revision: 4,
  });
});

it("saves the topics and phrases the manager edited", async () => {
  const user = show();
  const topics = await screen.findByRole("region", { name: "Sensitive topics" });
  const save = within(topics).getByRole("button", { name: "Save" });
  expect(save).toBeDisabled();

  await user.click(within(topics).getByRole("switch", { name: "Politics" }));
  // The Politics reply comes first; the reply to a blocked phrase follows the phrases.
  const [politicsReply] = within(topics).getAllByRole("textbox", { name: "Reply when blocked" });
  await user.type(politicsReply!, "No.");
  await user.type(
    within(topics).getByRole("textbox", { name: "Blocked phrases" }),
    "secret plan\n\n  launch date ",
  );
  await user.click(save);

  await waitFor(() =>
    expect(savedGuardrails).toHaveBeenCalledWith({
      topics: [
        { topic: "POLITICS", enabled: true, message: "No." },
        { topic: "LEADERS", enabled: false, message: "" },
        { topic: "RELIGION", enabled: false, message: "" },
      ],
      blockedPhrases: ["secret plan", "launch date"],
      blockedPhraseMessage: "",
      revision: 7,
    }),
  );
  await waitFor(() => expect(save).toBeDisabled());
});

it("refuses more blocked phrases than the API keeps", async () => {
  const user = show();
  const topics = await screen.findByRole("region", { name: "Sensitive topics" });
  await user.click(within(topics).getByRole("textbox", { name: "Blocked phrases" }));
  await user.paste(Array.from({ length: 21 }, (_, index) => `phrase ${index}`).join("\n"));
  await user.click(within(topics).getByRole("button", { name: "Save" }));

  expect(await within(topics).findByText("At most 20 phrases.")).toBeVisible();
  expect(savedGuardrails).not.toHaveBeenCalled();
});
