import type { UIMessageChunk } from "ai";
import { z } from "zod";
import { activitySchema, type ChatActivity } from "./chat-activity";

const statusSchema = z.enum(["RUNNING", "COMPLETED", "FAILED"]);
const citationSchema = z.object({
  marker: z.number().int().min(1),
  citationId: z.number().int().min(1),
});
const agentSchema = z.object({
  toolCallId: z.string().min(1).max(256),
  cycle: z.number().int().min(0),
  tabIndex: z.number().int().min(0).max(2),
  task: z.string().max(4000).nullable(),
  status: statusSchema,
  durationMs: z.number().int().nonnegative().nullable(),
  report: z.string().nullable(),
  citations: z.array(citationSchema).default([]),
  activity: activitySchema,
});
export const researchSchema = z
  .object({
    clarification: z.boolean().default(false),
    plan: z.string().nullable().default(null),
    agents: z.array(agentSchema).max(64).default([]),
  })
  .default({ clarification: false, plan: null, agents: [] });

export const researchPlanEventSchema = z.object({ text: z.string().min(1) });
export const researchBranchingEventSchema = z.object({ branches: z.number().int().min(2).max(3) });
export const researchAgentEventSchema = z.object({
  toolCallId: z.string().min(1).max(256),
  tabIndex: z.number().int().min(0).max(2),
  task: z.string().min(1).max(4000),
});
export const researchReportEventSchema = z.object({
  toolCallId: z.string().min(1).max(256),
  text: z.string().min(1),
});
export const researchCitationsEventSchema = z.object({
  toolCallId: z.string().min(1).max(256),
  citations: z.array(citationSchema),
});

export type ResearchAgent = z.infer<typeof agentSchema>;
/** What the research part renders: the plan and every research agent with its own steps and report. */
export type ResearchState = { plan: string; agents: ResearchAgent[] };
export type ResearchStep = ChatActivity["steps"][number];

/** An intermediate report uses its agent's citation numbers; the answer's sources use the merged numbers. */
export function mergedReport(agent: Pick<ResearchAgent, "report" | "citations">) {
  if (!agent.report) return "";
  const mapping = new Map(
    agent.citations.map((citation) => [citation.marker, citation.citationId]),
  );
  if (!mapping.size) return agent.report;
  return agent.report.replace(
    /([[【［]{1,2})(\d+(?:, ?\d+)*)([\]】］]{1,2})/g,
    (_, open, numbers, close) =>
      `${open}${(numbers as string)
        .split(",")
        .map((value) => mapping.get(Number(value.trim())) ?? value.trim())
        .join(", ")}${close}`,
  );
}

/** The saved research of an answer, or undefined for an ordinary answer. */
export function historyResearch(research: unknown): ResearchState | undefined {
  const parsed = researchSchema.parse(research ?? undefined);
  if (!parsed.plan && !parsed.agents.length) return undefined;
  return { plan: parsed.plan ?? "", agents: parsed.agents };
}

type ToolLike = {
  toolCallId: string;
  toolName: string;
  stage: string;
  search: { queries: string[] } | null;
  documents: ResearchStep["documents"];
  durationMs: number | null;
  parentToolCallId?: string | null;
  tabIndex?: number | null;
};

/**
 * Deep research progress as one AI SDK data part that is replaced on every change, so the plan and agent tabs render
 * in place above the report. Agent steps never become top-level tool parts.
 */
export class ResearchChunks {
  private readonly id: string;
  private plan = "";
  private cycle = -1;
  private readonly agents = new Map<string, ResearchAgent & { startedAt: number }>();
  private started = false;

  constructor(runId: string) {
    this.id = `${runId}:research`;
  }

  get active() {
    return this.started;
  }

  planDelta(text: string): UIMessageChunk[] {
    this.plan += text;
    return this.emit();
  }

