import { useAppTranslation } from "@/i18n/use-app-translation";
import { useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { Bot, Pencil, Trash2 } from "lucide-react";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Checkbox } from "@/components/ui/checkbox";
import { ConfirmDialog } from "@/components/ui/confirm-dialog";
import { IconButton } from "@/components/ui/icon-button";
import { Input } from "@/components/ui/input";
import { PageHeader, SettingsLayout } from "@/components/ui/settings-layout";
import { Switch } from "@/components/ui/switch";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";
import { useApplicationSession } from "@/features/identity/application-session-context";
import { sameOriginMutationHeaders } from "@/lib/api";
import {
  deleteChatPersonaLabel,
  listChatPersonaLabels,
  listChatPersonasForAdministration,
  renameChatPersonaLabel,
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
import { AgentEditor } from "./agent-editor";
import { AgentTransferDialog } from "./agent-transfer-dialog";
import { PublicPromptShortcuts } from "./prompt-shortcuts";

/** Agent administration for AGENTS_MANAGE: listing, featuring, restore, vacant owners, labels, public shortcuts. */
export function AdminAgentsPage() {
  const ui = useAppTranslation();
  const cache = useQueryClient();
  const { actorId, authorizationVersion } = useApplicationSession();
  const [includeDeleted, setIncludeDeleted] = useState(false);
  const [editing, setEditing] = useState<Persona>();
  const [transferring, setTransferring] = useState<Persona>();
  const [listing, setListing] = useState<Persona>();
  const [error, setError] = useState<string>();
  const agents = useQuery({
    queryKey: ["chat-personas", "administration", actorId, authorizationVersion, includeDeleted],
    queryFn: async ({ signal }) =>
      personaSchema.array().parse(
        (
          await listChatPersonasForAdministration({
            query: { includeDeleted, limit: 100 },
            signal,
            throwOnError: true,
          })
        ).data,
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
          <div className="overflow-x-auto rounded-xl border border-border-default">
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>{ui("Trợ lý")}</TableHead>
                  <TableHead>{ui("Chủ sở hữu")}</TableHead>
                  <TableHead>{ui("Truy cập")}</TableHead>
                  <TableHead>{ui("Hiển thị")}</TableHead>
                  <TableHead className="text-right">{ui("Thao tác")}</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {agents.data.map((agent) => {
                  const visibility = agentVisibility(agent);
                  return (
                    <TableRow key={agent.id}>
                      <TableCell>
                        <div className="flex min-w-48 items-center gap-2">
                          <AgentAvatar agent={agent} size="sm" />
                          <span className="truncate font-medium">{agent.name}</span>
                        </div>
                      </TableCell>
                      <TableCell>
                        {agent.builtin ? (
                          ui("MemoryOS")
                        ) : agent.vacant ? (
                          <Badge variant="destructive">{ui("Chưa có chủ sở hữu")}</Badge>
                        ) : (
                          (agent.owner.group?.name ?? personLabel(agent.owner.actor))
                        )}
                      </TableCell>
                      <TableCell>
                        {visibility === "public"
                          ? ui("Công khai")
                          : visibility === "shared"
                            ? ui("Đã chia sẻ")
                            : ui("Riêng tư")}
                      </TableCell>
                      <TableCell>
                        <div className="flex flex-wrap gap-1">
                          {agent.deletedAt && <Badge variant="destructive">{ui("Đã xóa")}</Badge>}
                          {agent.featured && <Badge>{ui("Nổi bật")}</Badge>}
                          {!agent.listed && <Badge variant="outline">{ui("Ẩn")}</Badge>}
                          {agent.displayPriority != null && (
                            <Badge variant="secondary">
                              {ui("Thứ tự {{v1}}", { v1: agent.displayPriority })}
                            </Badge>
                          )}
                        </div>
                      </TableCell>
                      <TableCell>
                        <div className="flex justify-end gap-1">
                          {agent.deletedAt ? (
                            <Button
                              size="sm"
                              prominence="secondary"
                              onClick={() => void restore(agent)}
                            >
                              {ui("Khôi phục")}
                            </Button>
                          ) : (
                            <>
                              <Button
                                size="sm"
                                prominence="internal"
                                onClick={() => setEditing(agent)}
                              >
                                {ui("Sửa")}
                              </Button>
                              {!agent.builtin && (
                                <Button
                                  size="sm"
                                  prominence="internal"
                                  onClick={() => setListing(agent)}
                                >
                                  {ui("Hiển thị")}
                                </Button>
                              )}
                              {agent.permissions.transfer && (
                                <Button
                                  size="sm"
                                  prominence="internal"
                                  onClick={() => setTransferring(agent)}
                                >
                                  {ui("Chuyển chủ sở hữu")}
                                </Button>
                              )}
                            </>
                          )}
                        </div>
                      </TableCell>
                    </TableRow>
                  );
                })}
              </TableBody>
            </Table>
          </div>
        )}
      </section>
      <AgentLabels />
      <PublicPromptShortcuts />
      {editing && <AgentEditor agent={editing} onClose={() => setEditing(undefined)} />}
      {transferring && (
        <AgentTransferDialog agent={transferring} onClose={() => setTransferring(undefined)} />
      )}
      {listing && (
        <ListingDialog agent={listing} onClose={() => setListing(undefined)} onSaved={refresh} />
      )}
    </SettingsLayout>
  );
}

function ListingDialog({
  agent,
  onClose,
  onSaved,
}: {
  agent: Persona;
  onClose: () => void;
  onSaved: () => Promise<void>;
}) {
  const ui = useAppTranslation();
  const [listed, setListed] = useState(agent.listed);
  const [featured, setFeatured] = useState(agent.featured);
  const [priority, setPriority] = useState(agent.displayPriority?.toString() ?? "");
  return (
    <ChatDialog
      open
      onOpenChange={(open) => !open && onClose()}
      title={ui("Hiển thị {{v1}}", { v1: agent.name })}
      description={ui(
        "Trợ lý nổi bật, công khai và đang hiển thị được ghim sẵn cho người dùng mới. Trợ lý bị ẩn vẫn mở được bằng liên kết.",
      )}
      onSubmit={async () => {
        await setChatPersonaListing({
          path: { personaId: agent.id },
          query: { revision: agent.revision },
          body: { listed, featured, displayPriority: priority ? Number(priority) : undefined },
          headers: sameOriginMutationHeaders,
          signal: AbortSignal.timeout(30000),
          throwOnError: true,
        });
        await onSaved();
      }}
    >
      <div className="space-y-4">
        <label className="flex items-center justify-between gap-4">
          {ui("Hiển thị trong thư viện")}
          <Switch
            checked={listed}
            onCheckedChange={setListed}
            aria-label={ui("Hiển thị trong thư viện")}
          />
        </label>
        <label className="flex items-center justify-between gap-4">
          {ui("Nổi bật")}
          <Switch checked={featured} onCheckedChange={setFeatured} aria-label={ui("Nổi bật")} />
        </label>
        <label className="block space-y-1">
          <span>{ui("Thứ tự hiển thị")}</span>
          <Input
            type="number"
            min={0}
            max={100000}
            value={priority}
            onChange={(e) => setPriority(e.target.value)}
          />
          <span className="block text-xs text-content-muted">
            {ui("Số nhỏ hiện trước. Bỏ trống để xếp theo tên.")}
          </span>
        </label>
      </div>
    </ChatDialog>
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
