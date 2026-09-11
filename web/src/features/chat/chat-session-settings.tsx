import { useState } from "react";
import { useAui } from "@assistant-ui/react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { Link, useNavigate } from "@tanstack/react-router";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Select } from "@/components/ui/select";
import { ConfirmDialog } from "@/components/ui/confirm-dialog";
import { useApplicationSession } from "@/features/identity/application-session-context";
import { sameOriginMutationHeaders } from "@/lib/api";
import {
  deleteChatSession,
  renameChatSession,
  configureChatSession,
  getChatSharing,
  setChatSharing,
} from "@/lib/hey-api/sdk.gen";
import type { ChatSession } from "@/lib/hey-api/types.gen";
import { ChatDialog } from "./chat-dialog";
import { chatActionError } from "./chat-action-utils";
import { loadPersonas, loadProjects, sharingSchema } from "./chat-workspace-api";
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
  const navigate = useNavigate();
  const personas = useQuery({
    queryKey: ["chat-personas", actorId, authorizationVersion],
    queryFn: ({ signal }) => loadPersonas(signal),
  });
  const projects = useQuery({
    queryKey: ["chat-projects", actorId, authorizationVersion],
    queryFn: ({ signal }) => loadProjects(signal),
    enabled: !!session,
  });
  const [title, setTitle] = useState("");
  const [settingsOpen, setSettingsOpen] = useState(false);
  const [personaId, setPersona] = useState("");
  const [projectId, setProject] = useState("");
  const selected = personas.data?.find((persona) => persona.id === session?.personaId);
  return (
    <div className="flex flex-wrap items-center gap-2 border-b border-border-subtle px-4 py-2 sm:px-8">
      {session ? (
        <>
          <span
            className="mr-auto min-w-0 max-w-64 truncate text-sm font-medium"
            title={session.title}
          >
            {session.title}
          </span>
          <ChatDialog
            title="Đổi tên hội thoại"
            description="Tên mới được cập nhật trong lịch sử hội thoại."
            trigger={
              <Button size="sm" prominence="internal" onClick={() => setTitle(session.title)}>
                Đổi tên
              </Button>
            }
            onSubmit={async () => {
              await renameChatSession({
                path: { sessionId: session.id },
                body: { title },
                headers: sameOriginMutationHeaders,
                signal: AbortSignal.timeout(30000),
                throwOnError: true,
              });
              await onChange();
              await cache.invalidateQueries({ queryKey: chatSessionsKey });
            }}
          >
            <Input
              aria-label="Tên hội thoại"
              required
              maxLength={200}
              value={title}
              onChange={(e) => setTitle(e.target.value)}
            />
          </ChatDialog>
          <Button
            size="sm"
            prominence="internal"
            disabled={busy}
            onClick={() => {
              setPersona(session.personaId);
              setProject(session.projectId ?? "");
              setSettingsOpen(true);
            }}
          >
            {selected?.name ?? "Cấu hình"}
          </Button>
          <SharingDialog sessionId={session.id} />
          <ConfirmDialog
            title="Xóa hội thoại?"
            description="Hội thoại sẽ bị xóa khỏi lịch sử và liên kết chia sẻ. Câu trả lời đang chạy cũng sẽ dừng."
            confirmLabel="Xóa hội thoại"
            pendingLabel="Đang xóa…"
            errorMessage={chatActionError}
            trigger={
              <Button size="sm" prominence="internal">
                Xóa
              </Button>
            }
            onConfirm={async () => {
              await deleteChatSession({
                path: { sessionId: session.id },
                headers: sameOriginMutationHeaders,
                signal: AbortSignal.timeout(30000),
                throwOnError: true,
              });
              onDelete();
              await cache.invalidateQueries({ queryKey: chatSessionsKey });
              await cache.invalidateQueries({ queryKey: ["chat-project-sessions"] });
              await navigate({ to: "/" });
            }}
          />
          {settingsOpen && (
            <ChatDialog
              open
              onOpenChange={setSettingsOpen}
              title="Cấu hình hội thoại"
              description="Thay đổi áp dụng cho lượt tiếp theo. Lịch sử đã lưu giữ nguyên."
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
                  {!personas.data?.some((p) => p.id === personaId) && (
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
                  {projectId && !projects.data?.some((p) => p.id === projectId) && (
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
        </>
      ) : (
        <>
          <Link to="/assistants" className="text-sm underline">
            Chọn trợ lý
          </Link>
          <Link to="/projects" className="text-sm underline">
            Mở dự án
          </Link>
        </>
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

function SharingDialog({ sessionId }: { sessionId: string }) {
  const [open, setOpen] = useState(false);
  const [enabled, setEnabled] = useState<boolean>();
  const [copied, setCopied] = useState(false);
  const cache = useQueryClient();
  const sharing = useQuery({
    queryKey: ["chat-sharing", sessionId],
    queryFn: async ({ signal }) =>
      sharingSchema.parse(
        (await getChatSharing({ path: { sessionId }, signal, throwOnError: true })).data,
      ),
    enabled: open,
    staleTime: 0,
  });
  const link = new URL(`/shared/${sessionId}`, window.location.origin).toString();
  return (
    <ChatDialog
      open={open}
      onOpenChange={(next) => {
        setOpen(next);
        setCopied(false);
        setEnabled(undefined);
      }}
      title="Chia sẻ hội thoại"
      description="Thành viên đang hoạt động trong cùng Tenant có liên kết được đọc nhánh đang chọn. Người đọc cần đăng nhập."
      trigger={
        <Button size="sm" prominence="internal">
          Chia sẻ
        </Button>
      }
      onSubmit={
        sharing.data && !sharing.isFetching && !sharing.isError
          ? async () => {
              const { data } = await setChatSharing({
                path: { sessionId },
                body: { enabled: enabled ?? sharing.data.enabled, revision: sharing.data.revision },
                headers: sameOriginMutationHeaders,
                signal: AbortSignal.timeout(30000),
                throwOnError: true,
              });
              cache.setQueryData(["chat-sharing", sessionId], sharingSchema.parse(data));
            }
          : undefined
      }
    >
      {sharing.isPending && <p role="status">Đang tải quyền chia sẻ…</p>}
      {sharing.isError && (
        <p role="alert">
          Không tải được quyền chia sẻ.{" "}
          <Button type="button" prominence="internal" onClick={() => void sharing.refetch()}>
            Tải lại
          </Button>
        </p>
      )}
      {sharing.data && !sharing.isError && (
        <>
          <p role="status">Hiện tại: {sharing.data.enabled ? "Đang chia sẻ" : "Riêng tư"}</p>
          <Select
            aria-label="Thay đổi quyền chia sẻ"
            value={(enabled ?? sharing.data.enabled) ? "shared" : "private"}
            onChange={(e) => setEnabled(e.target.value === "shared")}
          >
            <option value="private">Riêng tư — thu hồi liên kết</option>
            <option value="shared">Chia sẻ trong Tenant</option>
          </Select>
          {sharing.data.enabled && (
            <>
              <label className="block space-y-1">
                <span>Liên kết chỉ đọc</span>
                <Input readOnly value={link} onFocus={(e) => e.target.select()} />
              </label>
              <Button
                type="button"
                prominence="secondary"
                onClick={() => {
                  void navigator.clipboard
                    .writeText(link)
                    .then(() => setCopied(true))
                    .catch(() => setCopied(false));
                }}
              >
                {copied ? "Đã sao chép" : "Sao chép liên kết"}
              </Button>
              <p className="text-xs text-content-muted">
                Nếu không sao chép được, chọn liên kết ở ô trên để sao chép thủ công.
              </p>
            </>
          )}
        </>
      )}
    </ChatDialog>
  );
}
