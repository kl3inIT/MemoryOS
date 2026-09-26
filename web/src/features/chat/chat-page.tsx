import { useAppTranslation } from "@/i18n/use-app-translation";
import { useAttachOnOpen } from "@/features/chat/composer/use-attach-on-open";
import { useAuiState } from "@assistant-ui/react";
import { useQuery } from "@tanstack/react-query";
import { useNavigate, useParams } from "@tanstack/react-router";
import { useSyncExternalStore, type ReactNode } from "react";
import { useTranslation } from "react-i18next";
import { AppShellHeader } from "@/components/app-shell/app-shell-header";
import { Alert, AlertAction, AlertDescription } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { useApplicationSession } from "@/features/identity/application-session-context";
import { getChatProjectOptions } from "@/lib/hey-api/@tanstack/react-query.gen";
import { useProblemMessage } from "@/lib/use-problem-message";
import { ChatThread } from "@/features/chat/thread/chat-thread";
import { ChatComposerMenu } from "@/features/chat/composer/chat-composer-menu";
import { ChatEditingContext } from "@/features/chat/thread/chat-editing-context";
import { ChatImageEditContext } from "@/features/chat/image/chat-image-edit-context";
import {
  ChatSessionSettings,
  ChatStarterPrompts,
} from "@/features/chat/session/chat-session-settings";
import {
  ChatTemporaryBadge,
  ChatTemporaryNotice,
  ChatTemporaryToggle,
} from "@/features/chat/session/chat-temporary";
import { ChatConversationSearch } from "@/features/chat/thread/chat-conversation-search";
import { projectOf, type Project } from "@/features/chat/projects/chat-projects-api";
import { ProjectContextPanel } from "@/features/chat/projects/project-context-panel";
import { ProjectConversationList } from "@/features/chat/projects/project-conversation-list";
import { useChatThreads } from "@/features/chat/runtime/chat-threads-context";
import type { ChatThreadController } from "@/features/chat/runtime/chat-thread-controller";
import { ChatModelPicker } from "./chat-model-picker";
import { useChatConversation } from "./use-chat-conversation";

/** A page state before or instead of the conversation: loading, unavailable or failed. */
function ChatPageState({
  title,
  failed,
  children,
}: {
  title: string;
  failed?: boolean;
  children: ReactNode;
}) {
  return (
    <>
      <AppShellHeader title={title} />
      <div role={failed ? "alert" : "status"} className="flex flex-col gap-3 p-6">
        {children}
      </div>
    </>
  );
}

export function ChatPage() {
  const ui = useAppTranslation();
  useAttachOnOpen();
  const { sessionId, projectId } = useParams({ strict: false });
  const { registry, routeError, retryRoute } = useChatThreads();
  const identity = useApplicationSession();
  const mainId = useAuiState((state) => state.threads.mainThreadId);
  const mainRemoteId = useAuiState(
    (state) =>
      state.threads.threadItems.find((item) => item.id === state.threads.mainThreadId)?.remoteId,
  );
  const controller = useSyncExternalStore(registry.subscribe, () => registry.get(mainId));
  const project = useQuery({
    ...getChatProjectOptions({ path: { projectId: projectId ?? "" } }),
    select: projectOf,
    enabled: !!projectId,
    retry: false,
  });
  const authority = JSON.stringify([
    identity.actorId,
    identity.authorizationVersion,
    identity.capabilities,
    identity.scopedCapabilities,
  ]);
  if (sessionId && routeError === sessionId)
    return (
      <ChatPageState title={ui("Chat")} failed>
        <p>{ui("Không tải được hội thoại.")}</p>
        <div>
          <Button prominence="secondary" onClick={retryRoute}>
            {ui("Thử lại")}
          </Button>
        </div>
      </ChatPageState>
    );
  // The server ID of a conversation created here arrives before the route changes, so promotion
  // never passes through this branch and keeps the composer mounted.
  if ((sessionId && mainRemoteId !== sessionId) || !controller)
    return (
      <ChatPageState title={ui("Chat")}>
        <p className="text-content-secondary">{ui("Đang tải hội thoại…")}</p>
      </ChatPageState>
    );
  if (projectId && !project.data)
    return (
      <ChatPageState title={ui("Dự án")} failed={project.isError}>
        {project.isError ? (
          <p>
            {ui("Dự án không khả dụng.")}{" "}
            <Button onClick={() => void project.refetch()}>{ui("Tải lại")}</Button>
          </p>
        ) : (
          <p>{ui("Đang tải dự án…")}</p>
        )}
      </ChatPageState>
    );
  return (
    <ChatConversation
      key={`${authority}:${mainId}`}
      controller={controller}
      project={projectId ? project.data : undefined}
    />
  );
}

