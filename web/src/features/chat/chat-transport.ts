import { injectQuoteContext } from "@assistant-ui/ai-sdk";
import type { ChatTransport, UIMessageChunk } from "ai";
import { z } from "zod";
import { ApiError, sameOriginMutationHeaders } from "@/lib/api";
import {
  cancelChatMessage,
  getChatHistory,
  sendChatMessage,
  streamChatMessage,
} from "@/lib/hey-api/sdk.gen";
import type { Accepted, ChatMessage, ChatSession } from "@/lib/hey-api/types.gen";
import { newChatSession, type ChatUiMessage } from "./chat-api";
import { fileIdFromReference } from "./chat-files";
import { readWebPreference, writeWebPreference, type WebSearchMode } from "./chat-web-preference";
import {
  parseGeneratedImages,
  readImagePreference,
  writeImagePreference,
  type GeneratedImage,
  type ImageMode,
} from "./chat-image";
import { artifactsSchema, type ChatArtifact } from "./chat-artifacts";
import { sourcesSchema, type ChatSource } from "./chat-evidence";
import {
  ActivityChunks,
  activitySchema,
  reasoningEventSchema,
  toolEventSchema,
  type ChatActivity,
} from "./chat-activity";

const eventSchema = z.object({
  assistantMessageId: z.string().uuid(),
  sequence: z.number().int().positive(),
});
const textSchema = eventSchema.extend({ text: z.string().max(1_000_000) });
const outcomeSchema = eventSchema.extend({
  status: z.enum(["COMPLETED", "CANCELED", "FAILED"]),
  hasArtifacts: z.boolean().default(false),
});
const imageSchema = eventSchema.extend({
  stage: z.enum(["GENERATING", "COMPLETED", "FAILED"]),
  id: z.string().uuid().nullish(),
  mediaType: z.string().max(128).nullish(),
  revisedPrompt: z.string().max(4000).nullish(),
});
export type ConnectionState = "ready" | "sending" | "streaming" | "recovering" | "uncertain";
type Callbacks = {
  state: (state: ConnectionState) => void;
  accepted: (session: ChatSession, userId: string, localId: string) => void;
  canceled: () => void;
  error: (error: unknown) => void;
};

/** Adapts the Java wire contract. AI SDK owns message content and tool state. */
export class MemoryOsChatTransport implements ChatTransport<ChatUiMessage> {
  webSearch: WebSearchMode = "off";
  selectWeb(mode: WebSearchMode) {
    this.webSearch = mode;
    writeWebPreference(this.preferenceOwner, this.session?.id, mode);
  }
  image: ImageMode = "off";
  selectImage(mode: ImageMode) {
    this.image = mode;
    writeImagePreference(this.preferenceOwner, this.session?.id, mode);
  }
  private modelConfigurationId?: string;
  private onModelAccepted?: (selection: Accepted) => void;
  /** Project for the session created by the first send; ignored once the session exists. */
  projectId?: string;
  onSessionCreated?: (session: ChatSession) => void;
  /** Any failure before the first session exists, so thread initialization can be retried. */
  onSessionFailed?: (error: unknown) => void;
  private readonly preferenceOwner?: string;

  selectModel(id?: string) {
    this.modelConfigurationId = id;
  }
  recordModelSelection(selection: Accepted) {
    this.onModelAccepted?.(selection);
  }
  listenModelSelection(listener: (selection: Accepted) => void) {
    this.onModelAccepted = listener;
    return () => {
      this.onModelAccepted = undefined;
    };
  }
  private reader?: AbortController;
  private runId?: string;
  private runParentId?: string;
  private runCreatedAt?: string;
  private stopRequest?: Promise<void>;
  private sending = false;
  private stopWhenAccepted = false;
  session?: ChatSession;
  callbacks: Callbacks = {
    state: () => {},
    accepted: () => {},
    canceled: () => {},
    error: () => {},
  };

  constructor(
    session?: ChatSession,
    runningMessage?: ChatMessage,
    projectId?: string,
    preferenceOwner?: string,
  ) {
    this.projectId = projectId;
    this.preferenceOwner = preferenceOwner;
    this.session = session;
    this.webSearch = readWebPreference(preferenceOwner, session?.id);
    this.image = readImagePreference(preferenceOwner, session?.id);
    this.runId = runningMessage?.id;
    this.runParentId = runningMessage?.parentMessageId ?? undefined;
    this.runCreatedAt = runningMessage?.createdAt;
  }

