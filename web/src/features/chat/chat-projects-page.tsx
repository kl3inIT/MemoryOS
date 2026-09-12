import { useRef, useState } from "react";
import { DropdownMenu } from "radix-ui";
import { useInfiniteQuery, useQuery, useQueryClient } from "@tanstack/react-query";
import { Link, useNavigate } from "@tanstack/react-router";
import { Folder, MoreHorizontal, Pencil, Plus, Settings2, Trash2, X } from "lucide-react";
import { AppShell } from "@/components/app-shell/app-shell";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { IconButton } from "@/components/ui/icon-button";
import { ConfirmDialog } from "@/components/ui/confirm-dialog";
import { ThreadList } from "@/components/assistant-ui/elements/thread-list";
import { useApplicationSession } from "@/features/identity/application-session-context";
import { sameOriginMutationHeaders } from "@/lib/api";
import {
  createChatProject,
  updateChatProject,
  deleteChatProject,
  listProjectChatSessions,
  getChatFile,
} from "@/lib/hey-api/sdk.gen";
import { chatSessionsKey } from "./chat-api";
import { ChatDialog } from "./chat-dialog";
import { chatField, chatActionError } from "./chat-action-utils";
import { loadProjects, projectSchema, type Project } from "./chat-workspace-api";
import { ChatSessionRow } from "./chat-session-row";
import { ChatFilePicker } from "./chat-file-picker";
import { chatFileSchema } from "./chat-files";
import { ChatFilePart } from "./chat-attachments";
import { fileReference } from "./chat-files";

export function ChatProjectsPage() {
  const { actorId, authorizationVersion } = useApplicationSession();
  const [creating, setCreating] = useState(false);
  const projects = useQuery({
    queryKey: ["chat-projects", actorId, authorizationVersion],
    queryFn: ({ signal }) => loadProjects(signal),
  });
  return (
    <AppShell pageTitle="Dự án">
      <div className="mx-auto w-full max-w-3xl overflow-y-auto px-6 py-10">
        <div className="mb-8 flex items-center justify-between gap-4">
          <h1 className="text-2xl font-medium">Dự án</h1>
          <Button onClick={() => setCreating(true)}>
            <Plus className="size-4" />
            Tạo dự án
          </Button>
        </div>
        {projects.isPending && <p role="status">Đang tải dự án…</p>}
        {projects.isError && (
          <p role="alert">
            Không tải được dự án. <Button onClick={() => void projects.refetch()}>Tải lại</Button>
          </p>
        )}
        {projects.data?.length === 0 && (
          <div className="flex flex-col items-center gap-3 py-16 text-center">
            <Folder className="size-10 text-content-muted" />
            <h2 className="text-lg font-medium">Một nơi cho công việc của bạn</h2>
            <p className="max-w-sm text-sm text-content-secondary">
              Gom hội thoại và dùng chung hướng dẫn trong một dự án.
            </p>
            <Button prominence="secondary" onClick={() => setCreating(true)}>
              Tạo dự án đầu tiên
            </Button>
          </div>
        )}
        <div className="divide-y divide-border-subtle">
          {projects.data?.map((project) => (
            <Link
              key={project.id}
              to="/projects/$projectId"
              params={{ projectId: project.id }}
              className="flex items-center gap-3 rounded-xl p-4 hover:bg-surface-sunken focus-visible:ring-2 focus-visible:ring-ring"
            >
              <Folder className="size-5 shrink-0 text-content-muted" />
              <div className="min-w-0">
                <h2 className="truncate font-medium">{project.name}</h2>
                {project.description && (
                  <p className="mt-1 line-clamp-2 text-sm text-content-secondary">
                    {project.description}
                  </p>
                )}
              </div>
            </Link>
          ))}
        </div>
        {creating && <ProjectEditor onClose={() => setCreating(false)} />}
      </div>
    </AppShell>
  );
}

