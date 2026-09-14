import { render, screen } from "@testing-library/react";
import { expect, it } from "vitest";
import { ImageGeneration } from "./image-generation";

it("shows the shimmer status and the dot grid while generating, without an image", () => {
  const { container } = render(<ImageGeneration generating label="Đang tạo ảnh" prompt="a red fox" />);
  expect(screen.getByRole("status")).toHaveTextContent("Đang tạo ảnh");
  expect(container.querySelector('[data-slot="image-generation-grid"]')).not.toBeNull();
  expect(container.querySelector("img")).toBeNull();
  expect(screen.queryByRole("link")).toBeNull();
});

it("shows the resolved image and a download link once generation finishes", () => {
  render(
    <ImageGeneration
      generating={false}
      src="/api/chat/image-artifacts/abc/content"
      prompt="a red fox"
      label="a red fox"
      downloadLabel="Tải ảnh"
    />,
  );
  const image = screen.getByRole("img");
  expect(image).toHaveAttribute("src", "/api/chat/image-artifacts/abc/content");
  expect(image).toHaveAttribute("alt", "a red fox");
  const download = screen.getByRole("link", { name: "Tải ảnh" });
  expect(download).toHaveAttribute("href", "/api/chat/image-artifacts/abc/content");
  expect(download).toHaveAttribute("download");
});

it("shows the failure label and no image when generation failed", () => {
  const { container } = render(
    <ImageGeneration generating={false} failed label="Không tạo được ảnh" src="/api/chat/image-artifacts/abc/content" />,
  );
  expect(screen.getByRole("status")).toHaveTextContent("Không tạo được ảnh");
  expect(container.querySelector("img")).toBeNull();
});
