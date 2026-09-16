import { render, screen, waitFor } from "@testing-library/react";
import {
  AssistantRuntimeProvider,
  MessagePrimitive,
  ThreadPrimitive,
  useLocalRuntime,
} from "@assistant-ui/react";
import { describe, expect, it } from "vitest";
import { MarkdownText } from "./markdown-text";

const link = "/api/chat/file-artifacts/00000000-0000-0000-0000-000000000000/content";

describe("assistant markdown", () => {
  it("keeps same-origin generated-file links and images and drops script URLs", async () => {
    function Thread() {
      const runtime = useLocalRuntime(
        { run: async () => ({ content: [] }) },
        {
          initialMessages: [
            {
              id: "answer",
              role: "assistant",
              content: [
                {
                  type: "text",
                  text: `[báo cáo.xlsx](${link})\n\n![Biểu đồ](${link})\n\n[bad](javascript:alert(1))`,
                },
              ],
            },
          ],
        },
      );
      return (
        <AssistantRuntimeProvider runtime={runtime}>
          <ThreadPrimitive.Messages
            components={{
              AssistantMessage: () => (
                <MessagePrimitive.Parts components={{ Text: MarkdownText }} />
              ),
              UserMessage: () => null,
            }}
          />
        </AssistantRuntimeProvider>
      );
    }
    render(<Thread />);
    await waitFor(() =>
      expect(screen.getByRole("link", { name: "báo cáo.xlsx" })).toHaveAttribute("href", link),
    );
    expect(screen.getByRole("img", { name: "Biểu đồ" })).toHaveAttribute("src", link);
    expect(screen.getByText("bad").closest("a")?.getAttribute("href") ?? "").not.toMatch(
      /javascript/,
    );
  });
});