export function ProjectContextPanel({ project }: { project: Project }) {
  const [editing, setEditing] = useState(false);
  const [deleting, setDeleting] = useState(false);
  const menuTrigger = useRef<HTMLButtonElement>(null);
  const cache = useQueryClient();
  const navigate = useNavigate();
  return (
    <div className="space-y-5 pt-4 text-left">
      <div className="flex items-center gap-3">
        <Folder className="size-7 shrink-0 text-content-muted" />
        <h1 className="min-w-0 flex-1 break-words text-2xl font-medium">{project.name}</h1>
        <DropdownMenu.Root>
          <DropdownMenu.Trigger asChild>
            <IconButton
              ref={menuTrigger}
              size="sm"
              prominence="internal"
              aria-label="Thao tác dự án"
            >
              <MoreHorizontal />
            </IconButton>
          </DropdownMenu.Trigger>
          <DropdownMenu.Portal>
            <DropdownMenu.Content
              align="end"
              sideOffset={5}
              className="z-50 min-w-48 rounded-xl border border-border-subtle bg-surface-overlay p-1.5 shadow-md"
              onCloseAutoFocus={(event) => {
                if (editing || deleting) event.preventDefault();
              }}
            >
              <DropdownMenu.Item
                className="flex cursor-default items-center gap-2 rounded-lg px-3 py-2 text-sm outline-none data-[highlighted]:bg-surface-sunken"
                onSelect={() => setEditing(true)}
              >
                <Pencil className="size-4" /> Chỉnh sửa dự án
              </DropdownMenu.Item>
              <DropdownMenu.Separator className="my-1 border-t border-border-subtle" />
              <DropdownMenu.Item
                className="flex cursor-default items-center gap-2 rounded-lg px-3 py-2 text-sm text-status-danger-content outline-none data-[highlighted]:bg-surface-sunken"
                onSelect={() => setDeleting(true)}
              >
                <Trash2 className="size-4" /> Xóa dự án
              </DropdownMenu.Item>
            </DropdownMenu.Content>
          </DropdownMenu.Portal>
        </DropdownMenu.Root>
        <ConfirmDialog
          open={deleting}
          onOpenChange={setDeleting}
          restoreFocusRef={menuTrigger}
          title="Xóa dự án?"
          description="Các hội thoại được chuyển ra ngoài dự án và vẫn giữ nguyên lịch sử."
          confirmLabel="Xóa dự án"
          pendingLabel="Đang xóa…"
          errorMessage={chatActionError}
          onConfirm={async () => {
            await deleteChatProject({
              path: { projectId: project.id },
              query: { revision: project.revision },
              headers: sameOriginMutationHeaders,
              signal: AbortSignal.timeout(30000),
              throwOnError: true,
            });
            await Promise.all([
              cache.invalidateQueries({ queryKey: ["chat-projects"] }),
              cache.invalidateQueries({ queryKey: ["chat-project-sessions"] }),
              cache.invalidateQueries({ queryKey: chatSessionsKey }),
            ]);
            await navigate({ to: "/" });
          }}
        />
      </div>
      {project.description && (
        <p className="whitespace-pre-wrap text-sm text-content-secondary">{project.description}</p>
      )}
      <button
        type="button"
        className="flex w-full items-start gap-3 rounded-xl border border-border-subtle p-4 text-left hover:bg-surface-sunken focus-visible:ring-2 focus-visible:ring-ring"
        onClick={() => setEditing(true)}
      >
        <Settings2 className="mt-0.5 size-4 shrink-0 text-content-muted" />
        <span className="min-w-0">
          <span className="block font-medium">Hướng dẫn dự án</span>
          <span className="mt-1 block line-clamp-3 whitespace-pre-wrap text-sm text-content-secondary">
            {project.instructions ||
              "Thêm hướng dẫn để các cuộc trò chuyện hiểu công việc của bạn."}
          </span>
        </span>
      </button>
      <ProjectFiles project={project} />
      {editing && <ProjectEditor project={project} onClose={() => setEditing(false)} />}
    </div>
  );
}

