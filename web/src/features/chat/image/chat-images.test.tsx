import { cleanup, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { i18n } from "@/i18n/index";
import type { GeneratedImage } from "./chat-image";
import { ChatImages } from "./chat-images";

const state = vi.hoisted(() => ({
  current: { message: { metadata: { custom: {} as Record<string, unknown> } } },
}));

vi.mock("@assistant-ui/react", () => ({
  useAui: () => ({ thread: { composer: () => ({ setText: () => {}, addAttachment: () => {} }) } }),
  useAuiState: (select: (value: unknown) => unknown) => select(state.current),
}));

function show(images: GeneratedImage[], generating = false) {
  state.current = { message: { metadata: { custom: { images, imageGenerating: generating } } } };
  render(<ChatImages />);
}

const image = (id: string, deleted: boolean): GeneratedImage => ({
  id,
  mediaType: "image/png",
  revisedPrompt: "Một con mèo",
  deleted,
});

beforeEach(async () => {
  await i18n.changeLanguage("vi");
});
afterEach(cleanup);

/**
 * The answer outlives the bytes: deleting an image from the library, or letting the byte sweep release it,
 * leaves a card that says so rather than a broken picture or a silent gap.
 */
it("says an image was deleted instead of trying to show it", () => {
  show([image("11111111-1111-4111-8111-111111111111", true)]);

  expect(screen.getByText("Ảnh đã bị xoá")).toBeInTheDocument();
  expect(screen.queryByRole("img")).not.toBeInTheDocument();
});

it("shows the images that are still there beside the ones that are gone", () => {
  show([
    image("11111111-1111-4111-8111-111111111111", false),
    image("22222222-2222-4222-8222-222222222222", true),
  ]);

  expect(screen.getByRole("img")).toHaveAttribute(
    "src",
    expect.stringContaining("11111111-1111-4111-8111-111111111111"),
  );
  expect(screen.getByText("Ảnh đã bị xoá")).toBeInTheDocument();
});

it("renders nothing at all for an answer with no images", () => {
  show([]);

  expect(screen.queryByText("Ảnh đã bị xoá")).not.toBeInTheDocument();
  expect(screen.queryByRole("img")).not.toBeInTheDocument();
});
