import { useAppTranslation } from "@/i18n/use-app-translation";
import { useEffect, useState } from "react";
import { useMatch, useNavigate, useParams } from "@tanstack/react-router";
import { useAui } from "@assistant-ui/react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { Button } from "@/components/ui/button";
import { NativeSelect } from "@/components/ui/native-select";
import { configureChatSession } from "@/lib/hey-api/sdk.gen";
import type { ChatSession } from "@/lib/hey-api/types.gen";
import { FormDialog } from "@/components/composites/form-dialog";
import { personasOptions } from "@/features/chat/chat-personas-api";
import { projectsOptions } from "@/features/chat/projects/chat-projects-api";
import { ChatBranchOrigin } from "@/features/chat/thread/chat-branch-action";
import { ChatSessionFiles } from "./chat-session-files";
import { ChatSessionMenu } from "./chat-session-menu";
import { SharingDialog } from "./chat-sharing-dialog";
import { waitForChatFile } from "@/features/library/files";
import { composerAttachment } from "@/features/chat/composer/use-composer-file-selection";
import { useRefreshChatSessions } from "@/features/chat/runtime/chat-threads-context";

export function ChatSessionSettings({
  session,
  busy,
  onChange,
  deleteSession,
  onDelete,
  onShowMessage,
}: {
  session?: ChatSession;
  busy: boolean;
  onChange: () => Promise<void>;
  deleteSession?: () => Promise<void>;
  onDelete: () => void;
  onShowMessage?: (messageId: string) => Promise<boolean>;
}) {
  const ui = useAppTranslation();

  const cache = useQueryClient();
  const refreshSessions = useRefreshChatSessions();
  const [settingsOpen, setSettingsOpen] = useState(false);
  const [personaId, setPersona] = useState("");
  const [projectId, setProject] = useState("");
  const personas = useQuery({
    ...personasOptions(),
    enabled: settingsOpen,
  });
  const projects = useQuery({
    ...projectsOptions(),
    enabled: settingsOpen,
  });
  if (!session) return null;
  return (
    <div className="flex shrink-0 items-center gap-1">
      <ChatBranchOrigin session={session} />
      <ChatSessionFiles sessionId={session.id} onShowMessage={onShowMessage} />
      <SharingDialog sessionId={session.id} />
      <ChatSessionMenu
        session={session}
        busy={busy}
        onChange={onChange}
        deleteSession={deleteSession}
        onDelete={onDelete}
        onConfigure={() => {
          setPersona(session.personaId);
          setProject(session.projectId ?? "");
          setSettingsOpen(true);
        }}
      />
      {settingsOpen && (
        <FormDialog
          open
          onOpenChange={setSettingsOpen}
          title={ui("Cấu hình hội thoại")}
          description={ui("Thay đổi áp dụng cho lượt tiếp theo. Lịch sử đã lưu giữ nguyên.")}
          submitDisabled={
            busy ||
            personas.isFetching ||
            projects.isFetching ||
            personas.isError ||
            projects.isError
          }
          onSubmit={async () => {
            await configureChatSession({
              path: { sessionId: session.id },
              body: { personaId, projectId: projectId || null },
              signal: AbortSignal.timeout(30000),
            });
            await onChange();
            await cache.invalidateQueries({ queryKey: ["chat-models"] });
            await refreshSessions(session.id);
          }}
        >
          <label className="block space-y-1">
            <span>{ui("Trợ lý")}</span>
            <NativeSelect value={personaId} onChange={(e) => setPersona(e.target.value)}>
              {personas.data?.map((persona) => (
                <option key={persona.id} value={persona.id}>
                  {persona.name}
                </option>
              ))}
              {personas.isPending && <option value={personaId}>{ui("Đang tải trợ lý…")}</option>}
              {personas.data && !personas.data.some((p) => p.id === personaId) && (
                <option value={personaId}>{ui("Trợ lý không còn khả dụng")}</option>
              )}
            </NativeSelect>
          </label>
          <label className="block space-y-1">
            <span>{ui("Dự án")}</span>
            <NativeSelect value={projectId} onChange={(e) => setProject(e.target.value)}>
              <option value="">{ui("Ngoài dự án")}</option>
              {projects.data?.map((project) => (
                <option key={project.id} value={project.id}>
                  {project.name}
                </option>
              ))}
              {projectId && projects.isPending && (
                <option value={projectId}>{ui("Đang tải dự án…")}</option>
              )}
              {projectId && projects.data && !projects.data.some((p) => p.id === projectId) && (
                <option value={projectId}>{ui("Dự án không còn khả dụng")}</option>
              )}
            </NativeSelect>
          </label>
          {(personas.isError || projects.isError) && (
            <p role="alert">
              {ui("Không tải đủ cấu hình.")}{" "}
              <Button
                type="button"
                prominence="internal"
                onClick={() => {
                  void personas.refetch();
                  void projects.refetch();
                }}
              >
                {ui("Tải lại")}
              </Button>
            </p>
          )}
        </FormDialog>
      )}
    </div>
  );
}

const seededSessions = new Set<string>();

export function ChatStarterPrompts({
  personaId,
  disabled,
}: {
  personaId?: string;
  disabled: boolean;
}) {
  const aui = useAui();
  const personas = useQuery({
    ...personasOptions(),
  });
  const persona = personaId
    ? personas.data?.find((p) => p.id === personaId)
    : personas.data?.find((p) => p.builtin);
  const navigate = useNavigate();
  const { sessionId } = useParams({ strict: false });
  // A conversation carries the question and the list of files the file preview may send.
  const { ask, attach } =
    useMatch({ from: "/_authenticated/_chat/chat/$sessionId", shouldThrow: false })?.search ?? {};
  /**
   * What a new conversation was opened with: a question from an agent's detail view, and the library files a
   * question in the file preview was about. Every file is attached before the question is sent, so the answer
   * is given about them; dropping both search values keeps a reload from asking twice.
   */
  useEffect(() => {
    const attachments = attach ?? [];
    if (
      (!ask && attachments.length === 0) ||
      !sessionId ||
      disabled ||
      seededSessions.has(sessionId)
    )
      return;
    seededSessions.add(sessionId);
    const composer = aui.thread.composer();
    void (async () => {
      let attached = true;
      for (const id of attachments)
        try {
          const file = await waitForChatFile(id, AbortSignal.timeout(120_000));
          await composer.addAttachment(composerAttachment(file));
        } catch {
          attached = false;
        }
      if (!ask) return;
      composer.setText(ask);
      // send() appends the question to the thread at once; from here the thread's own error and retry handle
      // it. A file that never arrived leaves the question in the composer instead, so it is not asked about
      // nothing.
      if (attached) composer.send();
    })().finally(() => navigate({ to: "/chat/$sessionId", params: { sessionId }, replace: true }));
  }, [ask, attach, sessionId, disabled, aui, navigate]);
  return (
    <div className="mt-5 flex flex-wrap justify-center gap-2">
      {persona?.starterPrompts.map((text, index) => (
        <Button
          key={index}
          prominence="secondary"
          disabled={disabled}
          className="h-auto max-w-full whitespace-normal text-left"
          onClick={() => aui.thread.composer().setText(text)}
        >
          {text}
        </Button>
      ))}
    </div>
  );
}
