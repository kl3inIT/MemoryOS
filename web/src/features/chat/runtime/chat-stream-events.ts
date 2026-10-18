import { z } from "zod";
import { ApiError } from "@/lib/api";
import { unavailableAppSchema } from "@/features/chat/activity/chat-activity";
import type { GeneratedImage } from "@/features/chat/image/chat-image";
import {
  generatedFileSchema,
  MAX_CODE_CHARACTERS,
  MAX_OUTPUT_CHARACTERS,
  type CodeRun,
  type GeneratedFile,
} from "@/features/chat/interpreter/chat-code";

/*
 * The reply stream's own events. OpenAPI does not describe server-sent event payloads, so they are validated
 * here; the transport reads them in order and folds each into the answer it renders.
 */
export const eventSchema = z.object({
  assistantMessageId: z.string().uuid(),
  sequence: z.number().int().positive(),
});
export const textSchema = eventSchema.extend({ text: z.string().max(1_000_000) });
export const outcomeSchema = eventSchema.extend({
  status: z.enum(["COMPLETED", "CANCELED", "FAILED"]),
  failureCode: z.string().max(100).nullish(),
  hasArtifacts: z.boolean().default(false),
  /** MEM-267: what the reply's last model call carried, against the model's input limit. */
  contextTokens: z.number().int().nonnegative().nullish(),
  contextLimit: z.number().int().positive().nullish(),
});
export const codeSchema = eventSchema.extend({
  toolCallId: z.string().min(1).max(256),
  stage: z.enum(["RUNNING", "OUTPUT", "COMPLETED", "FAILED"]),
  code: z.string().max(MAX_CODE_CHARACTERS).nullish(),
  output: z.string().max(MAX_OUTPUT_CHARACTERS).nullish(),
  stream: z.enum(["stdout", "stderr"]).nullish(),
  files: z.array(generatedFileSchema).max(25).default([]),
  /** MEM-270: a sandbox command's title, from the model's description, on its running stage. */
  title: z.string().max(200).nullish(),
});
/**
 * A tool call that waits for the person's approval, then the same call with the decision (MEM-253). It carries the
 * call's arguments, bounded, because the person decides on them.
 */
export const toolApprovalSchema = eventSchema.extend({
  toolCallId: z.string().min(1).max(256),
  serverName: z.string().max(200),
  toolName: z.string().max(128),
  /** MEM-274: the tool's display name, when the server gives one. */
  toolTitle: z.string().max(200).nullish(),
  arguments: z.string().max(4000),
  decision: z.enum(["APPROVED", "DECLINED", "NO_DECISION"]).nullish(),
  /** MEM-267: the reply's owner may answer with "always allow". */
  alwaysAllowable: z.boolean().default(false),
});

/** MEM-273: the reply's apps the person must connect first, sent before the model runs. */
export const mcpUnavailableSchema = eventSchema.extend({
  apps: z.array(unavailableAppSchema).max(32),
});

/** The reply waits for a turn, or has just been given one. */
export const waitingSchema = eventSchema.extend({ waiting: z.boolean() });
export const imageSchema = eventSchema.extend({
  stage: z.enum(["GENERATING", "COMPLETED", "FAILED"]),
  id: z.string().uuid().nullish(),
  mediaType: z.string().max(128).nullish(),
  revisedPrompt: z.string().max(4000).nullish(),
});

/** An image event folded into the answer's images: a finished image replaces an earlier one with its id. */
export function applyImageEvent(images: GeneratedImage[], image: z.infer<typeof imageSchema>) {
  if (image.stage === "COMPLETED" && image.id)
    return {
      images: [
        ...images.filter((existing) => existing.id !== image.id),
        {
          id: image.id,
          mediaType: image.mediaType ?? "image/png",
          revisedPrompt: image.revisedPrompt ?? null,
        },
      ],
      imageGenerating: false,
    };
  return { images, imageGenerating: image.stage === "GENERATING" };
}

/** A code event folded into its run and, once the run ends, the files it produced. */
export function applyCodeEvent(
  codeRuns: Record<string, CodeRun>,
  generatedFiles: GeneratedFile[],
  run: z.infer<typeof codeSchema>,
) {
  const previous = codeRuns[run.toolCallId] ?? {
    title: undefined,
    code: "",
    stdout: "",
    stderr: "",
    files: [],
    status: "running" as const,
  };
  const delta = run.stage === "OUTPUT" ? (run.output ?? "") : "";
  const onStderr = run.stream === "stderr";
  const ended = run.stage === "COMPLETED" || run.stage === "FAILED";
  return {
    codeRuns: {
      ...codeRuns,
      [run.toolCallId]: {
        title: run.stage === "RUNNING" ? (run.title ?? undefined) : previous.title,
        code: run.stage === "RUNNING" ? (run.code ?? "") : previous.code,
        stdout: onStderr ? previous.stdout : previous.stdout + delta,
        stderr: onStderr ? previous.stderr + delta : previous.stderr,
        files: ended ? run.files : previous.files,
        status:
          run.stage === "COMPLETED"
            ? ("done" as const)
            : run.stage === "FAILED"
              ? ("failed" as const)
              : ("running" as const),
      },
    },
    generatedFiles:
      ended && run.files.length
        ? [
            ...generatedFiles.filter((file) => !run.files.some((one) => one.id === file.id)),
            ...run.files,
          ]
        : generatedFiles,
  };
}

/** Keep the generated SSE parser; bound bytes and preserve HTTP authorization errors. */
export const boundedEventFetch: typeof fetch = async (input, init) => {
  const response = await fetch(input, init);
  if (!response.ok) {
    await response.body?.cancel();
    throw new ApiError(response.status, undefined);
  }
  if (!response.headers.get("content-type")?.includes("text/event-stream") || !response.body) {
    await response.body?.cancel();
    throw new Error("Invalid reply stream");
  }
  let bytes = 0;
  const body = response.body.pipeThrough(
    new TransformStream<Uint8Array, Uint8Array>({
      transform(chunk, controller) {
        bytes += chunk.byteLength;
        if (bytes > 8 * 1024 * 1024) throw new Error("Reply stream exceeds the supported limit");
        controller.enqueue(chunk);
      },
    }),
  );
  return new Response(body, { status: response.status, headers: response.headers });
};

export function online(signal: AbortSignal) {
  return new Promise<void>((resolve, reject) => {
    signal.throwIfAborted();
    const done = () => {
      globalThis.removeEventListener("online", done);
      signal.removeEventListener("abort", abort);
      resolve();
    };
    const abort = () => {
      globalThis.removeEventListener("online", done);
      reject(signal.reason);
    };
    globalThis.addEventListener("online", done, { once: true });
    signal.addEventListener("abort", abort, { once: true });
  });
}

export function pause(ms: number, signal: AbortSignal) {
  return new Promise<void>((resolve, reject) => {
    signal.throwIfAborted();
    const abort = () => {
      clearTimeout(timer);
      reject(signal.reason);
    };
    const timer = setTimeout(() => {
      signal.removeEventListener("abort", abort);
      resolve();
    }, ms);
    signal.addEventListener("abort", abort, { once: true });
  });
}
