import type { UIMessage, UIMessageChunk } from "ai";
import { z } from "zod";
import { sourceSchema } from "@/features/chat/sources/chat-evidence";
import { documentSourceTypesSchema } from "@/features/search/document-source-presentation";

const toolNameSchema = z.string().regex(/^[A-Za-z0-9_.-]{1,64}$/);
const intervalSchema = z.object({
  from: z.string().datetime({ offset: true }).nullable(),
  to: z.string().datetime({ offset: true }).nullable(),
});
const filtersSchema = z.object({
  sources: documentSourceTypesSchema,
  created: intervalSchema.nullable(),
  updated: intervalSchema.nullable(),
});
const readingDocumentSchema = z.object({
  documentId: z.string().uuid(),
  generation: z.string().uuid(),
  title: z.string().max(255),
  startOrdinal: z.number().int().min(0).max(9999),
  endOrdinal: z.number().int().min(0).max(9999),
});
const stageSchema = z.enum([
  "STARTED",
  "SEARCHING",
  "SELECTING",
  "EXPANDING",
  "SOURCE",
  "COMPLETED",
  "FAILED",
]);
/** Why a failed step failed when the person can act on it; a category only, never upstream text. */
const failureSchema = z.enum(["AUTHORIZATION_REQUIRED", "TIMEOUT", "UNAVAILABLE"]);

export const toolEventSchema = z.object({
  toolCallId: z.string().min(1).max(256),
  toolName: toolNameSchema,
  stage: stageSchema,
  source: sourceSchema.nullable(),
  search: z
    .object({
      queries: z.array(z.string().min(1).max(2000)).min(1).max(8),
      filters: filtersSchema,
    })
    .nullable()
    .default(null),
  documents: z.array(readingDocumentSchema).max(10).default([]),
  durationMs: z.number().int().nonnegative().nullable().default(null),
  /** A deep research agent's own step names its agent call; the agent call carries its tab. */
  parentToolCallId: z.string().min(1).max(256).nullable().default(null),
  tabIndex: z.number().int().min(0).max(2).nullable().default(null),
  failure: failureSchema.nullable().default(null),
});
// Reasoning shares the answer chunking, so one event can exceed a single provider delta.
export const reasoningEventSchema = z.object({
  text: z.string().min(1).max(1_000_000),
  parentToolCallId: z.string().min(1).max(256).nullable().default(null),
});

export const activitySchema = z
  .object({
    steps: z
      .array(
        z.object({
          position: z.number().int().min(0),
          toolCallId: z.string().min(1).max(256),
          toolName: toolNameSchema,
          status: z.enum(["RUNNING", "COMPLETED", "FAILED"]),
          durationMs: z.number().int().nonnegative().nullish(),
          textOffset: z.number().int().min(0),
          queries: z.array(z.string().min(1).max(500)).max(8).default([]),
          filters: filtersSchema.nullish(),
          documents: z.array(readingDocumentSchema).max(10).default([]),
          citations: z.array(z.number().int().min(1)).default([]),
          failure: failureSchema.nullish(),
        }),
      )
      .max(32)
      .default([]),
    reasoning: z
      .array(
        z.object({
          position: z.number().int().min(0),
          textOffset: z.number().int().min(0),
          text: z.string().min(1).max(16_010),
        }),
      )
      .max(32)
      .default([]),
  })
  .default({ steps: [], reasoning: [] });

export type ToolEvent = z.infer<typeof toolEventSchema>;
export type ChatActivity = z.infer<typeof activitySchema>;

/** Input of a server-executed tool part: allowlisted progress only, never raw arguments or results. */
export type ToolProgress = {
  stage: ToolEvent["stage"];
  queries: string[];
  filters: z.infer<typeof filtersSchema> | null;
  documents: z.infer<typeof readingDocumentSchema>[];
  citations: number[];
  durationMs: number | null;
  failure: z.infer<typeof failureSchema> | null;
};
export const toolProgressSchema = z.object({
  stage: stageSchema.catch("STARTED"),
  queries: z.array(z.string()).catch([]),
  filters: filtersSchema.nullable().catch(null),
  documents: z.array(readingDocumentSchema).catch([]),
  citations: z.array(z.number().int()).catch([]),
  durationMs: z.number().nullable().catch(null),
  failure: failureSchema.nullable().catch(null),
});
/** Non-sensitive marker; the UI shows its own localized failure copy. */
const TOOL_FAILED = "TOOL_FAILED";

const emptyProgress = (): ToolProgress => ({
  stage: "STARTED",
  queries: [],
  filters: null,
  documents: [],
  citations: [],
  durationMs: null,
  failure: null,
});

/**
 * Converts ordered server activity into AI SDK chunks. Tool progress travels in the tool input because
 * the assistant-ui AI SDK converter marks a tool complete on any output. Text and reasoning each open a
 * new part after other activity, so parts keep their streamed order.
 */
export class ActivityChunks {
  private textId?: string;
  private reasoningId?: string;
  private segments = 0;
  private citations: Record<string, number[]> = {};
  private readonly tools = new Map<
    string,
    { name: string; progress: ToolProgress; done: boolean }
  >();
  private readonly runId: string;

  constructor(runId: string) {
    this.runId = runId;
  }

  text(delta: string): UIMessageChunk[] {
    const chunks = this.closeReasoning();
    if (!this.textId) {
      this.textId = this.segments++ === 0 ? this.runId : `${this.runId}:text:${this.segments}`;
      chunks.push({ type: "text-start", id: this.textId });
    }
    chunks.push({ type: "text-delta", id: this.textId, delta });
    return chunks;
  }

