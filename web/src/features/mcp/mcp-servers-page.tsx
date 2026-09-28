import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useSearch } from "@tanstack/react-router";
import { Blocks, MoreHorizontal, Pencil, Plus, RefreshCw, Trash2 } from "lucide-react";
import { EmptyState } from "@/components/composites/empty-state";
import { PageHeader, SettingsLayout } from "@/components/composites/settings-layout";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { Card, CardContent } from "@/components/ui/card";
import { ConfirmDialog } from "@/components/ui/confirm-dialog";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuGroup,
  DropdownMenuItem,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { IconButton } from "@/components/ui/icon-button";
import { Separator } from "@/components/ui/separator";
import { StatusBadge } from "@/components/ui/status-badge";
import { useAppTranslation } from "@/i18n/use-app-translation";
import {
  createMcpServerMutation,
  deleteMcpServerMutation,
  listMcpServersOptions,
  refreshMcpServerToolsMutation,
  updateMcpServerMutation,
} from "@/lib/hey-api/@tanstack/react-query.gen";
import type { McpServerInput, McpServerView } from "@/lib/hey-api/types.gen";
import { presentProblem } from "@/lib/problem-presentation";
import { useProblemMessage } from "@/lib/use-problem-message";
import { McpOAuthClients } from "./mcp-oauth-clients";
import { McpServerEditor } from "./mcp-server-editor";
import { McpServerMark } from "./mcp-server-mark";
import { invalidateMcpServers } from "./mcp-servers";
import { serverStatus } from "./mcp-status";
import { McpToolList } from "./mcp-tool-list";

/** Administration of the Tenant's MCP servers and which of their tools the model may see. */
export function McpServersPage() {
  const ui = useAppTranslation();
  const problem = useProblemMessage();
  const message = (failure: unknown) => problem(presentProblem(failure, "mutation").message);
  const cache = useQueryClient();
  const outcome = useSearch({ from: "/_authenticated/admin/mcp" });
  const [editing, setEditing] = useState<McpServerView | "new" | null>(null);
  const [removing, setRemoving] = useState<McpServerView | null>(null);
  const [opened, setOpened] = useState<string | null>(outcome.serverId ?? null);

  const list = useQuery(listMcpServersOptions());
  const saved = async () => {
    setEditing(null);
    await invalidateMcpServers(cache);
  };
  // A server input can carry a shared API key, so a finished save is not kept in the mutation cache.
  const create = useMutation({ ...createMcpServerMutation(), gcTime: 0, onSuccess: saved });
  const update = useMutation({ ...updateMcpServerMutation(), gcTime: 0, onSuccess: saved });
  const remove = useMutation({
    ...deleteMcpServerMutation(),
    onSuccess: async () => {
      setRemoving(null);
      await invalidateMcpServers(cache);
    },
  });
  const refresh = useMutation({
    ...refreshMcpServerToolsMutation(),
    onSuccess: (_, { path }) => invalidateMcpServers(cache, path.serverId),
  });
  const saveError = create.error ?? update.error;

  const closeEditor = () => {
    setEditing(null);
    create.reset();
    update.reset();
  };
  const save = (input: McpServerInput, revision?: number) => {
    if (editing === "new" || revision === undefined) create.mutate({ body: input });
    else if (editing)
      update.mutate({ path: { serverId: editing.id }, query: { revision }, body: input });
  };

  return (
    <SettingsLayout wide>
      <PageHeader
        title={ui("Máy chủ MCP")}
        icon={<Blocks />}
        description={ui(
          "Máy chủ MCP cung cấp công cụ cho Chat. Chúng là bên thứ ba: chỉ thêm máy chủ mà tổ chức của bạn tin tưởng.",
        )}
        actions={
          <Button onClick={() => setEditing("new")}>
            <Plus data-icon="inline-start" />
            {ui("Thêm máy chủ")}
          </Button>
        }
      />

      {outcome.mcp ? <ConnectionOutcome outcome={outcome.mcp} /> : null}
      {refresh.error ? (
        <Alert variant="destructive" role="alert">
          <AlertDescription>{message(refresh.error)}</AlertDescription>
        </Alert>
      ) : null}
      {list.isError ? (
        <Alert variant="destructive" role="alert">
          <AlertDescription>
            {problem(presentProblem(list.error, "initialLoad").message)}
          </AlertDescription>
        </Alert>
      ) : null}

      {list.data && list.data.length === 0 ? (
        <EmptyState
          icon={<Blocks />}
          title={ui("Chưa có máy chủ MCP nào. Thêm một máy chủ để Chat dùng công cụ của nó.")}
        />
      ) : null}

      <div className="flex flex-col gap-3">
        {(list.data ?? []).map((server) => (
          <McpServerCard
            key={server.id}
            server={server}
            opened={opened === server.id}
            refreshing={refresh.isPending && refresh.variables?.path.serverId === server.id}
            onRefresh={() => refresh.mutate({ path: { serverId: server.id } })}
            onToggle={() => setOpened(opened === server.id ? null : server.id)}
            onEdit={() => setEditing(server)}
            onRemove={() => setRemoving(server)}
          />
        ))}
      </div>

      {editing ? (
        <McpServerEditor
          server={editing === "new" ? undefined : editing}
          saving={create.isPending || update.isPending}
          error={saveError ? message(saveError) : undefined}
          onSave={save}
          onClose={closeEditor}
        />
      ) : null}

      {removing ? (
        <ConfirmDialog
          open
          title={ui("Xoá máy chủ MCP?")}
          description={ui(
            "Công cụ và mọi thông tin đăng nhập đã lưu của máy chủ này sẽ bị xoá. Không hoàn tác được.",
          )}
          confirmLabel={ui("Xoá")}
          pendingLabel={ui("Đang xoá")}
          confirmTone="danger"
          onConfirm={async () => {
            await remove.mutateAsync({
              path: { serverId: removing.id },
              query: { revision: removing.revision },
            });
          }}
          errorMessage={(failure) => presentProblem(failure, "mutation").message}
          onOpenChange={(open) => {
            if (!open) setRemoving(null);
          }}
        />
      ) : null}
    </SettingsLayout>
  );
}

