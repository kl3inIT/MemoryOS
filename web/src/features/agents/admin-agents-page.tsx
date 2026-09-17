import { useAppTranslation } from "@/i18n/use-app-translation";
import { useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { Bot, Eye, EyeOff, GripVertical, Pencil, Star, Trash2, UserRoundCog } from "lucide-react";
import { hoverReveal } from "@/components/composites/hover-reveal";
import { SortableList, type SortableHandle } from "@/components/composites/sortable-list";
import { useActionNotifications } from "@/components/ui/action-notifications";
import { cn } from "@/lib/utils";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Checkbox } from "@/components/ui/checkbox";
import { ConfirmDialog } from "@/components/ui/confirm-dialog";
import { IconButton } from "@/components/ui/icon-button";
import { Input } from "@/components/ui/input";
import { PageHeader, SettingsLayout } from "@/components/ui/settings-layout";
import { useNavigate } from "@tanstack/react-router";
import { useApplicationSession } from "@/features/identity/application-session-context";
import { sameOriginMutationHeaders } from "@/lib/api";
import {
  deleteChatPersonaLabel,
  listChatPersonaLabels,
  listChatPersonasForAdministration,
  renameChatPersonaLabel,
  reorderChatPersonas,
  restoreChatPersona,
  setChatPersonaListing,
} from "@/lib/hey-api/sdk.gen";
import { ChatDialog } from "@/features/chat/chat-dialog";
import { chatActionError } from "@/features/chat/chat-action-utils";
import {
  agentLabelSchema,
  agentVisibility,
  personLabel,
  personaSchema,
  type AgentRef,
  type Persona,
} from "@/features/chat/chat-workspace-api";
import { AgentAvatar } from "./agent-avatar";
import { AgentTransferDialog } from "./agent-transfer-dialog";
import { PublicPromptShortcuts } from "./prompt-shortcuts";

async function allAdministrationPages<T>(load: (offset: number) => Promise<T[]>) {
  const items: T[] = [];
  for (let offset = 0; ; offset += 100) {
    const page = await load(offset);
    items.push(...page);
    if (page.length < 100) return items;
  }
}