  reasoning(delta: string): UIMessageChunk[] {
    const chunks = this.closeText();
    if (!this.reasoningId) {
      this.reasoningId = `${this.runId}:reasoning:${this.segments++}`;
      chunks.push({ type: "reasoning-start", id: this.reasoningId });
    }
    chunks.push({ type: "reasoning-delta", id: this.reasoningId, delta });
    return chunks;
  }

  tool(event: ToolEvent): UIMessageChunk[] {
    let tool = this.tools.get(event.toolCallId);
    if (event.stage === "SOURCE") {
      if (!tool || !event.source) return [];
      const cited = this.citations[event.toolCallId] ?? [];
      if (!cited.includes(event.source.citationId))
        this.citations = {
          ...this.citations,
          [event.toolCallId]: [...cited, event.source.citationId],
        };
      // Hosted search cites after its step completes; a finished part keeps its output and the
      // citations travel in message metadata instead.
      if (tool.done) return [];
      tool.progress = { ...tool.progress, citations: this.citations[event.toolCallId]! };
      return [this.input(event.toolCallId, tool)];
    }
    const chunks: UIMessageChunk[] = [];
    if (!tool) {
      chunks.push(...this.closeText(), ...this.closeReasoning());
      tool = { name: event.toolName, progress: emptyProgress(), done: false };
      this.tools.set(event.toolCallId, tool);
      chunks.push({
        type: "tool-input-start",
        toolCallId: event.toolCallId,
        toolName: event.toolName,
        providerExecuted: true,
        dynamic: true,
      });
    }
    if (tool.done) return chunks;
    tool.progress = {
      ...tool.progress,
      stage: event.stage,
      ...(event.search && { queries: event.search.queries, filters: event.search.filters }),
      ...(event.documents.length > 0 && { documents: event.documents }),
      ...(event.durationMs !== null && { durationMs: event.durationMs }),
      ...(event.failure !== null && { failure: event.failure }),
    };
    chunks.push(this.input(event.toolCallId, tool));
    if (event.stage === "COMPLETED" || event.stage === "FAILED")
      chunks.push(this.output(event.toolCallId, tool, event.stage === "FAILED"));
    return chunks;
  }

  /** Citations per tool call, including those that arrived after the step finished. */
  toolCitations(): Record<string, number[]> {
    return this.citations;
  }

  /** Closes open parts; tools the stream never finished take their committed status, or fail. */
  finish(activity?: ChatActivity): UIMessageChunk[] {
    const chunks = [...this.closeText(), ...this.closeReasoning()];
    for (const [toolCallId, tool] of this.tools) {
      if (tool.done) continue;
      const step = activity?.steps.find((candidate) => candidate.toolCallId === toolCallId);
      tool.progress = {
        ...tool.progress,
        durationMs: step?.durationMs ?? tool.progress.durationMs,
      };
      chunks.push(this.output(toolCallId, tool, step?.status !== "COMPLETED"));
    }
    return chunks;
  }

  private input(
    toolCallId: string,
    tool: { name: string; progress: ToolProgress },
  ): UIMessageChunk {
    return {
      type: "tool-input-available",
      toolCallId,
      toolName: tool.name,
      input: tool.progress,
      providerExecuted: true,
      dynamic: true,
    };
  }

  private output(
    toolCallId: string,
    tool: { progress: ToolProgress; done: boolean },
    failed: boolean,
  ): UIMessageChunk {
    tool.done = true;
    return failed
      ? {
          type: "tool-output-error",
          toolCallId,
          errorText: TOOL_FAILED,
          providerExecuted: true,
          dynamic: true,
        }
      : {
          type: "tool-output-available",
          toolCallId,
          output: { durationMs: tool.progress.durationMs },
          providerExecuted: true,
          dynamic: true,
        };
  }

  private closeText(): UIMessageChunk[] {
    if (!this.textId) return [];
    const id = this.textId;
    this.textId = undefined;
    return [{ type: "text-end", id }];
  }

  private closeReasoning(): UIMessageChunk[] {
    if (!this.reasoningId) return [];
    const id = this.reasoningId;
    this.reasoningId = undefined;
    return [{ type: "reasoning-end", id }];
  }
}

type Part = UIMessage["parts"][number];

/** Rebuilds a saved answer's reasoning, tool steps and text in streamed order; legacy answers have no activity. */
export function historyParts(content: string, activity: ChatActivity): Part[] {
  const items = [
    ...activity.reasoning.map((segment) => ({
      position: segment.position,
      offset: segment.textOffset,
      part: { type: "reasoning", text: segment.text, state: "done" } satisfies Part as Part,
    })),
    ...activity.steps.map((step) => {
      const input: ToolProgress = {
        stage: step.status === "COMPLETED" ? "COMPLETED" : "FAILED",
        queries: step.queries,
        filters: step.filters ?? null,
        documents: step.documents,
        citations: step.citations,
        durationMs: step.durationMs ?? null,
        failure: step.failure ?? null,
      };
      const base = {
        type: "dynamic-tool",
        toolName: step.toolName,
        toolCallId: step.toolCallId,
        providerExecuted: true,
      } as const;
      const part: Part =
        step.status === "COMPLETED"
          ? { ...base, state: "output-available", input, output: { durationMs: input.durationMs } }
          : { ...base, state: "output-error", input, errorText: TOOL_FAILED };
      return { position: step.position, offset: step.textOffset, part };
    }),
  ].sort((left, right) => left.position - right.position);
  const parts: Part[] = [];
  let consumed = 0;
  for (const item of items) {
    const offset = Math.min(Math.max(item.offset, consumed), content.length);
    if (offset > consumed) parts.push({ type: "text", text: content.slice(consumed, offset) });
    consumed = offset;
    parts.push(item.part);
  }
  parts.push({ type: "text", text: content.slice(consumed) });
  return parts;
}
