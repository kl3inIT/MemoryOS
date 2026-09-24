import { useAppTranslation } from "@/i18n/use-app-translation";
import { useRef, useState } from "react";
import { DropdownMenu } from "radix-ui";
import { useInfiniteQuery, useQuery, useQueryClient } from "@tanstack/react-query";
import { Link, useNavigate } from "@tanstack/react-router";
import { ChevronDown, Folder, MoreHorizontal, Pencil, Plus, Trash2, X } from "lucide-react";
import { AppShell } from "@/components/app-shell/app-shell";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { IconButton } from "@/components/ui/icon-button";
import { ConfirmDialog } from "@/components/ui/confirm-dialog";
import { Collapsible, CollapsibleContent, CollapsibleTrigger } from "@/components/ui/collapsible";
import { Popover, PopoverContent, PopoverTrigger } from "@/components/ui/popover";
import { agentIcons, agentIconTones } from "@/features/agents/agent-icons";
import { cn } from "@/lib/utils";
import { ThreadList } from "@/components/assistant-ui/elements/thread-list";
import { useApplicationSession } from "@/features/identity/application-session-context";
import { isNotFound, sameOriginMutationHeaders } from "@/lib/api";
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

/** The project's chosen topic icon, or the plain folder when none was picked. */
export function ProjectIcon({
  iconName,
  className,
}: {
  iconName?: string | null;
  className?: string;
}) {
  const Icon = (iconName && agentIcons[iconName]) || Folder;
  return <Icon className={className} aria-hidden="true" />;
}

