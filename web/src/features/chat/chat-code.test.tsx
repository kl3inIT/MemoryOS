import { cleanup, fireEvent, render, screen, within } from "@testing-library/react";
import { AssistantRuntimeProvider, ThreadPrimitive, useLocalRuntime } from "@assistant-ui/react";
import { beforeEach, describe, expect, it } from "vitest";
import { i18n } from "@/i18n";
import { ChatGeneratedFiles } from "./chat-generated-files";
import { ChatPanelContext } from "./chat-panel-context";
import { fileSize, parseGeneratedFiles } from "./chat-code";

const file = {
  id: "6f1d2c3a-9b4e-4f77-8a21-5c0e7b8d9a10",
  filename: "báo cáo.xlsx",
  mediaType: "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
  sizeBytes: 12_698,
};

function Thread({ custom }: { custom: Record<string, unknown> }) {
  const runtime = useLocalRuntime(
    { run: async () => ({ content: [] }) },
    {
      initialMessages: [
        {
          id: "answer",
          role: "assistant",
          content: [{ type: "text", text: "Xong" }],
          metadata: { custom },
        },
      ],
    },
  );
  return (
    <AssistantRuntimeProvider runtime={runtime}>
      <ThreadPrimitive.Messages
        components={{ AssistantMessage: ChatGeneratedFiles, UserMessage: () => null }}
      />
    </AssistantRuntimeProvider>
  );
}

beforeEach(async () => {
  await i18n.changeLanguage("vi");
});

describe("files run_python generated", () => {
  it("offers each file as an authorized download and renders nothing without files", () => {
    render(<Thread custom={{ generatedFiles: [file] }} />);

    const link = screen.getByRole("link", { name: `Tải ${file.filename}` });
    expect(link).toHaveAttribute("href", `/api/chat/file-artifacts/${file.id}/content`);
    expect(link).toHaveAttribute("download", file.filename);
    const row = screen.getByRole("listitem");
    expect(within(row).getByText(file.filename)).toBeVisible();
    expect(within(row).getByText("12,4 KB")).toBeVisible();
    expect(row.querySelector("[data-slot=file-root]")).not.toBeNull();

    cleanup();
    render(<Thread custom={{}} />);
    expect(screen.queryByRole("link")).toBeNull();
  });

  it("opens the file in the side panel from its name", () => {
    const opened: unknown[] = [];
    render(
      <ChatPanelContext.Provider
        value={{
          panelId: "panel",
          open: () => {},
          openFile: (selected) => opened.push(selected),
          openArtifact: () => {},
          close: () => {},
        }}
      >
        <Thread custom={{ generatedFiles: [file] }} />
      </ChatPanelContext.Provider>,
    );

    const preview = screen.getByRole("button", { name: `Xem trước ${file.filename}` });
    expect(preview).toHaveAttribute("aria-expanded", "false");
    fireEvent.click(preview);
    expect(opened).toEqual([{ id: file.id, filename: file.filename, generated: file }]);
  });

  it("keeps only well-formed files from a reloaded conversation", () => {
    expect(parseGeneratedFiles([file])).toEqual([file]);
    expect(parseGeneratedFiles([{ ...file, id: "not-a-uuid" }])).toEqual([]);
    expect(parseGeneratedFiles(undefined)).toEqual([]);
  });

  it("states the size in the reader's locale", () => {
    expect(fileSize(900, "en")).toBe("900 B");
    expect(fileSize(12_698, "en")).toBe("12.4 KB");
    expect(fileSize(12_698, "vi")).toBe("12,4 KB");
    expect(fileSize(5 * 1024 * 1024, "en")).toBe("5 MB");
  });
});

// The step body is the only place the code and its output appear; both are streaming-only state.
describe("the run_python timeline step", () => {
  it("shows the code, output, errors, file count and no-output note like Onyx, in both languages", async () => {
    const { ChatToolStep } = await import("./chat-activity-view");
    const codeRuns = {
      call1: {
        code: "print('xin chào')",
        stdout: "xin chào\n",
        stderr: "",
        files: [],
        status: "running",
      },
      call2: {
        code: "1/0",
        stdout: "",
        stderr: "ZeroDivisionError: division by zero",
        files: [],
        status: "failed",
      },
      call3: { code: "open('a.csv','w')", stdout: "", stderr: "", files: [file], status: "done" },
    };
    function Steps() {
      const runtime = useLocalRuntime(
        { run: async () => ({ content: [] }) },
        {
          initialMessages: [
            {
              id: "answer",
              role: "assistant",
              content: [{ type: "text", text: "Xong" }],
              metadata: { custom: { codeRuns } },
            },
          ],
        },
      );
      const part = (toolCallId: string) =>
        ({
          type: "tool-call",
          toolCallId,
          toolName: "run_python",
          args: {},
          status: { type: "complete" },
          result: "{}",
        }) as never;
      return (
        <AssistantRuntimeProvider runtime={runtime}>
          <ThreadPrimitive.Messages
            components={{
              AssistantMessage: () => (
                <>
                  <ChatToolStep part={part("call1")} />
                  <ChatToolStep part={part("call2")} />
                  <ChatToolStep part={part("call3")} />
                </>
              ),
              UserMessage: () => null,
            }}
          />
        </AssistantRuntimeProvider>
      );
    }
    render(<Steps />);

    expect(await screen.findByText(/print\('xin chào'\)/)).toBeVisible();
    expect(screen.getByText(/^xin chào$/)).toBeVisible();
    expect(screen.getByRole("region", { name: "Kết quả" })).toBeVisible();
    const error = screen.getByRole("region", { name: "Lỗi" });
    expect(within(error).getByText("ZeroDivisionError: division by zero")).toBeVisible();
    expect(screen.getByText("Đã tạo 1 tệp")).toBeVisible();
    expect(screen.getAllByText("Không có output")).toHaveLength(1);
    await i18n.changeLanguage("en");
    expect(screen.getByRole("region", { name: "Output" })).toBeVisible();
    expect(screen.getByRole("region", { name: "Error" })).toBeVisible();
    expect(screen.getByText("Files created: 1")).toBeVisible();
    expect(screen.getByText("No output")).toBeVisible();
    await i18n.changeLanguage("vi");
  });
});
