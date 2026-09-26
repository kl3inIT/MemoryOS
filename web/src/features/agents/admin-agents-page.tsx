import { useAppTranslation } from "@/i18n/use-app-translation";
import { useId, useState } from "react";
import { useNavigate } from "@tanstack/react-router";
import { Bot, Eye, EyeOff, GripVertical, Pencil, Star, UserRoundCog } from "lucide-react";
import { hoverReveal } from "@/components/composites/hover-reveal";
import { PageHeader, SettingsLayout } from "@/components/composites/settings-layout";
import { SortableList, type SortableHandle } from "@/components/composites/sortable-list";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Checkbox } from "@/components/ui/checkbox";
import { Field, FieldLabel } from "@/components/ui/field";
import { IconButton } from "@/components/ui/icon-button";
import { agentVisibility, type Persona } from "@/features/chat/chat-personas-api";
import { personLabel } from "@/features/identity/principals";
import { actionErrorText } from "@/lib/action-errors";
import { cn } from "@/lib/utils";
import { AgentAvatar } from "./agent-avatar";
import { AgentLabelsAdministration } from "./agent-labels-admin";
import { AgentTransferDialog } from "./agent-transfer-dialog";
import { PublicPromptShortcuts } from "./prompt-shortcuts";
import { useAgentAdministration } from "./use-agent-administration";

/** Column widths shared by the header and every row of the administration list. */
const adminColumns = {
  handle: "w-6 shrink-0",
  agent: "min-w-48 flex-2",
  owner: "min-w-32 flex-1",
  access: "w-28 shrink-0",
  listing: "w-32 shrink-0",
  actions: "w-48 shrink-0",
};

/** Agent administration for AGENTS_MANAGE: listing, featuring, restore, vacant owners, labels, public shortcuts. */
export function AdminAgentsPage() {
  const ui = useAppTranslation();
  const navigate = useNavigate();
  const deletedId = useId();
  const [includeDeleted, setIncludeDeleted] = useState(false);
  const [transferring, setTransferring] = useState<Persona>();
  const { agents, restore, setListing, reorder, error } = useAgentAdministration(includeDeleted);

  return (
    <SettingsLayout wide>
      <PageHeader
        title={ui("Quản lý trợ lý")}
        description={ui(
          "Chọn trợ lý hiển thị và nổi bật cho cả tổ chức, khôi phục trợ lý đã xóa và giao trợ lý chưa có chủ sở hữu.",
        )}
        icon={<Bot />}
      />
      <section className="flex flex-col gap-3">
        <div className="flex flex-wrap items-center justify-between gap-3">
          <h2 className="text-lg font-medium">{ui("Tất cả trợ lý")}</h2>
          <Field orientation="horizontal" className="w-auto">
            <Checkbox
              id={deletedId}
              checked={includeDeleted}
              onCheckedChange={(checked) => setIncludeDeleted(checked === true)}
            />
            <FieldLabel htmlFor={deletedId}>{ui("Hiện trợ lý đã xóa")}</FieldLabel>
          </Field>
        </div>
        {(error || agents.isError) && (
          <Alert variant="destructive">
            <AlertDescription>
              {error ? actionErrorText(error) : ui("Không tải được danh sách trợ lý.")}
            </AlertDescription>
          </Alert>
        )}
        {agents.isPending && <p role="status">{ui("Đang tải trợ lý…")}</p>}
        {agents.data && (
          <div className="overflow-x-auto rounded-xl border border-border-subtle bg-surface-raised">
            <div className="min-w-192">
              <div className="flex items-center gap-3 border-b border-border-subtle px-3 py-2 font-secondary-action text-content-muted">
                <span className={adminColumns.handle} />
                <span className={adminColumns.agent}>{ui("Trợ lý")}</span>
                <span className={adminColumns.owner}>{ui("Chủ sở hữu")}</span>
                <span className={adminColumns.access}>{ui("Truy cập")}</span>
                <span className={adminColumns.listing}>{ui("Hiển thị")}</span>
                <span className={cn("text-right", adminColumns.actions)}>{ui("Thao tác")}</span>
              </div>
              <ul aria-label={ui("Tất cả trợ lý")}>
                <SortableList items={agents.data} getId={(agent) => agent.id} onReorder={reorder}>
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
                      onRestore={() => restore(agent)}
                      onListing={(change) => setListing(agent, change)}
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
      <AgentLabelsAdministration />
      <PublicPromptShortcuts />
      {transferring && (
        <AgentTransferDialog agent={transferring} onClose={() => setTransferring(undefined)} />
      )}
    </SettingsLayout>
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
        "group flex items-center gap-3 border-b border-border-subtle px-3 py-2 last:border-b-0 hover:bg-surface-base",
        dragging && "relative z-10 bg-surface-raised shadow-hover",
      )}
    >
      <span className={adminColumns.handle}>
        {sortable && (
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
        )}
      </span>
      <div className={cn("flex items-center gap-2.5", adminColumns.agent)}>
        <AgentAvatar agent={agent} size="sm" />
        <span className="truncate font-main-ui-action text-content-primary">{agent.name}</span>
      </div>
      <div className={cn("truncate font-main-ui-body text-content-secondary", adminColumns.owner)}>
        {agent.builtin ? (
          ui("MemoryOS")
        ) : agent.vacant ? (
          <Badge variant="destructive">{ui("Chưa có chủ sở hữu")}</Badge>
        ) : (
          (agent.owner.group?.name ?? personLabel(agent.owner.actor))
        )}
      </div>
      <span className={cn("font-main-ui-body text-content-secondary", adminColumns.access)}>
        {visibility === "public"
          ? ui("Công khai")
          : visibility === "shared"
            ? ui("Đã chia sẻ")
            : ui("Riêng tư")}
      </span>
      <div className={cn("flex flex-wrap gap-1", adminColumns.listing)}>
        {agent.deletedAt ? (
          <Badge variant="destructive">{ui("Đã xóa")}</Badge>
        ) : agent.listed ? (
          <span className="font-main-ui-body text-content-secondary">{ui("Trong thư viện")}</span>
        ) : (
          <Badge variant="outline">{ui("Ẩn")}</Badge>
        )}
      </div>
      <div className={cn("flex items-center justify-end gap-0.5", adminColumns.actions)}>
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
              <span className={cn("flex", !agent.featured && hoverReveal)}>
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
                  onClick={() => onListing({ featured: !agent.featured })}
                >
                  <Star
                    className={cn(agent.featured && "fill-current text-status-warning-content")}
                  />
                </IconButton>
              </span>
            )}
          </>
        )}
      </div>
    </li>
  );
}