  disconnect() {
    this.reader?.abort();
  }

  listen(callbacks: Callbacks) {
    this.callbacks = callbacks;
    return () => {
      this.callbacks = {
        state: () => {},
        accepted: () => {},
        canceled: () => {},
        error: () => {},
      };
    };
  }

  restore(session: ChatSession, messages: ChatMessage[]) {
    this.session = session;
    const running = messages.find((message) => message.status === "RUNNING");
    this.runId = running?.id;
    this.runParentId = running?.parentMessageId ?? undefined;
    this.runCreatedAt = running?.createdAt;
  }

  async sendMessages(options: Parameters<ChatTransport<ChatUiMessage>["sendMessages"]>[0]) {
    // Capture selection before any await; later UI changes affect the next turn.
    const modelConfigurationId = this.modelConfigurationId;
    const webSearch = this.webSearch;
    const image = this.image;
    const creating = !this.session;
    try {
      return await this.submit(options, modelConfigurationId, webSearch, image);
    } catch (error) {
      if (creating && !this.session) this.onSessionFailed?.(error);
      throw error;
    }
  }

  private async submit(
    options: Parameters<ChatTransport<ChatUiMessage>["sendMessages"]>[0],
    modelConfigurationId: string | undefined,
    webSearch: WebSearchMode,
    image: ImageMode,
  ) {
    if (options.trigger !== "submit-message")
      throw new Error("Use the conversation's message actions to create a saved version");
    const sent = options.messages.at(-1);
    // A composer quote travels in metadata; the saved question carries it as a leading blockquote.
    const message = sent && injectQuoteContext([sent])[0];
    const text =
      message?.parts
        .filter((part) => part.type === "text")
        .map((part) => part.text)
        .join("") ?? "";
    const fileIds =
      message?.parts
        .filter((part) => part.type === "file")
        .map((part) => fileIdFromReference(part.url)) ?? [];
    if (fileIds.some((id) => !id) || fileIds.length > 20)
      throw new Error("Danh sách tệp không hợp lệ.");
    if (!message || (!text.trim() && fileIds.length === 0) || text.length > 32_000)
      throw new Error("Enter a message of at most 32,000 characters");
    const signal = this.openReader(options.abortSignal);
    this.sending = true;
    this.stopWhenAccepted = false;
    this.callbacks.state("sending");
    try {
      if (!this.session) {
        this.session = await newChatSession(text, signal, undefined, this.projectId);
        this.onSessionCreated?.(this.session);
      }
      writeWebPreference(this.preferenceOwner, this.session.id, this.webSearch);
      writeImagePreference(this.preferenceOwner, this.session.id, this.image);
      const body = {
        parentMessageId: options.messages.at(-2)?.id ?? this.session.rootMessageId,
        clientRequestId: message.id,
        text,
        modelConfigurationId,
        webSearch,
        fileIds: fileIds as string[],
      };
      const { data } = await sendChatMessage({
        path: { sessionId: this.session.id },
        // `image` joins the generated body type once openapi regenerates for MEM-97; sent now per the wire contract.
        body: { ...body, image } as typeof body,
        headers: sameOriginMutationHeaders,
        signal: AbortSignal.any([signal, AbortSignal.timeout(30_000)]),
        throwOnError: true,
      });
      this.runId = data.assistantMessageId;
      this.runCreatedAt = new Date().toISOString();
      this.onModelAccepted?.(data);
      this.runParentId = data.userMessageId;
      if (this.stopWhenAccepted) {
        // Stop can be pressed before the reservation response supplies its run ID.
        try {
          await this.stop();
        } catch (error) {
          this.callbacks.error(error);
        }
      }
      this.callbacks.accepted(this.session, data.userMessageId, message.id);
      return this.read(signal);
    } catch (error) {
      this.callbacks.state("uncertain");
      this.callbacks.error(error);
      throw error;
    } finally {
      this.sending = false;
    }
  }

  async reconnectToStream(
    options: Parameters<ChatTransport<ChatUiMessage>["reconnectToStream"]>[0],
  ) {
    if (!this.runId || !this.session) return null;
    return this.read(this.openReader(options.abortSignal));
  }

