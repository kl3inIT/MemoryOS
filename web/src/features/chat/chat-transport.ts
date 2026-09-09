import type { ChatTransport, UIMessageChunk } from "ai";
import { z } from "zod";
import { ApiError, sameOriginMutationHeaders } from "@/lib/api";
import {
  cancelChatMessage,
  getChatHistory,
  sendChatMessage,
  streamChatMessage,
} from "@/lib/hey-api/sdk.gen";
import type { ChatMessage, ChatSession } from "@/lib/hey-api/types.gen";
import { newChatSession, type ChatUiMessage } from "./chat-api";

const eventSchema = z.object({
  assistantMessageId: z.string().uuid(),
  sequence: z.number().int().positive(),
});
const textSchema = eventSchema.extend({ text: z.string().max(1_000_000) });
const outcomeSchema = eventSchema.extend({ status: z.enum(["COMPLETED", "CANCELED", "FAILED"]) });
export type ConnectionState = "ready" | "sending" | "streaming" | "recovering" | "uncertain";
type Callbacks = {
  state: (state: ConnectionState) => void;
  accepted: (session: ChatSession, userId: string, localId: string) => void;
  canceled: () => void;
  error: (error: unknown) => void;
};

/** Adapts the Java wire contract. AI SDK owns message content and tool state. */
export class MemoryOsChatTransport implements ChatTransport<ChatUiMessage> {
  private reader?: AbortController;
  private runId?: string;
  private runParentId?: string;
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

  constructor(session?: ChatSession, runningMessage?: ChatMessage) {
    this.session = session;
    this.runId = runningMessage?.id;
    this.runParentId = runningMessage?.parentMessageId ?? undefined;
  }

  disconnect() {
    this.reader?.abort();
  }

  listen(callbacks: Callbacks) {
    this.callbacks = callbacks;
    return () => {
      this.callbacks = { state: () => {}, accepted: () => {}, canceled: () => {}, error: () => {} };
    };
  }

  restore(session: ChatSession, messages: ChatMessage[]) {
    this.session = session;
    const running = messages.find((message) => message.status === "RUNNING");
    this.runId = running?.id;
    this.runParentId = running?.parentMessageId ?? undefined;
  }

  async sendMessages(options: Parameters<ChatTransport<ChatUiMessage>["sendMessages"]>[0]) {
    if (options.trigger !== "submit-message") throw new Error("Editing is not available yet");
    const message = options.messages.at(-1);
    const text =
      message?.parts
        .filter((part) => part.type === "text")
        .map((part) => part.text)
        .join("") ?? "";
    if (!message || !text.trim() || text.length > 32_000)
      throw new Error("Enter a message of at most 32,000 characters");
    const signal = this.openReader(options.abortSignal);
    this.sending = true;
    this.stopWhenAccepted = false;
    this.callbacks.state("sending");
    try {
      this.session ??= await newChatSession(text, signal);
      const { data } = await sendChatMessage({
        path: { sessionId: this.session.id },
        body: {
          parentMessageId: options.messages.at(-2)?.id ?? this.session.rootMessageId,
          clientRequestId: message.id,
          text,
        },
        headers: sameOriginMutationHeaders,
        signal: AbortSignal.any([signal, AbortSignal.timeout(30_000)]),
        throwOnError: true,
      });
      this.runId = data.assistantMessageId;
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
    let outcome: "COMPLETED" | "CANCELED" | "FAILED" | undefined;
    let fallback = false;
    yield { type: "start", messageId: runId, messageMetadata: { serverStatus: "RUNNING" } };
    yield { type: "text-start", id: runId };
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
              yield { type: "text-delta", id: runId, delta };
            } else if (envelope.event === "outcome") {
              outcome = outcomeSchema.parse(data).status;
              break;
            } else throw new Error("Unexpected reply event type");
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
        // Covers the server's maximum 30-minute deadline plus finalization grace.
        const until = Date.now() + 31 * 60_000;
        while (!outcome && Date.now() < until) {
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
            if (delta) yield { type: "text-delta", id: runId, delta };
            outcome = message.status;
          } else await pause(2000, signal);
        }
        if (!outcome)
          throw new Error("Reply status could not be confirmed; check the conversation again");
      }
      yield { type: "message-metadata", messageMetadata: { serverStatus: outcome } };
      yield { type: "text-end", id: runId };
      this.runId = undefined;
      this.callbacks.state("ready");
      if (outcome === "CANCELED") {
        this.callbacks.canceled();
        return;
      }
      if (outcome === "FAILED")
        yield {
          type: "error",
          errorText: "The reply could not finish. Any saved partial answer is shown.",
        };
      else yield { type: "finish", finishReason: "stop" };
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
