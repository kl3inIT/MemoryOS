import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { AssistantRuntimeProvider, ThreadPrimitive, useLocalRuntime } from "@assistant-ui/react";
import { describe, expect, it } from "vitest";
import { ChatResearchView } from "./chat-research-view";
import type { ResearchAgent, ResearchState } from "./chat-research";

const agent = (
  toolCallId: string,
  cycle: number,
  tabIndex: number,
  overrides: Partial<ResearchAgent> = {},
): ResearchAgent => ({
  toolCallId,
  cycle,
  tabIndex,
  task: `Task ${toolCallId}`,
  status: "COMPLETED",
  durationMs: 90_000,
  report: "Report [1].",
  citations: [],
  activity: { steps: [], reasoning: [] },
  ...overrides,
});

const research: ResearchState = {
  plan: "1. Scope\n2. Sources",
  agents: [
    agent("a1", 0, 0),
    agent("a2", 0, 1, { durationMs: 30_000 }),
    agent("a3", 1, 0, { durationMs: 5_000 }),
  ],
};

function Thread({ state }: { state: ResearchState }) {
  const runtime = useLocalRuntime(
    { run: async () => ({ content: [] }) },
    {
      initialMessages: [
        {
          id: "answer",
          role: "assistant",
          content: [{ type: "text", text: "Answer [1]." }],
          metadata: {
            custom: { createdAt: new Date().toISOString(), sources: [{ citationId: 1 }] },
          },
        },
      ],
    },
  );
  return (
    <AssistantRuntimeProvider runtime={runtime}>
      <ThreadPrimitive.Root>
        <ThreadPrimitive.Messages
          components={{
            AssistantMessage: () => <ChatResearchView research={state} />,
            UserMessage: () => null,
          }}
        />
      </ThreadPrimitive.Root>
    </AssistantRuntimeProvider>
  );
}

describe("deep research progress on the timeline", () => {
  it("summarizes steps and sources, names each cycle and reports how long an agent ran", async () => {
    render(<Thread state={research} />);
    // Four steps: the plan and three agents, beside the sources the answer cites.
    expect(screen.getByText("4 steps · 1 source")).toBeVisible();
    await userEvent.click(screen.getByRole("button", { name: /Researched/ }));
    expect(screen.getByText("Cycle 1")).toBeVisible();
    expect(screen.getByText("Cycle 2")).toBeVisible();
    const tabs = screen.getAllByRole("tab");
    expect(tabs.map((tab) => tab.textContent)).toEqual(["Agent 1", "Agent 2"]);
    const panel = screen.getByRole("tabpanel");
    expect(within(panel).getByText("Task a1")).toBeVisible();
    expect(within(panel).getByText("Ran for 1 minute 30 seconds")).toBeVisible();
  });

  it("renders a single agent without tabs and names no cycle when only one ran", async () => {
    render(<Thread state={{ plan: "", agents: [agent("only", 0, 0, { durationMs: 1_000 })] }} />);
    await userEvent.click(screen.getByRole("button", { name: /Researched/ }));
    expect(screen.queryAllByRole("tab")).toHaveLength(0);
    expect(screen.queryByText(/Cycle/)).toBeNull();
    expect(screen.getByText("Task only")).toBeVisible();
    expect(screen.getByText("Ran for 1 second")).toBeVisible();
  });
});