  stop(): Promise<void> {
    if (!this.runId || !this.session) {
      if (this.sending) this.stopWhenAccepted = true;
      return Promise.resolve();
    }
    if (this.stopRequest) return this.stopRequest;
    this.stopRequest = cancelChatMessage({
      path: { sessionId: this.session.id, assistantMessageId: this.runId },
      headers: sameOriginMutationHeaders,
      signal: AbortSignal.timeout(15_000),
      throwOnError: true,
    })
      .then(() => undefined)
      .finally(() => {
        this.stopRequest = undefined;
      });
    // Only a committed outcome/history ends the UI run, not this 202 response.
    return this.stopRequest;
  }

  private openReader(abortSignal?: AbortSignal) {
    this.disconnect();
    this.reader = new AbortController();
    return abortSignal ? AbortSignal.any([this.reader.signal, abortSignal]) : this.reader.signal;
  }

  private read(signal: AbortSignal): ReadableStream<UIMessageChunk> {
    const iterator = this.chunks(signal);
    return new ReadableStream({
      async pull(controller) {
        try {
          const next = await iterator.next();
          if (next.done) controller.close();
          else controller.enqueue(next.value);
        } catch (error) {
          controller.error(error);
        }
      },
      cancel: async () => {
        this.disconnect();
        await iterator.return();
      },
    });
  }

