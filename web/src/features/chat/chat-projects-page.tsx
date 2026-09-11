import { useState } from "react";
import { useInfiniteQuery, useQuery, useQueryClient } from "@tanstack/react-query";
import { Link, useNavigate } from "@tanstack/react-router";
import { AppShell } from "@/components/app-shell/app-shell";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { ConfirmDialog } from "@/components/ui/confirm-dialog";
import { useApplicationSession } from "@/features/identity/application-session-context";
import { sameOriginMutationHeaders } from "@/lib/api";
import {
  createChatProject,
  updateChatProject,
  deleteChatProject,
  getChatProject,
  listProjectChatSessions,
  createProjectChatSession,
  moveChatProject,
} from "@/lib/hey-api/sdk.gen";
import { chatSessionsKey } from "./chat-api";
import { ChatDialog } from "./chat-dialog";
import { chatField, chatActionError } from "./chat-action-utils";
import { loadProjects, projectSchema, type Project } from "./chat-workspace-api";

export function ChatProjectsPage() {
  const { actorId, authorizationVersion } = useApplicationSession();
  const [editing, setEditing] = useState(false);
  const projects = useQuery({
    queryKey: ["chat-projects", actorId, authorizationVersion],
    queryFn: ({ signal }) => loadProjects(signal),
  });
  return (
    <AppShell pageTitle="Dự án">
      <div className="mx-auto w-full max-w-5xl overflow-y-auto p-6">
        <div className="mb-6 flex justify-between gap-4">
          <div>
            <h1 className="text-2xl font-semibold">Dự án</h1>
            <p className="mt-2 text-content-secondary">
              Gom hội thoại và dùng chung hướng dẫn cho một công việc.
            </p>
          </div>
          <Button onClick={() => setEditing(true)}>Tạo dự án</Button>
        </div>
        {projects.isPending && <p role="status">Đang tải dự án…</p>}
        {projects.isError && (
          <p role="alert">
            Không tải được dự án.{" "}
            <Button prominence="internal" onClick={() => void projects.refetch()}>
              Tải lại
            </Button>
          </p>
        )}
        {projects.data?.length === 0 && (
          <p className="rounded-2xl border border-dashed border-border-default p-8 text-content-secondary">
            Chưa có dự án. Tạo dự án để bắt đầu nhóm hội thoại.
          </p>
        )}
        <div className="grid gap-4 sm:grid-cols-2">
          {projects.data?.map((project) => (
            <Link
              key={project.id}
              to="/projects/$projectId"
              params={{ projectId: project.id }}
              className="rounded-2xl border border-border-default p-5 hover:bg-surface-sunken focus-visible:ring-2 focus-visible:ring-ring"
            >
              <h2 className="text-lg font-medium">{project.name}</h2>
              <p className="mt-2 text-sm text-content-secondary whitespace-pre-wrap">
                {project.description || "Dự án riêng của bạn"}
              </p>
            </Link>
          ))}
        </div>
        {editing && <ProjectEditor onClose={() => setEditing(false)} />}
      </div>
    </AppShell>
  );
}

