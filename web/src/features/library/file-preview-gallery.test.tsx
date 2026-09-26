import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { HttpResponse } from "msw";
import { i18n } from "@/i18n/index";
import { handleGetChatImageArtifact } from "@/lib/hey-api/msw.gen";
import { server } from "@/test/msw";
import { ChatFilePreviewModal } from "./file-preview-modal";
import type { PreviewTarget } from "./file-preview";

/** Every generated image the preview read, by id. */
let readImages: string[] = [];
const png = () =>
  server.use(
    handleGetChatImageArtifact(({ params }) => {
      readImages.push(params.artifactId);
      return new HttpResponse(new Blob(["png"], { type: "image/png" }), {
        headers: { "Content-Type": "image/png" },
      });
    }),
  );

const first: PreviewTarget = {
  source: "image",
  id: "11111111-1111-4111-8111-111111111111",
  filename: "anh-bia.png",
  mediaType: "image/png",
};
const second: PreviewTarget = {
  ...first,
  id: "22222222-2222-4222-8222-222222222222",
  filename: "anh-sau.png",
};

function show(siblings: PreviewTarget[] = [first, second]) {
  render(
    <QueryClientProvider
      client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}
    >
      <ChatFilePreviewModal target={first} siblings={siblings} onClose={vi.fn()} />
    </QueryClientProvider>,
  );
}

beforeEach(async () => {
  await i18n.changeLanguage("vi");
  vi.clearAllMocks();
  readImages = [];
  png();
  // jsdom has no object URLs; the preview only needs a stable string.
  globalThis.URL.createObjectURL = vi.fn(() => "blob:preview");
  globalThis.URL.revokeObjectURL = vi.fn();
});
afterEach(cleanup);

it("steps through the files beside the one opened, by button and by arrow key", async () => {
  show();
  const user = userEvent.setup();

  expect(await screen.findByText("1/2")).toBeInTheDocument();
  expect(screen.getByRole("button", { name: "Tệp trước" })).toBeDisabled();

  await user.click(screen.getByRole("button", { name: "Tệp sau" }));

  expect(await screen.findByText("2/2")).toBeInTheDocument();
  expect(screen.getByTitle("anh-sau.png")).toBeInTheDocument();
  await waitFor(() => expect(readImages.at(-1)).toBe(second.id));
  expect(screen.getByRole("button", { name: "Tệp sau" })).toBeDisabled();

  await user.keyboard("{ArrowLeft}");
  expect(await screen.findByText("1/2")).toBeInTheDocument();
});

it("turns the image either way and offers no navigation for a single file", async () => {
  show([first]);
  const user = userEvent.setup();

  expect(screen.queryByRole("button", { name: "Tệp sau" })).not.toBeInTheDocument();
  const image = await screen.findByRole("img", { name: "anh-bia.png" });
  expect(image.style.transform).toContain("rotate(0deg)");

  await user.click(screen.getByRole("button", { name: "Xoay phải" }));
  expect(image.style.transform).toContain("rotate(90deg)");

  // A fourth turn to the right keeps turning right rather than winding back to zero.
  for (let turn = 0; turn < 3; turn++)
    await user.click(screen.getByRole("button", { name: "Xoay phải" }));
  expect(image.style.transform).toContain("rotate(360deg)");

  await user.click(screen.getByRole("button", { name: "Xoay trái" }));
  expect(image.style.transform).toContain("rotate(270deg)");
});

it("scales the image with the wheel, within the viewer's own bounds", async () => {
  show([first]);
  const image = await screen.findByRole("img", { name: "anh-bia.png" });
  const frame = image.parentElement!;

  fireEvent.wheel(frame, { deltaY: -120 });
  expect(image.style.transform).toContain("scale(1.1)");

  // The wheel never scales past the bounds the buttons obey.
  for (let turn = 0; turn < 40; turn++) fireEvent.wheel(frame, { deltaY: 120 });
  expect(screen.getByText("25%")).toBeInTheDocument();
});
