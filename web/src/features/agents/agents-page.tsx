import { useAppTranslation } from "@/i18n/use-app-translation";
import { useDeferredValue, useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { useNavigate, useSearch } from "@tanstack/react-router";
import {
  ArrowRight,
  Bot,
  ChevronDown,
  Globe,
  Lock,
  Library,
  Pencil,
  Pin,
  Plus,
  Search,
  Share2,
  Sparkles,
  Star,
  Users,
  X,
} from "lucide-react";
import { AppShell } from "@/components/app-shell/app-shell";
import { Button } from "@/components/ui/button";
import { Checkbox } from "@/components/ui/checkbox";
import { IconButton } from "@/components/ui/icon-button";
import { Input } from "@/components/ui/input";
import { Popover, PopoverContent, PopoverTrigger } from "@/components/ui/popover";
import { PageHeader, SettingsLayout } from "@/components/ui/settings-layout";
import { Tabs, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { Tooltip, TooltipContent, TooltipProvider, TooltipTrigger } from "@/components/ui/tooltip";
import { CountSeparator } from "@/components/composites/count-separator";
import { FilterChips } from "@/components/composites/filter-chips";
import { hoverReveal } from "@/components/composites/hover-reveal";
import { PersonAvatar } from "@/components/composites/person-avatar";
import {
  useAdminAccess,
  useApplicationSession,
  useGlobalCapability,
} from "@/features/identity/application-session-context";
import { can } from "@/lib/resource-permissions";
import { cn } from "@/lib/utils";
import { actionErrorText } from "@/lib/action-errors";
import { chatSessionsKey, newChatSession } from "@/features/chat/chat-api";
import {
  agentVisibility,
  loadPersonas,
  type AgentView,
  type Persona,
} from "@/features/chat/chat-personas-api";
import { personLabel } from "@/features/identity/principals";
import { AgentAvatar } from "./agent-avatar";
import { AgentShareDialog } from "./agent-share-dialog";
import { AgentViewer } from "./agent-viewer";
import { AgentActions } from "./agent-actions";
import { usePinUpdates } from "./agent-pins";

const agentsKey = ["chat-personas"] as const;

export function AgentsPage() {
  const ui = useAppTranslation();
  const { actorId, authorizationVersion } = useApplicationSession();
  const canCreate = useGlobalCapability("AGENTS_CREATE");
  const { canReadSources } = useAdminAccess();
  const cache = useQueryClient();
  const navigate = useNavigate();
  const [view, setView] = useState<AgentView>("ALL");
  const [search, setSearch] = useState("");
  const query = useDeferredValue(search.trim().toLocaleLowerCase());
  const [labelId, setLabelId] = useState<string>();
  const [creators, setCreators] = useState<string[]>([]);
  const [viewer, setViewer] = useState<Persona>();
  const { agent: linkedAgent } = useSearch({ from: "/_authenticated/agents" });
  const [sharing, setSharing] = useState<Persona>();
  const [pending, setPending] = useState<string>();
  const [error, setError] = useState<string>();
  const list = useQuery({
    queryKey: [...agentsKey, actorId, authorizationVersion, view],
    queryFn: ({ signal }) => loadPersonas(signal, view),
  });
  const agents = list.data ?? [];
  // A copied share link opens that agent's detail view.
  const shownViewer =
    viewer ?? (linkedAgent ? agents.find((agent) => agent.id === linkedAgent) : undefined);
  const closeViewer = () => {
    setViewer(undefined);
    if (linkedAgent) void navigate({ to: "/agents", search: {}, replace: true });
  };
  const ownerName = (agent: Persona) =>
    agent.builtin
      ? ui("MemoryOS")
      : (agent.owner.group?.name ?? (personLabel(agent.owner.actor) || ui("Chưa có chủ sở hữu")));

  const matchesSearch = (agent: Persona) =>
    !query ||
    agent.name.toLocaleLowerCase().includes(query) ||
    agent.description.toLocaleLowerCase().includes(query) ||
    agent.labels.some((label) => label.name.toLocaleLowerCase().includes(query));
  const matchesCreator = (agent: Persona) =>
    creators.length === 0 || creators.includes(ownerName(agent));
  // Facet counts: how many agents each label would show under the other active filters.
  const labelCounts = new Map<string, { label: string; count: number }>();
  for (const agent of agents.filter((agent) => matchesSearch(agent) && matchesCreator(agent)))
    for (const label of agent.labels) {
      const entry = labelCounts.get(label.id) ?? { label: label.name, count: 0 };
      entry.count += 1;
      labelCounts.set(label.id, entry);
    }
  const labelChips = [...labelCounts.entries()]
    .sort((a, b) => b[1].count - a[1].count || a[1].label.localeCompare(b[1].label))
    .map(([value, entry]) => ({ value, label: entry.label, count: entry.count }));
  // The selected label stays visible (with no matches) so it can always be cleared.
  const selectedLabel =
    labelId && agents.flatMap((agent) => agent.labels).find((label) => label.id === labelId);
  if (selectedLabel && !labelChips.some((chip) => chip.value === labelId))
    labelChips.unshift({ value: selectedLabel.id, label: selectedLabel.name, count: 0 });
  const owners = [...new Set(agents.map(ownerName))].sort((a, b) => a.localeCompare(b));

  const visible = agents.filter(
    (agent) =>
      (!labelId || agent.labels.some((label) => label.id === labelId)) &&
      matchesCreator(agent) &&
      matchesSearch(agent),
  );
  const filtered = query !== "" || labelId !== undefined || creators.length > 0;
  // Featured agents lead the catalog instead of forming a sparse section of their own.
  const ordered = [...visible].sort((a, b) => Number(b.featured) - Number(a.featured));

  const updatePins = usePinUpdates();

  async function start(agent: Persona, ask?: string) {
    if (pending) return;
    setPending(agent.id);
    setError(undefined);
    try {
      // As Onyx: starting a chat with an agent pins it to the sidebar.
      if (!agent.pinned && !agent.builtin)
        await updatePins((current) =>
          current.includes(agent.id) ? current : [...current, agent.id],
        );
      const session = await newChatSession(agent.name, AbortSignal.timeout(30000), agent.id);
      await cache.invalidateQueries({ queryKey: chatSessionsKey });
      await navigate({
        to: "/chat/$sessionId",
        params: { sessionId: session.id },
        search: { ask },
      });
    } catch (cause) {
      setError(actionErrorText(cause));
    } finally {
      setPending(undefined);
    }
  }

  async function togglePin(agent: Persona) {
    setError(undefined);
    try {
      await updatePins((current) =>
        agent.pinned
          ? current.filter((id) => id !== agent.id)
          : current.includes(agent.id)
            ? current
            : [...current, agent.id],
      );
    } catch (cause) {
      setError(actionErrorText(cause));
    }
  }

  const card = (agent: Persona) => (
    <AgentCard
      key={agent.id}
      agent={agent}
      owner={ownerName(agent)}
      pending={pending === agent.id}
      onStart={() => void start(agent)}
      onOpen={() => setViewer(agent)}
      onEdit={() => void navigate({ to: "/agents/$agentId/edit", params: { agentId: agent.id } })}
      onShare={() => setSharing(agent)}
      onPin={() => void togglePin(agent)}
    />
  );

  const createButton = (
    <Button disabled={!canCreate} onClick={() => void navigate({ to: "/agents/create" })}>
      <Plus aria-hidden="true" />
      {ui("Tạo trợ lý")}
    </Button>
  );

  return (
    <AppShell pageTitle={ui("Trợ lý")}>
      <SettingsLayout wide className="gap-6 md:pt-8">
        <PageHeader
          icon={<Bot />}
          title={ui("Trợ lý")}
          description={ui(
            "Trợ lý theo chủ đề cho từng phòng ban, với hướng dẫn, nguồn tài liệu và công cụ riêng.",
          )}
          actions={
            <div className="flex gap-2">
              {canReadSources ? (
                <Button
                  prominence="secondary"
                  onClick={() => void navigate({ to: "/admin/document-sets" })}
                >
                  <Library aria-hidden="true" />
                  {ui("Bộ tài liệu")}
                </Button>
              ) : null}
              {canCreate ? (
                createButton
              ) : (
                <TooltipProvider>
                  <Tooltip>
                    <TooltipTrigger asChild>
                      <span tabIndex={0}>{createButton}</span>
                    </TooltipTrigger>
                    <TooltipContent>
                      {ui("Bạn chưa có quyền tạo trợ lý. Liên hệ quản trị viên để được cấp quyền.")}
                    </TooltipContent>
                  </Tooltip>
                </TooltipProvider>
              )}
            </div>
          }
        />

        <div className="flex flex-col gap-4">
          <Tabs value={view} onValueChange={(value) => setView(value as AgentView)}>
            <TabsList variant="line" className="w-full justify-start border-b border-border-subtle">
              <TabsTrigger value="ALL" className="flex-none">
                {ui("Tất cả")}
              </TabsTrigger>
              <TabsTrigger value="MINE" className="flex-none">
                {ui("Của tôi")}
              </TabsTrigger>
              <TabsTrigger value="SHARED" className="flex-none">
                {ui("Được chia sẻ")}
              </TabsTrigger>
            </TabsList>
          </Tabs>
          <div className="flex flex-col gap-2 sm:flex-row sm:items-center">
            <label className="relative min-w-0 flex-1">
              <span className="sr-only">{ui("Tìm trợ lý")}</span>
              <Search
                aria-hidden="true"
                className="pointer-events-none absolute top-1/2 left-3 size-4 -translate-y-1/2 text-content-disabled"
              />
              <Input
                className="pl-9"
                value={search}
                placeholder={ui("Tìm theo tên, mô tả hoặc nhãn")}
                onChange={(event) => setSearch(event.target.value)}
              />
            </label>
            <CreatorFilter owners={owners} value={creators} onChange={setCreators} />
          </div>
          {labelChips.length > 0 && (
            <FilterChips
              label={ui("Lọc theo nhãn")}
              value={labelId}
              onChange={setLabelId}
              chips={labelChips}
            />
          )}
        </div>

        {(error || list.isError) && (
          <p role="alert" className="font-main-ui-body text-status-danger-content">
            {error ?? ui("Không tải được danh sách trợ lý.")}{" "}
            <Button size="sm" prominence="tertiary" onClick={() => void list.refetch()}>
              {ui("Tải lại")}
            </Button>
          </p>
        )}
        {list.isPending && (
          <div
            className="grid gap-3 sm:grid-cols-2 xl:grid-cols-3"
            role="status"
            aria-label={ui("Đang tải trợ lý…")}
          >
            {Array.from({ length: 6 }, (_, index) => (
              <div key={index} className="h-40 animate-pulse rounded-2xl bg-surface-subtle" />
            ))}
          </div>
        )}

        {list.isSuccess && visible.length === 0 && (
          <div className="flex flex-col items-center gap-2 rounded-2xl border border-dashed border-border-default px-6 py-14 text-center">
            <Sparkles aria-hidden="true" className="size-6 text-content-muted" />
            <p className="font-main-content-body text-content-primary">
              {view === "SHARED" && !filtered
                ? ui("Chưa có trợ lý nào được chia sẻ")
                : ui("Không có trợ lý phù hợp")}
            </p>
            <p className="max-w-md font-secondary-body text-content-muted">
              {view === "MINE" && canCreate && !filtered
                ? ui("Tạo trợ lý cho một chủ đề như OKR/KPI, tài chính hoặc nhân sự.")
                : ui("Thử đổi bộ lọc hoặc từ khóa tìm kiếm.")}
            </p>
          </div>
        )}

        {ordered.length > 0 && (
          <div className="grid gap-3 sm:grid-cols-2 xl:grid-cols-3">{ordered.map(card)}</div>
        )}
        {visible.length > 0 && (
          <CountSeparator>{ui("{{v1}} trợ lý", { v1: visible.length })}</CountSeparator>
        )}
        {shownViewer && (
          <AgentViewer
            agent={shownViewer}
            pending={pending === shownViewer.id}
            onClose={closeViewer}
            onStart={(ask) => void start(shownViewer, ask)}
            onEdit={() =>
              void navigate({ to: "/agents/$agentId/edit", params: { agentId: shownViewer.id } })
            }
          />
        )}
        {sharing && <AgentShareDialog agent={sharing} onClose={() => setSharing(undefined)} />}
      </SettingsLayout>
    </AppShell>
  );
}

function CreatorFilter({
  owners,
  value,
  onChange,
}: {
  owners: string[];
  value: string[];
  onChange: (value: string[]) => void;
}) {
  const ui = useAppTranslation();
  const [search, setSearch] = useState("");
  const shown = owners.filter((owner) =>
    owner.toLocaleLowerCase().includes(search.trim().toLocaleLowerCase()),
  );
  const label =
    value.length === 0
      ? ui("Mọi người tạo")
      : value.length === 1
        ? value[0]!
        : ui("{{v1}} người tạo", { v1: value.length });
  return (
    <div className="flex items-center">
      <Popover>
        <PopoverTrigger asChild>
          <Button
            prominence="secondary"
            className={cn(
              "flex-1 justify-start sm:flex-none",
              value.length > 0 && "rounded-r-none",
            )}
          >
            <Users aria-hidden="true" />
            {label}
            <ChevronDown aria-hidden="true" />
          </Button>
        </PopoverTrigger>
        <PopoverContent align="end" className="w-72 p-1">
          <Input
            size="sm"
            value={search}
            placeholder={ui("Tìm người tạo…")}
            onChange={(event) => setSearch(event.target.value)}
            className="mb-1"
          />
          <div className="max-h-64 overflow-y-auto">
            {shown.map((owner) => (
              <label
                key={owner}
                className="flex cursor-pointer items-center gap-2 rounded-lg px-2 py-1.5 font-main-ui-body hover:bg-surface-subtle"
              >
                <Checkbox
                  checked={value.includes(owner)}
                  onCheckedChange={(checked) =>
                    onChange(
                      checked === true ? [...value, owner] : value.filter((item) => item !== owner),
                    )
                  }
                />
                <PersonAvatar name={owner} size="sm" />
                <span className="min-w-0 flex-1 truncate">{owner}</span>
              </label>
            ))}
            {shown.length === 0 && (
              <p className="px-2 py-3 font-secondary-body text-content-muted">
                {ui("Không tìm thấy kết quả")}
              </p>
            )}
          </div>
        </PopoverContent>
      </Popover>
      {value.length > 0 && (
        <IconButton
          prominence="secondary"
          className="-ml-px rounded-l-none"
          aria-label={ui("Xoá bộ lọc người tạo")}
          onClick={() => onChange([])}
        >
          <X />
        </IconButton>
      )}
    </div>
  );
}

function AgentCard({
  agent,
  owner,
  pending,
  onStart,
  onOpen,
  onEdit,
  onShare,
  onPin,
}: {
  agent: Persona;
  owner: string;
  pending: boolean;
  onStart: () => void;
  onOpen: () => void;
  onEdit: () => void;
  onShare: () => void;
  onPin: () => void;
}) {
  const ui = useAppTranslation();
  const visibility = agentVisibility(agent);
  const VisibilityIcon = visibility === "public" ? Globe : visibility === "shared" ? Users : Lock;
  return (
    <article className="group relative flex min-w-0 flex-col overflow-hidden rounded-2xl border border-border-subtle surface-card transition-shadow duration-150 hover:shadow-hover">
      <div className="flex gap-3 p-4 pb-5">
        <AgentAvatar agent={agent} />
        <div className="min-w-0 flex-1">
          <div className="flex min-w-0 items-start gap-2 pr-7">
            <h3 className="min-w-0 font-main-content-body text-content-primary">
              <button
                type="button"
                onClick={onOpen}
                className="line-clamp-2 text-left outline-none after:absolute after:inset-0 after:rounded-2xl focus-visible:after:ring-3 focus-visible:after:ring-focus-ring/40"
              >
                {agent.name}
              </button>
            </h3>
            {agent.featured && (
              <span className="inline-flex h-5 shrink-0 items-center gap-1 rounded-full bg-status-warning-surface px-1.5 font-secondary-action text-status-warning-content">
                <Star aria-hidden="true" className="size-3 fill-current" />
                {ui("Nổi bật")}
              </span>
            )}
          </div>
          <p className="mt-1 line-clamp-2 font-secondary-body text-content-muted">
            {agent.description || ui("Chưa có mô tả.")}
          </p>
        </div>
        <div className="absolute top-3 right-3 z-10 flex items-center gap-0.5">
          <div
            className={cn(
              "flex items-center gap-0.5 rounded-lg bg-surface-raised shadow-hover",
              hoverReveal,
            )}
          >
            {can(agent, "edit") && (
              <IconButton
                size="sm"
                prominence="tertiary"
                aria-label={ui("Sửa {{v1}}", { v1: agent.name })}
                title={ui("Sửa trợ lý")}
                onClick={onEdit}
              >
                <Pencil />
              </IconButton>
            )}
            {can(agent, "share") && (
              <IconButton
                size="sm"
                prominence="tertiary"
                aria-label={ui("Chia sẻ {{v1}}", { v1: agent.name })}
                title={ui("Chia sẻ trợ lý")}
                onClick={onShare}
              >
                <Share2 />
              </IconButton>
            )}
            <AgentActions agent={agent} />
          </div>
          {!agent.builtin && (
            <IconButton
              size="sm"
              prominence="tertiary"
              aria-label={agent.pinned ? ui("Bỏ ghim") : ui("Ghim vào thanh bên")}
              title={agent.pinned ? ui("Bỏ ghim") : ui("Ghim vào thanh bên")}
              aria-pressed={agent.pinned}
              className={agent.pinned ? "text-content-primary" : hoverReveal}
              onClick={onPin}
            >
              <Pin className={agent.pinned ? "fill-current" : undefined} />
            </IconButton>
          )}
        </div>
      </div>
      <div className="relative z-10 mt-auto flex items-center gap-3 border-t border-border-subtle bg-surface-base/80 py-1.5 pr-1.5 pl-4">
        <span className="flex min-w-0 flex-1 items-center gap-2 font-secondary-body text-content-muted">
          <PersonAvatar
            name={owner}
            seed={agent.owner.actor?.actorId ?? agent.owner.group?.id ?? owner}
            kind={agent.owner.group ? "group" : "person"}
            size="xs"
          />
          <span className="truncate">{owner}</span>
          <span aria-hidden="true">·</span>
          <VisibilityIcon aria-hidden="true" className="size-3 shrink-0" />
          <span className="shrink-0">
            {visibility === "public"
              ? ui("Công khai")
              : visibility === "shared"
                ? ui("Đã chia sẻ")
                : ui("Riêng tư")}
          </span>
        </span>
        <Button size="sm" prominence="tertiary" pending={pending} onClick={onStart}>
          {ui("Bắt đầu chat")}
          <ArrowRight aria-hidden="true" />
        </Button>
      </div>
    </article>
  );
}