export function ChatProjectPage({ projectId }: { projectId: string }) {
  const { actorId, authorizationVersion } = useApplicationSession();
  const navigate = useNavigate();
  const cache = useQueryClient();
  const [editing, setEditing] = useState(false);
  const [pending, setPending] = useState(false);
  const [error, setError] = useState<string>();
  const project = useQuery({
    queryKey: ["chat-project", actorId, authorizationVersion, projectId],
    queryFn: async ({ signal }) =>
      projectSchema.parse(
        (await getChatProject({ path: { projectId }, signal, throwOnError: true })).data,
      ),
  });
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
    <AppShell pageTitle="Dự án">
      <div className="mx-auto w-full max-w-4xl overflow-y-auto p-6">
        <Link to="/projects" className="text-sm underline">
          Tất cả dự án
        </Link>
        {project.isPending && (
          <p role="status" className="mt-6">
            Đang tải dự án…
          </p>
        )}
        {project.isError && (
          <p role="alert" className="mt-6">
            Dự án không khả dụng.{" "}
            <Button prominence="internal" onClick={() => void project.refetch()}>
              Tải lại
            </Button>
          </p>
        )}
        {project.data && (
          <>
            <div className="mt-5 flex flex-wrap items-start justify-between gap-4">
              <div>
                <h1 className="text-2xl font-semibold">{project.data.name}</h1>
                <p className="mt-2 whitespace-pre-wrap text-content-secondary">
                  {project.data.description}
                </p>
              </div>
              <div className="flex gap-2">
                <Button prominence="secondary" onClick={() => setEditing(true)}>
                  Chỉnh sửa dự án
                </Button>
                <ConfirmDialog
                  pendingLabel="Đang lưu…"
                  title="Xóa dự án?"
                  description="Các hội thoại được chuyển ra ngoài dự án và vẫn giữ nguyên lịch sử."
                  confirmLabel="Xóa dự án"
                  errorMessage={chatActionError}
                  trigger={<Button prominence="internal">Xóa</Button>}
                  onConfirm={async () => {
                    await deleteChatProject({
                      path: { projectId },
                      query: { revision: project.data.revision },
                      headers: sameOriginMutationHeaders,
                      throwOnError: true,
                    });
                    await cache.invalidateQueries({ queryKey: ["chat-projects"] });
                    await cache.invalidateQueries({ queryKey: chatSessionsKey });
                    await navigate({ to: "/projects" });
                  }}
                />
              </div>
            </div>
            <details className="my-6 rounded-xl border border-border-default p-4">
              <summary className="cursor-pointer font-medium">Hướng dẫn dự án</summary>
              <p className="mt-3 whitespace-pre-wrap text-sm">
                {project.data.instructions || "Chưa có hướng dẫn."}
              </p>
              <p className="mt-3 text-xs text-content-muted">
                Áp dụng cho lượt mới dùng trợ lý mặc định. Trợ lý riêng dùng hướng dẫn của mình.
              </p>
            </details>
            <Button
              pending={pending}
              onClick={() => {
                if (pending) return;
                setPending(true);
                setError(undefined);
                void createProjectChatSession({
                  path: { projectId },
                  body: { title: "Hội thoại mới" },
                  headers: sameOriginMutationHeaders,
                  throwOnError: true,
                })
                  .then(async ({ data }) => {
                    await cache.invalidateQueries({ queryKey: chatSessionsKey });
                    await navigate({ to: "/chat/$sessionId", params: { sessionId: data.id } });
                  })
                  .catch((cause: unknown) => setError(chatActionError(cause)))
                  .finally(() => setPending(false));
              }}
            >
              Hội thoại mới trong dự án
            </Button>
            {(error || sessions.isError) && (
              <p role="alert" className="mt-4">
                {error ?? "Không tải được hội thoại."}{" "}
                <Button prominence="internal" onClick={() => void sessions.refetch()}>
                  Tải lại
                </Button>
              </p>
            )}
            {sessions.isPending && (
              <p role="status" className="mt-4">
                Đang tải hội thoại…
              </p>
            )}
            <ul className="mt-6 divide-y divide-border-subtle">
              {sessions.data?.pages.flat().map((session) => (
                <li key={session.id} className="flex items-center justify-between gap-3 py-3">
                  <Link
                    className="min-w-0 truncate underline-offset-4 hover:underline"
                    to="/chat/$sessionId"
                    params={{ sessionId: session.id }}
                  >
                    {session.title}
                  </Link>
                  <ConfirmDialog
                    pendingLabel="Đang lưu…"
                    title="Đưa hội thoại ra khỏi dự án?"
                    description="Hội thoại vẫn nằm trong lịch sử của bạn. Các lượt sau dùng cấu hình của trợ lý."
                    confirmLabel="Đưa ra ngoài"
                    errorMessage={chatActionError}
                    trigger={
                      <Button size="sm" prominence="internal">
                        Đưa ra ngoài
                      </Button>
                    }
                    onConfirm={async () => {
                      await moveChatProject({
                        path: { sessionId: session.id },
                        body: { projectId: null },
                        headers: sameOriginMutationHeaders,
                        throwOnError: true,
                      });
                      await sessions.refetch();
                      await cache.invalidateQueries({ queryKey: chatSessionsKey });
                    }}
                  />
                </li>
              ))}
            </ul>
            {sessions.data?.pages[0]?.length === 0 && (
              <p className="mt-6 text-content-secondary">Chưa có hội thoại trong dự án.</p>
            )}
            {sessions.hasNextPage && (
              <Button
                prominence="secondary"
                pending={sessions.isFetchingNextPage}
                onClick={() => void sessions.fetchNextPage()}
              >
                Xem thêm
              </Button>
            )}
            {editing && <ProjectEditor project={project.data} onClose={() => setEditing(false)} />}
          </>
        )}
      </div>
    </AppShell>
  );
}

function ProjectEditor({ project, onClose }: { project?: Project; onClose: () => void }) {
  const cache = useQueryClient();
  const [name, setName] = useState(project?.name ?? "");
  const [description, setDescription] = useState(project?.description ?? "");
  const [instructions, setInstructions] = useState(project?.instructions ?? "");
  return (
    <ChatDialog
      open
      onOpenChange={(open) => {
        if (!open) onClose();
      }}
      title={project ? "Chỉnh sửa dự án" : "Tạo dự án"}
      description="Dự án và các hội thoại bên trong chỉ mình bạn quản lý."
      onSubmit={async () => {
        const body = { name, description, instructions };
        if (project)
          await updateChatProject({
            path: { projectId: project.id },
            query: { revision: project.revision },
            body,
            headers: sameOriginMutationHeaders,
            throwOnError: true,
          });
        else
          await createChatProject({ body, headers: sameOriginMutationHeaders, throwOnError: true });
        await cache.invalidateQueries({ queryKey: ["chat-projects"] });
        await cache.invalidateQueries({ queryKey: ["chat-project"] });
      }}
    >
      <label className="block space-y-1">
        <span>Tên dự án</span>
        <Input required maxLength={200} value={name} onChange={(e) => setName(e.target.value)} />
      </label>
      <label className="block space-y-1">
        <span>Mô tả</span>
        <textarea
          className={chatField}
          maxLength={2000}
          rows={2}
          value={description}
          onChange={(e) => setDescription(e.target.value)}
        />
      </label>
      <label className="block space-y-1">
        <span>Hướng dẫn dự án</span>
        <textarea
          className={chatField}
          maxLength={32000}
          rows={6}
          value={instructions}
          onChange={(e) => setInstructions(e.target.value)}
        />
      </label>
    </ChatDialog>
  );
}
