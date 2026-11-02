import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClientProvider } from "@tanstack/react-query";
import {
  AssistantRuntimeProvider,
  ComposerPrimitive,
  useLocalRuntime,
  type ThreadMessage,
} from "@assistant-ui/react";
import { beforeEach, describe, expect, it } from "vitest";
import { ComposerAttachments } from "@/components/assistant-ui/elements/attachment.aui";
import type { ApplicationSession } from "@/features/identity/application-session-context";
import { ApplicationSessionProvider } from "@/features/identity/application-session-provider";
import { i18n } from "@/i18n/index";
import { handleGetChatPreferences } from "@/lib/hey-api/msw.gen";
import { createMemoryOsQueryClient } from "@/lib/query-client";
import { server } from "@/test/msw";
import { ChatComposerRoot, ChatComposerSend } from "./chat-composer";
import { ChatPasteLimitAlert, ChatPasteTile, ChatPasteTiles } from "./chat-paste-tiles";
import { useLargePaste, usePasteTiles } from "./chat-paste-tiles-context";

const session: ApplicationSession = {
  actorId: "7b9f56d0-3026-4d2d-8e5f-1d6af6da93a1",
  displayName: null,
  authorizationVersion: 1,
  uiLanguage: "en",
  tenant: { displayName: "Tasco", role: "MEMBER" },
  capabilities: ["CHAT_READ", "CHAT_WRITE"],
  scopedCapabilities: [],
};

const preferences = (pasteAsTile: boolean) => ({
  workRole: "",
  personalPreferences: "",
  defaultModelId: null,
  temperatureDefault: null,
  reasoningEffortDefault: "OFF" as const,
  autoScroll: true,
  pasteAsTile,
  displayName: null,
  email: null,
});

const log = [
  "10:01 INFO started",
  "10:02 WARN slow disk",
  "10:03 ERROR timeout",
  "10:04 INFO retry",
];

function Tiles() {
  const tiles = usePasteTiles()?.tiles ?? [];
  return (
    <ComposerAttachments
      extra={tiles.map((tile) => (
        <ChatPasteTile key={tile.id} tile={tile} />
      ))}
    />
  );
}

function Field() {
  return <ComposerPrimitive.Input aria-label="Question" onPaste={useLargePaste()} />;
}

function Harness({ sent }: { sent: string[] }) {
  const runtime = useLocalRuntime({
    run: async ({ messages }: { messages: readonly ThreadMessage[] }) => {
      const last = messages.at(-1);
      sent.push(
        last?.content.map((part) => (part.type === "text" ? part.text : "")).join("") ?? "",
      );
      return { content: [{ type: "text" as const, text: "ok" }] };
    },
  });
  return (
    <AssistantRuntimeProvider runtime={runtime}>
      <ChatPasteTiles>
        <ChatComposerRoot>
          <Tiles />
          <Field />
          <ChatPasteLimitAlert />
          <ChatComposerSend>Send</ChatComposerSend>
        </ChatComposerRoot>
      </ChatPasteTiles>
    </AssistantRuntimeProvider>
  );
}

async function mount({ pasteAsTile = true } = {}) {
  server.use(handleGetChatPreferences({ body: preferences(pasteAsTile) }));
  const client = createMemoryOsQueryClient();
  const sent: string[] = [];
  render(
    <QueryClientProvider client={client}>
      <ApplicationSessionProvider session={session}>
        <Harness sent={sent} />
      </ApplicationSessionProvider>
    </QueryClientProvider>,
  );
  await waitFor(() => expect(client.isFetching()).toBe(0));
  return sent;
}

/** Pastes plain text; returns whether the composer kept the browser from inserting it. */
function paste(text: string) {
  const event = fireEvent.paste(screen.getByLabelText("Question"), {
    clipboardData: { getData: () => text, files: [] },
  });
  return !event;
}

