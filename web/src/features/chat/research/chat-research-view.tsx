import { useLayoutEffect, useRef, useState } from "react";
import { TextMessagePartProvider, useAuiState } from "@assistant-ui/react";
import { Brain, ListChecks, Search } from "lucide-react";
import {
  ActivityChips,
  ActivityGroupContent,
  ActivityGroupRoot,
  ActivityGroupTrigger,
  ActivityStep,
} from "@/components/assistant-ui/elements/activity-group";
import { MarkdownText } from "@/components/assistant-ui/elements/markdown-text";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { cn } from "@/lib/utils";
import { Spinner } from "@/components/ui/spinner";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { ChatResearchToolStep } from "@/features/chat/activity/chat-activity-view";
import { spokenDuration, useElapsed } from "@/features/chat/activity/chat-duration";
import { mergedReport, type ResearchAgent, type ResearchState } from "./chat-research";

type StepState = "running" | "done" | "failed";

function stateOf(status: ResearchAgent["status"], messageRunning: boolean): StepState {
  if (status === "COMPLETED") return "done";
  return status === "RUNNING" && messageRunning ? "running" : "failed";
}

/**
 * Deep research on the activity timeline, after the Onyx plan and research agent renderers: the plan, then each
 * orchestrator cycle with one tab per parallel agent showing its task, own steps and intermediate report.
 */
export function ChatResearchView({ research }: { research: ResearchState }) {
  const ui = useAppTranslation();
  const running = useAuiState((state) => state.message.status?.type === "running");
  const answerStarted = useAuiState((state) =>
    state.message.parts.some((part) => part.type === "text" && part.text.trim().length > 0),
  );
  const [manual, setManual] = useState<boolean>();
  const open = manual ?? (running && !answerStarted);
  const active = running && !answerStarted;
  const label = active
    ? research.agents.length
      ? ui("Đang nghiên cứu…")
      : ui("Đang lập kế hoạch nghiên cứu…")
    : running || research.agents.every((agent) => agent.status === "COMPLETED")
      ? ui("Đã nghiên cứu")
      : ui("Đã dừng nghiên cứu");
  const cycles = new Map<number, ResearchAgent[]>();
  for (const agent of research.agents)
    cycles.set(agent.cycle, [...(cycles.get(agent.cycle) ?? []), agent]);
  const count = research.agents.length + (research.plan ? 1 : 0);
  // As ChatGPT, Mistral and Copilot do, a long run says how much it has found and how long it has been working.
  const sources = useAuiState(
    (state) => (state.message.metadata.custom.sources as unknown[] | undefined)?.length ?? 0,
  );
  const elapsed = useElapsed(
    useAuiState((state) => state.message.metadata.custom.createdAt),
    active,
  );
  const found =
    sources === 0 ? "" : sources === 1 ? ui("1 nguồn") : ui("{{n}} nguồn", { n: sources });
  const steps = count === 1 ? ui("1 bước") : ui("{{n}} bước", { n: count });
  const summary = (
    active ? [found, elapsed === null ? "" : spokenDuration(elapsed)] : [steps, found]
  )
    .filter(Boolean)
    .join(" · ");
  return (
    <ActivityGroupRoot open={open} onOpenChange={setManual}>
      <ActivityGroupTrigger label={label} active={active} steps={summary || undefined} />
      <ActivityGroupContent>
        {research.plan && (
          <ActivityStep
            icon={<ListChecks />}
            status={active && !research.agents.length ? "running" : "done"}
            title={ui("Kế hoạch nghiên cứu")}
          >
            <ResearchMarkdown
              text={research.plan}
              running={active && !research.agents.length}
              collapsedHeight={132}
            />
          </ActivityStep>
        )}
        {[...cycles.entries()].map(([cycle, agents]) => (
          <li key={cycle} className="min-w-0 text-sm">
            {cycles.size > 1 && (
              <p className="mb-1 text-content-muted">{ui("Chu kỳ {{n}}", { n: cycle + 1 })}</p>
            )}
            {agents.length > 1 ? (
              <Tabs defaultValue={agents[0]?.toolCallId}>
                {/* Scrolling sideways must not add a vertical scrollbar to the fixed-height tab row. */}
                <TabsList className="max-w-full overflow-x-auto overflow-y-hidden">
                  {agents.map((agent) => (
                    <TabsTrigger key={agent.toolCallId} value={agent.toolCallId}>
                      <span className="flex items-center gap-1.5">
                        {agent.status === "RUNNING" && running && (
                          <Spinner className="size-3" aria-hidden />
                        )}
                        {ui("Tác tử {{n}}", { n: agent.tabIndex + 1 })}
                      </span>
                    </TabsTrigger>
                  ))}
                </TabsList>
                {agents.map((agent) => (
                  <TabsContent key={agent.toolCallId} value={agent.toolCallId}>
                    <AgentPanel agent={agent} running={running} />
                  </TabsContent>
                ))}
              </Tabs>
            ) : (
              agents[0] && <AgentPanel agent={agents[0]} running={running} />
            )}
          </li>
        ))}
      </ActivityGroupContent>
    </ActivityGroupRoot>
  );
}