function ProjectFiles({ project }: { project: Project }) {
  const cache = useQueryClient();
  const { actorId, authorizationVersion } = useApplicationSession();
  const [busy, setBusy] = useState(false);
  const updating = useRef(false);
  const [error, setError] = useState<string>();
  const ids = project.fileIds ?? [];
  const files = useQuery({
    queryKey: ["project-files", actorId, authorizationVersion, project.id, ids],
    queryFn: ({ signal }) =>
      Promise.all(
        ids.map(async (fileId) => {
          const result = await getChatFile({ path: { fileId }, signal });
          if (result.response?.status === 404) return { fileId, file: null };
          if (result.error) throw result.error;
          return { fileId, file: chatFileSchema.parse(result.data) };
        }),
      ),
  });
  async function update(fileIds: string[]) {
    if (updating.current) return;
    updating.current = true;
    setBusy(true);
    setError(undefined);
    try {
      await updateChatProject({
        path: { projectId: project.id },
        query: { revision: project.revision },
        body: {
          name: project.name,
          description: project.description ?? "",
          instructions: project.instructions,
          fileIds,
        },
        headers: sameOriginMutationHeaders,
        signal: AbortSignal.timeout(30000),
        throwOnError: true,
      });
      await Promise.all([
        cache.invalidateQueries({ queryKey: ["chat-project"] }),
        cache.invalidateQueries({ queryKey: ["chat-projects"] }),
      ]);
    } catch (cause) {
      setError(chatActionError(cause));
    } finally {
      updating.current = false;
      setBusy(false);
    }
  }
  return (
    <section aria-label="Tệp dự án" className="space-y-3">
      <div className="flex items-center justify-between gap-3">
        <h2 className="font-medium">Tệp</h2>
        <ChatFilePicker
          selected={ids}
          disabled={busy}
          onSelect={(next) => void update(next)}
          trigger={
            <Button type="button" size="sm" prominence="secondary" disabled={busy}>
              <Plus className="size-4" />
              Thêm tệp
            </Button>
          }
        />
      </div>
      {ids.length === 0 && (
        <p className="text-sm text-content-muted">
          Thêm tài liệu dùng chung cho các hội thoại trong dự án.
        </p>
      )}
      {(error || files.isError) && (
        <p role="alert" className="text-sm">
          {error ?? "Không tải được tệp dự án."}
        </p>
      )}
      {files.data?.map(({ fileId, file }) => (
        <div key={fileId} className="flex min-w-0 items-center justify-between gap-2">
          {file ? (
            <ChatFilePart
              filename={file.filename}
              mimeType={file.mediaType}
              data={fileReference(file.id)}
            />
          ) : (
            <span className="text-sm text-content-muted">Tệp không còn khả dụng</span>
          )}
          <IconButton
            size="sm"
            prominence="internal"
            disabled={busy}
            aria-label={`Gỡ ${file?.filename ?? "tệp không còn khả dụng"} khỏi dự án`}
            onClick={() => void update(ids.filter((id) => id !== fileId))}
          >
            <X />
          </IconButton>
        </div>
      ))}
    </section>
  );
}

