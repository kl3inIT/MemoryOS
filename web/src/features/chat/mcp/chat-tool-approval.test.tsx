import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, expect, it, vi } from "vitest";
import { i18n } from "@/i18n";
import type { ToolPart } from "@/features/chat/activity/chat-activity-labels";
import { ChatToolApproval } from "./chat-tool-approval";

/** A tool part as assistant-ui hands it to the step: the gate the server streamed and its answer callback. */
function part(approved?: boolean, gate: Record<string, unknown> = {}): ToolPart {
  return {
    type: "tool-call",
    toolCallId: "call-1",
    toolName: "mcp_gmail_send_email",
    args: {},
    argsText: "{}",
    status: { type: approved === undefined ? "requires-action" : "complete" },
    approval: {
      id: "call-1",
      ...(approved === undefined ? {} : { approved }),
      descriptor: {
        serverName: "Gmail",
        toolName: "send_email",
        arguments: JSON.stringify({ to: "an@tasco.vn", body: "Chào anh An,\nEm gửi báo cáo." }),
        ...gate,
      },
    },
    respondToApproval: vi.fn().mockResolvedValue(undefined),
  } as unknown as ToolPart;
}

beforeEach(async () => {
  await i18n.changeLanguage("vi");
});

it("shows each argument and answers the gate through the runtime", async () => {
  const waiting = part();
  render(<ChatToolApproval part={waiting} tool="send_email" server="Gmail" />);
  expect(screen.getByText("Cho phép send_email trên Gmail?")).toBeInTheDocument();
  expect(screen.getByText("an@tasco.vn")).toBeInTheDocument();
  await userEvent.click(screen.getByRole("button", { name: "Từ chối" }));
  await waitFor(() => expect(waiting.respondToApproval).toHaveBeenCalledWith({ approved: false }));
});

it("leaves a receipt instead of controls once answered", () => {
  render(<ChatToolApproval part={part(false)} tool="send_email" server="Gmail" />);
  expect(screen.getByText("Bạn đã từ chối.")).toBeInTheDocument();
  expect(screen.queryByRole("button", { name: "Cho phép" })).not.toBeInTheDocument();
});

it("offers always allow only when the gate does, and answers it with that option", async () => {
  const waiting = part(undefined, {
    options: [
      { id: "allow-once", kind: "allow-once" },
      { id: "allow-always", kind: "allow-always" },
    ],
  });
  render(<ChatToolApproval part={waiting} tool="send_email" server="Gmail" />);
  await userEvent.click(screen.getByRole("button", { name: "Luôn cho phép" }));
  await waitFor(() =>
    expect(waiting.respondToApproval).toHaveBeenCalledWith({
      approved: true,
      optionId: "allow-always",
    }),
  );
});

it("keeps no buttons for a call nobody answered in time", () => {
  render(
    <ChatToolApproval
      part={part(undefined, { resolution: "expired" })}
      tool="send_email"
      server="Gmail"
    />,
  );
  expect(screen.getByText("Hết thời gian chờ, công cụ không chạy.")).toBeInTheDocument();
  expect(screen.queryByRole("button", { name: "Cho phép" })).not.toBeInTheDocument();
  expect(screen.queryByRole("button", { name: "Luôn cho phép" })).not.toBeInTheDocument();
});

it("names the tool by the title its server gives", () => {
  render(
    <ChatToolApproval
      part={part(undefined, { toolTitle: "Send email" })}
      tool="send_email"
      server="Gmail"
    />,
  );
  expect(screen.getByText("Cho phép Send email trên Gmail?")).toBeInTheDocument();
});
