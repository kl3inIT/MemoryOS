import { fireEvent, render, screen } from "@testing-library/react";
import { ChatPanelContext } from "./chat-panel-context";
import { describe, expect, it } from "vitest";
import { ChatMarkdownLink } from "./chat-sources";

const file = "/api/chat/file-artifacts/00000000-0000-0000-0000-000000000000/content";

describe("links in an answer body", () => {
  it("opens a generated-file link in the preview and neutralizes every other model-written path", () => {
    const previewed: unknown[] = [];
    render(
      <ChatPanelContext.Provider
        value={{
          open: () => {},
          previewFile: (target) => previewed.push(target),
          openArtifact: () => {},
          close: () => {},
        }}
      >
        <ChatMarkdownLink href={file}>báo cáo.xlsx</ChatMarkdownLink>
        <ChatMarkdownLink href="/admin/users">nội bộ</ChatMarkdownLink>
        <ChatMarkdownLink href="javascript:alert(1)">bấm đi</ChatMarkdownLink>
        <ChatMarkdownLink href="/api/chat/file-artifacts/../../etc/content">giả</ChatMarkdownLink>
      </ChatPanelContext.Provider>,
    );

    // Onyx: a link to a chat file opens the preview modal instead of downloading.
    fireEvent.click(screen.getByRole("button", { name: "báo cáo.xlsx" }));
    expect(previewed).toEqual([
      { source: "generated", id: "00000000-0000-0000-0000-000000000000", filename: "báo cáo.xlsx" },
    ]);
    for (const label of ["nội bộ", "bấm đi", "giả"]) {
      expect(screen.queryByRole("link", { name: label })).toBeNull();
      expect(screen.getByText(label)).toBeVisible();
    }
  });
});
