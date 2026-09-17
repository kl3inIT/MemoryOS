import { fireEvent, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, expect, it, vi } from "vitest";
import {
  ApplicationSessionContext,
  type ApplicationSession,
} from "@/features/identity/application-session-context";
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

it("stays visible but disabled and hints at contacting an administrator", async () => {
  const onChange = vi.fn();
  const onDone = vi.fn();
  render(<ChatImageToggle value="off" onChange={onChange} onDone={onDone} available={false} />);
  const button = screen.getByRole("button", { name: "Tạo ảnh" });
  expect(button).toBeDisabled();
  await userEvent.click(button);
  expect(onChange).not.toHaveBeenCalled();
  expect(onDone).not.toHaveBeenCalled();
  // The disabled button cannot be a tooltip trigger; the wrapping span carries it.
  // Focus opens it deterministically — pointer hover races Radix's open delay in CI.
  fireEvent.focus(button.parentElement!);
  expect(await screen.findByRole("tooltip", {}, { timeout: 3000 })).toHaveTextContent(
    "Tạo ảnh chưa được bật. Liên hệ quản trị viên để thêm mô hình tạo ảnh.",
  );
});

it("points model managers at the image-generation administration page", async () => {
  const session = {
    capabilities: ["MODELS_MANAGE"],
    scopedCapabilities: [],
  } as unknown as ApplicationSession;
  render(
    <ApplicationSessionContext.Provider value={session}>
      <ChatImageToggle value="off" onChange={vi.fn()} onDone={vi.fn()} available={false} />
    </ApplicationSessionContext.Provider>,
  );
  const button = screen.getByRole("button", { name: "Tạo ảnh" });
  expect(button).toBeDisabled();
  fireEvent.focus(button.parentElement!);
  expect(await screen.findByRole("tooltip", {}, { timeout: 3000 })).toHaveTextContent(
    "Chưa có nhà cung cấp tạo ảnh — thêm mô hình trong Quản trị › Tạo ảnh.",
  );
});