export function ChatProjectsPage() {
  const ui = useAppTranslation();

  const { actorId, authorizationVersion } = useApplicationSession();
  const [creating, setCreating] = useState(false);
  const projects = useQuery({
    queryKey: ["chat-projects", actorId, authorizationVersion],
    queryFn: ({ signal }) => loadProjects(signal),
  });
  return (
    <AppShell pageTitle={ui("Dự án")}>
      <div className="mx-auto w-full max-w-3xl overflow-y-auto px-6 py-10">
        <div className="mb-8 flex items-center justify-between gap-4">
          <h1 className="font-heading-h2 text-content-primary">{ui("Dự án")}</h1>
          <Button onClick={() => setCreating(true)}>
            <Plus className="size-4" />
            {ui("Tạo dự án")}
          </Button>
        </div>
        {projects.isPending && <p role="status">{ui("Đang tải dự án…")}</p>}
        {projects.isError && (
          <p role="alert">
            {ui("Không tải được dự án.")}{" "}
            <Button onClick={() => void projects.refetch()}>{ui("Tải lại")}</Button>
          </p>
        )}
        {projects.data?.length === 0 && (
          <div className="flex flex-col items-center gap-3 py-16 text-center">
            <Folder className="size-10 text-content-muted" />
            <h2 className="text-lg font-medium">{ui("Một nơi cho công việc của bạn")}</h2>
            <p className="max-w-sm text-sm text-content-secondary">
              {ui("Gom hội thoại và dùng chung hướng dẫn trong một dự án.")}
            </p>
            <Button prominence="secondary" onClick={() => setCreating(true)}>
              {ui("Tạo dự án đầu tiên")}
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
              <ProjectIcon
                iconName={project.iconName}
                className="size-5 shrink-0 text-content-muted"
              />
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
  const ui = useAppTranslation();

  const [editing, setEditing] = useState(false);
  const [deleting, setDeleting] = useState(false);
  const menuTrigger = useRef<HTMLButtonElement>(null);
  const cache = useQueryClient();
  const navigate = useNavigate();
  return (
    <div className="space-y-5 pt-4 text-left">
      <div className="flex items-center gap-3">
        <ProjectIcon iconName={project.iconName} className="size-7 shrink-0 text-content-muted" />
        <h1 className="min-w-0 flex-1 break-words font-heading-h2 text-content-primary">
          {project.name}
        </h1>
        <DropdownMenu.Root>
          <DropdownMenu.Trigger asChild>
            <IconButton
              ref={menuTrigger}
              size="sm"
              prominence="internal"
              aria-label={ui("Thao tác dự án")}
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
                <Pencil className="size-4" /> {ui("Chỉnh sửa dự án")}
              </DropdownMenu.Item>
              <DropdownMenu.Separator className="my-1 border-t border-border-subtle" />
              <DropdownMenu.Item
                className="flex cursor-default items-center gap-2 rounded-lg px-3 py-2 text-sm text-status-danger-content outline-none data-[highlighted]:bg-surface-sunken"
                onSelect={() => setDeleting(true)}
              >
                <Trash2 className="size-4" /> {ui("Xóa dự án")}
              </DropdownMenu.Item>
            </DropdownMenu.Content>
          </DropdownMenu.Portal>
        </DropdownMenu.Root>
        <ConfirmDialog
          open={deleting}
          onOpenChange={setDeleting}
          restoreFocusRef={menuTrigger}
          title={ui("Xóa dự án?")}
          description={ui("Các hội thoại được chuyển ra ngoài dự án và vẫn giữ nguyên lịch sử.")}
          confirmLabel={ui("Xóa dự án")}
          pendingLabel={ui("Đang xóa…")}
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
      <ProjectFiles project={project} />
      {editing && <ProjectEditor project={project} onClose={() => setEditing(false)} />}
    </div>
  );
}

function ProjectFiles({ project }: { project: Project }) {
  const ui = useAppTranslation();

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
          try {
            const { data } = await getChatFile({ path: { fileId }, signal });
            return { fileId, file: chatFileSchema.parse(data) };
          } catch (error) {
            if (isNotFound(error)) return { fileId, file: null };
            throw error;
          }
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
    <section aria-label={ui("Tệp dự án")} className="space-y-3">
      <Collapsible>
        <div className="flex items-center justify-between gap-3">
          <CollapsibleTrigger className="group flex min-w-0 items-center gap-2 rounded-lg px-1 py-1 text-left outline-none focus-visible:ring-2 focus-visible:ring-ring">
            <ChevronDown className="size-4 shrink-0 text-content-muted transition-transform group-data-[state=closed]:-rotate-90" />
            <h2 className="font-medium">
              {ids.length > 0 ? ui("Tệp ({{count}})", { count: ids.length }) : ui("Tệp")}
            </h2>
          </CollapsibleTrigger>
          <ChatFilePicker
            selected={ids}
            disabled={busy}
            onSelect={(next) => void update(next)}
            trigger={
              <Button type="button" size="sm" prominence="secondary" disabled={busy}>
                <Plus className="size-4" />
                {ui("Thêm tệp")}
              </Button>
            }
          />
        </div>
        {ids.length === 0 && (
          <p className="text-sm text-content-muted">
            {ui("Thêm tài liệu dùng chung cho các hội thoại trong dự án.")}
          </p>
        )}
        {(error || files.isError) && (
          <p role="alert" className="text-sm">
            {ui(error ?? "Không tải được tệp dự án.")}
          </p>
        )}
        <CollapsibleContent className="space-y-2 pt-1">
          {files.data?.map(({ fileId, file }) => (
            <div key={fileId} className="flex min-w-0 items-center justify-between gap-2">
              {file ? (
                <ChatFilePart
                  filename={file.filename}
                  mimeType={file.mediaType}
                  data={fileReference(file.id)}
                />
              ) : (
                <span className="text-sm text-content-muted">{ui("Tệp không còn khả dụng")}</span>
              )}
              <IconButton
                size="sm"
                prominence="internal"
                disabled={busy}
                aria-label={ui("Gỡ {{v1}} khỏi dự án", {
                  v1: file?.filename ?? ui("tệp không còn khả dụng"),
                })}
                onClick={() => void update(ids.filter((id) => id !== fileId))}
              >
                <X />
              </IconButton>
            </div>
          ))}
        </CollapsibleContent>
      </Collapsible>
    </section>
  );
}

/** The project page shows a handful of recent conversations; "Xem thêm" loads the next five. */
const PROJECT_SESSIONS_PAGE = 5;

export function ProjectConversationList({ projectId }: { projectId: string }) {
  const ui = useAppTranslation();

  const { actorId, authorizationVersion } = useApplicationSession();
  const sessions = useInfiniteQuery({
    queryKey: ["chat-project-sessions", actorId, authorizationVersion, projectId],
    initialPageParam: 0,
    queryFn: async ({ signal, pageParam }) =>
      (
        await listProjectChatSessions({
          path: { projectId },
          query: { offset: pageParam, limit: PROJECT_SESSIONS_PAGE },
          signal,
          throwOnError: true,
        })
      ).data,
    getNextPageParam: (last, pages) =>
      last.length === PROJECT_SESSIONS_PAGE && pages.length * PROJECT_SESSIONS_PAGE <= 10000
        ? pages.length * PROJECT_SESSIONS_PAGE
        : undefined,
  });
  return (
    <section className="mt-6" aria-label={ui("Hội thoại trong dự án")}>
      <h2 className="mb-3 text-sm font-medium text-content-secondary">{ui("Hội thoại gần đây")}</h2>
      {sessions.isPending && (
        <p role="status" className="px-2 py-2 text-sm text-content-muted">
          {ui("Đang tải hội thoại…")}
        </p>
      )}
      {sessions.isError && (
        <p role="alert" className="text-sm">
          {ui("Không tải được hội thoại.")}{" "}
          <Button size="sm" onClick={() => void sessions.refetch()}>
            {ui("Tải lại")}
          </Button>
        </p>
      )}
      <ThreadList label={ui("Hội thoại dự án")}>
        {sessions.data?.pages.flat().map((session) => (
          <ChatSessionRow key={session.id} session={session} showTime />
        ))}
      </ThreadList>
      {sessions.data?.pages[0]?.length === 0 && (
        <p className="px-2 py-3 text-sm text-content-muted">
          {ui("Chưa có hội thoại. Gửi câu hỏi ở trên để bắt đầu.")}
        </p>
      )}
      {sessions.hasNextPage && (
        <Button
          size="sm"
          prominence="internal"
          pending={sessions.isFetchingNextPage}
          onClick={() => void sessions.fetchNextPage()}
        >
          {ui("Xem thêm hội thoại")}
        </Button>
      )}
    </section>
  );
}

export function ProjectEditor({ project, onClose }: { project?: Project; onClose: () => void }) {
  const ui = useAppTranslation();

  const cache = useQueryClient();
  const navigate = useNavigate();
  const [name, setName] = useState(project?.name ?? "");
  const [instructions, setInstructions] = useState(project?.instructions ?? "");
  const [iconName, setIconName] = useState(project?.iconName ?? "");
  const fileIds = project?.fileIds ?? [];
  return (
    <ChatDialog
      open
      onOpenChange={(open) => {
        if (!open) onClose();
      }}
      title={project ? ui("Chỉnh sửa dự án") : ui("Tạo dự án")}
      description={
        project
          ? ui("Hướng dẫn áp dụng cho những lượt tiếp theo trong dự án.")
          : ui("Đặt tên cho công việc bạn muốn tập trung.")
      }
      submitLabel={project ? ui("Lưu") : ui("Tạo dự án")}
      onSubmit={async () => {
        const body = {
          name: name.trim(),
          description: project?.description ?? "",
          instructions,
          fileIds,
          iconName,
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
      <div className="space-y-1">
        <label htmlFor="project-name" className="block">
          {ui("Tên dự án")}
        </label>
        <div className="relative">
          <ProjectIconPicker iconName={iconName} onIcon={setIconName} />
          <Input
            id="project-name"
            autoFocus
            required
            maxLength={200}
            value={name}
            onChange={(event) => setName(event.target.value)}
            className="pl-10"
          />
        </div>
      </div>
      {project && (
        <label className="block space-y-1">
          <span>{ui("Hướng dẫn dự án")}</span>
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

/** Small icon button inside the name field that opens the shared topic-icon grid. */
function ProjectIconPicker({
  iconName,
  onIcon,
}: {
  iconName: string;
  onIcon: (key: string) => void;
}) {
  const ui = useAppTranslation();
  const iconNames: Record<string, string> = {
    bot: ui("Trợ lý"),
    chart: ui("Biểu đồ"),
    finance: ui("Tài chính"),
    calculator: ui("Máy tính"),
    people: ui("Nhân sự"),
    legal: ui("Pháp chế"),
    document: ui("Tài liệu"),
    book: ui("Sổ tay"),
    briefcase: ui("Công việc"),
    search: ui("Tìm kiếm"),
    idea: ui("Ý tưởng"),
    shield: ui("An toàn"),
    chat: ui("Hỏi đáp"),
  };
  return (
    <Popover>
      <PopoverTrigger asChild>
        <button
          type="button"
          aria-label={ui("Đổi biểu tượng")}
          className="absolute inset-y-0 left-0 grid w-10 place-items-center rounded-l-md text-content-muted outline-none hover:text-content-primary focus-visible:ring-2 focus-visible:ring-ring"
        >
          <ProjectIcon iconName={iconName} className="size-4" />
        </button>
      </PopoverTrigger>
      <PopoverContent align="start" className="w-[19.5rem] p-3">
        <p className="mb-2 font-secondary-action text-content-muted">{ui("Biểu tượng")}</p>
        <div role="radiogroup" aria-label={ui("Biểu tượng")} className="grid grid-cols-7 gap-1.5">
          <button
            type="button"
            role="radio"
            aria-checked={iconName === ""}
            aria-label={ui("Thư mục")}
            onClick={() => onIcon("")}
            className={cn(
              "grid size-9 place-items-center rounded-lg outline-none transition-shadow focus-visible:ring-3 focus-visible:ring-focus-ring/40",
              agentIconTones.bot,
              iconName === ""
                ? "ring-2 ring-content-primary ring-offset-2 ring-offset-surface-overlay"
                : "hover:ring-1 hover:ring-border-default",
            )}
          >
            <Folder aria-hidden="true" className="size-4.5" strokeWidth={1.75} />
          </button>
          {Object.entries(agentIcons).map(([key, Icon]) => (
            <button
              key={key}
              type="button"
              role="radio"
              aria-checked={iconName === key}
              aria-label={iconNames[key] ?? ui("Biểu tượng")}
              onClick={() => onIcon(key)}
              className={cn(
                "grid size-9 place-items-center rounded-lg outline-none transition-shadow focus-visible:ring-3 focus-visible:ring-focus-ring/40",
                agentIconTones[key],
                iconName === key
                  ? "ring-2 ring-content-primary ring-offset-2 ring-offset-surface-overlay"
                  : "hover:ring-1 hover:ring-border-default",
              )}
            >
              <Icon aria-hidden="true" className="size-4.5" strokeWidth={1.75} />
            </button>
          ))}
        </div>
      </PopoverContent>
    </Popover>
  );
}
