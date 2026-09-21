import { cleanup, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { i18n } from "@/i18n";
import { ChatTemporaryBadge, ChatTemporaryNotice, ChatTemporaryToggle } from "./chat-temporary";

beforeEach(async () => {
  await i18n.changeLanguage("vi");
});
afterEach(cleanup);

/**
 * The toggle renders on the new-conversation screen, which is outside any tooltip provider: a Radix tooltip
 * here took the whole chat screen down, so the control is asserted to mount on its own.
 */
it("mounts on its own and explains temporary chat before turning it on", async () => {
  const onChange = vi.fn();
  render(<ChatTemporaryToggle value={false} onChange={onChange} />);
  const user = userEvent.setup();

  const toggle = screen.getByRole("button", { name: "Bật chat tạm thời" });
  expect(toggle).toHaveAttribute("aria-pressed", "false");
  expect(toggle).toHaveAttribute("title", "Chat tạm thời");

  await user.click(toggle);
  // Turning it on is explained once, in the terms that matter.
  expect(await screen.findByText("Không vào lịch sử")).toBeInTheDocument();
  expect(screen.getByText("Tự xoá")).toBeInTheDocument();
  expect(screen.getByText("Không chia sẻ, không dự án")).toBeInTheDocument();
  expect(onChange).not.toHaveBeenCalled();

  await user.click(screen.getByRole("button", { name: "Bắt đầu" }));
  expect(onChange).toHaveBeenCalledWith(true);
});

it("turns itself off without asking again", async () => {
  const onChange = vi.fn();
  render(<ChatTemporaryToggle value onChange={onChange} />);
  const user = userEvent.setup();

  await user.click(screen.getByRole("button", { name: "Tắt chat tạm thời" }));

  expect(onChange).toHaveBeenCalledWith(false);
  expect(screen.queryByText("Không vào lịch sử")).not.toBeInTheDocument();
});

it("marks a temporary conversation and offers the way out of it", async () => {
  const onLeave = vi.fn();
  render(
    <>
      <ChatTemporaryBadge temporary />
      <ChatTemporaryNotice onLeave={onLeave} />
    </>,
  );
  const user = userEvent.setup();

  expect(screen.getByText("Tạm thời")).toBeInTheDocument();
  expect(
    screen.getByText("Cuộc trò chuyện này không được lưu và sẽ tự xoá cùng tệp của nó."),
  ).toBeInTheDocument();

  await user.click(screen.getByRole("button", { name: "Hội thoại mới" }));
  expect(onLeave).toHaveBeenCalled();
});

it("marks nothing when the conversation is kept", () => {
  render(<ChatTemporaryBadge temporary={false} />);

  expect(screen.queryByText("Tạm thời")).not.toBeInTheDocument();
});
