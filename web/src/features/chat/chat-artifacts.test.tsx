import { act, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import {
  AssistantRuntimeProvider,
  ComposerPrimitive,
  ThreadPrimitive,
  useLocalRuntime,
} from "@assistant-ui/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { artifactSpec, artifactsSchema } from "./chat-artifacts";
import { ChatArtifactCards, ChatArtifactView } from "./chat-artifact-view";
import { ChatSourcesWorkspace } from "./chat-sources";
import { i18n } from "@/i18n";

const artifact = {
  id: "3fdc54bf-778f-4a21-bd96-e08f53a12c3a",
  title: "Revenue report with a deliberately long file-like title.md",
  spec: JSON.stringify({
    root: {
      component: "Card",
      props: { title: "Revenue" },
      children: [
        { component: "Metric", props: { label: "September", value: "125,000" } },
        { component: "Text", props: { text: "<script>alert('untrusted')</script>" } },
        {
          component: "Table",
          children: [
            { component: "Row", children: [{ component: "Cell", props: { text: "ORION" } }] },
          ],
        },
      ],
    },
  }),
};
afterEach(() => vi.unstubAllGlobals());

describe("bounded read-only model presentations", () => {
  it("validates native specs, UTF-8 size, depth, node count and disallowed actions", () => {
    expect(artifactSpec(artifact.spec)).toBeDefined();
    for (const root of [
      { component: "iframe", props: { src: "https://example.com" } },
      { component: "Text", props: { text: "ok", onClick: "fetch('/api')" } },
      { component: "Text", props: { dangerouslySetInnerHTML: "<script/>" } },
      { component: "Card", props: { href: "javascript:alert(1)" } },
      { component: "Text", props: { text: "x".repeat(2049) } },
      { component: "Table", children: [{ component: "Text" }] },
      { component: "Card", children: Array.from({ length: 25 }, () => ({ component: "Text" })) },
    ])
      expect(artifactSpec(JSON.stringify({ root }))).toBeUndefined();
    let root: object = { component: "Text" };
    for (let n = 0; n < 10; n++) root = { component: "Card", children: [root] };
    expect(artifactSpec(JSON.stringify({ root }))).toBeUndefined();
    expect(
      artifactSpec(
        JSON.stringify({
          root: {
            component: "Card",
            children: Array.from({ length: 24 }, () => ({
              component: "Text",
              props: { text: "漢".repeat(300) },
            })),
          },
        }),
      ),
    ).toBeUndefined();
    expect(artifactsSchema.safeParse(Array(4).fill(artifact)).success).toBe(false);
  });

  it("renders native Generative UI with escaped data and a translated unsupported fallback", async () => {
    await i18n.changeLanguage("en");
    const view = render(<ChatArtifactView artifact={artifact} />);
    expect(screen.getByText("125,000")).toBeVisible();
    expect(screen.getByRole("cell", { name: "ORION" })).toBeVisible();
    expect(screen.getByText("<script>alert('untrusted')</script>")).toBeVisible();
    expect(view.container.querySelector("script,iframe,[onclick]")).toBeNull();
    view.rerender(
      <ChatArtifactView artifact={{ ...artifact, spec: '{"root":{"component":"HTML"}}' }} />,
    );
    expect(screen.getByText("Presentation unavailable")).toBeVisible();
    await act(() => i18n.changeLanguage("vi"));
    expect(screen.getByText("Bản trình bày không khả dụng")).toBeVisible();
  });

  for (const mobile of [false, true])
    it(`opens persisted artifacts in the ${mobile ? "mobile dialog" : "desktop sidebar"}, preserving draft and focus`, async () => {
      await i18n.changeLanguage("en");
      vi.stubGlobal(
        "matchMedia",
        vi.fn(() => ({
          matches: !mobile,
          addEventListener: vi.fn(),
          removeEventListener: vi.fn(),
        })),
      );
      function Workspace() {
        const runtime = useLocalRuntime(
          { run: async () => ({ content: [] }) },
          {
            initialMessages: [
              {
                id: "answer",
                role: "assistant",
                content: [{ type: "text", text: "Saved answer" }],
                metadata: { custom: { artifacts: [artifact] } },
              },
            ],
          },
        );
        return (
          <AssistantRuntimeProvider runtime={runtime}>
            <ChatSourcesWorkspace>
              <div>
                <ThreadPrimitive.Root>
                  <ThreadPrimitive.Messages
                    components={{ AssistantMessage: ChatArtifactCards, UserMessage: () => null }}
                  />
                  <ComposerPrimitive.Root>
                    <ComposerPrimitive.Input aria-label="Draft" />
                  </ComposerPrimitive.Root>
                </ThreadPrimitive.Root>
              </div>
            </ChatSourcesWorkspace>
          </AssistantRuntimeProvider>
        );
      }
      render(<Workspace />);
      const draft = screen.getByRole("textbox", { name: "Draft" });
      await userEvent.type(draft, "Keep my draft");
      const open = screen.getByRole("button", { name: `Open presentation: ${artifact.title}` });
      await userEvent.click(open);
      const panel = screen.getByRole(mobile ? "dialog" : "complementary");
      expect(within(panel).getByText("125,000")).toBeVisible();
      expect(open).toHaveAttribute("aria-expanded", "true");
      await userEvent.keyboard("{Escape}");
      await waitFor(() =>
        expect(screen.queryByRole(mobile ? "dialog" : "complementary")).toBeNull(),
      );
      expect(draft).toHaveValue("Keep my draft");
      expect(open).toHaveFocus();
    });
});