  /** A research agent call (with its tab) or one of its steps (with its parent). */
  tool(event: ToolLike): UIMessageChunk[] {
    if (event.parentToolCallId) {
      const agent = this.agents.get(event.parentToolCallId);
      if (!agent || event.stage === "SOURCE") return [];
      const steps = agent.activity.steps;
      let step = steps.find((candidate) => candidate.toolCallId === event.toolCallId);
      if (!step) {
        step = {
          position: steps.length,
          toolCallId: event.toolCallId,
          toolName: event.toolName,
          status: "RUNNING",
          durationMs: null,
          textOffset: 0,
          queries: [],
          filters: null,
          documents: [],
          citations: [],
        };
        steps.push(step);
      }
      if (event.search) step.queries = event.search.queries.slice(0, 8);
      if (event.documents.length) step.documents = event.documents;
      if (event.stage === "COMPLETED" || event.stage === "FAILED") {
        step.status = event.stage;
        step.durationMs = event.durationMs;
      }
      return this.emit();
    }
    if (event.tabIndex === null || event.tabIndex === undefined) return [];
    const existing = this.agents.get(event.toolCallId);
    if (!existing) {
      if (event.stage !== "STARTED") return [];
      if (event.tabIndex === 0 || this.cycle < 0) this.cycle++;
      this.agents.set(event.toolCallId, {
        toolCallId: event.toolCallId,
        cycle: this.cycle,
        tabIndex: event.tabIndex,
        task: null,
        status: "RUNNING",
        durationMs: null,
        report: null,
        citations: [],
        activity: { steps: [], reasoning: [] },
        startedAt: Date.now(),
      });
      return this.emit();
    }
    if (event.stage === "COMPLETED" || event.stage === "FAILED") {
      existing.status = event.stage;
      existing.durationMs = event.durationMs ?? Date.now() - existing.startedAt;
      return this.emit();
    }
    return [];
  }

  reasoning(parentToolCallId: string, text: string): UIMessageChunk[] {
    const agent = this.agents.get(parentToolCallId);
    if (!agent) return [];
    const last = agent.activity.reasoning.at(-1);
    if (last && last.position === agent.activity.steps.length) last.text += text;
    else
      agent.activity.reasoning.push({ position: agent.activity.steps.length, textOffset: 0, text });
    return this.emit();
  }

  agent(toolCallId: string, task: string): UIMessageChunk[] {
    const agent = this.agents.get(toolCallId);
    if (!agent) return [];
    agent.task = task;
    return this.emit();
  }

  report(toolCallId: string, text: string): UIMessageChunk[] {
    const agent = this.agents.get(toolCallId);
    if (!agent) return [];
    agent.report = (agent.report ?? "") + text;
    return this.emit();
  }

  citations(toolCallId: string, citations: ResearchAgent["citations"]): UIMessageChunk[] {
    const agent = this.agents.get(toolCallId);
    if (!agent) return [];
    agent.citations = citations;
    return this.emit();
  }

  /** The committed research replaces live state; agents the stream never finished take the saved status or fail. */
  finish(saved?: ResearchState): UIMessageChunk[] {
    if (!this.started && !saved) return [];
    if (saved) {
      this.plan = saved.plan;
      this.agents.clear();
      for (const agent of saved.agents)
        this.agents.set(agent.toolCallId, { ...agent, startedAt: 0 });
    }
    for (const agent of this.agents.values())
      if (agent.status === "RUNNING") agent.status = "FAILED";
    return this.emit();
  }

  private emit(): UIMessageChunk[] {
    this.started = true;
    const data: ResearchState = {
      plan: this.plan,
      agents: [...this.agents.values()].map((agent) => ({
        toolCallId: agent.toolCallId,
        cycle: agent.cycle,
        tabIndex: agent.tabIndex,
        task: agent.task,
        status: agent.status,
        durationMs: agent.durationMs,
        report: agent.report,
        citations: [...agent.citations],
        activity: {
          steps: agent.activity.steps.map((step) => ({ ...step })),
          reasoning: agent.activity.reasoning.map((segment) => ({ ...segment })),
        },
      })),
    };
    return [{ type: "data-research", id: this.id, data }];
  }
}
