import { useAppTranslation } from "@/i18n/use-app-translation";
import { useDeferredValue, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { useNavigate, useSearch } from "@tanstack/react-router";
import { Bot, Library, Plus, Search, Sparkles } from "lucide-react";
import { AppShellHeader } from "@/components/app-shell/app-shell-header";
import { CountSeparator } from "@/components/composites/count-separator";
import { EmptyState } from "@/components/composites/empty-state";
import { FilterChips } from "@/components/composites/filter-chips";
import { PageHeader, SettingsLayout } from "@/components/composites/settings-layout";
import { Alert, AlertAction, AlertDescription } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { InputGroup, InputGroupAddon, InputGroupInput } from "@/components/ui/input-group";
import { Skeleton } from "@/components/ui/skeleton";
import { Tabs, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { Tooltip, TooltipContent, TooltipProvider, TooltipTrigger } from "@/components/ui/tooltip";
import {
  useAdminAccess,
  useGlobalCapability,
} from "@/features/identity/application-session-context";
import { personasOptions, type AgentView, type Persona } from "@/features/chat/chat-personas-api";
import { actionErrorText } from "@/lib/action-errors";
import { AgentCard } from "./agent-card";
import { AgentCreatorFilter } from "./agent-creator-filter";
import { AgentShareDialog } from "./agent-share-dialog";
import { AgentViewer } from "./agent-viewer";
import { agentCatalog, useAgentChatActions, useOwnerName } from "./use-agent-catalog";

/** The agent catalog: every agent the actor can use, filtered by view, search, label and owner. */
export function AgentsPage() {
  const ui = useAppTranslation();
  const canCreate = useGlobalCapability("AGENTS_CREATE");
  const { canReadSources } = useAdminAccess();
  const navigate = useNavigate();
  const [view, setView] = useState<AgentView>("ALL");
  const [search, setSearch] = useState("");
  const query = useDeferredValue(search.trim().toLocaleLowerCase());
  const [labelId, setLabelId] = useState<string>();
  const [creators, setCreators] = useState<string[]>([]);
  const [viewer, setViewer] = useState<Persona>();
  const [sharing, setSharing] = useState<Persona>();
  const { agent: linkedAgent } = useSearch({ from: "/_authenticated/agents" });
  const list = useQuery(personasOptions(view));
  const agents = list.data ?? [];
  const ownerName = useOwnerName();
  const { labelChips, owners, shown } = agentCatalog(
    agents,
    { query, labelId, creators },
    ownerName,
  );
  const filtered = query !== "" || labelId !== undefined || creators.length > 0;
  const { start, pin, starting, error } = useAgentChatActions();
  const startChat = (agent: Persona, ask?: string) => {
    if (!start.isPending) start.mutate({ agent, ask });
  };
  const edit = (agent: Persona) =>
    void navigate({ to: "/agents/$agentId/edit", params: { agentId: agent.id } });
  // A copied share link opens that agent's detail view.
  const shownViewer =
    viewer ?? (linkedAgent ? agents.find((agent) => agent.id === linkedAgent) : undefined);
  const closeViewer = () => {
    setViewer(undefined);
    if (linkedAgent) void navigate({ to: "/agents", search: {}, replace: true });
  };

  return (
    <>
      <AppShellHeader title={ui("Trợ lý")} />
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
                  <Library data-icon="inline-start" aria-hidden="true" />
                  {ui("Bộ tài liệu")}
                </Button>
              ) : null}
              <CreateAgentButton
                allowed={canCreate}
                onCreate={() => void navigate({ to: "/agents/create" })}
              />
            </div>
          }
        />

        <div className="flex flex-col gap-4">
          <Tabs value={view} onValueChange={(value) => setView(value as AgentView)}>
            <div className="border-b border-border-subtle">
              <TabsList variant="line" className="w-full justify-start">
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
            </div>
          </Tabs>
          <div className="flex flex-col gap-2 sm:flex-row sm:items-center">
            <InputGroup className="min-w-0 flex-1">
              <InputGroupAddon>
                <Search aria-hidden="true" />
              </InputGroupAddon>
              <InputGroupInput
                aria-label={ui("Tìm trợ lý")}
                value={search}
                placeholder={ui("Tìm theo tên, mô tả hoặc nhãn")}
                onChange={(event) => setSearch(event.target.value)}
              />
            </InputGroup>
            <AgentCreatorFilter owners={owners} value={creators} onChange={setCreators} />
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
          <Alert variant="destructive">
            <AlertDescription>
              {error ? actionErrorText(error) : ui("Không tải được danh sách trợ lý.")}
            </AlertDescription>
            <AlertAction>
              <Button size="sm" prominence="tertiary" onClick={() => void list.refetch()}>
                {ui("Tải lại")}
              </Button>
            </AlertAction>
          </Alert>
        )}
        {list.isPending && (
          <div
            className="grid gap-3 sm:grid-cols-2 xl:grid-cols-3"
            role="status"
            aria-label={ui("Đang tải trợ lý…")}
          >
            {Array.from({ length: 6 }, (_, index) => (
              <Skeleton key={index} className="h-40" />
            ))}
          </div>
        )}

        {list.isSuccess && shown.length === 0 && (
          <EmptyState
            icon={<Sparkles />}
            title={
              view === "SHARED" && !filtered
                ? ui("Chưa có trợ lý nào được chia sẻ")
                : ui("Không có trợ lý phù hợp")
            }
            detail={
              view === "MINE" && canCreate && !filtered
                ? ui("Tạo trợ lý cho một chủ đề như OKR/KPI, tài chính hoặc nhân sự.")
                : ui("Thử đổi bộ lọc hoặc từ khóa tìm kiếm.")
            }
          />
        )}

        {shown.length > 0 && (
          <>
            <div className="grid gap-3 sm:grid-cols-2 xl:grid-cols-3">
              {shown.map((agent) => (
                <AgentCard
                  key={agent.id}
                  agent={agent}
                  owner={ownerName(agent)}
                  pending={starting === agent.id}
                  onStart={() => startChat(agent)}
                  onOpen={() => setViewer(agent)}
                  onEdit={() => edit(agent)}
                  onShare={() => setSharing(agent)}
                  onPin={() => pin.mutate(agent)}
                />
              ))}
            </div>
            <CountSeparator>{ui("{{v1}} trợ lý", { v1: shown.length })}</CountSeparator>
          </>
        )}
        {shownViewer && (
          <AgentViewer
            agent={shownViewer}
            pending={starting === shownViewer.id}
            onClose={closeViewer}
            onStart={(ask) => startChat(shownViewer, ask)}
            onEdit={() => edit(shownViewer)}
          />
        )}
        {sharing && <AgentShareDialog agent={sharing} onClose={() => setSharing(undefined)} />}
      </SettingsLayout>
    </>
  );
}

/** Creating an agent; without the capability the disabled action says why on hover and focus. */
function CreateAgentButton({ allowed, onCreate }: { allowed: boolean; onCreate: () => void }) {
  const ui = useAppTranslation();
  if (allowed)
    return (
      <Button onClick={onCreate}>
        <Plus data-icon="inline-start" aria-hidden="true" />
        {ui("Tạo trợ lý")}
      </Button>
    );
  return (
    <TooltipProvider>
      <Tooltip>
        <TooltipTrigger asChild>
          {/* The span receives hover; the focusable, aria-disabled button inside receives focus. */}
          <span className="inline-flex">
            <Button asChild disabled>
              <button type="button">
                <Plus data-icon="inline-start" aria-hidden="true" />
                {ui("Tạo trợ lý")}
              </button>
            </Button>
          </span>
        </TooltipTrigger>
        <TooltipContent>
          {ui("Bạn chưa có quyền tạo trợ lý. Liên hệ quản trị viên để được cấp quyền.")}
        </TooltipContent>
      </Tooltip>
    </TooltipProvider>
  );
}
