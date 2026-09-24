import type { UseChatHelpers } from "@ai-sdk/react";
import type { QueryClient } from "@tanstack/react-query";
import { ApiError } from "@/lib/api";
import { getCurrentIdentityQueryKey } from "@/lib/hey-api/@tanstack/react-query.gen";
import type { ChatSession } from "@/lib/hey-api/types.gen";
import { presentProblem, type ErrorMessage } from "@/lib/problem-presentation";
import { actionProblem } from "@/lib/action-errors";
import {
  loadChatHistory,
  toUiMessages,
  type ChatHistory,
  type ChatUiMessage,
} from "@/features/chat/chat-api";
import { MemoryOsChatTransport, type ConnectionState } from "./chat-transport";

const sendProblems = {
  CHAT_PROVIDER_UNAVAILABLE: { key: "chatProviderUnavailable" },
  CHAT_CAPACITY_EXCEEDED: { key: "chatBusy" },
  CHAT_INVALID_REQUEST: { key: "chatRejected" },
  CHAT_WEB_UNAVAILABLE: { key: "chatWebUnavailable" },
  CHAT_RESEARCH_UNAVAILABLE: { key: "chatResearchUnavailable" },
  CHAT_RESEARCH_MODEL_UNSUPPORTED: { key: "chatResearchModelUnsupported" },
} as const satisfies Record<string, ErrorMessage>;

export type ChatThreadState = {
  session?: ChatSession;
  connection: ConnectionState;
  error?: ErrorMessage | "unfinished" | "disconnected";
  unavailable: boolean;
  stopping: boolean;
  checking: boolean;
  historyFailed: boolean;
  resume: boolean;
  attachmentError?: ErrorMessage;
};

type Deferred<T> = {
  promise: Promise<T>;
  resolve: (value: T) => void;
  reject: (cause: unknown) => void;
};

function deferred<T>(): Deferred<T> {
  let resolve!: (value: T) => void;
  let reject!: (cause: unknown) => void;
  const promise = new Promise<T>((onResolve, onReject) => {
    resolve = onResolve;
    reject = onReject;
  });
  promise.catch(() => {
    /* Observed by the thread-list adapter; an unmounted thread must not raise an unhandled rejection. */
  });
  return { promise, resolve, reject };
}

/**
 * Conversation state for one mounted assistant-ui thread. The server remains the transcript
 * authority: this object only tracks the live connection and replaces page-local state so it
 * survives page navigation while the thread body stays mounted.
 */
export class ChatThreadController {
  readonly transport: MemoryOsChatTransport;
  remoteId?: string;
  private state: ChatThreadState;
  private readonly listeners = new Set<() => void>();
  private created = deferred<ChatSession>();
  private readonly answered = deferred<void>();
  private accepted = false;
  private chat?: UseChatHelpers<ChatUiMessage>;
  private cancelRun?: () => void;
  private readonly lifetime = new AbortController();
  private mutationInFlight = false;
  private visible = false;
  private suspended = false;
  readonly id: string;
  private readonly queries: QueryClient;
  private readonly onRemoteId: (controller: ChatThreadController) => void;

  constructor(
    id: string,
    remoteId: string | undefined,
    queries: QueryClient,
    actorId: string,
    onRemoteId: (controller: ChatThreadController) => void,
  ) {
    this.id = id;
    this.queries = queries;
    this.onRemoteId = onRemoteId;
    this.remoteId = remoteId;
    this.transport = new MemoryOsChatTransport(undefined, undefined, undefined, actorId);
    this.state = {
      connection: "ready",
      unavailable: false,
      stopping: false,
      checking: false,
      historyFailed: false,
      resume: false,
    };
    // Session creation stays in the transport where the first question provides the initial title.
    this.transport.onSessionCreated = (session) => {
      this.remoteId = session.id;
      this.set({ session });
      this.created.resolve(session);
      this.onRemoteId(this);
    };
    this.transport.onSessionFailed = (cause) => {
      const failed = this.created;
      this.created = deferred();
      failed.reject(cause);
    };
  }

  getState = () => this.state;

  subscribe = (listener: () => void) => {
    this.listeners.add(listener);
    return () => {
      this.listeners.delete(listener);
    };
  };

