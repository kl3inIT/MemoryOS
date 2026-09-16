import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { ChatMarkdownLink } from "./chat-sources";

const file = "/api/chat/file-artifacts/00000000-0000-0000-0000-000000000000/content";

describe("links in an answer body", () => {
  it("keeps a generated-file link and neutralizes every other model-written path", () => {
    render(
      <>
        <ChatMarkdownLink href={file}>báo cáo.xlsx</ChatMarkdownLink>
        <ChatMarkdownLink href="/admin/users">nội bộ</ChatMarkdownLink>
        <ChatMarkdownLink href="javascript:alert(1)">bấm đi</ChatMarkdownLink>
        <ChatMarkdownLink href="/api/chat/file-artifacts/../../etc/content">giả</ChatMarkdownLink>
      </>,
    );

    expect(screen.getByRole("link", { name: "báo cáo.xlsx" })).toHaveAttribute("href", file);
    for (const label of ["nội bộ", "bấm đi", "giả"]) {
      expect(screen.queryByRole("link", { name: label })).toBeNull();
      expect(screen.getByText(label)).toBeVisible();
    }
  });
});
