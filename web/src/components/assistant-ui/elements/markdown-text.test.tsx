import { render, screen, waitFor } from "@testing-library/react";
import {
  AssistantRuntimeProvider,
  MessagePrimitive,
  ThreadPrimitive,
  useLocalRuntime,
} from "@assistant-ui/react";
import { describe, expect, it } from "vitest";
import { MarkdownText } from "./markdown-text";

const link = "/api/chat/file-artifacts/00000000-0000-0000-0000-000000000000/content";

function renderAnswer(text: string) {
  function Thread() {
    const runtime = useLocalRuntime(
      { run: async () => ({ content: [] }) },
      { initialMessages: [{ id: "answer", role: "assistant", content: [{ type: "text", text }] }] },
    );
    return (
      <AssistantRuntimeProvider runtime={runtime}>
        <ThreadPrimitive.Messages
          components={{
            AssistantMessage: () => <MessagePrimitive.Parts components={{ Text: MarkdownText }} />,
            UserMessage: () => null,
          }}
        />
      </AssistantRuntimeProvider>
    );
  }
  return render(<Thread />);
}

describe("assistant math", () => {
  it("renders the LaTeX brackets models write, as Onyx does, and leaves currency as text", async () => {
    renderAnswer(
      "Biên lợi nhuận:\n\n" +
        String.raw`\[ \text{Biên} = \frac{\text{Doanh thu} - \text{Chi phí}}{\text{Doanh thu}} \]` +
        "\n\n" +
        String.raw`Với \(x^2\), giá $5 và $7.`,
    );
    await waitFor(() =>
      expect(document.querySelectorAll(".katex").length).toBeGreaterThanOrEqual(2),
    );
    expect(document.body.textContent).toContain("$5 và $7");
    // KaTeX keeps the TeX source in its MathML annotation; no raw TeX remains outside rendered math.
    const prose = document.body.cloneNode(true) as HTMLElement;
    prose.querySelectorAll(".katex").forEach((math) => math.remove());
    expect(prose.textContent).not.toContain(String.raw`\frac`);
  });
});

describe("assistant markdown", () => {
  it("keeps same-origin generated-file links and images and drops script URLs", async () => {
    function Thread() {
      const runtime = useLocalRuntime(
        { run: async () => ({ content: [] }) },
        {
          initialMessages: [
            {
              id: "answer",
              role: "assistant",
              content: [
                {
                  type: "text",
                  text: `[báo cáo.xlsx](${link})\n\n![Biểu đồ](${link})\n\n[bad](javascript:alert(1))

<img src="x" onerror="alert(1)"><script>alert(2)</script><iframe src="https://evil.example"></iframe>`,
                },
              ],
            },
          ],
        },
      );
      return (
        <AssistantRuntimeProvider runtime={runtime}>
          <ThreadPrimitive.Messages
            components={{
              AssistantMessage: () => (
                <MessagePrimitive.Parts components={{ Text: MarkdownText }} />
              ),
              UserMessage: () => null,
            }}
          />
        </AssistantRuntimeProvider>
      );
    }
    render(<Thread />);
    await waitFor(() =>
      expect(screen.getByRole("link", { name: "báo cáo.xlsx" })).toHaveAttribute("href", link),
    );
    expect(screen.getByRole("img", { name: "Biểu đồ" })).toHaveAttribute("src", link);
    // Streamdown's hardening renders a blocked script URL as text without a link target.
    expect(screen.getByText(/bad/).closest("a")?.getAttribute("href") ?? "").not.toMatch(
      /javascript/,
    );
    expect(document.body.innerHTML).not.toMatch(/javascript:/);
    // Model text never renders raw HTML: Streamdown's rehype-raw default is not used.
    expect(document.querySelector("[onerror], script, iframe")).toBeNull();
  });
});