/** Agent administration for AGENTS_MANAGE: listing, featuring, restore, vacant owners, labels, public shortcuts. */
export function AdminAgentsPage() {
  const ui = useAppTranslation();
  const cache = useQueryClient();
  const navigate = useNavigate();
  const { actorId, authorizationVersion } = useApplicationSession();
  const [includeDeleted, setIncludeDeleted] = useState(false);
  const [transferring, setTransferring] = useState<Persona>();
  const notify = useActionNotifications();
  const [error, setError] = useState<string>();
  const agents = useQuery({
    queryKey: ["chat-personas", "administration", actorId, authorizationVersion, includeDeleted],
    queryFn: async ({ signal }) =>
      allAdministrationPages(async (offset) =>
        personaSchema.array().parse(
          (
            await listChatPersonasForAdministration({
              query: { includeDeleted, offset, limit: 100 },
              signal,
              throwOnError: true,
            })
          ).data,
        ),
      ),
  });
  const refresh = () => cache.invalidateQueries({ queryKey: ["chat-personas"] });

  async function restore(agent: Persona) {
    setError(undefined);
    try {
      await restoreChatPersona({
        path: { personaId: agent.id },
        headers: sameOriginMutationHeaders,
        signal: AbortSignal.timeout(30000),
        throwOnError: true,
      });
      await refresh();
    } catch (cause) {
      setError(chatActionError(cause));
    }
  }

  async function listing(agent: Persona, change: { listed?: boolean; featured?: boolean }) {
    setError(undefined);
    try {
      await setChatPersonaListing({
        path: { personaId: agent.id },
        query: { revision: agent.revision },
        body: {
          listed: change.listed ?? agent.listed,
          featured: change.featured ?? agent.featured,
          displayPriority: agent.displayPriority ?? undefined,
        },
        headers: sameOriginMutationHeaders,
        signal: AbortSignal.timeout(30000),
        throwOnError: true,
      });
      if (change.featured !== undefined)
        notify({
          title: change.featured
            ? ui("Đã đặt {{v1}} nổi bật", { v1: agent.name })
            : ui("Đã bỏ nổi bật {{v1}}", { v1: agent.name }),
          tone: "success",
        });
      await refresh();
    } catch (cause) {
      setError(chatActionError(cause));
    }
  }

  // Dragging writes each moved agent's display priority as its position (Onyx admin agent ordering).
  async function reorder(next: Persona[]) {
    const key = ["chat-personas", "administration", actorId, authorizationVersion, includeDeleted];
    const previous = agents.data;
    cache.setQueryData(key, next);
    setError(undefined);
    try {
      // One request writes the whole order in a server transaction; a failure changes nothing.
      await reorderChatPersonas({
        body: {
          personaIds: next
            .filter((agent) => !agent.builtin && !agent.deletedAt)
            .map((agent) => agent.id),
        },
        headers: sameOriginMutationHeaders,
        signal: AbortSignal.timeout(30000),
        throwOnError: true,
      });
    } catch (cause) {
      cache.setQueryData(key, previous);
      setError(chatActionError(cause));
    }
    await refresh();
  }

  return (
    <SettingsLayout wide>
      <PageHeader
        title={ui("Quản lý trợ lý")}
        description={ui(
          "Chọn trợ lý hiển thị và nổi bật cho cả tổ chức, khôi phục trợ lý đã xóa và giao trợ lý chưa có chủ sở hữu.",
        )}
        icon={<Bot />}
      />
      <section className="space-y-3">
        <div className="flex flex-wrap items-center justify-between gap-3">
          <h2 className="text-lg font-medium">{ui("Tất cả trợ lý")}</h2>
          <label className="flex items-center gap-2 text-sm">
            <Checkbox
              checked={includeDeleted}
              onCheckedChange={(checked) => setIncludeDeleted(checked === true)}
            />
            {ui("Hiện trợ lý đã xóa")}
          </label>
        </div>
        {(error || agents.isError) && (
          <p role="alert" className="text-status-danger-content">
            {error ?? ui("Không tải được danh sách trợ lý.")}
          </p>
        )}
        {agents.isPending && <p role="status">{ui("Đang tải trợ lý…")}</p>}
        {agents.data && (
          <div className="overflow-x-auto rounded-xl border border-border-subtle bg-surface-raised">
            <div className="min-w-[48rem]">
              <div className="grid grid-cols-[1.5rem_minmax(12rem,2fr)_minmax(8rem,1fr)_7rem_8rem_12rem] items-center gap-3 border-b border-border-subtle px-3 py-2 font-secondary-action text-content-muted">
                <span />
                <span>{ui("Trợ lý")}</span>
                <span>{ui("Chủ sở hữu")}</span>
                <span>{ui("Truy cập")}</span>
                <span>{ui("Hiển thị")}</span>
                <span className="text-right">{ui("Thao tác")}</span>
              </div>
              <ul aria-label={ui("Tất cả trợ lý")}>
                <SortableList
                  items={agents.data}
                  getId={(agent) => agent.id}
                  onReorder={(next) => void reorder(next)}
                >
                  {(agent, handle) => (
                    <AdminAgentRow
                      key={agent.id}
                      agent={agent}
                      handle={handle}
                      sortable={!includeDeleted && !agent.builtin && !agent.deletedAt}
                      onEdit={() =>
                        void navigate({
                          to: "/agents/$agentId/edit",
                          params: { agentId: agent.id },
                        })
                      }
                      onTransfer={() => setTransferring(agent)}
                      onRestore={() => void restore(agent)}
                      onListing={(change) => void listing(agent, change)}
                    />
                  )}
                </SortableList>
              </ul>
            </div>
          </div>
        )}
        {agents.data && !includeDeleted && (
          <p className="font-secondary-body text-content-muted">
            {ui("Kéo để đổi thứ tự hiển thị trong thư viện. Trợ lý nổi bật luôn đứng đầu.")}
          </p>
        )}
      </section>
      <AgentLabels />
      <PublicPromptShortcuts />
      {transferring && (
        <AgentTransferDialog agent={transferring} onClose={() => setTransferring(undefined)} />
      )}
    </SettingsLayout>
  );
}

