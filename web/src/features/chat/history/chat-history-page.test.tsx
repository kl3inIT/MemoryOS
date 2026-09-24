import { QueryClientProvider } from "@tanstack/react-query";
import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import { createMemoryOsQueryClient } from "@/lib/query-client";
import { ChatHistoryPage } from "./chat-history-page";

afterEach(() => vi.unstubAllGlobals());

const entry = {
  id: "7f000000-0000-4000-8000-000000000001",
  actorId: "10000000-0000-4000-8000-000000000001",
  person: "Trần Thu Hà",
  email: "ha@tasco.vn",
  title: "Nghỉ phép",
  question: "Tôi còn bao nhiêu ngày phép?",
  answer: "Bạn còn 5 ngày.",
  modelName: "gpt-5.1",
  messages: 4,
  feedback: "POSITIVE",
  deleted: false,
  updatedAt: "2026-09-21T03:14:00Z",
};

const transcript = {
  conversation: entry,
  messages: [
    {
      id: "8f000000-0000-4000-8000-000000000001",
      role: "USER",
      content: "Tôi còn bao nhiêu ngày phép?",
      modelName: null,
      createdAt: "2026-09-21T03:14:00Z",
      positive: null,
      comment: null,
      citations: [],
    },
    {
      id: "8f000000-0000-4000-8000-000000000002",
      role: "ASSISTANT",
      content: "Bạn còn 5 ngày.",
      modelName: "gpt-5.1",
      createdAt: "2026-09-21T03:14:20Z",
      positive: true,
      comment: "Đúng rồi",
      citations: ["Quy chế nghỉ phép 2026"],
    },
  ],
};

function mount(items: unknown[], overrides: Record<string, unknown> = {}) {
  const requested: URL[] = [];
  vi.stubGlobal(
    "fetch",
    vi.fn(async (request: Request) => {
      const url = new URL(request.url);
      requested.push(url);
      if (url.pathname.endsWith("/api/chat/history"))
        return Response.json({
          items,
          nextCursor: null,
          conversations: items.length,
          positive: 1,
          negative: 0,
          ...overrides,
        });
      return Response.json(transcript);
    }),
  );
  render(
    <QueryClientProvider client={createMemoryOsQueryClient()}>
      <ChatHistoryPage />
    </QueryClientProvider>,
  );
  return requested;
}

describe("conversation history", () => {
  it("lists who asked, what they asked and how it was rated", async () => {
    mount([entry]);
    const row = (await screen.findByText("Tôi còn bao nhiêu ngày phép?")).closest("tr")!;
    expect(within(row).getByText("Trần Thu Hà")).toBeVisible();
    expect(within(row).getByText("ha@tasco.vn")).toBeVisible();
    expect(within(row).getByText("4 messages")).toBeVisible();
    expect(within(row).getByText("Marked good")).toBeVisible();
    expect(screen.getByText("Conversations").closest("div")).toHaveTextContent("1");
  });

  it("marks a conversation its owner deleted, which is still listed", async () => {
    mount([{ ...entry, deleted: true }]);
    const row = (await screen.findByText("Tôi còn bao nhiêu ngày phép?")).closest("tr")!;
    expect(within(row).getByText("Deleted")).toBeVisible();
  });

  it("names the asker as hidden when the organization hides who asked", async () => {
    mount([{ ...entry, actorId: null, person: null, email: null }]);
    const row = (await screen.findByText("Tôi còn bao nhiêu ngày phép?")).closest("tr")!;
    expect(within(row).getByText("Hidden")).toBeVisible();
    // The question itself is not hidden; only who asked is.
    expect(within(row).getByText("Tôi còn bao nhiêu ngày phép?")).toBeVisible();
  });

  it("opens the transcript with its feedback and the titles it cited", async () => {
    mount([entry]);
    await userEvent.click(await screen.findByText("Tôi còn bao nhiêu ngày phép?"));
    const panel = await screen.findByRole("dialog");
    expect(within(panel).getByText("Bạn còn 5 ngày.")).toBeVisible();
    expect(within(panel).getByText("Quy chế nghỉ phép 2026")).toBeVisible();
    expect(within(panel).getByText("Feedback: Đúng rồi")).toBeVisible();
  });

  it("exports what the filters select", async () => {
    mount([entry]);
    const link = await screen.findByRole("link", { name: "Export CSV" });
    const href = new URL(link.getAttribute("href")!, "http://memoryos.test");
    expect(href.pathname).toBe("/api/chat/history/export");
    expect(href.searchParams.get("from")).toMatch(/^\d{4}-\d{2}-\d{2}T/);
    expect(href.searchParams.has("size")).toBe(false);
  });

  it("filters by feedback through the server, not in the browser", async () => {
    const requested = mount([entry]);
    await screen.findByText("Tôi còn bao nhiêu ngày phép?");
    await userEvent.click(screen.getByRole("combobox", { name: "Feedback" }));
    await userEvent.click(await screen.findByRole("option", { name: "Marked bad" }));
    await vi.waitFor(() => expect(requested.at(-1)!.searchParams.get("feedback")).toBe("NEGATIVE"));
  });

  it("explains an empty period instead of an empty table", async () => {
    mount([], { conversations: 0, positive: 0, negative: 0 });
    expect(await screen.findByText("No conversation in this period.")).toBeVisible();
  });
});
