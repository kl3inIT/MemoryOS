import { render, screen, waitFor } from "@testing-library/react";
import { describe, expect, it } from "vitest";

import { MarkdownView } from "./markdown-view";

const CHARTER = [
  "# Điều lệ công ty",
  "",
  "## Chương III",
  "",
  "Mỗi cổ đông có tổng số phiếu biểu quyết tương ứng với số cổ phần.",
  "",
  "| Điều | Nội dung |",
  "| --- | --- |",
  "| 5.1 | Bỏ phiếu kín |",
].join("\n");

describe("MarkdownView", () => {
  it("reads a Markdown original as a document, not as source code", async () => {
    render(<MarkdownView text={CHARTER} />);

    await waitFor(() =>
      expect(
        screen.getByRole("heading", { level: 1, name: "Điều lệ công ty" }),
      ).toBeInTheDocument(),
    );
    expect(screen.getByRole("heading", { level: 2, name: "Chương III" })).toBeInTheDocument();
    expect(screen.getByRole("table")).toBeInTheDocument();
    expect(screen.getByRole("cell", { name: "Bỏ phiếu kín" })).toBeInTheDocument();
    // The marks that make a heading a heading belong to the rendering, not to the text a reader sees.
    expect(screen.queryByText(/^#/)).toBeNull();
  });

  it("says so rather than showing nothing when the file was cut short", async () => {
    render(<MarkdownView text="# Điều lệ" truncated />);

    await waitFor(() =>
      expect(screen.getByRole("heading", { level: 1, name: "Điều lệ" })).toBeInTheDocument(),
    );
    expect(screen.getByText("Showing only the first 1 MB of the file.")).toBeInTheDocument();
  });
});
