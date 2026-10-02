import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import {
  createMemoryHistory,
  createRootRoute,
  createRouter,
  RouterProvider,
} from "@tanstack/react-router";
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
  handleSaveChatHistoryVisibility,
} from "@/lib/hey-api/msw.gen";
import type {
  ChatGroundedRequest,
  ChatGuardrailsRequest,
  ChatGuardrailsResponse,
  ChatGuardrailTopic,
  ChatHistoryVisibilityRequest,
  ChatSettingsResponse,
} from "@/lib/hey-api/types.gen";
import { server } from "@/test/msw";
import { AdminChatSettings } from "./admin-chat-settings";

const SESSION: ApplicationSession = {
  actorId: "0f2f5e6e-4e6c-4d55-9c07-6b0b1d4b39a4",
  displayName: null,
  authorizationVersion: 1,
  uiLanguage: "en",
  tenant: { displayName: "Tasco", role: "OWNER" },
  capabilities: ["SYSTEM_BASIC", "MODELS_MANAGE"],
  scopedCapabilities: [],
};

const POLITICS: ChatGuardrailTopic = {
  id: "0f5b6f2a-7c1d-4e8a-9b3c-000000000001",
  name: "Chính trị",
  description: "Câu hỏi về đảng phái và bầu cử.",
  examples: ["Đảng nào tốt hơn?"],
  message: "Trợ lý không trả lời câu hỏi về chính trị.",
  enabled: false,
};
const LEADERS: ChatGuardrailTopic = {
  id: "0f5b6f2a-7c1d-4e8a-9b3c-000000000002",
  name: "Lãnh tụ và lãnh đạo",
  description: "Câu hỏi về đời tư lãnh tụ.",
  examples: [],
  message: "Trợ lý không trả lời câu hỏi về lãnh tụ và lãnh đạo.",
  enabled: true,
};

let settings: ChatSettingsResponse;
let guardrails: ChatGuardrailsResponse;
const savedGrounded = vi.fn<(body: ChatGroundedRequest) => void>();
const savedHistory = vi.fn<(body: ChatHistoryVisibilityRequest) => void>();
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
    handleSaveChatHistoryVisibility(async ({ request }) => {
      const body = await request.json();
      savedHistory(body);
      settings = {
        ...settings,
        chatHistoryVisibility: body.visibility,
        revision: settings.revision + 1,
      };
      return HttpResponse.json(settings);
    }),
    handleSaveChatGuardrails(async ({ request }) => {
      const body = await request.json();
      savedGuardrails(body);
      guardrails = {
        topics: body.topics.map((topic, index) => ({
          ...topic,
          id: topic.id ?? `00000000-0000-4000-8000-00000000000${index}`,
        })),
        blockedPhrases: body.blockedPhrases,
        blockedPhraseMessage: body.blockedPhraseMessage ?? "",
        revision: guardrails.revision + 1,
      };
      return HttpResponse.json(guardrails);
    }),
  );
  const rootRoute = createRootRoute({ component: AdminChatSettings });
  const router = createRouter({
    routeTree: rootRoute,
    history: createMemoryHistory({ initialEntries: ["/"] }),
  });
  render(
    <ApplicationSessionProvider session={SESSION}>
      <QueryClientProvider
        client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}
      >
        <RouterProvider router={router} />
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
    topics: [POLITICS, LEADERS],
    blockedPhrases: ["Dự án Phoenix"],
    blockedPhraseMessage: "Trợ lý không trả lời câu hỏi này.",
    revision: 7,
  };
});
afterEach(() => {
  cleanup();
  vi.clearAllMocks();
});

it("offers Web search only under answers from documents", async () => {
  const user = show();
  const answers = await screen.findByRole("region", { name: "Answers" });
  expect(
    within(answers).queryByRole("switch", { name: "Let people turn on Web search" }),
  ).toBeNull();

  await user.click(
    within(answers).getByRole("switch", { name: "Answer only from the organization's documents" }),
  );

  expect(savedGrounded).toHaveBeenCalledWith({
    groundedAnswers: true,
    groundedAllowWeb: false,
    revision: 3,
  });
  const allowWeb = await within(answers).findByRole("switch", {
    name: "Let people turn on Web search",
  });
  await user.click(allowWeb);
  await waitFor(() =>
    expect(savedGrounded).toHaveBeenLastCalledWith({
      groundedAnswers: true,
      groundedAllowWeb: true,
      revision: 4,
    }),
  );
});

