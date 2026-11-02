import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import {
  AssistantRuntimeProvider,
  ThreadPrimitive,
  useExternalStoreRuntime,
  type AppendMessage,
  type ThreadMessageLike,
} from "@assistant-ui/react";
import { beforeEach, expect, it, vi } from "vitest";
import { i18n } from "@/i18n/index";
import { ChatFollowUps } from "./chat-follow-ups";

const question: ThreadMessageLike = { id: "q1", role: "user", content: "Quy trình xin nghỉ phép?" };
const answer = (id: string): ThreadMessageLike => ({
  id,
  role: "assistant",
  content: "Gửi đơn trên cổng HR.",
  status: { type: "complete", reason: "stop" },
});

function Thread({
  suggestions = [],
  pendingFor,
  onNew = async () => {},
}: {
  suggestions?: string[];
  pendingFor?: string;
  onNew?: (message: AppendMessage) => Promise<void>;
}) {
  const runtime = useExternalStoreRuntime({
    messages: [question, answer("a1"), { ...question, id: "q2" }, answer("a2")],
    convertMessage: (value: ThreadMessageLike) => value,
    suggestions: suggestions.map((prompt) => ({ prompt })),
    onNew,
  });
  return (
    <AssistantRuntimeProvider runtime={runtime}>
      <ThreadPrimitive.Messages
        components={{
          AssistantMessage: () => (
            <div data-testid="answer">
              <ChatFollowUps pendingFor={pendingFor} />
            </div>
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

it("shows the questions under the last answer only, and choosing one asks it", async () => {
  const onNew = vi.fn(async () => {});
  const user = userEvent.setup();
  render(<Thread suggestions={["Mẫu đơn ở đâu?", "Ai duyệt khi quản lý vắng?"]} onNew={onNew} />);

  const [first, last] = screen.getAllByTestId("answer");
  expect(first).toBeEmptyDOMElement();
  expect(last).toHaveTextContent("Mẫu đơn ở đâu?");
  await user.click(screen.getByRole("button", { name: "Ai duyệt khi quản lý vắng?" }));

  expect(onNew).toHaveBeenCalledWith(
    expect.objectContaining({ content: [{ type: "text", text: "Ai duyệt khi quản lý vắng?" }] }),
  );
});

it("holds three placeholders while the last answer's questions are written", () => {
  render(<Thread pendingFor="a2" />);
  const status = screen.getByRole("status", { name: "Đang gợi ý câu hỏi tiếp theo" });
  expect(status.children).toHaveLength(3);
});

it("shows nothing when no question came", () => {
  render(<Thread pendingFor="a1" />);
  expect(screen.queryByRole("status")).toBeNull();
  expect(screen.queryByRole("button")).toBeNull();
});