function AgentLabels() {
  const ui = useAppTranslation();
  const cache = useQueryClient();
  const { actorId, authorizationVersion } = useApplicationSession();
  const [renaming, setRenaming] = useState<AgentRef>();
  const [name, setName] = useState("");
  const [removing, setRemoving] = useState<AgentRef>();
  const labels = useQuery({
    queryKey: ["chat-persona-labels", actorId, authorizationVersion],
    queryFn: async ({ signal }) =>
      agentLabelSchema
        .array()
        .parse((await listChatPersonaLabels({ signal, throwOnError: true })).data),
  });
  const refresh = async () => {
    await cache.invalidateQueries({ queryKey: ["chat-persona-labels"] });
    await cache.invalidateQueries({ queryKey: ["chat-personas"] });
  };
  return (
    <section className="space-y-3">
      <h2 className="text-lg font-medium">{ui("Nhãn trợ lý")}</h2>
      {labels.data?.length === 0 && (
        <p className="text-sm text-content-muted">
          {ui("Chưa có nhãn. Người tạo trợ lý thêm nhãn trong trình chỉnh sửa.")}
        </p>
      )}
      <ul className="flex flex-wrap gap-2">
        {labels.data?.map((label) => (
          <li
            key={label.id}
            className="flex items-center gap-1 rounded-lg border border-border-default py-0.5 pr-0.5 pl-3 text-sm"
          >
            {label.name}
            <IconButton
              size="sm"
              prominence="internal"
              aria-label={ui("Đổi tên nhãn {{v1}}", { v1: label.name })}
              onClick={() => {
                setRenaming(label);
                setName(label.name);
              }}
            >
              <Pencil />
            </IconButton>
            <IconButton
              size="sm"
              prominence="internal"
              aria-label={ui("Xóa nhãn {{v1}}", { v1: label.name })}
              onClick={() => setRemoving(label)}
            >
              <Trash2 />
            </IconButton>
          </li>
        ))}
      </ul>
      {renaming && (
        <ChatDialog
          open
          onOpenChange={(open) => !open && setRenaming(undefined)}
          title={ui("Đổi tên nhãn")}
          description={ui("Tên mới áp dụng cho mọi trợ lý đang gắn nhãn này.")}
          submitDisabled={!name.trim()}
          onSubmit={async () => {
            await renameChatPersonaLabel({
              path: { labelId: renaming.id },
              body: { name },
              headers: sameOriginMutationHeaders,
              signal: AbortSignal.timeout(30000),
              throwOnError: true,
            });
            await refresh();
          }}
        >
          <label className="block space-y-1">
            <span>{ui("Tên nhãn")}</span>
            <Input maxLength={100} value={name} onChange={(e) => setName(e.target.value)} />
          </label>
        </ChatDialog>
      )}
      <ConfirmDialog
        open={removing !== undefined}
        onOpenChange={(open) => !open && setRemoving(undefined)}
        title={ui("Xóa nhãn?")}
        description={ui("Nhãn sẽ được gỡ khỏi mọi trợ lý. Trợ lý không bị ảnh hưởng.")}
        confirmLabel={ui("Xóa nhãn")}
        pendingLabel={ui("Đang lưu…")}
        confirmTone="danger"
        errorMessage={chatActionError}
        onConfirm={async () => {
          if (!removing) return;
          await deleteChatPersonaLabel({
            path: { labelId: removing.id },
            headers: sameOriginMutationHeaders,
            signal: AbortSignal.timeout(30000),
            throwOnError: true,
          });
          await refresh();
        }}
      />
    </section>
  );
}