it("chooses who may read conversations with one radio choice", async () => {
  const user = show();
  const history = await screen.findByRole("radiogroup", { name: "Conversation history" });
  expect(within(history).getByRole("radio", { name: "Show who asked" })).toBeChecked();

  await user.click(within(history).getByRole("radio", { name: "Hide who asked" }));

  expect(savedHistory).toHaveBeenCalledWith({ visibility: "ANONYMIZED", revision: 3 });
});

it("saves a topic switch at once with the whole guardrails", async () => {
  const user = show();
  const topics = await screen.findByRole("region", { name: "Sensitive topics" });
  expect(within(topics).getByText("Câu hỏi về đảng phái và bầu cử.")).toBeVisible();

  await user.click(within(topics).getByRole("switch", { name: "Chính trị" }));

  expect(savedGuardrails).toHaveBeenCalledWith({
    topics: [{ ...POLITICS, enabled: true }, LEADERS],
    blockedPhrases: ["Dự án Phoenix"],
    blockedPhraseMessage: "Trợ lý không trả lời câu hỏi này.",
    revision: 7,
  });
  await waitFor(() =>
    expect(within(topics).getByRole("switch", { name: "Chính trị" })).toBeChecked(),
  );
});

it("adds a topic from the dialog, on from the start", async () => {
  const user = show();
  const topics = await screen.findByRole("region", { name: "Sensitive topics" });
  await user.click(within(topics).getByRole("button", { name: "Add topic" }));
  const dialog = await screen.findByRole("dialog", { name: "Add topic" });

  await user.type(within(dialog).getByRole("textbox", { name: "Topic name" }), "Lương thưởng");
  await user.type(
    within(dialog).getByRole("textbox", { name: "Description" }),
    "Câu hỏi về lương của từng người.",
  );
  await user.type(
    within(dialog).getByRole("textbox", { name: "Example questions" }),
    "Lương giám đốc bao nhiêu?\n\n",
  );
  await user.click(within(dialog).getByRole("button", { name: "Save" }));

  await waitFor(() =>
    expect(savedGuardrails).toHaveBeenCalledWith(
      expect.objectContaining({
        topics: [
          POLITICS,
          LEADERS,
          {
            id: undefined,
            name: "Lương thưởng",
            description: "Câu hỏi về lương của từng người.",
            examples: ["Lương giám đốc bao nhiêu?"],
            message: "",
            enabled: true,
          },
        ],
        revision: 7,
      }),
    ),
  );
  expect(await within(topics).findByRole("switch", { name: "Lương thưởng" })).toBeChecked();
});

it("refuses a second topic with the same name", async () => {
  const user = show();
  const topics = await screen.findByRole("region", { name: "Sensitive topics" });
  await user.click(within(topics).getByRole("button", { name: "Add topic" }));
  const dialog = await screen.findByRole("dialog", { name: "Add topic" });

  await user.type(within(dialog).getByRole("textbox", { name: "Topic name" }), " chính trị ");
  await user.type(within(dialog).getByRole("textbox", { name: "Description" }), "Trùng tên.");

  expect(within(dialog).getByText("A topic with this name already exists.")).toBeVisible();
  expect(within(dialog).getByRole("button", { name: "Save" })).toBeDisabled();
});