function ChatConversation({
  controller,
  project,
}: {
  controller: ChatThreadController;
  project?: Project;
}) {
  const ui = useAppTranslation();
  const { runtime } = useChatThreads();
  const loadingHistory = useAuiState((s) => s.thread.isLoading);
  const conversation = useChatConversation(controller, project);
  const { state, session, headerSession, busy, tools } = conversation;

  if (state.unavailable)
    return (
      <ChatPageState title={ui("Chat")} failed>
        <p>{ui("Hội thoại không còn khả dụng.")}</p>
      </ChatPageState>
    );
  if (state.historyFailed && !session)
    return (
      <ChatPageState title={ui("Chat")} failed>
        <p>{ui("Không tải được hội thoại.")}</p>
        <div>
          <Button prominence="secondary" onClick={() => void controller.check()}>
            {ui("Thử lại")}
          </Button>
        </div>
      </ChatPageState>
    );
  if (loadingHistory && controller.remoteId)
    return (
      <ChatPageState title={ui("Chat")}>
        <p className="text-content-secondary">{ui("Đang tải hội thoại…")}</p>
      </ChatPageState>
    );
  return (
    <>
      <AppShellHeader
        title={headerSession?.title ?? (project ? ui("Dự án") : ui("Chat"))}
        actions={
          <div className="flex items-center gap-1">
            <ChatTemporaryBadge temporary={headerSession?.temporary ?? tools.temporary} />
            {!session && !project && (
              <ChatTemporaryToggle
                value={tools.temporary}
                disabled={busy}
                onChange={tools.selectTemporary}
              />
            )}
            <ChatConversationSearch key={headerSession?.id ?? "new"} />
            <ChatSessionSettings
              session={headerSession}
              busy={busy}
              onChange={async () => {
                conversation.model.select(undefined);
                await controller.check();
              }}
              deleteSession={() => runtime.threads.getItemById(controller.id).delete()}
              onDelete={() => controller.markUnavailable()}
              onShowMessage={conversation.showMessage}
            />
          </div>
        }
      />
      <div className="flex h-full min-h-0 flex-col">
        <ChatImageEditContext value={tools.imageEditing}>
          <ChatEditingContext value={conversation.editing}>
            <ChatConversationBody
              controller={controller}
              conversation={conversation}
              project={project}
            />
          </ChatEditingContext>
        </ChatImageEditContext>
      </div>
    </>
  );
}

/** The notices above the thread and the thread with its composer controls. */
function ChatConversationBody({
  controller,
  conversation,
  project,
}: {
  controller: ChatThreadController;
  conversation: ReturnType<typeof useChatConversation>;
  project?: Project;
}) {
  const ui = useAppTranslation();
  const { t } = useTranslation("chatStatus");
  const problemMessage = useProblemMessage();
  const navigate = useNavigate();
  const { state, session, headerSession, busy, tools, model, imageAvailability } = conversation;
  return (
    <>
      {conversation.versionsFailed && (
        <Alert variant="destructive">
          <AlertDescription>{ui("Không tải được phiên bản hoặc đánh giá.")}</AlertDescription>
          <AlertAction>
            <Button prominence="internal" size="sm" onClick={conversation.reloadVersions}>
              {ui("Tải lại")}
            </Button>
          </AlertAction>
        </Alert>
      )}
      {state.attachmentError && (
        <Alert variant="destructive">
          <AlertDescription>{problemMessage(state.attachmentError)}</AlertDescription>
          <AlertAction>
            <Button
              type="button"
              size="sm"
              prominence="internal"
              onClick={() => controller.setAttachmentError(undefined)}
            >
              {ui("Đóng")}
            </Button>
          </AlertAction>
        </Alert>
      )}
      <ChatThread
        sendDisabled={!conversation.persona}
        welcome={project && !session ? <ProjectContextPanel project={project} /> : undefined}
        afterComposer={
          (headerSession?.temporary ?? (tools.temporary && !session)) ? (
            <ChatTemporaryNotice
              onLeave={() => {
                tools.selectTemporary(false);
                void navigate({ to: "/" });
              }}
            />
          ) : project && !session ? (
            <ProjectConversationList projectId={project.id} />
          ) : undefined
        }
        starters={<ChatStarterPrompts personaId={session?.personaId} disabled={busy} />}
        composerMenu={
          <ChatComposerMenu
            disabled={busy}
            allowed={conversation.allowedTools}
            web={{
              sessionId: session?.id,
              modelId: model.choice.id,
              value: conversation.shownWebSearch,
              onChange: tools.selectWeb,
            }}
            research={
              conversation.research
                ? {
                    unsupported: conversation.research.unsupported,
                    value: tools.deepResearch,
                    onChange: tools.selectResearch,
                  }
                : undefined
            }
            image={{
              available: imageAvailability.data?.available === true,
              pending: imageAvailability.isPending,
              onRetry: imageAvailability.isError
                ? () => void imageAvailability.refetch()
                : undefined,
              value: conversation.shownImage,
              onChange: tools.selectImage,
            }}
            mcp={{
              selected: conversation.shownMcpServerIds,
              sessionId: session?.id,
              onChange: tools.selectMcpServers,
            }}
          />
        }
        modelPicker={
          <ChatModelPicker
            sessionId={controller.transport.session?.id}
            value={model.choice.id}
            onChange={model.select}
            effort={conversation.pinnedEffort}
            onEffortChange={conversation.pinEffort}
            disabled={busy}
          />
        }
        modelNotice={
          model.choice.fallback
            ? ui(
                "Mô hình đã chọn không khả dụng. Câu trả lời đang dùng mô hình mặc định mà bạn được phép sử dụng.",
              )
            : undefined
        }
        connection={state.connection}
        stopping={state.stopping}
        onStop={() => void controller.stop()}
        error={
          state.error
            ? typeof state.error === "string"
              ? t(state.error)
              : problemMessage(state.error)
            : undefined
        }
        onCheck={() => controller.check()}
        checking={state.checking}
      />
    </>
  );
}