  /** Resolves `RemoteThreadListAdapter.initialize` once the transport has created the session. */
  sessionCreated() {
    return this.created.promise;
  }

  /** Server naming needs a completed answer; the list asks for a title as soon as the question exists. */
  firstAnswer() {
    return this.answered.promise;
  }

  /** Project for the session created by the first send; an existing session keeps its own. */
  setProject(projectId: string | undefined) {
    if (!this.transport.session) this.transport.projectId = projectId;
  }

  updateSession(session: ChatSession) {
    this.transport.session = session;
    this.set({ session });
  }

  connect(chat: UseChatHelpers<ChatUiMessage>, cancelRun: () => void) {
    this.chat = chat;
    this.cancelRun = cancelRun;
  }

  listen() {
    const unlisten = this.transport.listen({
      state: (state) => this.onState(state),
      error: (cause) => this.handleError(cause),
      canceled: () => this.cancelRun?.(),
      accepted: (session, userId, localId) => {
        this.chat?.setMessages((messages) =>
          messages.map((message) =>
            message.id === localId ? { ...message, id: userId } : message,
          ),
        );
        this.accepted = true;
        if (!this.state.session) this.set({ session });
        void this.queries.invalidateQueries({ queryKey: ["chat-project-sessions"] });
      },
    });
    return unlisten;
  }

  /**
   * The list keeps visited thread bodies mounted. Leaving a conversation only closes its reader;
   * the server run continues and is reconciled from saved history when the conversation is shown.
   */
  setVisible(visible: boolean) {
    if (visible === this.visible) return;
    this.visible = visible;
    if (!visible) {
      this.suspended = true;
      this.transport.disconnect();
      void this.chat?.stop();
    } else if (this.suspended) {
      this.suspended = false;
      void this.check();
    }
  }

  /** Final release by the registry; never on a Strict Mode effect replay. */
  dispose() {
    this.lifetime.abort();
    this.transport.disconnect();
    void this.chat?.stop();
    this.answered.reject(new Error("Conversation closed"));
  }

  setAttachmentError = (attachmentError?: ErrorMessage) => {
    this.set({ attachmentError });
  };

  async loadHistory(): Promise<ChatUiMessage[]> {
    if (!this.remoteId) return [];
    try {
      const history = await loadChatHistory(this.remoteId, this.lifetime.signal);
      this.restore(history);
      return toUiMessages(history.messages.filter((message) => message.status !== "RUNNING"));
    } catch (cause) {
      if (!this.lifetime.signal.aborted) {
        this.set({ historyFailed: true });
        this.handleError(cause);
      }
      throw cause;
    }
  }

  /** Called after the history loader has applied messages, so the resumed stream appends to them. */
  resumePending() {
    if (!this.state.resume) return;
    this.set({ resume: false });
    void this.chat?.resumeStream().catch((cause: unknown) => this.handleError(cause));
  }

  async stop() {
    if (this.state.stopping) return;
    this.set({ stopping: true });
    try {
      await this.transport.stop();
    } catch (cause) {
      this.handleError(cause);
      this.set({ stopping: false });
    }
  }

  async check() {
    this.set({ checking: true, error: undefined });
    try {
      await this.refresh();
    } catch (cause) {
      this.handleError(cause);
    } finally {
      this.set({ checking: false });
    }
  }

  async mutate(command: () => Promise<unknown>) {
    if (this.mutationInFlight || this.state.connection !== "ready" || this.state.checking)
      throw new Error("Conversation is busy");
    this.mutationInFlight = true;
    this.set({ checking: true, error: undefined });
    try {
      await command();
      await this.refresh();
    } catch (cause) {
      this.set({ error: actionProblem(cause), connection: "uncertain" });
      throw cause;
    } finally {
      this.mutationInFlight = false;
      this.set({ checking: false });
    }
  }

  /** A named rejection tells the actor what to change; an unnamed transport failure keeps the generic notice. */
  markUnfinished(cause?: unknown) {
    const presented =
      cause === undefined ? undefined : presentProblem(cause, "mutation", sendProblems);
    this.set({ error: presented?.code ? presented.message : "unfinished" });
  }

  markUnavailable() {
    this.transport.disconnect();
    this.set({ unavailable: true });
  }