it("edits a topic without changing its switch and deletes one after confirmation", async () => {
  const user = show();
  const topics = await screen.findByRole("region", { name: "Sensitive topics" });
  await user.click(within(topics).getByRole("button", { name: "Actions for Lãnh tụ và lãnh đạo" }));
  await user.click(await screen.findByRole("menuitem", { name: "Edit" }));
  const dialog = await screen.findByRole("dialog", { name: "Edit topic" });
  const reply = within(dialog).getByRole("textbox", { name: "Reply when blocked" });
  await user.clear(reply);
  await user.type(reply, "Không bàn về lãnh tụ.");
  await user.click(within(dialog).getByRole("button", { name: "Save" }));

  await waitFor(() =>
    expect(savedGuardrails).toHaveBeenLastCalledWith(
      expect.objectContaining({
        topics: [POLITICS, { ...LEADERS, message: "Không bàn về lãnh tụ." }],
      }),
    ),
  );

  await user.click(within(topics).getByRole("button", { name: "Actions for Chính trị" }));
  await user.click(await screen.findByRole("menuitem", { name: "Delete topic" }));
  const confirm = await screen.findByRole("alertdialog", { name: "Delete the topic?" });
  await user.click(within(confirm).getByRole("button", { name: "Delete topic" }));

  await waitFor(() =>
    expect(savedGuardrails).toHaveBeenLastCalledWith(
      expect.objectContaining({
        topics: [{ ...LEADERS, message: "Không bàn về lãnh tụ." }],
        revision: 8,
      }),
    ),
  );
});

it("saves the blocked phrases with the topics as they stand", async () => {
  const user = show();
  const phrases = await screen.findByRole("region", { name: "Blocked phrases" });
  const save = within(phrases).getByRole("button", { name: "Save" });
  expect(save).toBeDisabled();

  await user.type(
    within(phrases).getByRole("textbox", { name: "Blocked phrases" }),
    "\nsecret plan\n\n  launch date ",
  );
  await user.click(save);

  await waitFor(() =>
    expect(savedGuardrails).toHaveBeenCalledWith({
      topics: [POLITICS, LEADERS],
      blockedPhrases: ["Dự án Phoenix", "secret plan", "launch date"],
      blockedPhraseMessage: "Trợ lý không trả lời câu hỏi này.",
      revision: 7,
    }),
  );
  await waitFor(() => expect(save).toBeDisabled());
});

it("waits for a topic save before the phrases can be saved", async () => {
  const user = show();
  let release!: () => void;
  const held = new Promise<void>((resolve) => (release = resolve));
  server.use(
    handleSaveChatGuardrails(async ({ request }) => {
      const body = await request.json();
      savedGuardrails(body);
      await held;
      guardrails = { ...guardrails, topics: body.topics, revision: guardrails.revision + 1 };
      return HttpResponse.json(guardrails);
    }),
  );
  const topics = await screen.findByRole("region", { name: "Sensitive topics" });
  const phrases = screen.getByRole("region", { name: "Blocked phrases" });
  await user.type(within(phrases).getByRole("textbox", { name: "Blocked phrases" }), "\nmã nội bộ");
  const save = within(phrases).getByRole("button", { name: "Save" });
  expect(save).toBeEnabled();

  await user.click(within(topics).getByRole("switch", { name: "Chính trị" }));

  // A second save now would carry the revision the first one is about to replace.
  await waitFor(() => expect(save).toBeDisabled());
  expect(within(topics).getByRole("switch", { name: "Lãnh tụ và lãnh đạo" })).toBeDisabled();
  release();
  await waitFor(() => expect(save).toBeEnabled());
  await user.click(save);
  await waitFor(() =>
    expect(savedGuardrails).toHaveBeenLastCalledWith(
      expect.objectContaining({ topics: [{ ...POLITICS, enabled: true }, LEADERS], revision: 8 }),
    ),
  );
});

it("refuses more blocked phrases than the API keeps", async () => {
  const user = show();
  const phrases = await screen.findByRole("region", { name: "Blocked phrases" });
  const field = within(phrases).getByRole("textbox", { name: "Blocked phrases" });
  await user.clear(field);
  await user.click(field);
  await user.paste(Array.from({ length: 21 }, (_, index) => `phrase ${index}`).join("\n"));
  await user.click(within(phrases).getByRole("button", { name: "Save" }));

  expect(await within(phrases).findByText("At most 20 phrases.")).toBeVisible();
  expect(savedGuardrails).not.toHaveBeenCalled();
});
