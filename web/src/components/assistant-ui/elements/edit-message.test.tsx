import { fireEvent, render, screen } from "@testing-library/react";
import { beforeEach, expect, it, vi } from "vitest";
import { i18n } from "@/i18n";
import { EditMessage } from "./edit-message";

beforeEach(async () => {
  await i18n.changeLanguage("vi");
});

it("keeps editing keyboard actions, focus, errors and attachment actions in the footer", () => {
  const save = vi.fn();
  const cancel = vi.fn();
  render(
    <EditMessage
      value="Câu hỏi"
      onValueChange={vi.fn()}
      onSave={save}
      onCancel={cancel}
      pending={false}
      error="Không gửi được"
    >
      <button type="button">Đính kèm</button>
    </EditMessage>,
  );
  const input = screen.getByRole("textbox");
  expect(input).toHaveFocus();
  expect(input).toHaveAccessibleDescription("Câu hỏi và câu trả lời cũ vẫn có thể chọn lại.");
  expect(screen.getByRole("alert")).toHaveTextContent("Không gửi được");
  expect(
    screen.getByRole("button", { name: "Đính kèm" }).closest('[data-slot="edit-message-footer"]'),
  ).not.toBeNull();
  fireEvent.keyDown(input, { key: "Enter" });
  fireEvent.keyDown(input, { key: "Enter", ctrlKey: true, isComposing: true });
  expect(save).not.toHaveBeenCalled();
  fireEvent.keyDown(input, { key: "Enter", ctrlKey: true });
  expect(save).toHaveBeenCalledTimes(1);
  fireEvent.keyDown(input, { key: "Escape" });
  expect(cancel).toHaveBeenCalledTimes(1);
});

it("blocks submit and cancel while saving and blocks submit while the conversation is busy", () => {
  const save = vi.fn();
  const cancel = vi.fn();
  const props = { value: "Câu hỏi", onValueChange: vi.fn(), onSave: save, onCancel: cancel };
  const view = render(<EditMessage {...props} pending />);
  expect(screen.getByRole("textbox")).toBeDisabled();
  expect(screen.getByRole("button", { name: "Hủy" })).toBeDisabled();
  expect(screen.getByRole("button", { name: "Lưu và gửi" })).toBeDisabled();
  fireEvent.keyDown(screen.getByRole("textbox"), { key: "Enter", ctrlKey: true });
  fireEvent.keyDown(screen.getByRole("textbox"), { key: "Escape" });
  expect(save).not.toHaveBeenCalled();
  expect(cancel).not.toHaveBeenCalled();
  view.rerender(<EditMessage {...props} pending={false} saveDisabled />);
  fireEvent.keyDown(screen.getByRole("textbox"), { key: "Enter", metaKey: true });
  expect(save).not.toHaveBeenCalled();
});
