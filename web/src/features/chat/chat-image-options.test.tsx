import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, expect, it, vi } from "vitest";
import { i18n } from "@/i18n";
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
