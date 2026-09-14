import { act, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { i18n } from "@/i18n";
import MermaidDiagram from "./mermaid-diagram";
import ShikiHighlighter from "./shiki-highlighter";

describe("read-only code renderers", () => {
  it("highlights complete code and retains whitespace and inert markup", async () => {
    const code = '  const label = "<script>alert(1)</script>";\n';
    const { container } = render(<ShikiHighlighter code={code} language="typescript" />);
    await waitFor(() => expect(container.querySelector(".shiki span[style]")).not.toBeNull());
    expect(container.querySelector("code")?.textContent).toBe(code);
    expect(container.querySelector("script")).toBeNull();
  });

  it("renders a supported diagram as an inert image and reuses accessible dialog controls", async () => {
    const user = userEvent.setup();
    const { container } = render(<MermaidDiagram code={"graph TD\n A[Upload] --> B[Read]"} />);
    expect(screen.getByRole("img", { name: "Diagram" }).getAttribute("src")).toMatch(
      /^data:image\/svg\+xml/,
    );
    expect(container.querySelector("svg script, foreignObject, iframe")).toBeNull();
    const expand = screen.getByRole("button", { name: "Expand diagram" });
    await user.click(expand);
    expect(screen.getByRole("dialog")).toBeVisible();
    await user.click(screen.getByRole("button", { name: "Zoom in" }));
    await act(() => i18n.changeLanguage("vi"));
    expect(screen.getByRole("dialog", { name: "Sơ đồ" })).toBeVisible();
    await user.keyboard("{Escape}");
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    expect(expand).toHaveFocus();
  });

  it.each(["not a diagram", "graph TD\n" + "A-->B\n".repeat(251)])(
    "retains unsupported or oversized source without executing it",
    (code) => {
      const { container } = render(<MermaidDiagram code={code} />);
      expect(container.querySelector("[data-slot=mermaid-fallback] code")?.textContent).toBe(code);
      expect(screen.queryByRole("img")).not.toBeInTheDocument();
      expect(screen.getByRole("status")).toBeVisible();
    },
  );
});

// Native runtime boundary is separately exercised by the Chat browser suite.
describe("stream admission", () => {
  it("keeps syntax and Mermaid source plain while the message part is running", async () => {
    vi.doMock("@assistant-ui/react", () => ({ useAuiState: () => true }));
    const { SyntaxHighlighter, MermaidDiagram: RuntimeMermaid } =
      await import("./code-renderers.aui");
    const { container } = render(
      <>
        <SyntaxHighlighter
          code="const x = 1"
          language="js"
          components={{ Pre: (props) => <pre {...props} />, Code: (props) => <code {...props} /> }}
        />
        <RuntimeMermaid
          code="graph TD; A-->B"
          language="mermaid"
          components={{ Pre: (props) => <pre {...props} />, Code: (props) => <code {...props} /> }}
        />
      </>,
    );
    expect(container.querySelectorAll("pre code")).toHaveLength(2);
    expect(container.querySelector("img, .aui-shiki-base")).toBeNull();
    vi.doUnmock("@assistant-ui/react");
  });
});