  private async *chunks(signal: AbortSignal): AsyncGenerator<UIMessageChunk, void> {
    const sessionId = this.session!.id;
    const runId = this.runId!;
    let sequence = 0;
    let text = "";
    let sources: ChatSource[] = [];
    let artifacts: ChatArtifact[] = [];
    let hasArtifacts = false;
    const activity = new ActivityChunks(runId);
    let committedActivity: ChatActivity | undefined;
    let images: GeneratedImage[] = [];
    let imageGenerating = false;
    let outcome: "COMPLETED" | "CANCELED" | "FAILED" | undefined;
    let fallback = false;
    const createdAt = this.runCreatedAt;
    yield {
      type: "start",
      messageId: runId,
      messageMetadata: { serverStatus: "RUNNING", ...(createdAt && { createdAt }) },
    };
    try {
      for (let attempt = 0; attempt < 3 && !outcome && !fallback; attempt++) {
        signal.throwIfAborted();
        this.callbacks.state(attempt === 0 ? "streaming" : "recovering");
        const connection = new AbortController();
        const connectionSignal = AbortSignal.any([
          signal,
          connection.signal,
          AbortSignal.timeout(65_000),
        ]);
        let envelope: { event?: string; id?: string } = {};
        let failure: unknown;
        try {
          const events = await streamChatMessage({
            path: { sessionId, assistantMessageId: runId },
            query: sequence ? { after: `${runId}:${sequence}` } : undefined,
            signal: connectionSignal,
            sseMaxRetryAttempts: 1,
            fetch: boundedEventFetch,
            onSseEvent: (event) => {
              envelope = event;
            },
            onSseError: (error) => {
              failure = error;
            },
          });
          for await (const data of events.stream) {
            signal.throwIfAborted();
            if (envelope.event === "reset") {
              fallback = true;
              break;
            }
            const event = eventSchema.parse(data);
            if (event.assistantMessageId !== runId || envelope.id !== `${runId}:${event.sequence}`)
              throw new Error("Unexpected reply event");
            if (event.sequence <= sequence) continue;
            if (event.sequence !== sequence + 1) {
              fallback = true;
              break;
            }
            sequence = event.sequence;
            if (envelope.event === "text-delta") {
              const delta = textSchema.parse(data).text;
              if (text.length + delta.length > 1_000_000)
                throw new Error("Reply exceeds the supported limit");
              text += delta;
              yield* activity.text(delta);
            } else if (envelope.event === "outcome") {
              const terminal = outcomeSchema.parse(data);
              outcome = terminal.status;
              hasArtifacts = terminal.hasArtifacts;
              break;
            } else if (envelope.event === "tool") {
              const tool = toolEventSchema.parse(data);
              if (tool.stage === "SOURCE") {
                if (!tool.source) throw new Error("Missing reply source");
                sources = sourcesSchema.parse([
                  ...sources.filter((source) => source.citationId !== tool.source!.citationId),
                  tool.source,
                ]);
              }
              const chunks = activity.tool(tool);
              if (tool.stage === "SOURCE") {
                const toolCitations = activity.toolCitations();
                yield {
                  type: "message-metadata",
                  messageMetadata: {
                    sources,
                    ...(Object.keys(toolCitations).length > 0 && { toolCitations }),
                  },
                };
              }
              yield* chunks;
            } else if (envelope.event === "reasoning") {
              yield* activity.reasoning(reasoningEventSchema.parse(data).text);
            } else if (envelope.event === "image") {
              const image = imageSchema.parse(data);
              if (image.stage === "COMPLETED" && image.id) {
                images = [
                  ...images.filter((existing) => existing.id !== image.id),
                  {
                    id: image.id,
                    mediaType: image.mediaType ?? "image/png",
                    revisedPrompt: image.revisedPrompt ?? null,
                  },
                ];
                imageGenerating = false;
              } else imageGenerating = image.stage === "GENERATING";
              yield { type: "message-metadata", messageMetadata: { images, imageGenerating } };
            }
            // Other event types from a newer server are skipped; the committed history stays authoritative.
          }
          if (failure instanceof ApiError && [401, 403, 404].includes(failure.status ?? 0))
            throw failure;
        } finally {
          connection.abort();
        }
        // Clean EOF without an outcome is a disconnect, never successful completion.
        if (!outcome && !fallback) await pause(500 * (attempt + 1), signal);
      }
      if (!outcome) {
        this.callbacks.state("recovering");
        // A turn has no total deadline, as Onyx: poll while it is RUNNING. The server lease fails a dead run.
        while (!outcome) {
          signal.throwIfAborted();
          // Only the reply after its stable USER parent is needed; don't reload a long transcript on every poll.
          if (!this.runParentId) throw new Error("Reply parent is unavailable");
          const { data: messages } = await getChatHistory({
            path: { sessionId },
            query: { after: this.runParentId, limit: 1 },
            signal: AbortSignal.any([signal, AbortSignal.timeout(30_000)]),
            throwOnError: true,
          });
          const message = messages.find((candidate) => candidate.id === runId);
          if (!message) throw new Error("Reply is no longer available");
          if (message.status !== "RUNNING") {
            if (!message.content.startsWith(text))
              throw new Error("Reply changed; reload the saved conversation");
            const delta = message.content.slice(text.length);
            if (delta) yield* activity.text(delta);
            outcome = message.status;
            sources = sourcesSchema.parse(message.sources);
            artifacts = artifactsSchema.parse(message.artifacts);
            committedActivity = activitySchema.parse(message.activity);
            images = parseGeneratedImages((message as { images?: unknown }).images);
            imageGenerating = false;
          } else await pause(2000, signal);
        }
      }
      // Terminal SSE carries only a flag so bounded replay buffers never contain large UI specs.
      // Reuse the authorized history reader, scoped to the stable user parent, exactly once.
      if (hasArtifacts && !artifacts.length) {
        if (!this.runParentId) throw new Error("Reply parent is unavailable");
        const { data: messages } = await getChatHistory({
          path: { sessionId },
          query: { after: this.runParentId, limit: 1 },
          signal: AbortSignal.any([signal, AbortSignal.timeout(30_000)]),
          throwOnError: true,
        });
        const message = messages.find((candidate) => candidate.id === runId);
        if (!message || message.status === "RUNNING")
          throw new Error("Presentation status unavailable");
        artifacts = artifactsSchema.parse(message.artifacts);
      }
      yield {
        type: "message-metadata",
        messageMetadata: {
          serverStatus: outcome,
          sources,
          artifacts,
          images,
          imageGenerating: false,
        },
      };
      yield* activity.finish(committedActivity);
      this.runId = undefined;
      this.callbacks.state("ready");
      if (outcome === "CANCELED") {
        this.callbacks.canceled();
        return;
      }
      if (outcome === "FAILED") {
        yield {
          type: "error",
          errorText: "The reply could not finish. Any saved partial answer is shown.",
        };
      } else yield { type: "finish", finishReason: "stop" };
    } catch (error) {
      if (!signal.aborted) {
        this.callbacks.state("uncertain");
        this.callbacks.error(error);
      }
      throw error;
    }
  }
}

/** Keep the generated SSE parser; bound bytes and preserve HTTP authorization errors. */
const boundedEventFetch: typeof fetch = async (input, init) => {
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

function pause(ms: number, signal: AbortSignal) {
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
