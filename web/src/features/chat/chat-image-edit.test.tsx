import { fireEvent, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { i18n } from "@/i18n";
import { ChatImageEditDialog } from "./chat-image-edit";

vi.mock("./chat-image-mask", async (importOriginal) => ({
  ...(await importOriginal<typeof import("./chat-image-mask")>()),
  renderMask: vi.fn(async () => new Blob(["mask"], { type: "image/png" })),
}));

const imageId = "7d1e0c7a-2f7b-4f1a-9f55-0f5c7b1d8e21";

beforeEach(async () => {
  await i18n.changeLanguage("vi");
  vi.spyOn(HTMLCanvasElement.prototype, "getContext").mockReturnValue(null);
});

afterEach(() => {
  vi.restoreAllMocks();
});

function loadImage() {
  const image = screen.getByRole("img", { name: "Ảnh đã tạo" });
  Object.defineProperty(image, "naturalWidth", { value: 1024 });
  Object.defineProperty(image, "naturalHeight", { value: 768 });
  fireEvent.load(image);
}

it("puts the instruction and a mask named after the image in the composer", async () => {
  const onSubmit = vi.fn();
  const onOpenChange = vi.fn();
  render(
    <ChatImageEditDialog
      imageId={imageId}
      src={`/api/chat/image-artifacts/${imageId}/content`}
      onOpenChange={onOpenChange}
      onSubmit={onSubmit}
    />,
  );
  const send = screen.getByRole("button", { name: "Đưa vào khung chat" });
  await userEvent.type(screen.getByLabelText("Mô tả thay đổi"), "Đổi áo sang màu đỏ");
  expect(send).toBeDisabled(); // The mask needs the image's natural size.
  loadImage();
  expect(screen.getByLabelText("Tô vùng cần sửa")).toBeInTheDocument();
  await userEvent.click(send);

  expect(onSubmit).toHaveBeenCalledOnce();
  const [{ instruction, mask }] = onSubmit.mock.calls[0] as [{ instruction: string; mask: File }];
  expect(instruction).toBe("Đổi áo sang màu đỏ");
  expect(mask.name).toBe(`mask-for-${imageId}.png`);
  expect(mask.type).toBe("image/png");
  expect(onOpenChange).toHaveBeenCalledWith(false);
});

it("needs an instruction and closes without sending on cancel", async () => {
  const onSubmit = vi.fn();
  const onOpenChange = vi.fn();
  render(
    <ChatImageEditDialog
      imageId={imageId}
      src="/image"
      onOpenChange={onOpenChange}
      onSubmit={onSubmit}
    />,
  );
  loadImage();
  expect(screen.getByRole("button", { name: "Đưa vào khung chat" })).toBeDisabled();
  expect(screen.getByRole("button", { name: "Xoá vùng tô" })).toBeDisabled();
  await userEvent.click(screen.getByRole("button", { name: "Hủy" }));
  expect(onOpenChange).toHaveBeenCalledWith(false);
  expect(onSubmit).not.toHaveBeenCalled();
});
