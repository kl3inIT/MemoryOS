import { render, screen } from "@testing-library/react";
import {
  AssistantRuntimeProvider,
  ThreadPrimitive,
  useExternalStoreRuntime,
  type ThreadMessageLike,
} from "@assistant-ui/react";
import { beforeEach, expect, it } from "vitest";
import { i18n } from "@/i18n";
import { ChatActivityGroup } from "./chat-activity-view";

const finishedStep = {
  type: "tool-call",
  toolCallId: "call1",
  toolName: "run_python",
  args: { durationMs: 3000 },
  result: "{}",
} as const;

function Thread({ running, text }: { running: boolean; text?: string }) {
  const message: ThreadMessageLike = {
    id: "answer",
    role: "assistant",
    content: text ? [finishedStep, { type: "text", text }] : [finishedStep],
    status: running ? { type: "running" } : { type: "complete", reason: "stop" },
  };
  const runtime = useExternalStoreRuntime({
    messages: [message],
    isRunning: running,
    convertMessage: (value: ThreadMessageLike) => value,
    onNew: async () => {},
  });
  return (
    <AssistantRuntimeProvider runtime={runtime}>
      <ThreadPrimitive.Messages
        components={{
          AssistantMessage: () => (
            // The step finished; the group itself is not running.
            <ChatActivityGroup indices={[0]} running={false}>
              <span>step</span>
            </ChatActivityGroup>
          ),
          UserMessage: () => null,
        }}
      />
    </AssistantRuntimeProvider>
  );
}

beforeEach(async () => {
  await i18n.changeLanguage("vi");
});

it("keeps the last group live while the run continues after a finished step", async () => {
  render(<Thread running />);
  // The model is writing its next call: a finished header here reads as a stalled answer.
  expect(await screen.findByText("Đang suy nghĩ…")).toBeVisible();
  expect(screen.queryByText(/Đã suy nghĩ/)).toBeNull();
});

it("shows the finished header once the answer starts or the run ends", async () => {
  const { unmount } = render(<Thread running text="Doanh thu quý 3 tăng 6,8%." />);
  expect(await screen.findByText(/Đã suy nghĩ/)).toBeVisible();
  unmount();
  render(<Thread running={false} />);
  expect(await screen.findByText(/Đã suy nghĩ/)).toBeVisible();
});
