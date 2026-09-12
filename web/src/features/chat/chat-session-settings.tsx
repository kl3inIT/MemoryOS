import { useState } from "react";
import { useAui } from "@assistant-ui/react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { Button } from "@/components/ui/button";
import { Select } from "@/components/ui/select";
import { useApplicationSession } from "@/features/identity/application-session-context";
import { sameOriginMutationHeaders } from "@/lib/api";
import { configureChatSession } from "@/lib/hey-api/sdk.gen";
import type { ChatSession } from "@/lib/hey-api/types.gen";
import { ChatDialog } from "./chat-dialog";
import { loadPersonas, loadProjects } from "./chat-workspace-api";
import { ChatSessionMenu } from "./chat-session-menu";
import { SharingDialog } from "./chat-sharing-dialog";
import { chatSessionsKey } from "./chat-api";

export function ChatSessionSettings({
  session,
  busy,
  onChange,
  onDelete,
}: {
  session?: ChatSession;
  busy: boolean;
  onChange: () => Promise<void>;
  onDelete: () => void;
}) {
  const { actorId, authorizationVersion } = useApplicationSession();
  const cache = useQueryClient();
  const [settingsOpen, setSettingsOpen] = useState(false);
  const [personaId, setPersona] = useState("");
  const [projectId, setProject] = useState("");
  const personas = useQuery({
    queryKey: ["chat-personas", actorId, authorizationVersion],
    queryFn: ({ signal }) => loadPersonas(signal),
    enabled: settingsOpen,
  });
  const projects = useQuery({
    queryKey: ["chat-projects", actorId, authorizationVersion],
    queryFn: ({ signal }) => loadProjects(signal),
    enabled: settingsOpen,
  });
  if (!session) return null;
  return (
    <div className="flex shrink-0 items-center gap-1">
      <SharingDialog sessionId={session.id} />
      <ChatSessionMenu
        session={session}
        busy={busy}
        onChange={onChange}
        onDelete={onDelete}
        onConfigure={() => {
          setPersona(session.personaId);
          setProject(session.projectId ?? "");
          setSettingsOpen(true);
        }}
      />
      {settingsOpen && (
        <ChatDialog
          open
          onOpenChange={setSettingsOpen}
          title="Cấu hình hội thoại"
          description="Thay đổi áp dụng cho lượt tiếp theo. Lịch sử đã lưu giữ nguyên."
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
              headers: sameOriginMutationHeaders,
              signal: AbortSignal.timeout(30000),
              throwOnError: true,
            });
            await onChange();
            await cache.invalidateQueries({ queryKey: ["chat-models"] });
            await cache.invalidateQueries({ queryKey: ["chat-project-sessions"] });
            await cache.invalidateQueries({ queryKey: chatSessionsKey });
          }}
        >
          <label className="block space-y-1">
            <span>Trợ lý</span>
            <Select value={personaId} onChange={(e) => setPersona(e.target.value)}>
              {personas.data?.map((persona) => (
                <option key={persona.id} value={persona.id}>
                  {persona.name}
                </option>
              ))}
              {personas.isPending && <option value={personaId}>Đang tải trợ lý…</option>}
              {personas.data && !personas.data.some((p) => p.id === personaId) && (
                <option value={personaId}>Trợ lý không còn khả dụng</option>
              )}
            </Select>
          </label>
          <label className="block space-y-1">
            <span>Dự án</span>
            <Select value={projectId} onChange={(e) => setProject(e.target.value)}>
              <option value="">Ngoài dự án</option>
              {projects.data?.map((project) => (
                <option key={project.id} value={project.id}>
                  {project.name}
                </option>
              ))}
              {projectId && projects.isPending && (
                <option value={projectId}>Đang tải dự án…</option>
              )}
              {projectId && projects.data && !projects.data.some((p) => p.id === projectId) && (
                <option value={projectId}>Dự án không còn khả dụng</option>
              )}
            </Select>
          </label>
          {(personas.isError || projects.isError) && (
            <p role="alert">
              Không tải đủ cấu hình.{" "}
              <Button
                type="button"
                prominence="internal"
                onClick={() => {
                  void personas.refetch();
                  void projects.refetch();
                }}
              >
                Tải lại
              </Button>
            </p>
          )}
        </ChatDialog>
      )}
    </div>
  );
}

export function ChatStarterPrompts({
  personaId,
  disabled,
}: {
  personaId?: string;
  disabled: boolean;
}) {
  const aui = useAui();
  const { actorId, authorizationVersion } = useApplicationSession();
  const personas = useQuery({
    queryKey: ["chat-personas", actorId, authorizationVersion],
    queryFn: ({ signal }) => loadPersonas(signal),
  });
  const persona = personaId
    ? personas.data?.find((p) => p.id === personaId)
    : personas.data?.find((p) => p.builtin);
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