  private async refresh() {
    const signal = this.lifetime.signal;
    const remoteId = this.transport.session?.id ?? this.remoteId;
    if (!remoteId) {
      this.chat?.setMessages([]);
      this.chat?.clearError();
      this.onState("ready");
      return;
    }
    this.transport.disconnect();
    await this.chat?.stop();
    const history = await loadChatHistory(remoteId, signal);
    signal.throwIfAborted();
    this.restore(history);
    this.chat?.setMessages(
      toUiMessages(history.messages.filter((message) => message.status !== "RUNNING")),
    );
    this.chat?.clearError();
    this.set({ historyFailed: false });
    if (this.state.resume) {
      this.onState("recovering");
      this.resumePending();
    } else this.onState("ready");
    await this.queries.invalidateQueries({ queryKey: ["chat-branches", remoteId] });
    await this.queries.invalidateQueries({ queryKey: ["chat-feedback", remoteId] });
  }

  private restore(history: ChatHistory) {
    this.transport.restore(history.session, history.messages);
    const running = history.messages.some((message) => message.status === "RUNNING");
    this.set({
      session: history.session,
      resume: running,
      connection: running ? "recovering" : this.state.connection,
    });
    this.queries.setQueryData(["chat-session", history.session.id], history.session);
  }

  private onState(connection: ConnectionState) {
    this.set({
      connection,
      ...(connection === "sending" && { error: undefined }),
      ...((connection === "ready" || connection === "uncertain") && { stopping: false }),
    });
    if (connection !== "ready") return;
    const id = this.transport.session?.id;
    if (this.accepted) this.answered.resolve();
    if (!id) return;
    void this.queries.invalidateQueries({ queryKey: ["chat-branches", id] });
    void this.queries.invalidateQueries({ queryKey: ["chat-feedback", id] });
  }

  private handleError(cause: unknown) {
    if (this.lifetime.signal.aborted) return;
    if (cause instanceof ApiError && [401, 403, 404].includes(cause.status ?? 0)) {
      this.markUnavailable();
      void this.queries.invalidateQueries({ queryKey: getCurrentIdentityQueryKey() });
    } else this.set({ error: "disconnected" });
  }

  private set(next: Partial<ChatThreadState>) {
    this.state = { ...this.state, ...next };
    for (const listener of this.listeners) listener();
  }
}

/** Controllers keyed by assistant-ui's local thread ID, which stays stable through server-ID promotion. */
export class ChatThreadRegistry {
  private readonly controllers = new Map<
    string,
    { controller: ChatThreadController; retained: number }
  >();
  private readonly remote = new Map<string, ChatThreadController>();
  private readonly listeners = new Set<() => void>();

  private readonly queries: QueryClient;
  private readonly actorId: string;

  constructor(queries: QueryClient, actorId: string) {
    this.queries = queries;
    this.actorId = actorId;
  }

  subscribe = (listener: () => void) => {
    this.listeners.add(listener);
    return () => {
      this.listeners.delete(listener);
    };
  };

  get(id: string | undefined) {
    return id === undefined ? undefined : this.controllers.get(id)?.controller;
  }

  byRemoteId(remoteId: string) {
    return this.remote.get(remoteId);
  }

  obtain(id: string, remoteId: string | undefined) {
    const existing = this.controllers.get(id);
    if (existing) return existing.controller;
    const controller = new ChatThreadController(
      id,
      remoteId,
      this.queries,
      this.actorId,
      (created) => {
        if (created.remoteId) this.remote.set(created.remoteId, created);
        this.notify();
      },
    );
    this.controllers.set(id, { controller, retained: 0 });
    if (remoteId) this.remote.set(remoteId, controller);
    return controller;
  }

  /** Reference-counted so React Strict Mode remounts keep the same controller. */
  retain(controller: ChatThreadController) {
    const entry = this.controllers.get(controller.id);
    if (!entry || entry.controller !== controller) return () => {};
    entry.retained++;
    this.notify();
    return () => {
      entry.retained--;
      queueMicrotask(() => {
        if (entry.retained > 0 || this.controllers.get(controller.id) !== entry) return;
        this.controllers.delete(controller.id);
        if (controller.remoteId && this.remote.get(controller.remoteId) === controller)
          this.remote.delete(controller.remoteId);
        controller.dispose();
        this.notify();
      });
    };
  }

  private notify() {
    for (const listener of this.listeners) listener();
  }
}