function AgentPanel({ agent, running }: { agent: ResearchAgent; running: boolean }) {
  const ui = useAppTranslation();
  const state = stateOf(agent.status, running);
  const report = mergedReport(agent);
  const thoughts = agent.activity.reasoning.map((segment) => segment.text).join("\n\n");
  return (
    <div className="mt-2 flex min-w-0 flex-col gap-2">
      {agent.task && <p className="text-content-primary wrap-anywhere">{agent.task}</p>}
      {agent.durationMs !== null && (
        <p className="text-xs text-content-muted">
          {ui("Đã chạy {{duration}}", { duration: spokenDuration(agent.durationMs) })}
        </p>
      )}
      {(agent.activity.steps.length > 0 || thoughts) && (
        <ol className="flex flex-col gap-2 border-l border-border-subtle pl-3">
          {agent.activity.steps.map((step) => {
            const stepState = stateOf(step.status, running && state === "running");
            return (
              <ChatResearchToolStep
                key={step.toolCallId}
                toolName={step.toolName}
                status={stepState}
              >
                {step.queries.length > 0 && (
                  <ActivityChips
                    items={step.queries.map((query) => ({
                      key: query,
                      icon: <Search />,
                      label: query,
                    }))}
                    moreLabel={(n) => ui("+{{n}}", { n })}
                  />
                )}
              </ChatResearchToolStep>
            );
          })}
          {thoughts && (
            <ActivityStep icon={<Brain />} status="done" title={ui("Suy nghĩ")}>
              <ResearchMarkdown text={thoughts} running={false} />
            </ActivityStep>
          )}
        </ol>
      )}
      {report ? (
        <details className="group/report min-w-0" open={state === "running"}>
          <summary className="cursor-pointer text-content-muted hover:text-content-primary">
            {ui("Báo cáo trung gian")}
          </summary>
          <div className="mt-1.5">
            <ResearchMarkdown text={report} running={state === "running"} collapsedHeight={112} />
          </div>
        </details>
      ) : (
        state === "failed" && (
          <p className="text-content-secondary">{ui("Tác tử nghiên cứu không hoàn thành.")}</p>
        )
      )}
    </div>
  );
}

/**
 * Research text is model Markdown, rendered like reasoning with the answer renderer. A long plan or report is clamped
 * with a reveal, as Onyx does, so the answer stays on screen while the reader inspects the research.
 */
function ResearchMarkdown({
  text,
  running,
  collapsedHeight,
}: {
  text: string;
  running: boolean;
  collapsedHeight?: number;
}) {
  const ui = useAppTranslation();
  const [expanded, setExpanded] = useState(false);
  const [overflows, setOverflows] = useState(false);
  const body = useRef<HTMLDivElement>(null);
  useLayoutEffect(() => {
    if (collapsedHeight === undefined || !body.current) return;
    setOverflows(body.current.scrollHeight > collapsedHeight + 8);
  }, [text, collapsedHeight]);
  const clamped = collapsedHeight !== undefined && overflows && !expanded;
  return (
    <div className="min-w-0">
      <div
        ref={body}
        style={clamped ? { maxHeight: collapsedHeight } : undefined}
        className={cn(
          "min-w-0 text-sm wrap-anywhere [&_.aui-md]:text-sm [&_.aui-md]:leading-6 [&_.aui-md]:text-content-secondary",
          clamped && "overflow-hidden mask-b-from-55%",
        )}
      >
        <TextMessagePartProvider text={text} isRunning={running}>
          <MarkdownText />
        </TextMessagePartProvider>
      </div>
      {collapsedHeight !== undefined && overflows && (
        <button
          type="button"
          onClick={() => setExpanded(!expanded)}
          className="mt-1 text-xs text-content-muted underline-offset-2 hover:text-content-primary hover:underline"
        >
          {expanded ? ui("Thu gọn") : ui("Xem thêm")}
        </button>
      )}
    </div>
  );
}
