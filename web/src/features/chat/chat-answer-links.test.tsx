import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import {
  AssistantRuntimeProvider,
  MessagePrimitive,
  ThreadPrimitive,
  useLocalRuntime,
} from "@assistant-ui/react";
import { MarkdownText } from "@/components/assistant-ui/elements/markdown-text";
import { remarkSandboxLinks } from "./chat-evidence";
import { ChatPanelContext } from "./chat-panel-context";
import { describe, expect, it } from "vitest";
import { ChatMarkdownLink } from "./chat-sources";

const file = "/api/chat/file-artifacts/00000000-0000-0000-0000-000000000000/content";

describe("links in an answer body", () => {
  it("opens a generated-file link in the preview and neutralizes every other model-written path", () => {
    const previewed: unknown[] = [];
    render(
      <ChatPanelContext.Provider
        value={{
          open: () => {},
          previewFile: (target) => previewed.push(target),
          openArtifact: () => {},
          close: () => {},
        }}
      >
        <ChatMarkdownLink href={file}>báo cáo.xlsx</ChatMarkdownLink>
        <ChatMarkdownLink href="/admin/users">nội bộ</ChatMarkdownLink>
        <ChatMarkdownLink href="javascript:alert(1)">bấm đi</ChatMarkdownLink>
        <ChatMarkdownLink href="/api/chat/file-artifacts/../../etc/content">giả</ChatMarkdownLink>
      </ChatPanelContext.Provider>,
    );

    // Onyx: a link to a chat file opens the preview modal instead of downloading.
    fireEvent.click(screen.getByRole("button", { name: "báo cáo.xlsx" }));
    expect(previewed).toEqual([
      { source: "generated", id: "00000000-0000-0000-0000-000000000000", filename: "báo cáo.xlsx" },
    ]);
    for (const label of ["nội bộ", "bấm đi", "giả"]) {
      expect(screen.queryByRole("link", { name: label })).toBeNull();
      expect(screen.getByText(label)).toBeVisible();
    }
  });

  it("drops the sandbox: prefix models put on a generated file's link instead of blocking it", async () => {
    const plugins = [remarkSandboxLinks];
    const components = { a: ChatMarkdownLink };
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
                  text: `[Báo cáo Q3.xlsx](sandbox:${file})

[khác](sandbox:/mnt/data/x.csv)`,
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
                <MessagePrimitive.Parts
                  components={{
                    Text: () => <MarkdownText remarkPlugins={plugins} components={components} />,
                  }}
                />
              ),
              UserMessage: () => null,
            }}
          />
        </AssistantRuntimeProvider>
      );
    }
    render(<Thread />);

    await waitFor(() =>
      expect(screen.getByRole("button", { name: "Báo cáo Q3.xlsx" })).toBeVisible(),
    );
    expect(document.body.textContent).not.toContain("[blocked]");
    // Any other sandbox path is not a file MemoryOS serves and stays plain text.
    expect(screen.queryByRole("link", { name: "khác" })).toBeNull();
    expect(screen.getByText("khác")).toBeVisible();
  });
});