function McpServerCard({
  server,
  opened,
  refreshing,
  onRefresh,
  onToggle,
  onEdit,
  onRemove,
}: {
  server: McpServerView;
  opened: boolean;
  refreshing: boolean;
  onRefresh: () => void;
  onToggle: () => void;
  onEdit: () => void;
  onRemove: () => void;
}) {
  const ui = useAppTranslation();
  const status = serverStatus(server.status);
  return (
    <Card size="sm">
      <CardContent>
        <div className="flex flex-wrap items-start justify-between gap-3">
          <div className="flex min-w-0 gap-3">
            <McpServerMark slug={server.slug} />
            <div className="min-w-0">
              <div className="flex flex-wrap items-center gap-2">
                <h3 className="font-main-ui-action text-content-primary">{server.name}</h3>
                <StatusBadge tone={status.tone}>{ui(status.label)}</StatusBadge>
              </div>
              {server.description ? (
                <p className="mt-0.5 font-secondary-body text-content-secondary">
                  {server.description}
                </p>
              ) : null}
              <p className="mt-1 font-secondary-body break-all text-content-muted">{server.url}</p>
              <p className="mt-1 font-secondary-body text-content-muted">
                {ui("{{enabled}}/{{total}} công cụ đang bật", {
                  enabled: server.enabledToolCount,
                  total: server.toolCount,
                })}
                {" · "}
                {ui(
                  server.authPerformer === "ADMIN"
                    ? "Một kết nối dùng chung"
                    : "Mỗi người tự kết nối",
                )}
                {" · "}
                {ui(server.tenantWide ? "Cả tổ chức" : "Chọn nhóm")}
              </p>
            </div>
          </div>
          <div className="flex items-center gap-2">
            <Button prominence="secondary" size="sm" pending={refreshing} onClick={onRefresh}>
              <RefreshCw data-icon="inline-start" />
              {ui("Lấy công cụ")}
            </Button>
            <Button prominence="secondary" size="sm" onClick={onToggle}>
              {ui(opened ? "Ẩn công cụ" : "Xem công cụ")}
            </Button>
            {/* Rare actions share one menu, so the row stays on one line at phone width. */}
            <DropdownMenu>
              <DropdownMenuTrigger asChild>
                <IconButton
                  size="sm"
                  prominence="internal"
                  aria-label={ui("Thao tác với {{name}}", { name: server.name })}
                >
                  <MoreHorizontal />
                </IconButton>
              </DropdownMenuTrigger>
              <DropdownMenuContent align="end" className="min-w-44">
                <DropdownMenuGroup>
                  <DropdownMenuItem onSelect={onEdit}>
                    <Pencil /> {ui("Sửa")}
                  </DropdownMenuItem>
                </DropdownMenuGroup>
                <DropdownMenuSeparator />
                <DropdownMenuGroup>
                  <DropdownMenuItem variant="destructive" onSelect={onRemove}>
                    <Trash2 /> {ui("Xoá máy chủ")}
                  </DropdownMenuItem>
                </DropdownMenuGroup>
              </DropdownMenuContent>
            </DropdownMenu>
          </div>
        </div>
      </CardContent>
      {opened ? (
        <>
          <Separator />
          <CardContent>
            <div className="flex flex-col gap-4">
              {server.authType === "OAUTH" ? <McpOAuthClients server={server} /> : null}
              <McpToolList serverId={server.id} />
            </div>
          </CardContent>
        </>
      ) : null}
    </Card>
  );
}

/** The outcome code the OAuth callback appended; it never carries provider text. */
function ConnectionOutcome({ outcome }: { outcome: string }) {
  const ui = useAppTranslation();
  const copy =
    outcome === "connected"
      ? { tone: "success" as const, text: "Đã kết nối máy chủ MCP." }
      : outcome === "authorization-cancelled"
        ? { tone: "neutral" as const, text: "Bạn đã huỷ việc cấp quyền." }
        : outcome === "issuer-mismatch"
          ? { tone: "danger" as const, text: "Máy chủ cấp quyền không khớp cấu hình đã lưu." }
          : outcome === "configuration-changed"
            ? { tone: "warning" as const, text: "Cấu hình đã đổi khi đang cấp quyền. Hãy thử lại." }
            : { tone: "danger" as const, text: "Cấp quyền không thành công." };
  return <StatusBadge tone={copy.tone}>{ui(copy.text)}</StatusBadge>;
}