describe("large pastes as composer tiles", () => {
  beforeEach(() => {
    void i18n.changeLanguage("en");
    sessionStorage.clear();
  });

  it("collapses a paste of more than 3 lines or 200 characters, and leaves the rest inline", async () => {
    await mount();
    expect(paste(log.slice(0, 3).join("\n"))).toBe(false);
    expect(paste(`${log.slice(0, 3).join("\n")}\n`)).toBe(false);
    expect(paste("x".repeat(200))).toBe(false);
    expect(screen.queryByText("Pasted text")).toBeNull();

    expect(paste(log.join("\n"))).toBe(true);
    expect(await screen.findByRole("button", { name: "Open pasted text, 4 lines" })).toBeVisible();
    expect(paste("x".repeat(201))).toBe(true);
    expect(await screen.findByRole("button", { name: "Open pasted text, 1 line" })).toBeVisible();
  });

  it("inserts every paste inline while the preference is off", async () => {
    await mount({ pasteAsTile: false });
    expect(paste(log.join("\n"))).toBe(false);
    expect(screen.queryByText("Pasted text")).toBeNull();
  });

  it("opens a tile to edit its text, and removes it with its button or an emptied text", async () => {
    const sent = await mount();
    paste(log.join("\n"));
    await userEvent.click(await screen.findByRole("button", { name: "Open pasted text, 4 lines" }));
    const dialog = await screen.findByRole("dialog");
    const content = within(dialog).getByLabelText("Pasted text content");
    expect(content).toHaveValue(log.join("\n"));
    await userEvent.clear(content);
    await userEvent.type(content, "one{Enter}two");
    await userEvent.click(within(dialog).getByRole("button", { name: "Save" }));
    expect(await screen.findByRole("button", { name: "Open pasted text, 2 lines" })).toBeVisible();

    await userEvent.click(screen.getByRole("button", { name: "Open pasted text, 2 lines" }));
    await userEvent.clear(
      within(await screen.findByRole("dialog")).getByLabelText("Pasted text content"),
    );
    await userEvent.click(within(screen.getByRole("dialog")).getByRole("button", { name: "Save" }));
    await waitFor(() => expect(screen.queryByText("Pasted text")).toBeNull());

    paste(log.join("\n"));
    await userEvent.click(await screen.findByRole("button", { name: "Remove pasted text" }));
    expect(screen.queryByText("Pasted text")).toBeNull();
    // Saving in the dialog never reaches the composer's own form.
    expect(sent).toHaveLength(0);
  });

  it("keeps the tiles of an unsent draft across a reload of the tab", async () => {
    await mount();
    paste(log.join("\n"));
    await screen.findByText("Pasted text");
    cleanup();
    await mount();
    expect(await screen.findByRole("button", { name: "Open pasted text, 4 lines" })).toBeVisible();
  });

  it("sends the typed text with each tile appended in paste order, from Send and from Enter", async () => {
    const sent = await mount();
    await userEvent.type(screen.getByLabelText("Question"), "Summarize these");
    paste(log.join("\n"));
    paste("y".repeat(250));
    await userEvent.click(await screen.findByRole("button", { name: "Send" }));
    await waitFor(() => expect(sent).toHaveLength(1));
    expect(sent[0]).toBe(`Summarize these\n\n${log.join("\n")}\n\n${"y".repeat(250)}`);
    expect(screen.queryByText("Pasted text")).toBeNull();
    expect(screen.getByLabelText("Question")).toHaveValue("");

    // A tile alone is a message, sent with Enter from the empty field.
    paste(log.join("\n"));
    await screen.findByText("Pasted text");
    await userEvent.type(screen.getByLabelText("Question"), "{Enter}");
    await waitFor(() => expect(sent).toHaveLength(2));
    expect(sent[1]).toBe(log.join("\n"));
  });

  it("keeps Send disabled and says why while the joined text exceeds the message limit", async () => {
    const sent = await mount();
    await userEvent.type(screen.getByLabelText("Question"), "Read");
    paste("z".repeat(31996));
    expect(await screen.findByRole("alert")).toHaveTextContent(
      "The message is longer than 32,000 characters.",
    );
    expect(screen.getByRole("button", { name: "Send" })).toBeDisabled();
    await userEvent.type(screen.getByLabelText("Question"), "{Enter}");
    expect(sent).toHaveLength(0);
    // Removing the tile lifts the block.
    await userEvent.click(screen.getByRole("button", { name: "Remove pasted text" }));
    expect(screen.queryByRole("alert")).toBeNull();
  });
});
