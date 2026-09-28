import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, expect, it, vi } from "vitest";
import { i18n } from "@/i18n/index";
import { ChatImageToggle } from "./chat-image-options";

beforeEach(async () => {
  await i18n.changeLanguage("vi");
});

it("enables image generation from off and closes the menu", async () => {
  const onChange = vi.fn();
  const onDone = vi.fn();
  render(<ChatImageToggle value="off" onChange={onChange} onDone={onDone} />);
  const button = screen.getByRole("button", { name: "Tạo ảnh" });
  expect(button).toHaveAttribute("aria-pressed", "false");
  await userEvent.click(button);
  expect(onChange).toHaveBeenCalledWith("auto");
  expect(onDone).toHaveBeenCalledTimes(1);
});

it("shows the active state and turns image generation off again", async () => {
  const onChange = vi.fn();
  render(<ChatImageToggle value="auto" onChange={onChange} onDone={vi.fn()} />);
  const button = screen.getByRole("button", { name: "Tạo ảnh" });
  expect(button).toHaveAttribute("aria-pressed", "true");
  await userEvent.click(button);
  expect(onChange).toHaveBeenCalledWith("off");
});

it("stays disabled with a notice while no image provider is connected", () => {
  render(<ChatImageToggle value="off" onChange={vi.fn()} onDone={vi.fn()} available={false} />);
  expect(screen.getByRole("button", { name: "Tạo ảnh" })).toBeDisabled();
  expect(screen.getByText("Chưa kết nối mô hình tạo ảnh.")).toBeInTheDocument();
});

it("hides the notice while availability is loading and offers retry on failure", () => {
  const onRetry = vi.fn();
  const { rerender } = render(
    <ChatImageToggle value="off" onChange={vi.fn()} onDone={vi.fn()} available={false} pending />,
  );
  expect(screen.queryByText("Chưa kết nối mô hình tạo ảnh.")).not.toBeInTheDocument();
  rerender(
    <ChatImageToggle
      value="off"
      onChange={vi.fn()}
      onDone={vi.fn()}
      available={false}
      onRetry={onRetry}
    />,
  );
  expect(screen.getByRole("button", { name: "Tải lại" })).toBeInTheDocument();
});