function AdminAgentRow({
  agent,
  handle: { setNodeRef, setHandleRef, style, attributes, dragging },
  sortable,
  onEdit,
  onTransfer,
  onRestore,
  onListing,
}: {
  agent: Persona;
  handle: SortableHandle;
  sortable: boolean;
  onEdit: () => void;
  onTransfer: () => void;
  onRestore: () => void;
  onListing: (change: { listed?: boolean; featured?: boolean }) => void;
}) {
  const ui = useAppTranslation();
  const visibility = agentVisibility(agent);
  return (
    <li
      ref={setNodeRef}
      style={style}
      className={cn(
        "group grid grid-cols-[1.5rem_minmax(12rem,2fr)_minmax(8rem,1fr)_7rem_8rem_12rem] items-center gap-3 border-b border-border-subtle px-3 py-2 last:border-b-0 hover:bg-surface-base",
        dragging && "relative z-10 bg-surface-raised shadow-hover",
      )}
    >
      {sortable ? (
        <button
          ref={setHandleRef}
          type="button"
          aria-label={ui("Kéo để sắp xếp {{v1}}", { v1: agent.name })}
          className={cn(
            "grid size-6 cursor-grab place-items-center rounded text-content-muted outline-none focus-visible:opacity-100 focus-visible:ring-2 focus-visible:ring-focus-ring/40 active:cursor-grabbing",
            hoverReveal,
          )}
          {...attributes}
        >
          <GripVertical aria-hidden="true" className="size-4" />
        </button>
      ) : (
        <span />
      )}
      <div className="flex min-w-0 items-center gap-2.5">
        <AgentAvatar agent={agent} size="sm" />
        <span className="truncate font-main-ui-action text-content-primary">{agent.name}</span>
      </div>
      <div className="min-w-0 truncate font-main-ui-body text-content-secondary">
        {agent.builtin ? (
          ui("MemoryOS")
        ) : agent.vacant ? (
          <Badge variant="destructive">{ui("Chưa có chủ sở hữu")}</Badge>
        ) : (
          (agent.owner.group?.name ?? personLabel(agent.owner.actor))
        )}
      </div>
      <span className="font-main-ui-body text-content-secondary">
        {visibility === "public"
          ? ui("Công khai")
          : visibility === "shared"
            ? ui("Đã chia sẻ")
            : ui("Riêng tư")}
      </span>
      <div className="flex flex-wrap gap-1">
        {agent.deletedAt ? (
          <Badge variant="destructive">{ui("Đã xóa")}</Badge>
        ) : agent.listed ? (
          <span className="font-main-ui-body text-content-secondary">{ui("Trong thư viện")}</span>
        ) : (
          <Badge variant="outline">{ui("Ẩn")}</Badge>
        )}
      </div>
      <div className="flex items-center justify-end gap-0.5">
        {agent.deletedAt ? (
          <Button size="sm" prominence="secondary" onClick={onRestore}>
            {ui("Khôi phục")}
          </Button>
        ) : (
          <>
            <div className={cn("flex items-center gap-0.5", hoverReveal)}>
              {!agent.builtin && (
                <IconButton
                  size="sm"
                  prominence="tertiary"
                  aria-label={
                    agent.listed
                      ? ui("Ẩn {{v1}} khỏi thư viện", { v1: agent.name })
                      : ui("Hiện {{v1}} trong thư viện", { v1: agent.name })
                  }
                  title={agent.listed ? ui("Ẩn khỏi thư viện") : ui("Hiện trong thư viện")}
                  onClick={() => onListing({ listed: !agent.listed })}
                >
                  {agent.listed ? <EyeOff /> : <Eye />}
                </IconButton>
              )}
              {agent.permissions.transfer && (
                <IconButton
                  size="sm"
                  prominence="tertiary"
                  aria-label={ui("Chuyển chủ sở hữu {{v1}}", { v1: agent.name })}
                  title={ui("Chuyển chủ sở hữu")}
                  onClick={onTransfer}
                >
                  <UserRoundCog />
                </IconButton>
              )}
              <IconButton
                size="sm"
                prominence="tertiary"
                aria-label={ui("Sửa {{v1}}", { v1: agent.name })}
                title={ui("Sửa trợ lý")}
                onClick={onEdit}
              >
                <Pencil />
              </IconButton>
            </div>
            {!agent.builtin && (
              <IconButton
                size="sm"
                prominence="tertiary"
                aria-pressed={agent.featured}
                aria-label={
                  agent.featured
                    ? ui("Bỏ nổi bật {{v1}}", { v1: agent.name })
                    : ui("Đặt {{v1}} nổi bật", { v1: agent.name })
                }
                title={agent.featured ? ui("Bỏ nổi bật") : ui("Đặt nổi bật")}
                className={agent.featured ? "text-status-warning-content" : hoverReveal}
                onClick={() => onListing({ featured: !agent.featured })}
              >
                <Star className={agent.featured ? "fill-current" : undefined} />
              </IconButton>
            )}
          </>
        )}
      </div>
    </li>
  );
}
