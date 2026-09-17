import { useAppTranslation } from "@/i18n/use-app-translation";
import { useMemo, useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { useNavigate } from "@tanstack/react-router";
import { Globe, Lock, Pin, PinOff, Search, Star, Users } from "lucide-react";
import { AppShell } from "@/components/app-shell/app-shell";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { IconButton } from "@/components/ui/icon-button";
import { Input } from "@/components/ui/input";
import { Select } from "@/components/ui/select";
import { Tabs, TabsList, TabsTrigger } from "@/components/ui/tabs";
import {
  Empty,
  EmptyDescription,
  EmptyHeader,
  EmptyMedia,
  EmptyTitle,
} from "@/components/ui/empty";
import {
  useApplicationSession,
  useGlobalCapability,
} from "@/features/identity/application-session-context";
import { sameOriginMutationHeaders } from "@/lib/api";
import { listChatPersonaLabels, replaceChatPersonaPins } from "@/lib/hey-api/sdk.gen";
import { can } from "@/lib/resource-permissions";
import { chatActionError } from "@/features/chat/chat-action-utils";
import { chatSessionsKey, newChatSession } from "@/features/chat/chat-api";
import {
  agentLabelSchema,
  agentVisibility,
  loadPersonas,
  personLabel,
  type AgentView,
  type Persona,
} from "@/features/chat/chat-workspace-api";
import { AgentAvatar } from "./agent-avatar";
import { AgentEditor } from "./agent-editor";
import { AgentShareDialog } from "./agent-share-dialog";
import { AgentViewer } from "./agent-viewer";
import { AgentActions } from "./agent-actions";

const agentsKey = ["chat-personas"] as const;

export function AgentsPage() {
  const ui = useAppTranslation();
  const { actorId, authorizationVersion } = useApplicationSession();
  const canCreate = useGlobalCapability("AGENTS_CREATE");
  const cache = useQueryClient();
  const navigate = useNavigate();
  const [view, setView] = useState<AgentView>("ALL");
  const [query, setQuery] = useState("");
  const [labelId, setLabelId] = useState("");
  const [editor, setEditor] = useState<Persona | "new">();
  const [viewer, setViewer] = useState<Persona>();
  const [sharing, setSharing] = useState<Persona>();
  const [pending, setPending] = useState<string>();
  const [error, setError] = useState<string>();
  const list = useQuery({
    queryKey: [...agentsKey, actorId, authorizationVersion, view],
    queryFn: ({ signal }) => loadPersonas(signal, view),
  });
  const labels = useQuery({
    queryKey: ["chat-persona-labels", actorId, authorizationVersion],
    queryFn: async ({ signal }) =>
      agentLabelSchema
        .array()
        .parse((await listChatPersonaLabels({ signal, throwOnError: true })).data),
  });
  const visible = useMemo(() => {
    const needle = query.trim().toLocaleLowerCase();
    return (list.data ?? []).filter(
      (agent) =>
        (!labelId || agent.labels.some((label) => label.id === labelId)) &&
        (!needle ||
          agent.name.toLocaleLowerCase().includes(needle) ||
          agent.description.toLocaleLowerCase().includes(needle)),
    );
  }, [list.data, query, labelId]);
  const featured = view === "ALL" && !query && !labelId ? visible.filter((a) => a.featured) : [];
  const rest = featured.length > 0 ? visible.filter((a) => !a.featured) : visible;

  async function start(agent: Persona) {
    if (pending) return;
    setPending(agent.id);
    setError(undefined);
    try {
      const session = await newChatSession(agent.name, AbortSignal.timeout(30000), agent.id);
      await cache.invalidateQueries({ queryKey: chatSessionsKey });
      await navigate({ to: "/chat/$sessionId", params: { sessionId: session.id } });
    } catch (cause) {
      setError(chatActionError(cause));
    } finally {
      setPending(undefined);
    }
  }

  async function togglePin(agent: Persona) {
    const pinned = (list.data ?? []).filter((a) => a.pinned).map((a) => a.id);
    const next = agent.pinned ? pinned.filter((id) => id !== agent.id) : [...pinned, agent.id];
    setError(undefined);
    try {
      await replaceChatPersonaPins({
        body: { personaIds: next },
        headers: sameOriginMutationHeaders,
        signal: AbortSignal.timeout(30000),
        throwOnError: true,
      });
      await cache.invalidateQueries({ queryKey: agentsKey });
      await cache.invalidateQueries({ queryKey: ["chat-persona-pins"] });
    } catch (cause) {
      setError(chatActionError(cause));
    }
  }

  const card = (agent: Persona) => (
    <AgentCard
      key={agent.id}
      agent={agent}
      pending={pending === agent.id}
      onStart={() => void start(agent)}
      onOpen={() => (can(agent, "edit") ? setEditor(agent) : setViewer(agent))}
      onShare={() => setSharing(agent)}
      onPin={() => void togglePin(agent)}
    />
  );

  return (
    <AppShell pageTitle={ui("Trợ lý")}>
      <div className="mx-auto w-full max-w-6xl overflow-y-auto p-4 sm:p-6">
        <div className="mb-5 flex flex-wrap items-start justify-between gap-4">
          <div className="min-w-0">
            <h1 className="text-2xl font-semibold">{ui("Trợ lý")}</h1>
            <p className="mt-1 text-content-secondary">
              {ui("Trợ lý theo chủ đề với hướng dẫn, nguồn tài liệu và công cụ riêng.")}
            </p>
          </div>
          {canCreate && <Button onClick={() => setEditor("new")}>{ui("Tạo trợ lý")}</Button>}
        </div>
        <div className="mb-5 flex flex-col gap-3 md:flex-row md:items-center">
          <Tabs value={view} onValueChange={(value) => setView(value as AgentView)}>
            <TabsList>
              <TabsTrigger value="ALL">{ui("Tất cả")}</TabsTrigger>
              <TabsTrigger value="MINE">{ui("Của tôi")}</TabsTrigger>
              <TabsTrigger value="SHARED">{ui("Được chia sẻ")}</TabsTrigger>
            </TabsList>
          </Tabs>
          <div className="flex flex-1 flex-col gap-2 sm:flex-row md:justify-end">
            <label className="relative flex-1 md:max-w-xs">
              <span className="sr-only">{ui("Tìm trợ lý")}</span>
              <Search
                aria-hidden="true"
                className="pointer-events-none absolute top-1/2 left-3 size-4 -translate-y-1/2 text-content-muted"
              />
              <Input
                className="pl-9"
                value={query}
                placeholder={ui("Tìm theo tên hoặc mô tả")}
                onChange={(event) => setQuery(event.target.value)}
              />
            </label>
            <label className="sm:w-48">
              <span className="sr-only">{ui("Lọc theo nhãn")}</span>
              <Select value={labelId} onChange={(event) => setLabelId(event.target.value)}>
                <option value="">{ui("Mọi nhãn")}</option>
                {labels.data?.map((label) => (
                  <option key={label.id} value={label.id}>
                    {label.name}
                  </option>
                ))}
              </Select>
            </label>
          </div>
        </div>
        {(error || list.isError) && (
          <p role="alert" className="mb-4 text-status-danger-content">
            {error ?? ui("Không tải được danh sách trợ lý.")}{" "}
            <Button prominence="internal" onClick={() => void list.refetch()}>
              {ui("Tải lại")}
            </Button>
          </p>
        )}
        {list.isPending && <p role="status">{ui("Đang tải trợ lý…")}</p>}
        {list.isSuccess && visible.length === 0 && (
          <Empty className="rounded-2xl border border-dashed border-border-default">
            <EmptyHeader>
              <EmptyMedia variant="icon">
                <Users />
              </EmptyMedia>
              <EmptyTitle>
                {view === "SHARED"
                  ? ui("Chưa có trợ lý nào được chia sẻ")
                  : ui("Không có trợ lý phù hợp")}
              </EmptyTitle>
              <EmptyDescription>
                {view === "MINE" && canCreate
                  ? ui("Tạo trợ lý cho một chủ đề như OKR/KPI, tài chính hoặc nhân sự.")
                  : ui("Thử đổi bộ lọc hoặc từ khóa tìm kiếm.")}
              </EmptyDescription>
            </EmptyHeader>
          </Empty>
        )}
        {featured.length > 0 && (
          <section className="mb-6" aria-labelledby="featured-agents">
            <h2 id="featured-agents" className="mb-3 flex items-center gap-2 text-sm font-medium">
              <Star aria-hidden="true" className="size-4" /> {ui("Nổi bật")}
            </h2>
            <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3">{featured.map(card)}</div>
          </section>
        )}
        {rest.length > 0 && (
          <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3">{rest.map(card)}</div>
        )}
        {editor && (
          <AgentEditor
            key={editor === "new" ? "new" : editor.id}
            agent={editor === "new" ? undefined : editor}
            onClose={() => setEditor(undefined)}
          />
        )}
        {viewer && (
          <AgentViewer
            agent={viewer}
            onClose={() => setViewer(undefined)}
            onStart={() => void start(viewer)}
          />
        )}
        {sharing && <AgentShareDialog agent={sharing} onClose={() => setSharing(undefined)} />}
      </div>
    </AppShell>
  );
}

function AgentCard({
  agent,
  pending,
  onStart,
  onOpen,
  onShare,
  onPin,
}: {
  agent: Persona;
  pending: boolean;
  onStart: () => void;
  onOpen: () => void;
  onShare: () => void;
  onPin: () => void;
}) {
  const ui = useAppTranslation();
  const visibility = agentVisibility(agent);
  const owner = agent.builtin
    ? ui("MemoryOS")
    : (agent.owner.group?.name ?? (personLabel(agent.owner.actor) || ui("Chưa có chủ sở hữu")));
  return (
    <article className="flex min-w-0 flex-col rounded-2xl border border-border-default bg-surface-raised p-4">
      <div className="flex items-start gap-3">
        <AgentAvatar agent={agent} />
        <div className="min-w-0 flex-1">
          <h3 className="truncate font-medium" title={agent.name}>
            {agent.name}
          </h3>
          <p className="truncate text-xs text-content-muted">{owner}</p>
        </div>
        {!agent.builtin && (
          <IconButton
            size="sm"
            prominence="internal"
            aria-label={agent.pinned ? ui("Bỏ ghim") : ui("Ghim vào thanh bên")}
            title={agent.pinned ? ui("Bỏ ghim") : ui("Ghim vào thanh bên")}
            aria-pressed={agent.pinned}
            onClick={onPin}
          >
            {agent.pinned ? <PinOff /> : <Pin />}
          </IconButton>
        )}
      </div>
      <p className="mt-3 line-clamp-2 min-h-10 text-sm text-content-secondary">
        {agent.description || ui("Chưa có mô tả.")}
      </p>
      <div className="mt-3 flex flex-wrap items-center gap-1.5">
        <Badge variant="outline">
          {visibility === "public" ? (
            <Globe aria-hidden="true" />
          ) : visibility === "shared" ? (
            <Users aria-hidden="true" />
          ) : (
            <Lock aria-hidden="true" />
          )}
          {visibility === "public"
            ? ui("Công khai")
            : visibility === "shared"
              ? ui("Đã chia sẻ")
              : ui("Riêng tư")}
        </Badge>
        {agent.labels.slice(0, 3).map((label) => (
          <Badge key={label.id} variant="secondary">
            {label.name}
          </Badge>
        ))}
        {agent.vacant && <Badge variant="destructive">{ui("Chưa có chủ sở hữu")}</Badge>}
      </div>
      <div className="mt-4 flex flex-wrap items-center gap-2">
        <Button size="sm" disabled={pending} onClick={onStart}>
          {ui("Bắt đầu hội thoại")}
        </Button>
        <Button size="sm" prominence="secondary" onClick={onOpen}>
          {can(agent, "edit") ? ui("Chỉnh sửa") : ui("Xem chi tiết")}
        </Button>
        {can(agent, "share") && (
          <Button size="sm" prominence="internal" onClick={onShare}>
            {ui("Chia sẻ")}
          </Button>
        )}
        <AgentActions agent={agent} />
      </div>
    </article>
  );
}