export function ProjectConversationList({
  projectId,
  compact = false,
  onNavigate,
}: {
  projectId: string;
  compact?: boolean;
  onNavigate?: () => void;
}) {
  const { actorId, authorizationVersion } = useApplicationSession();
  const sessions = useInfiniteQuery({
    queryKey: ["chat-project-sessions", actorId, authorizationVersion, projectId],
    initialPageParam: 0,
    queryFn: async ({ signal, pageParam }) =>
      (
        await listProjectChatSessions({
          path: { projectId },
          query: { offset: pageParam, limit: 30 },
          signal,
          throwOnError: true,
        })
      ).data,
    getNextPageParam: (last, pages) =>
      last.length === 30 && pages.length * 30 <= 10000 ? pages.length * 30 : undefined,
  });
  return (
    <section
      className={compact ? "ml-4 border-l border-border-subtle pl-2" : "mt-6"}
      aria-label="Hội thoại trong dự án"
    >
      {!compact && (
        <h2 className="mb-3 text-sm font-medium text-content-secondary">Hội thoại gần đây</h2>
      )}
      {sessions.isPending && (
        <p role="status" className="px-2 py-2 text-sm text-content-muted">
          Đang tải hội thoại…
        </p>
      )}
      {sessions.isError && (
        <p role="alert" className="text-sm">
          Không tải được hội thoại.{" "}
          <Button size="sm" onClick={() => void sessions.refetch()}>
            Tải lại
          </Button>
        </p>
      )}
      <ThreadList label="Hội thoại dự án">
        {sessions.data?.pages.flat().map((session) => (
          <ChatSessionRow
            key={session.id}
            session={session}
            onNavigate={onNavigate}
            showTime={!compact}
          />
        ))}
      </ThreadList>
      {sessions.data?.pages[0]?.length === 0 && (
        <p className="px-2 py-3 text-sm text-content-muted">
          Chưa có hội thoại{compact ? "." : ". Gửi câu hỏi ở trên để bắt đầu."}
        </p>
      )}
      {sessions.hasNextPage && (
        <Button
          size="sm"
          prominence="internal"
          pending={sessions.isFetchingNextPage}
          onClick={() => void sessions.fetchNextPage()}
        >
          Xem thêm hội thoại
        </Button>
      )}
    </section>
  );
}

export function ProjectEditor({ project, onClose }: { project?: Project; onClose: () => void }) {
  const cache = useQueryClient();
  const navigate = useNavigate();
  const [name, setName] = useState(project?.name ?? "");
  const [instructions, setInstructions] = useState(project?.instructions ?? "");
  const fileIds = project?.fileIds ?? [];
  return (
    <ChatDialog
      open
      onOpenChange={(open) => {
        if (!open) onClose();
      }}
      title={project ? "Chỉnh sửa dự án" : "Tạo dự án"}
      description={
        project
          ? "Hướng dẫn áp dụng cho những lượt tiếp theo trong dự án."
          : "Đặt tên cho công việc bạn muốn tập trung."
      }
      submitLabel={project ? "Lưu" : "Tạo dự án"}
      onSubmit={async () => {
        const body = {
          name: name.trim(),
          description: project?.description ?? "",
          instructions,
          fileIds,
        };
        if (project)
          await updateChatProject({
            path: { projectId: project.id },
            query: { revision: project.revision },
            body,
            headers: sameOriginMutationHeaders,
            signal: AbortSignal.timeout(30000),
            throwOnError: true,
          });
        else {
          const { data } = await createChatProject({
            body,
            headers: sameOriginMutationHeaders,
            signal: AbortSignal.timeout(30000),
            throwOnError: true,
          });
          const created = projectSchema.parse(data);
          await cache.invalidateQueries({ queryKey: ["chat-projects"] });
          onClose();
          await navigate({
            to: "/projects/$projectId",
            params: { projectId: created.id },
          });
          return;
        }
        await Promise.all([
          cache.invalidateQueries({ queryKey: ["chat-projects"] }),
          cache.invalidateQueries({ queryKey: ["chat-project"] }),
        ]);
      }}
    >
      <label className="block space-y-1">
        <span>Tên dự án</span>
        <Input
          autoFocus
          required
          maxLength={200}
          value={name}
          onChange={(event) => setName(event.target.value)}
        />
      </label>
      {project && (
        <label className="block space-y-1">
          <span>Hướng dẫn dự án</span>
          <textarea
            className={chatField}
            maxLength={32000}
            rows={6}
            value={instructions}
            onChange={(event) => setInstructions(event.target.value)}
          />
        </label>
      )}
    </ChatDialog>
  );
}
