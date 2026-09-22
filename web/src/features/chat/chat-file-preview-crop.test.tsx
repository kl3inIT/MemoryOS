import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import type { UserEvent } from "@testing-library/user-event";
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { i18n } from "@/i18n";
import { ChatFilePreviewModal } from "./chat-file-preview-modal";
import type { PreviewTarget } from "./chat-file-preview";

const getChatImageArtifact = vi.hoisted(() => vi.fn());
const renderCrop = vi.hoisted(() => vi.fn());

vi.mock("@/lib/hey-api/sdk.gen", () => ({
  getChatImageArtifact: (...args: unknown[]) => getChatImageArtifact(...args),
  getChatFileArtifact: vi.fn(),
  downloadChatFile: vi.fn(),
  getChatFileArtifactPdfPreview: vi.fn(),
  previewChatFileArtifactSpreadsheet: vi.fn(),
  previewChatFileSpreadsheet: vi.fn(),
}));

// Canvas encoding is the browser's; the crop the modal asks for is what this test is about.
vi.mock("./chat-image-crop", async (importOriginal) => ({
  ...(await importOriginal<typeof import("./chat-image-crop")>()),
  renderCrop: (...args: unknown[]) => renderCrop(...args),
}));

vi.mock("@/features/identity/application-session-context", () => ({
  useApplicationSession: () => ({ actorId: "actor", authorizationVersion: 1, capabilities: [] }),
}));

const target: PreviewTarget = {
  source: "image",
  id: "11111111-1111-4111-8111-111111111111",
  filename: "So do kien truc.png",
  mediaType: "image/png",
};

function show(onSaveImage?: (file: File) => void) {
  render(
    <QueryClientProvider
      client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}
    >
      <ChatFilePreviewModal target={target} onClose={vi.fn()} onSaveImage={onSaveImage} />
    </QueryClientProvider>,
  );
}

/** The drawn image, which jsdom does not lay out. */
const shownBox = { left: 100, top: 50, width: 200, height: 100 };

beforeEach(async () => {
  await i18n.changeLanguage("vi");
  vi.clearAllMocks();
  getChatImageArtifact.mockResolvedValue({ data: new Blob(["png"], { type: "image/png" }) });
  renderCrop.mockResolvedValue(new Blob(["cropped"], { type: "image/png" }));
  globalThis.URL.createObjectURL = vi.fn(() => "blob:preview");
  globalThis.URL.revokeObjectURL = vi.fn();
  globalThis.createImageBitmap = vi.fn(async () => ({
    width: 1280,
    height: 720,
    close: vi.fn(),
  })) as unknown as typeof createImageBitmap;
  vi.spyOn(Element.prototype, "getBoundingClientRect").mockReturnValue({
    ...shownBox,
    right: shownBox.left + shownBox.width,
    bottom: shownBox.top + shownBox.height,
    x: shownBox.left,
    y: shownBox.top,
    toJSON: () => "",
  });
});
afterEach(() => {
  vi.restoreAllMocks();
  cleanup();
});

async function selectHalfTheImage(user: UserEvent) {
  const image = await screen.findByRole("img", { name: target.filename });
  // jsdom decodes nothing, so the image reports its size the way a browser would once it has loaded.
  Object.defineProperty(image, "naturalWidth", { value: 1280 });
  Object.defineProperty(image, "naturalHeight", { value: 720 });
  fireEvent.load(image);
  await user.pointer([
    { keys: "[MouseLeft>]", target: image, coords: { clientX: 150, clientY: 75 } },
    { target: image, coords: { clientX: 250, clientY: 125 } },
    { keys: "[/MouseLeft]", target: image, coords: { clientX: 250, clientY: 125 } },
  ]);
}

it("saves the selected part of an image as a new file", async () => {
  const saved = vi.fn();
  show(saved);
  const user = userEvent.setup();

  await user.click(await screen.findByRole("button", { name: "Cắt ảnh" }));
  expect(screen.getByRole("button", { name: "Lưu thành tệp mới" })).toBeDisabled();

  await selectHalfTheImage(user);

  expect(screen.getByText("640 × 360 px")).toBeInTheDocument();
  await user.click(screen.getByRole("button", { name: "Lưu thành tệp mới" }));

  expect(renderCrop).toHaveBeenCalledWith(
    expect.objectContaining({ width: 1280, height: 720 }),
    { x: 0.25, y: 0.25, width: 0.5, height: 0.5 },
    "image/png",
  );
  const [file] = saved.mock.calls[0] as [File];
  expect(file.name).toBe("So do kien truc (đã cắt).png");
  expect(file.type).toBe("image/png");
  // Saving leaves cropping, so the preview shows the image again.
  expect(await screen.findByRole("button", { name: "Cắt ảnh" })).toBeInTheDocument();
});

it("offers only a download where nothing can be saved, and reports a failed crop", async () => {
  show();
  const user = userEvent.setup();
  renderCrop.mockRejectedValue(new Error("no canvas"));

  await user.click(await screen.findByRole("button", { name: "Cắt ảnh" }));
  expect(screen.queryByRole("button", { name: "Lưu thành tệp mới" })).not.toBeInTheDocument();

  await selectHalfTheImage(user);
  await user.click(screen.getByRole("button", { name: "Tải ảnh đã cắt" }));

  expect(await screen.findByRole("alert")).toHaveTextContent("Không cắt được ảnh.");
});
